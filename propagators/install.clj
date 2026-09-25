(ns propagators.install
  "Threaded installer API for named network declarations.

  This namespace is an authoring surface over existing installers. It resolves
  cell names to stable node ids, emits network declaration patches, and leaves
  propagation to the constructed runner."
  (:refer-clojure :exclude [+ - * / <= not and or when cons])
  (:require [clojure.core :as c]
            [propagators.core :as kernel]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.reducer-cell :as reducer-cell]
            [propagators.datastructures.tms :as tms]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.gur.flat :as fvm]
            [propagators.propagator :as p]
            [propagators.stdlib.prop :as prop]))

(def cell-bindings-scope-tag :propagators.install/cells)

(defn context
  [network scope]
  {:net (fvm/vm-net network)
   :scope scope
   :cells {}
   :effects []
   :messages []})

(defn effects
  [ctx]
  (:effects ctx))

(defn result
  [ctx]
  {:effects (:effects ctx)
   :messages (:messages ctx)})

(defn context?
  [x]
  (c/and (map? x)
         (contains? x :net)
         (contains? x :scope)
         (contains? x :effects)
         (contains? x :messages)))

(defn- emit
  [ctx effect]
  (update ctx :effects conj effect))

(defn- emit-all
  [ctx effects]
  (update ctx :effects into effects))

(defn- emit-message
  [ctx m]
  (update ctx :messages conj m))

(defn- deterministic-id-fn
  [install-key]
  (let [counter (atom -1)]
    (fn []
      (fvm/stable-node-id [:installer install-key (swap! counter inc)]))))

(defn- installer-prop-ids
  [result]
  (->> (tree-seq sequential? seq result)
       (filter ids/node-id?)
       vec))

(defn- prop-declaration
  [n prop-id]
  (let [node (graph/get-node (net/net-graph n) prop-id)
        prop (net/network-env-lookup n prop-id)]
    (fvm/declare-prop prop-id
                      (p/prop-name prop)
                      (vec (graph/node-input-ids node))
                      (vec (graph/node-output-ids node))
                      (p/prop-f prop))))

