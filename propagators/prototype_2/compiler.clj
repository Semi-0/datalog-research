(ns propagators.prototype-2.compiler
  "L-2 — the language this Meander compiler builds (s-expr → prototype-2 network).

  Follows compiler-2's shape at a small scale:

    define      — extend lexical env (Church p-list of bindings)
    application — wire operator + args into a result cell

  Lexical env is a primitive compound linked list (not a host map):

    env ::= () | (cons (cons name value-cell) parent)

  Lookup/extend install pending compounds — lazily applied network.

  Primitives: + - * /  cons  car  cdr  empty-list?  eq?  origins  neighbors

  Layers (on top of L-2, not a core cell patch): see `l2-layers-prelude` —
  a layer bag is a Church p-list of `(cons name value)` bindings.

  Closures: `define` / anonymous `network` are Church fns. Application walks a
  Church p-list of argument cells (Lain/compiler-2 linked apply):

    (f a b)     → apply f to (cons a (cons b ()))
    (apply f xs) → apply f to linked list xs

  Open arg tails (cdr = nothing) leave apply pending — long-lived / curry
  without host arity counting. Exact empty spine runs the body; leftover
  params with () yield a curried Church fn; leftover args over-apply.

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
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.list-ops :as lists]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.trace-ops :as trace]
            [propagators.prototype-2.types :as t]))

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

(defn- compile-network-fn
  "Anonymous Church fn — Lain `network` / compiler-2 closure shape.

  No extra lambda machinery: same Church fn as `define`, application curries."
  [state params body]
  (let [cell (result-cell state "network")
        network (net/ensure-cell (:network state) cell)
        fn-val (church-fn params body (:env state))
        [tasks network] (session/tell network cell fn-val)
        network (ev/run-tasks network tasks)]
    (with-result state network cell)))

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
          'eq? (lists/install-eq?-prop network (arg-cells 0) (arg-cells 1) out)
          'origins (trace/install-origins-prop network (arg-cells 0) out)
          'neighbors (trace/install-neighbors-prop network (arg-cells 0) out)
          _ (throw (ex-info "unknown primitive" {:op op})))]
    (with-result state network out)))

