(ns propagators.prototype-2.compiler
  "L-2 — the language this Meander compiler builds (s-expr → prototype-2 network).

  Follows compiler-2's shape at a small scale:

    define      — extend lexical env (Church p-list of bindings)
    application — wire operator + args into a result cell

  Lexical env is a primitive compound linked list (not a host map):

    env ::= () | (cons (cons name value-cell) parent)

  Lookup/extend install pending compounds — lazily applied network.

  Primitives: + - * /  cons  car  cdr  empty-list?  origins  neighbors

  Introspection (EdgeChain dependence):
    (origins c)   — ultimate upstream cells as a p-list
    (neighbors c) — immediate upstream cells as a p-list
  See `l2-tracer-prelude` for recursive `reason` / `trace` in L-2 itself.

  Conditional: `(if c t e)` compiles `c`, then a compound that compiles only
  the taken branch when `c` is concrete (delayed branches → map-list-safe).

  Tail calls: `:tail?` reuses `:out`. A Church fn compound recompiles its
  body into that cell — topology falls through with no return-cell stack."
  (:require [meander.epsilon :as m]
            [propagators.prototype-2.arith :as arith]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.list-ops :as lists]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.trace-ops :as trace]))

(defn fresh-state
  ([] (fresh-state net/empty-network))
  ([network]
   (let [[env-cell network] (lenv/empty-env network)]
     {:network network
      :env env-cell
      :out nil
      :tail? false
      :result nil})))

(defn- result-cell
  [state label]
  (if (and (:tail? state) (:out state))
    (:out state)
    (net/new-cell-id label)))

(defn- with-result [state network cell]
  (assoc state :network network :result cell))

(defn- wake
  "Scheduler drain of Network.queue (install/tell already enqueued)."
  [network]
  (ev/run-tasks network))

;; ---------------------------------------------------------------------------
;; Church fn — captures env *cell* (live linked list), not a host snapshot
;; ---------------------------------------------------------------------------

(defn church-fn
  [params body env-cell]
  {:church/fn true :params (vec params) :body body :env env-cell})

(defn church-fn? [x]
  (boolean (and (map? x) (:church/fn x))))

(declare compile-form)

(defn- compile-literal
  [state value]
  (let [cell (result-cell state "lit")
        network (net/ensure-cell (:network state) cell)
        [tasks network] (session/tell network cell value)
        network (ev/run-tasks network tasks)]
    (with-result state network cell)))

(defn- compile-symbol
  "Lexical access via lazy env walk → result cell (pending; scheduler drains)."
  [state sym]
  (let [out (result-cell state "sym")
        network (lenv/install-lookup (:network state) (:env state) sym out)]
    (with-result state network out)))

