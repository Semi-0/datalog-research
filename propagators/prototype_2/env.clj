(ns propagators.prototype-2.env
  "Lexical environment as a Church p-list of bindings (compiler-2 style, small).

  env      ::=  ()  |  (cons binding parent)
  binding  ::=  (cons name value-cell-id)

  Frames are primitive compound linked-list values (church-cons). Lookup is a
  pending compound that walks one frame at a time — lazily applied network."
  (:require [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(defn empty-env
  "Fresh env cell holding empty-list (`'()`)."
  ([network]
   (let [env-cell (net/new-cell-id "env")
         network (net/ensure-cell network env-cell)
         [tasks network] (session/tell network env-cell plist/empty-list)]
     [env-cell (ev/run-tasks network tasks)])))

(defn env-extend
  "Extend env: new-env = (cons (cons name value-cell) parent).

  Builds the two church-cons spine values eagerly (compound objects), so the
  frame is a real linked list in cells. Returns `[new-env-cell network]`."
  [network parent-env name value-cell]
  (let [name-cell (net/new-cell-id "ename")
        ref-cell (net/new-cell-id "eref")
        bind-cell (net/new-cell-id "ebind")
        new-env (net/new-cell-id "env")
        network (net/ensure-cells network
                                  [name-cell ref-cell bind-cell new-env
                                   parent-env value-cell])
        [t1 network] (session/tell network name-cell name)
        [t2 network] (session/tell network ref-cell value-cell)
        network (ev/run-tasks network (into t1 t2))
        binding (plist/church-cons name-cell ref-cell nil)
        frame (plist/church-cons bind-cell parent-env nil)
        network (net/assoc-value network bind-cell (t/cell binding))
        network (net/assoc-value network new-env (t/cell frame))]
    [new-env network]))

(defn install-lookup
  "Lexical access compound: walk env p-list; on match, id-sync value → `out`.

  One frame per expand; miss installs lookup on the parent cdr (lazy fall-through)."
  [network env-cell sym out]
  (let [expand
        (fn [n]
          (let [v (net/cell-value n env-cell)]
            (cond
              (plist/empty-list? v)
              (do (println "lookup: unbound" (pr-str sym))
                  n)

              (plist/church-cons? v)
              (let [binding (net/cell-value n (:a v))]
                (if-not (plist/church-cons? binding)
                  (do (println "lookup: malformed binding" (pr-str binding))
                      n)
                  (let [bound-name (net/cell-value n (:a binding))
                        value-cell (net/cell-value n (:b binding))]
                    (if (= bound-name sym)
                      (if (keyword? value-cell)
                        (prim/install-id n value-cell out)
                        (do (println "lookup: bad value-cell ref" (pr-str value-cell))
                            n))
                      (install-lookup n (:b v) sym out)))))

              :else
              (do (println "lookup: env not a list" (pr-str v))
                  n))))]
    (ctor/pending-compound-constructor network expand [env-cell] [out]
                                       (keyword (str "lookup-" (name sym))))))