(defn- installer-effects*
  [base-net install-key cell-ids installer-f]
  (let [cell-ids (vec (distinct cell-ids))
        n0 (reduce nb/ensure-cell (fvm/vm-net base-net) cell-ids)
        [ids* n*] (with-redefs [ids/new-node-id
                                (deterministic-id-fn install-key)]
                    ((installer-f) n0))
        prop-ids (installer-prop-ids ids*)]
    (vec (concat (map fvm/declare-cell cell-ids)
                 (map #(prop-declaration n* %) prop-ids)))))

(defn installer-effects
  "Expand an existing installer function into bounded flat VM declarations."
  [base-net install-key cell-ids installer]
  (installer-effects* base-net install-key cell-ids (constantly installer)))

(defn- emit-installer
  [ctx install-key cell-ids installer]
  (emit-all ctx (installer-effects* (:net ctx) install-key cell-ids installer)))

(defn- cell-scope
  [ctx]
  [cell-bindings-scope-tag (:scope ctx)])

(defn- dict-cell-id
  [ctx name]
  (get-in (net/network-dict-entry (:net ctx) fvm/name-bindings-key)
          [(cell-scope ctx) name]))

(defn- stable-cell-id
  [ctx name]
  (fvm/stable-node-id [cell-bindings-scope-tag (:scope ctx) name]))

(defn- named-cell-id
  [ctx name]
  (c/or (get-in ctx [:cells name])
        (dict-cell-id ctx name)
        (stable-cell-id ctx name)))

(defn cell-id
  [ctx x]
  (cond
    (ids/node-id? x) x
    (c/or (keyword? x) (symbol? x)) (named-cell-id ctx x)
    :else
    (throw (ex-info "install argument is not a cell name or NodeId"
                    {:argument x :scope (:scope ctx)}))))

(defn bind-cell
  [ctx name id]
  (when-not (c/or (keyword? name) (symbol? name))
    (throw (ex-info "cell binding name must be a keyword or symbol"
                    {:name name})))
  (when-not (ids/node-id? id)
    (throw (ex-info "cell binding id must be a NodeId"
                    {:name name :id id})))
  (-> ctx
      (assoc-in [:cells name] id)
      (emit (fvm/bind-name (cell-scope ctx) name id))
      (emit (fvm/declare-cell id))))

(defn $
  ([ctx bindings]
   (if (map? bindings)
     (reduce-kv bind-cell ctx bindings)
     (throw (ex-info "$ expects a map or names/ids vectors"
                     {:bindings bindings}))))
  ([ctx names ids]
   (when-not (= (count names) (count ids))
     (throw (ex-info "$ names and ids must have the same count"
                     {:names names :ids ids})))
   (reduce (fn [ctx* [name id]]
             (bind-cell ctx* name id))
           ctx
           (map vector names ids))))

(defn- ensure-cell-name
  [ctx x]
  (if (c/or (keyword? x) (symbol? x))
    (let [id (cell-id ctx x)]
      (-> ctx
          (assoc-in [:cells x] id)
          (emit (fvm/bind-name (cell-scope ctx) x id))
          (emit (fvm/declare-cell id))))
    ctx))

(defn- resolve-arg
  [ctx x]
  (if (ids/node-id? x)
    [(emit ctx (fvm/declare-cell x)) x]
    [(ensure-cell-name ctx x) (cell-id ctx x)]))

(defn- resolve-args
  [ctx args]
  (reduce (fn [[ctx* ids] arg]
            (let [[ctx** id] (resolve-arg ctx* arg)]
              [ctx** (conj ids id)]))
          [ctx []]
          args))

(defn install*
  [ctx tag installer args]
  (let [[ctx* ids] (resolve-args ctx args)]
    (emit-installer ctx* [(:scope ctx*) tag ids] ids #(apply installer ids))))

(defn install
  [& xs]
  (if (context? (first xs))
    (let [[ctx tag installer & args] xs]
      (install* ctx tag installer args))
    (let [[tag installer & args] xs]
      (fn [ctx]
        (install* ctx tag installer args)))))

(defn prop
  ([tag inputs outputs activate]
   (fn [ctx]
     (prop ctx tag inputs outputs activate)))
  ([ctx tag inputs outputs activate]
   (let [[ctx* input-ids] (resolve-args ctx inputs)
         [ctx** output-ids] (resolve-args ctx* outputs)
         prop-id (fvm/stable-node-id [(:scope ctx**) tag input-ids output-ids])]
     (emit ctx**
           (fvm/declare-prop prop-id tag input-ids output-ids activate)))))

(defn tell
  ([target value]
   (fn [ctx]
     (tell ctx target value)))
  ([ctx target value]
   (let [[ctx* id] (resolve-arg ctx target)]
     (emit-message ctx* (message id value)))))

(defn commit
  [ctx]
  (let [[_tasks n] (kernel/eval-activation-result (result ctx) (:net ctx))]
    n))

(defn run
  [ctx]
  (let [[tasks n] (kernel/eval-activation-result (result ctx) (:net ctx))]
    (nb/run-propagators n tasks)))

(defn relation
  [tag installer]
  (fn [& xs]
    (if (context? (first xs))
      (let [[ctx & args] xs]
        (install* ctx tag installer args))
      (fn [ctx]
        (install* ctx tag installer xs)))))

(def id-prop (relation :id prop/id))
(def copy id-prop)
(def + (relation :+ prop/+))
(def - (relation :- prop/-))
(def * (relation :* prop/*))
(def / (relation :/ prop//))
(def <= (relation :<= prop/<=))
(def not (relation :not prop/not))
(def and (relation :and prop/and))
(def or (relation :or prop/or))
(def nothing? (relation :nothing? prop/nothing?))
(def switch (relation :switch prop/switch))

(def car (relation :car obj/p:car))
(def cdr (relation :cdr obj/p:cdr))
(def cons (relation :cons obj/p:cons))

(defn slot
  ([slot-key elem coll]
   (fn [ctx]
     (slot ctx slot-key elem coll)))
  ([ctx slot-key elem coll]
   (let [[ctx* elem-id] (resolve-arg ctx elem)
         [ctx** coll-id] (resolve-arg ctx* coll)]
     (emit-installer ctx**
                     [(:scope ctx**) :slot slot-key elem-id coll-id]
                     [elem-id coll-id]
                     #(obj/p:slot slot-key elem-id coll-id)))))

(defn- reducer-slot*
  [ctx reducer-id args]
  (let [[merge-net strongest-net slot-key value reducer]
        (if (= 5 (count args))
          args
          (throw (ex-info "reducer-slot expects merge net and strongest net"
                          {:reducer-id reducer-id :args args})))
        [ctx* value-id] (resolve-arg ctx value)
        [ctx** reducer-id*] (resolve-arg ctx* reducer)]
    (emit-installer ctx**
                    [(:scope ctx**)
                     :reducer-slot
                     reducer-id
                     (some-> merge-net pr-str hash)
                     (hash (pr-str strongest-net))
                     slot-key
                     value-id
                     reducer-id*]
                    [value-id reducer-id*]
                    #(reducer-cell/p:reducer-slot reducer-id
                                                  merge-net
                                                  strongest-net
                                                  slot-key
                                                  value-id
                                                  reducer-id*))))

(defn reducer-slot
  [& xs]
  (if (context? (first xs))
    (let [[ctx reducer-id & args] xs]
      (reducer-slot* ctx reducer-id args))
    (let [[reducer-id & args] xs]
      (fn [ctx]
        (reducer-slot* ctx reducer-id args)))))

(def reduced-result
  (relation :reduced-result reducer-cell/p:reduced-result))

(defn tms-premise
  ([tms-id premise epoch active reducer]
   (fn [ctx]
     (tms-premise ctx tms-id premise epoch active reducer)))
  ([ctx tms-id premise epoch active reducer]
   (let [[ctx* active-id] (resolve-arg ctx active)
         [ctx** reducer-id*] (resolve-arg ctx* reducer)]
     (emit-installer ctx**
                     [(:scope ctx**)
                      :tms-premise
                      tms-id
                      premise
                     epoch
                     active-id
                     reducer-id*]
                     [active-id reducer-id*]
                     #(tms/p:tms-premise tms-id
                                         premise
                                         epoch
                                         active-id
                                         reducer-id*)))))

(defn tms-premise-source
  ([tms-id premise epoch active reducer]
   (fn [ctx]
     (tms-premise-source ctx tms-id premise epoch active reducer)))
  ([ctx tms-id premise epoch active reducer]
   (let [[ctx* premise-id] (resolve-arg ctx premise)
         [ctx** active-id] (resolve-arg ctx* active)
         [ctx*** reducer-id*] (resolve-arg ctx** reducer)]
     (emit-installer ctx***
                     [(:scope ctx***)
                      :tms-premise-source
                      tms-id
                      premise-id
                      epoch
                      active-id
                      reducer-id*]
                     [premise-id active-id reducer-id*]
                     #(tms/p:tms-premise-source tms-id
                                                premise-id
                                                epoch
                                                active-id
                                                reducer-id*)))))

(defn tms-claim
  ([tms-id claim-id proposition supports value reducer]
   (fn [ctx]
     (tms-claim ctx tms-id claim-id proposition supports value reducer)))
  ([ctx tms-id claim-id proposition supports value reducer]
   (let [[ctx* value-id] (resolve-arg ctx value)
         [ctx** reducer-id*] (resolve-arg ctx* reducer)]
     (emit-installer ctx**
                     [(:scope ctx**)
                      :tms-claim
                      tms-id
                      claim-id
                      proposition
                      supports
                     value-id
                     reducer-id*]
                     [value-id reducer-id*]
                     #(tms/p:tms-claim tms-id
                                       claim-id
                                       proposition
                                       supports
                                       value-id
                                       reducer-id*)))))

(defn tms-proposition
  ([proposition reducer out]
   (fn [ctx]
     (tms-proposition ctx proposition reducer out)))
  ([ctx proposition reducer out]
   (let [[ctx* reducer-id*] (resolve-arg ctx reducer)
         [ctx** out-id] (resolve-arg ctx* out)]
     (emit-installer ctx**
                     [(:scope ctx**)
                      :tms-proposition
                      proposition
                      reducer-id*
                      out-id]
                     [reducer-id* out-id]
                     #(tms/p:tms-proposition reducer-id*
                                             proposition
                                             out-id)))))

(defn >>
  ([closure arg out]
   (fn [ctx]
     (>> ctx closure arg out)))
  ([ctx closure arg out]
   (let [apply-f (:apply ctx)]
     (when-not apply-f
       (throw (ex-info "closure application is unavailable in this install context"
                       {:scope (:scope ctx)})))
     (let [[ctx* closure-id] (resolve-arg ctx closure)
           arg-list (if (vector? arg) arg [arg])
           [ctx** arg-ids] (resolve-args ctx* arg-list)
           [ctx*** out-id] (resolve-arg ctx** out)]
       (emit ctx*** (apply-f closure-id arg-ids out-id))))))

(defn- recur*
  [ctx args out]
  (let [recur-f (:recur ctx)]
    (when-not recur-f
      (throw (ex-info "recursive application is unavailable in this install context"
                      {:scope (:scope ctx)})))
    (let [[ctx* arg-ids] (resolve-args ctx args)
          [ctx** out-id] (resolve-arg ctx* out)]
      (emit ctx** (recur-f arg-ids out-id)))))

(defn recur
  ([args out]
   (fn [ctx]
     (recur* ctx args out)))
  ([ctx args out]
   (recur* ctx args out)))

(defn when-named
  "Declare `body-transform` once when `condition` becomes usable.

  `role` is part of the stable topology identity, so callers can name the
  semantic branch instead of depending on its position in an effect vector."
  ([role condition body-transform]
   (fn [ctx]
     (when-named ctx role condition body-transform)))
  ([ctx role condition body-transform]
   (let [when-f (:when ctx)]
     (cond
       (nil? when-f)
       (throw
        (ex-info "lazy topology availability is unavailable in this install context"
                 {:scope (:scope ctx)
                  :role role
                  :condition condition}))

       :else
       (let [[resolved condition-id] (resolve-arg ctx condition)
             when-key [(:scope resolved) :when role condition-id]
             body (fn []
                    (result
                     (body-transform
                      (assoc resolved :effects [] :messages []))))]
         (emit resolved (when-f when-key condition-id body)))))))

(defn when
  ([condition body-transform]
   (fn [ctx]
     (when ctx condition body-transform)))
  ([ctx condition body-transform]
   (when-named ctx
               [:effect-position (count (:effects ctx))]
               condition
               body-transform)))
