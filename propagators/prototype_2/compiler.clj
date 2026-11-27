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

  Closures: `define` / anonymous `network` are Church *lists* (no host :env):

    (cons 'church-fn (cons params (cons body bound)))

  `bound` is an env-shaped p-list of closed-over / curry bindings. Apply always
  starts from the call-site world env, then replays `bound` — same handle
  reusable under W0/W1. Top-level `define` does not capture free vars (world-
  relative); `network` captures free vars into `bound` (nested params).

  Worlds (L-2):
    (current-world)      — world list handle for the current env
    (revive w transform) — fork; transform is data (usually quoted)
    (observe w expr)     — eval expr under w (expr not compiled outer)

  Reactive (L-2, see `l2-reactive-prelude`):
    observation lists + TTMS `compatible?` / `combine-support` / `dominates?`
    `(open)` / `(next cursor v)` / `(fill hole expr)` — outside growth of list spines

  Application walks a Church p-list of argument cells:

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
            [propagators.prototype-2.amb :as amb]
            [propagators.prototype-2.support-tag :as stag]
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
;; Church fn — L-2 list: (cons 'church-fn (cons params (cons body bound)))
;; ---------------------------------------------------------------------------

(def ^:private church-fn-tag 'church-fn)

(defn- materialize-bound-list
  "Host [[name value-cell] …] (apply-order) → Church p-list, first at car."
  [network pairs]
  (let [[bind-cells network]
        (reduce (fn [[cs n] [name value-cell]]
                  (let [[name-c n] (church/fresh-cell n name "bname")
                        [ref-c n] (church/fresh-cell n value-cell "bref")
                        {:keys [network list]} (church/pair-cells n name-c ref-c)]
                    [(conj cs list) network]))
                [[] network]
                (vec pairs))
        {:keys [network list]} (church/list-from-cells network bind-cells)]
    [list network]))

(defn install-church-fn
  "Build L-2 closure spine; return `{:network :handle}`.

  Shape: `(cons 'church-fn (cons params (cons body bound)))` via `list-from-cells`.
  `bound-pairs` is [[name value-cell] …] in replay order (curry / captures)."
  ([network params body bound-pairs]
   (install-church-fn network params body bound-pairs nil))
  ([network params body bound-pairs target]
   (let [[tag-c network] (church/fresh-cell network church-fn-tag "fntag")
         [params-c network] (church/fresh-cell network (vec params) "fnparams")
         [body-c network] (church/fresh-cell network (vec body) "fnbody")
         [bound-c network] (materialize-bound-list network (vec bound-pairs))
         {:keys [network list]} (church/list-from-cells network
                                                        [tag-c params-c body-c]
                                                        bound-c)
         network (if target (church/place network list target) network)
         handle (or target list)]
     {:network network :handle handle})))

(defn church-fn-node?
  "True when cell content `v` is an L-2 church-fn cons spine."
  [network v]
  (boolean
   (and (plist/church-cons? v)
        (= church-fn-tag (net/cell-value network (:a v))))))

(defn church-fn?
  "Host predicate for a church-fn cons value (needs network to read tag).

  `(church-fn? network v)` — preferred.
  `(church-fn? v)` — true only for legacy host-map tags (compat)."
  ([v] (boolean (and (map? v) (:church/fn v))))
  ([network v] (or (church-fn-node? network v) (church-fn? v))))

(defn- fn-spine
  "Read `{params-cell body-cell bound-cell}` from a church-fn handle."
  [network handle]
  (let [c0 (net/cell-value network handle)
        c1 (net/cell-value network (:b c0))
        c2 (net/cell-value network (:b c1))]
    {:params-cell (:a c1)
     :body-cell (:a c2)
     :bound-cell (:b c2)}))

(defn fn-params-of
  [network handle]
  (let [{:keys [params-cell]} (fn-spine network handle)]
    (vec (net/cell-value network params-cell))))

(defn fn-body-of
  [network handle]
  (let [{:keys [body-cell]} (fn-spine network handle)]
    (vec (net/cell-value network body-cell))))

(defn fn-bound-cell-of
  [network handle]
  (:bound-cell (fn-spine network handle)))

(defn- bound-pairs-of
  "Walk bound p-list → [[name value-cell] …] in car→cdr (replay) order."
  [network bound-cell]
  (loop [id bound-cell
         acc []]
    (let [v (net/cell-value network id)]
      (cond
        (plist/empty-list? v) acc
        (plist/church-cons? v)
        (let [binding (net/cell-value network (:a v))
              name (net/cell-value network (:a binding))
              value-cell (net/cell-value network (:b binding))]
          (recur (:b v) (conj acc [name value-cell])))
        :else acc))))

(defn fn-bound-pairs-of
  [network handle]
  (bound-pairs-of network (fn-bound-cell-of network handle)))

(defn- env-lookup-sync
  "Synchronous env walk → value-cell id or nil (for free-var capture)."
  [network env-cell sym]
  (loop [id env-cell]
    (let [v (net/cell-value network id)]
      (cond
        (plist/empty-list? v) nil
        (plist/church-cons? v)
        (let [binding (net/cell-value network (:a v))
              name (net/cell-value network (:a binding))]
          (if (= name sym)
            (net/cell-value network (:b binding))
            (recur (:b v))))
        :else nil))))

(def ^:private special-forms
  '#{quote define network do if apply current-world revive observe open next fill})

(def primitive-ops
  #{'+ '- '* '/ '<= 'and? 'cons 'car 'cdr 'empty-list? 'eq? 'origins 'neighbors
    'compatible? 'combine-support 'dominates? 'open
    'amb-chosen 'amb-support 'require-bool 'learn 'backtrack 'apply-repair
    'repair-choices 'repair-unsat?})

(defn- free-symbols
  "Symbols in `form` not bound by `params` / inner lambdas / specials."
  [form params]
  (letfn [(walk [f scope]
            (cond
              (symbol? f)
              (if (or (contains? scope f)
                      (contains? special-forms f)
                      (contains? primitive-ops f))
                []
                [f])

              (and (seq? f) (seq f))
              (let [op (first f)]
                (cond
                  (= 'quote op) []
                  (= 'network op)
                  (let [ps (second f)
                        body (nnext f)
                        scope' (into scope ps)]
                    (mapcat #(walk % scope') body))
                  (and (= 'define op) (seq? (second f)))
                  (let [sig (second f)
                        name (first sig)
                        ps (rest sig)
                        body (nnext f)
                        scope' (into (conj scope name) ps)]
                    (mapcat #(walk % scope') body))
                  (= 'define op)
                  (walk (nth f 2) (conj scope (second f)))
                  :else
                  (mapcat #(walk % scope) f)))

              (vector? f)
              (mapcat #(walk % scope) f)

              :else []))]
    (vec (distinct (walk form (set params))))))

(defn- capture-free-bound
  "Free vars of body (minus params) resolved in `env` → bound pairs."
  [network env params body]
  (let [form (if (= 1 (count body)) (first body) (cons 'do body))
        frees (free-symbols form params)]
    (into []
          (keep (fn [sym]
                  (when-let [vc (env-lookup-sync network env sym)]
                    (when (keyword? vc) [sym vc]))))
          frees)))

(declare compile-form)

(defn- compile-literal
  [state value]
  (let [cell (result-cell state "lit")
        network (net/ensure-cell (:network state) cell)
        [tasks network] (session/tell network cell value)
        network (ev/run-tasks network tasks)]
    (with-result state network cell)))

(defn- compile-quote
  "Quote → host data literal in a cell (compiler-2-style data boundary, small).

  Symbols/numbers/keywords/() land as-is; non-empty lists stay Clojure lists
  for rewrite rules / observe. Church spines remain for computed lists."
  [state form]
  (compile-literal state form))

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
  "define (name params…) body… — L-2 church-fn list in env (letrec-style).

  No free-var capture: body free names resolve at apply against the call-site
  world env (revive shadows). Recursion via the env binding of `name`."
  [state name params body]
  (let [cell (net/new-cell-id (str name))
        network (net/ensure-cell (:network state) cell)
        [env' network] (lenv/env-extend network (:env state) name cell)
        {:keys [network]} (install-church-fn network params body [] cell)]
    (-> state
        (assoc :network network :env env' :result cell))))

(defn- compile-network-fn
  "Anonymous L-2 church-fn — capture free vars into bound (nested params).

  Same handle is world-reusable: apply uses call-site env + bound, no :env."
  [state params body]
  (let [cell (result-cell state "network")
        network (net/ensure-cell (:network state) cell)
        pairs (capture-free-bound network (:env state) params body)
        {:keys [network]} (install-church-fn network params body pairs cell)]
    (with-result state network cell)))

(defn- compile-cell-ref
  "Compile `form` to a cell. Symbols reuse the env binding cell (shared identity)
  so `(cons x hole)` / `(fill hole …)` grow the same spine."
  [state form]
  (if (symbol? form)
    (if-let [vc (env-lookup-sync (:network state) (:env state) form)]
      [state vc]
      (let [state (compile-form (assoc state :tail? false :out nil) form)]
        [state (:result state)]))
    (let [state (compile-form (assoc state :tail? false :out nil) form)]
      [state (:result state)])))

(defn- compile-open
  "L-2 `(open)` — fresh cell left as `nothing` (reserved list slot / hole)."
  [state]
  (let [out (result-cell state "open")
        network (net/ensure-cell (:network state) out)]
    (with-result state network out)))

(defn- compile-fill
  "L-2 `(fill place expr)` — write `expr` into `place`'s binding cell (in-place).

  Typical close: `(fill cursor ())`."
  [state place value-form]
  (when-not (symbol? place)
    (throw (ex-info "fill: place must be a symbol" {:place place})))
  (let [vc (env-lookup-sync (:network state) (:env state) place)]
    (when-not (keyword? vc)
      (throw (ex-info "fill: unbound place" {:place place})))
    (let [state (compile-form (assoc state :out vc :tail? true) value-form)]
      (with-result state (:network state) vc))))

(defn- compile-next
  "L-2 `(next cursor value)` — outside growth of an open list.

  Writes `(cons value new-open)` into `cursor`'s binding cell and returns
  `new-open` so `(define c (next c v))` advances the write head while the
  original root (first `open`) stays map-able."
  [state cursor value-form]
  (when-not (symbol? cursor)
    (throw (ex-info "next: cursor must be a symbol" {:cursor cursor})))
  (let [vc (env-lookup-sync (:network state) (:env state) cursor)]
    (when-not (keyword? vc)
      (throw (ex-info "next: unbound cursor" {:cursor cursor})))
    (let [new-open (net/new-cell-id "next-open")
          network (net/ensure-cell (:network state) new-open)
          state (assoc state :network network)
          state (compile-form (assoc state :tail? false :out nil) value-form)
          val-cell (:result state)
          ;; cons into the cursor cell; result of next is the new open cdr
          network (lists/install-cons-prop (:network state) val-cell new-open vc)]
      (with-result (assoc state :network network) network new-open))))

(defn- install-primitive-apply
  [state op arg-cells out]
  (let [network (:network state)
        network
        (m/match op
          '+  (arith/install-add network (arg-cells 0) (arg-cells 1) out)
          '-  (arith/install-sub network (arg-cells 0) (arg-cells 1) out)
          '*  (arith/install-mul network (arg-cells 0) (arg-cells 1) out)
          '/  (arith/install-div network (arg-cells 0) (arg-cells 1) out)
          '<= (arith/install-le network (arg-cells 0) (arg-cells 1) out)
          'and? (lists/install-and?-prop network (arg-cells 0) (arg-cells 1) out)
          'cons (lists/install-cons-prop network (arg-cells 0) (arg-cells 1) out)
          'car  (lists/install-car-prop network (arg-cells 0) out)
          'cdr  (lists/install-cdr-prop network (arg-cells 0) out)
          'empty-list? (lists/install-empty-list?-prop network (arg-cells 0) out)
          'eq? (lists/install-eq?-prop network (arg-cells 0) (arg-cells 1) out)
          'origins (trace/install-origins-prop network (arg-cells 0) out)
          'neighbors (trace/install-neighbors-prop network (arg-cells 0) out)
          'compatible? (stag/install-compatible?-prop network (arg-cells 0) (arg-cells 1) out)
          'combine-support (stag/install-combine-support-prop network (arg-cells 0) (arg-cells 1) out)
          'dominates? (stag/install-dominates?-prop network (arg-cells 0) (arg-cells 1) out)
          'amb-chosen (amb/install-amb-chosen-prop network (arg-cells 0) out)
          'amb-support (amb/install-amb-support-prop network (arg-cells 0) out)
          'require-bool (amb/install-require-bool-prop network (arg-cells 0) (arg-cells 1) out)
          'learn (amb/install-learn-prop network (arg-cells 0) (arg-cells 1) (arg-cells 2) out)
          'backtrack (amb/install-backtrack-prop network (arg-cells 0) (arg-cells 1) out)
          'apply-repair (amb/install-apply-repair-prop network (arg-cells 0) (arg-cells 1) out)
          'repair-choices (amb/install-repair-choices-prop network (arg-cells 0) (arg-cells 1) out)
          'repair-unsat? (amb/install-repair-unsat?-prop network (arg-cells 0) out)
          'open (throw (ex-info "open takes no args" {}))
          _ (throw (ex-info "unknown primitive" {:op op})))]
    (with-result state network out)))

(defn- fn-body-form
  [body]
  (let [body (vec body)]
    (if (= 1 (count body))
      (first body)
      (cons 'do body))))

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
  (let [{:keys [network list]} (church/list-from-cells network arg-cells)]
    [list network]))

(defn- compile-arg-list
  "Compile arg forms and cons them into one linked argument-list cell."
  [state arg-forms]
  (let [out (:out state)
        tail? (:tail? state)
        [state cells] (compile-args state arg-forms)
        [list-cell network] (materialize-arg-list (:network state) cells)]
    [(assoc state :network network :out out :tail? tail?) list-cell]))

(declare install-fn-apply-list)

(defn- replay-bound-pairs
  "env-extend each [param value-cell] onto `env` (replay order)."
  [network env pairs]
  (reduce (fn [[e n] [p a]]
            (lenv/env-extend n e p a))
          [env network]
          pairs))

(defn- apply-church-to-arglist
  "Walk L-2 church-fn handle against arg p-list under call-site `env-cell`.

  Base env = call-site; then replay bound pairs. No captured creation env.
  - args nothing → leave unbuilt (open tail / long-lived)
  - params left + args () → curry with extended bound
  - no params + args () → compile body into `out`
  - no params + args cons → compile body to mid, apply mid to same arg list
  - params + args cons → record bind, continue on cdr"
  [network fn-cell args-list-cell env-cell out]
  (let [args-v (net/cell-value network args-list-cell)
        params (fn-params-of network fn-cell)
        body (fn-body-of network fn-cell)
        pairs (fn-bound-pairs-of network fn-cell)
        [env0 network] (replay-bound-pairs network env-cell pairs)]
    (cond
      (t/nothing? args-v)
      network

      (and (empty? params) (plist/empty-list? args-v))
      (let [compiled (compile-form {:network network
                                    :env env0
                                    :out out
                                    :tail? true
                                    :result nil}
                                   (fn-body-form body))]
        (:network compiled))

      (and (empty? params) (plist/church-cons? args-v))
      (let [mid (net/new-cell-id "over-mid")
            compiled (compile-form {:network network
                                    :env env0
                                    :out mid
                                    :tail? true
                                    :result nil}
                                   (fn-body-form body))]
        (install-fn-apply-list (:network compiled) mid args-list-cell env-cell out))

      (and (seq params) (plist/empty-list? args-v))
      (let [{:keys [network]} (install-church-fn network params body pairs out)]
        network)

      (and (seq params) (plist/church-cons? args-v))
      (let [p (first params)
            arg-cell (:a args-v)
            rest-args (:b args-v)
            rest-pairs (conj pairs [p arg-cell])
            rest-cell (net/new-cell-id "curry-fn")
            {:keys [network]} (install-church-fn network (subvec params 1) body
                                                 rest-pairs rest-cell)]
        (install-fn-apply-list network rest-cell rest-args env-cell out))

      :else
      (do (println "apply: malformed arg list" (pr-str args-v)
                   "params" (pr-str params))
          network))))

(defn- install-fn-apply-list
  "Pending apply: operator × arg-list × call-site env → `out`."
  [network op-cell args-list-cell env-cell out]
  (let [expand
        (fn [n]
          (let [fval (net/cell-value n op-cell)]
            (if-not (church-fn-node? n fval)
              (do (println "apply: expected church-fn, got" (pr-str fval))
                  n)
              (apply-church-to-arglist n op-cell args-list-cell env-cell out))))]
    (ctor/pending-compound-constructor
     network expand [op-cell args-list-cell env-cell] [out])))

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

(defn- world-var
  "Resolve a `world` ns var at call time (avoids compiler↔world load cycle)."
  [sym]
  (requiring-resolve (symbol "propagators.prototype-2.world" (name sym))))

(defn- compile-current-world
  "L-2 `(current-world)` → world list handle for `:env`."
  [state]
  (let [out (result-cell state "cur-world")
        install (world-var 'install-current-world-prop)
        network (install (:network state) (:env state) out)]
    (with-result state network out)))

(defn- compile-revive
  "L-2 `(revive w transform)` — transform compiled (usually quoted data)."
  [state w-form transform-form]
  (let [out (result-cell state "revive")
        state (compile-form (assoc state :tail? false :out nil) w-form)
        w-cell (:result state)
        state (compile-form (assoc state :tail? false :out nil) transform-form)
        t-cell (:result state)
        install (world-var 'install-revive-prop)
        network (install (:network state) w-cell t-cell out)]
    (with-result state network out)))

(defn- compile-observe
  "L-2 `(observe w expr)` — `expr` is host form, compiled under w's env only."
  [state w-form expr]
  (let [out (result-cell state "observe")
        state (compile-form (assoc state :tail? false :out nil) w-form)
        w-cell (:result state)
        install (world-var 'install-observe-prop)
        network (install (:network state) w-cell expr out)]
    (with-result state network out)))

(defn- compile-apply
  [state op args]
  (let [out (result-cell state "app")]
    (m/match op
      'open
      (compile-open state)

      ;; cons shares binding cells for symbol args (open-hole identity)
      'cons
      (let [[state a] (compile-cell-ref state (first args))
            [state b] (compile-cell-ref state (second args))
            network (lists/install-cons-prop (:network state) a b out)]
        (with-result state network out))

      (m/pred primitive-ops ?p)
      (let [[state arg-cells] (compile-args state args)]
        (install-primitive-apply state ?p arg-cells out))

      ;; (apply f xs) — f and linked arg list are expressions
      'apply
      (let [state (compile-form (assoc state :tail? false :out nil) (first args))
            f-cell (:result state)
            state (compile-form (assoc state :tail? false :out nil) (second args))
            args-list-cell (:result state)
            network (install-fn-apply-list (:network state) f-cell args-list-cell
                                           (:env state) out)]
        (with-result state network out))

      (m/pred symbol? ?s)
      (let [[state args-list-cell] (compile-arg-list state args)
            op-cell (net/new-cell-id "op")
            network (lenv/install-lookup (:network state) (:env state) ?s op-cell)
            network (install-fn-apply-list network op-cell args-list-cell
                                           (:env state) out)]
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

(def l2-reactive-prelude
  "Reactive values = Church lists of observations `(cons base support)`.

  TTMS concurrency via `compatible?` / `combine-support` / `dominates?`.
  Grow spines outside-in with `open` / `next` / `fill`.

  Cell project = incremental dataflow reducer over a lattice frontier:
    `front-add` / `tms-join` / `tms-front-reduce` / `tms-project` (tip view).
  Join is commutative, associative, idempotent — partial aggregates merge
  under async arrival. Tip view is `car` of the undominated frontier.

  DDB (`ddb-pyth`): nogoods from `learn` on require failure supports — not from
  projecting the `attempts` log. See `propagators/doc/prototype-2-ddb-rx.md`.

  `(supported-map f xss)` — Church fn over a list of spines."
  '[(define (tip rx) (car rx))
    (define (obs-base o) (car o))
    (define (obs-support o) (cdr o))
    (define (observation v s) (cons v s))
    (define (extend rx obs) (cons obs rx))
    (define (rss-empty? rss)
      (if (empty-list? rss)
        false
        (if (empty-list? (car rss))
          true
          (rss-empty? (cdr rss)))))
    (define (rss-cars rss)
      (if (empty-list? rss)
        ()
        (cons (car (car rss)) (rss-cars (cdr rss)))))
    (define (rss-cdrs rss)
      (if (empty-list? rss)
        ()
        (cons (cdr (car rss)) (rss-cdrs (cdr rss)))))
    (define (obs-list-bases os)
      (if (empty-list? os)
        ()
        (cons (obs-base (car os)) (obs-list-bases (cdr os)))))
    (define (obs-list-supports os)
      (if (empty-list? os)
        ()
        (cons (obs-support (car os)) (obs-list-supports (cdr os)))))
    (define (combine-supports ss)
      (if (empty-list? ss)
        ()
        (if (empty-list? (cdr ss))
          (car ss)
          (combine-support (car ss) (combine-supports (cdr ss))))))
    (define (supports-ok? ss)
      (if (empty-list? ss)
        true
        (if (empty-list? (cdr ss))
          true
          (compatible? (car ss) (combine-supports (cdr ss))))))
    (define (supported-map f xss)
      (if (rss-empty? xss)
        ()
        (if (supports-ok? (obs-list-supports (rss-cars xss)))
          (cons (observation (apply f (obs-list-bases (rss-cars xss)))
                             (combine-supports (obs-list-supports (rss-cars xss))))
                (supported-map f (rss-cdrs xss)))
          (supported-map f (rss-cdrs xss)))))
    (define (add a b) (+ a b))
    (define (add3 a b c) (+ (+ a b) c))
    (define (supported-map-plus xs ys)
      (supported-map add (cons xs (cons ys ()))))
    (define (supported-plus rx ry)
      (if (compatible? (obs-support (tip rx)) (obs-support (tip ry)))
        (extend ()
          (observation (+ (obs-base (tip rx)) (obs-base (tip ry)))
                       (combine-support (obs-support (tip rx))
                                        (obs-support (tip ry)))))
        ()))
    (define (retract-tip rx s)
      (extend rx (observation (obs-base (tip rx)) s)))
    (define (amb-join shared left right)
      (if (compatible? (obs-support (tip shared)) (obs-support (tip left)))
        (supported-plus shared left)
        (if (compatible? (obs-support (tip shared)) (obs-support (tip right)))
          (supported-plus shared right)
          ())))

    ;; --- TMS as incremental dataflow reducer (lattice frontier join) ---
    ;; Aggregate state = undominated frontier (list of observations).
    ;; front-add / tms-join are commutative, associative, idempotent — async-safe.
    (define (tms-better? a b)
      (dominates? (obs-support a) (obs-support b)))
    (define (tms-reduce f acc rx)
      (if (empty-list? rx)
        acc
        (tms-reduce f (f acc (car rx)) (cdr rx))))
    (define (tms-scan f acc rx)
      (if (empty-list? rx)
        ()
        (cons (f acc (car rx))
              (tms-scan f (f acc (car rx)) (cdr rx)))))
    ;; Assimilate one observation into a frontier (drop dominated / dups)
    (define (front-add front obs)
      (if (empty-list? front)
        (cons obs ())
        (if (tms-better? (car front) obs)
          front
          (if (tms-better? obs (car front))
            (front-add (cdr front) obs)
            (if (eq? (obs-support (car front)) (obs-support obs))
              front
              (cons (car front) (front-add (cdr front) obs)))))))
    ;; Join two partial reduce states (frontiers) — C / A / I lattice join
    (define (tms-join f1 f2)
      (if (empty-list? f2)
        f1
        (tms-join (front-add f1 (car f2)) (cdr f2))))
    (define (tms-join-obs a b)
      (tms-join (cons a ()) (cons b ())))
    ;; Incremental dataflow step: frontier × observation → frontier
    (define (tms-step front cand)
      (front-add front cand))
    (define (tms-front-reduce rx)
      (tms-reduce front-add () rx))
    ;; Tip view: car of frontier (singleton when supports totally order)
    (define (tms-project rx)
      (define f (tms-front-reduce rx))
      (if (empty-list? f) () (car f)))
    (define (tms-merge rx obs)
      (tms-project (extend rx obs)))
    (define (tms-scan-front rx)
      (tms-scan front-add () rx))
    ;; Dominating chains: each partial tip after joining cand into {best}
    (define (tms-scan-tip best cand)
      (car (front-add (cons best ()) cand)))
    (define (tms-scan-obs rx)
      (if (empty-list? rx)
        ()
        (cons (car rx)
              (tms-scan tms-scan-tip (car rx) (cdr rx)))))

    ;; --- In-network amb/require/switch via tip publish (no cell overwrite) ---
    (define (supported-under v s)
      (extend () (observation v s)))
    (define (world-observation v s)
      (observation v s))
    (define (amb-tip decision)
      (observation (amb-chosen decision) (amb-support decision)))
    (define (amb-publish rx decision)
      (extend rx (amb-tip decision)))
    (define (require-rx ok? s)
      (supported-under (require-bool ok? s) s))
    (define (switch cond? v)
      (if cond? v ()))
    (define (switch-rx cond? v s)
      (if cond? (supported-under v s) ()))
    (define (backtrack-unless ok? decision knowledge)
      (apply-repair decision
                    (backtrack (cons decision ())
                               (learn knowledge
                                      (require-bool ok? (amb-support decision))
                                      (cons decision ())))))
    ;; One repair round: publish old tip, repair, publish new tip, project
    (define (amb-repair-step choice-rx decision knowledge ok?)
      (tms-project
       (amb-publish (amb-publish choice-rx decision)
                    (backtrack-unless ok? decision knowledge))))

    ;; --- Propagator-sense DDB (tip publish + L-2 recursion, no host solve loop) ---
    (define (choices3 ca cb cc)
      (cons ca (cons cb (cons cc ()))))
    (define (support3 ca cb cc)
      (combine-support (amb-support ca)
                       (combine-support (amb-support cb) (amb-support cc))))
    (define (pyth-ok? ca cb cc)
      (and? (<= (amb-chosen ca) (amb-chosen cb))
            (and? (<= (amb-chosen cb) (amb-chosen cc))
                  (eq? (+ (* (amb-chosen ca) (amb-chosen ca))
                          (* (amb-chosen cb) (amb-chosen cb)))
                       (* (amb-chosen cc) (amb-chosen cc))))))
    (define (ddb-pyth-continue ca cb cc kn attempts cert)
      (define kn2 (learn kn cert (choices3 ca cb cc)))
      (define req (backtrack (choices3 ca cb cc) kn2))
      (if (repair-unsat? req)
        ()
        (do
          (define cs (repair-choices (choices3 ca cb cc) req))
          (ddb-pyth (car cs) (car (cdr cs)) (car (cdr (cdr cs))) kn2 attempts))))
    (define (ddb-pyth ca cb cc kn attempts)
      (define ok? (pyth-ok? ca cb cc))
      (define attempts2
        (extend attempts
                (observation (cons (amb-chosen ca)
                                   (cons (amb-chosen cb)
                                         (cons (amb-chosen cc) ())))
                             (support3 ca cb cc))))
      (if ok?
        (cons (amb-chosen ca)
              (cons (amb-chosen cb)
                    (cons (amb-chosen cc) ())))
        (ddb-pyth-continue ca cb cc kn attempts2
                           (require-bool ok? (support3 ca cb cc)))))])

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

    (m/pred boolean? ?b)
    (compile-literal state ?b)

    (m/pred string? ?s)
    (compile-literal state ?s)

    (m/pred keyword? ?k)
    (compile-literal state ?k)

    (m/pred symbol? ?s)
    (compile-symbol state ?s)

    (m/seqable)
    (compile-literal state plist/empty-list)

    (m/seqable 'quote ?x)
    (compile-quote state ?x)

    (m/seqable 'current-world)
    (compile-current-world state)

    (m/seqable 'revive ?w ?transform)
    (compile-revive state ?w ?transform)

    (m/seqable 'observe ?w ?expr)
    (compile-observe state ?w ?expr)

    (m/seqable 'open)
    (compile-open state)

    (m/seqable 'next (m/pred symbol? ?cursor) ?value)
    (compile-next state ?cursor ?value)

    (m/seqable 'fill (m/pred symbol? ?place) ?expr)
    (compile-fill state ?place ?expr)

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