(defn- fn-body
  [fval]
  (if (= 1 (count (:body fval)))
    (first (:body fval))
    (cons 'do (:body fval))))

(defn- compile-args
  "Compile each arg form → vector of cells (primitives still use fixed ports)."
  [state args]
  (let [out (:out state)
        tail? (:tail? state)]
    (reduce (fn [[st cells] arg]
              (let [st (compile-form (assoc st :tail? false :out nil) arg)]
                [(assoc st :out out :tail? tail?)
                 (conj cells (:result st))]))
            [state []]
            args)))

(defn- materialize-arg-list
  "Host-build a closed Church p-list spine over `arg-cells` ending in '().

  Returns `[list-cell network]`."
  [network arg-cells]
  (let [empty-c (net/new-cell-id "arg-nil")
        network (net/ensure-cell network empty-c)
        [_ network] (session/tell network empty-c plist/empty-list)
        network (ev/run-tasks network)]
    (reduce (fn [[tail n] arg-cell]
              (let [{:keys [network list]} (church/install-cons n arg-cell tail)]
                [list network]))
            [empty-c network]
            (reverse arg-cells))))

(defn- compile-arg-list
  "Compile arg forms and cons them into one linked argument-list cell."
  [state arg-forms]
  (let [out (:out state)
        tail? (:tail? state)
        [state cells] (compile-args state arg-forms)
        [list-cell network] (materialize-arg-list (:network state) cells)]
    [(assoc state :network network :out out :tail? tail?) list-cell]))

(declare install-fn-apply-list)

(defn- apply-church-to-arglist
  "Walk Church fn params against a live arg p-list cell (compiler-2 linked apply).

  - args nothing → leave unbuilt (open tail / long-lived)
  - params left + args () → curry: write Church fn with remaining params
  - no params + args () → compile body into `out`
  - no params + args cons → compile body to mid, apply mid to same arg list
  - params + args cons → bind car cell, continue on cdr"
  [network fval args-list-cell out]
  (let [args-v (net/cell-value network args-list-cell)
        params (vec (:params fval))]
    (cond
      (t/nothing? args-v)
      network

      (and (empty? params) (plist/empty-list? args-v))
      (let [compiled (compile-form {:network network
                                    :env (:env fval)
                                    :out out
                                    :tail? true
                                    :result nil}
                                   (fn-body fval))]
        (:network compiled))

      (and (empty? params) (plist/church-cons? args-v))
      (let [mid (net/new-cell-id "over-mid")
            compiled (compile-form {:network network
                                    :env (:env fval)
                                    :out mid
                                    :tail? true
                                    :result nil}
                                   (fn-body fval))]
        (install-fn-apply-list (:network compiled) mid args-list-cell out))

      (and (seq params) (plist/empty-list? args-v))
      (let [curried (church-fn params (:body fval) (:env fval))
            [_ network] (session/tell network out curried)]
        network)

      (and (seq params) (plist/church-cons? args-v))
      (let [p (first params)
            arg-cell (:a args-v)
            rest-args (:b args-v)
            [env' network] (lenv/env-extend network (:env fval) p arg-cell)
            rest-fn (church-fn (subvec params 1) (:body fval) env')
            rest-cell (net/new-cell-id "curry-fn")
            network (net/ensure-cell network rest-cell)
            [_ network] (session/tell network rest-cell rest-fn)]
        (install-fn-apply-list network rest-cell rest-args out))

      :else
      (do (println "apply: malformed arg list" (pr-str args-v)
                   "params" (pr-str params))
          network))))

(defn- install-fn-apply-list
  "Pending apply: operator cell × linked arg-list cell → `out`."
  [network op-cell args-list-cell out]
  (let [expand
        (fn [n]
          (let [fval (net/cell-value n op-cell)]
            (if-not (church-fn? fval)
              (do (println "apply: expected church-fn, got" (pr-str fval))
                  n)
              (apply-church-to-arglist n fval args-list-cell out))))]
    (ctor/pending-compound-constructor
     network expand [op-cell args-list-cell] [out])))

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
  #{'+ '- '* '/ 'cons 'car 'cdr 'empty-list? 'eq? 'origins 'neighbors})

(defn- compile-apply
  [state op args]
  (let [out (result-cell state "app")]
    (m/match op
      (m/pred primitive-ops ?p)
      (let [[state arg-cells] (compile-args state args)]
        (install-primitive-apply state ?p arg-cells out))

      ;; (apply f xs) — f and linked arg list are expressions
      'apply
      (let [state (compile-form (assoc state :tail? false :out nil) (first args))
            f-cell (:result state)
            state (compile-form (assoc state :tail? false :out nil) (second args))
            args-list-cell (:result state)
            network (install-fn-apply-list (:network state) f-cell args-list-cell out)]
        (with-result state network out))

      (m/pred symbol? ?s)
      (let [[state args-list-cell] (compile-arg-list state args)
            op-cell (net/new-cell-id "op")
            network (lenv/install-lookup (:network state) (:env state) ?s op-cell)
            network (install-fn-apply-list network op-cell args-list-cell out)]
        (with-result (assoc state :network network) network out))

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

(def l2-layers-prelude
  "L-2 layer bag on Church p-lists (env-shaped): () | (cons (cons name value) rest).

  `layer-assoc` shadows by consing; `layer-get` walks; missing → ().
  `layer-base` reads the `:base` facet — ordinary 'content' for later reactivity."
  '[(define (layer-assoc lv name value)
      (cons (cons name value) lv))
    (define (layer-get lv name)
      (if (empty-list? lv)
        ()
        (if (eq? (car (car lv)) name)
          (cdr (car lv))
          (layer-get (cdr lv) name))))
    (define (layer-base lv)
      (layer-get lv :base))])

(def l2-observer-prelude
  "L-2 Church observer — independent of layers.

  (observer observed) → (network (observe) (observe observed))
  Any value can be observed; any unary network/fn can be the observe hook.
  Compose with `l2-layers-prelude` when the observed thing is a layer bag."
  '[(define (observer observed)
      (network (observe) (observe observed)))])

(def l2-layers-tell-prelude
  "Optional: L-2 tell on layer bags (compose with layers + observer as needed).

  `tell` shadows `:base` and returns a new bag handle (pull content update)."
  (into (vec l2-layers-prelude)
        '[(define (tell lv value)
            (layer-assoc lv :base value))
          (define (observe-base lv)
            (layer-base lv))]))

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

    (m/seqable 'network (m/seqable & ?params) & ?body)
    (compile-network-fn state ?params ?body)

    (m/seqable 'do & ?forms)
    (compile-do state ?forms)

    (m/seqable 'if ?cond ?then ?else)
    (compile-if state ?cond ?then ?else)

    (m/seqable 'apply ?f ?args)
    (compile-apply state 'apply [?f ?args])

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

(defn run-with-prelude
  "Compile `prelude` forms then `forms`; return `[value network state]`."
  [prelude & forms]
  (apply run (concat (vec prelude) forms)))