(defn- compile-define-value
  "define name expr — compile expr, cons binding onto env p-list."
  [state name expr]
  (let [state (compile-form (assoc state :tail? false :out nil) expr)
        cell (:result state)
        [env' network] (lenv/env-extend (:network state) (:env state) name cell)]
    (-> state
        (assoc :network network :env env' :result cell))))

(defn- compile-define-fn
  "define (name params…) body… — Church fn capturing env that includes self
  (letrec-style), so recursive map-list / etc. can look themselves up."
  [state name params body]
  (let [cell (net/new-cell-id (str name))
        network (net/ensure-cell (:network state) cell)
        [env' network] (lenv/env-extend network (:env state) name cell)
        fn-val (church-fn params body env')
        [tasks network] (session/tell network cell fn-val)
        network (ev/run-tasks network tasks)]
    (-> state
        (assoc :network network :env env' :result cell))))

(defn- install-primitive-apply
  [state op arg-cells out]
  (let [network (:network state)
        network
        (m/match op
          '+  (arith/install-add network (arg-cells 0) (arg-cells 1) out)
          '-  (arith/install-sub network (arg-cells 0) (arg-cells 1) out)
          '*  (arith/install-mul network (arg-cells 0) (arg-cells 1) out)
          '/  (arith/install-div network (arg-cells 0) (arg-cells 1) out)
          'cons (lists/install-cons-prop network (arg-cells 0) (arg-cells 1) out)
          'car  (lists/install-car-prop network (arg-cells 0) out)
          'cdr  (lists/install-cdr-prop network (arg-cells 0) out)
          'empty-list? (lists/install-empty-list?-prop network (arg-cells 0) out)
          'origins (trace/install-origins-prop network (arg-cells 0) out)
          'neighbors (trace/install-neighbors-prop network (arg-cells 0) out)
          _ (throw (ex-info "unknown primitive" {:op op})))]
    (with-result state network out)))

(defn- install-fn-apply
  "Apply Church fn: extend captured env with arg bindings, compile body in
  that frame (`:tail? true` → reuse `out`)."
  [state op-cell arg-cells out]
  (let [expand
        (fn [n]
          (let [fval (net/cell-value n op-cell)]
            (if-not (church-fn? fval)
              (do (println "apply: expected church-fn, got" (pr-str fval))
                  n)
              (let [params (:params fval)
                    _ (when-not (= (count params) (count arg-cells))
                        (throw (ex-info "arity mismatch"
                                        {:params params :args arg-cells})))
                    [env' n]
                    (reduce (fn [[env n] [p a]]
                              (lenv/env-extend n env p a))
                            [(:env fval) n]
                            (map vector params arg-cells))
                    body (if (= 1 (count (:body fval)))
                           (first (:body fval))
                           (cons 'do (:body fval)))
                    compiled (compile-form {:network n
                                            :env env'
                                            :out out
                                            :tail? true
                                            :result nil}
                                           body)]
                (:network compiled)))))
        network (ctor/pending-compound-constructor
                 (:network state) expand (into [op-cell] arg-cells) [out])]
    (with-result state network out)))

(defn- compile-args
  [state args]
  (let [out (:out state)
        tail? (:tail? state)]
    (reduce (fn [[st cells] arg]
              (let [st (compile-form (assoc st :tail? false :out nil) arg)]
                [(assoc st :out out :tail? tail?)
                 (conj cells (:result st))]))
            [state []]
            args)))

(defn- compile-if
  "Compile condition now; delay then/else until cond is concrete.

  Taken branch compiles into `out` with `:tail? true` (GUR-shaped)."
  [state cond-form then-form else-form]
  (let [out (result-cell state "if")
        state (compile-form (assoc state :tail? false :out nil) cond-form)
        cond-cell (:result state)
        env (:env state)
        expand
        (fn [n]
          (let [c (net/cell-value n cond-cell)
                branch (if c then-form else-form)
                compiled (compile-form {:network n
                                        :env env
                                        :out out
                                        :tail? true
                                        :result nil}
                                       branch)]
            (:network compiled)))
        network (ctor/pending-compound-constructor
                 (:network state) expand [cond-cell] [out])]
    (with-result state network out)))

(def primitive-ops
  #{'+ '- '* '/ 'cons 'car 'cdr 'empty-list? 'origins 'neighbors})

(defn- compile-apply
  [state op args]
  (let [out (result-cell state "app")
        [state arg-cells] (compile-args state args)]
    (m/match op
      (m/pred primitive-ops ?p)
      (install-primitive-apply state ?p arg-cells out)

      (m/pred symbol? ?s)
      (let [op-cell (net/new-cell-id "op")
            network (lenv/install-lookup (:network state) (:env state) ?s op-cell)
            state (assoc state :network network)]
        (install-fn-apply state op-cell arg-cells out))

      _
      (throw (ex-info "unsupported operator form" {:op op})))))

;; ---------------------------------------------------------------------------
;; L-2 recursive tracer — built on origins / neighbors (EdgeChain dependence)
;; ---------------------------------------------------------------------------

(def l2-tracer-prelude
  "L-2 forms: `append`, `reason` (origins), `trace` (origin→end via neighbors)."
  '[(define (append xs ys)
      (if (empty-list? xs)
        ys
        (cons (car xs) (append (cdr xs) ys))))
    (define (reason cell)
      (origins cell))
    (define (trace cell)
      (if (empty-list? (neighbors cell))
        (cons cell ())
        (append (trace (car (neighbors cell)))
                (cons cell ()))))])

(defn- compile-do
  [state forms]
  (if-not (seq forms)
    (compile-literal state plist/empty-list)
    (let [out (:out state)
          tail? (:tail? state)
          state (reduce (fn [st f]
                          (-> (compile-form (assoc st :tail? false :out nil) f)
                              (assoc :out out :tail? false)))
                        state
                        (butlast forms))]
      (compile-form (assoc state :out out :tail? tail?) (last forms)))))

(defn compile-form
  [state form]
  (m/match form
    (m/pred number? ?n)
    (compile-literal state ?n)

    (m/pred string? ?s)
    (compile-literal state ?s)

    (m/pred keyword? ?k)
    (compile-literal state ?k)

    (m/pred symbol? ?s)
    (compile-symbol state ?s)

    (m/seqable)
    (compile-literal state plist/empty-list)

    (m/seqable 'define (m/seqable ?name & ?params) & ?body)
    (compile-define-fn state ?name ?params ?body)

    (m/seqable 'define (m/pred symbol? ?name) ?expr)
    (compile-define-value state ?name ?expr)

    (m/seqable 'do & ?forms)
    (compile-do state ?forms)

    (m/seqable 'if ?cond ?then ?else)
    (compile-if state ?cond ?then ?else)

    (m/seqable ?op & ?args)
    (compile-apply state ?op ?args)

    _
    (throw (ex-info "cannot compile form" {:form form}))))

(defn compile-program
  ([forms] (compile-program (fresh-state) forms))
  ([state forms]
   (compile-do (assoc state :tail? true
                      :out (or (:out state) (net/new-cell-id "program")))
               (vec forms))))

(defn run
  "Compile `forms`, drain tasks, return `[value network state]`."
  [& forms]
  (let [state (compile-program forms)
        network (wake (:network state))
        value (net/cell-value network (:result state))]
    [value network state]))
