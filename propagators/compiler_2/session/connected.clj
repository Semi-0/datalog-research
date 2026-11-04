(ns propagators.compiler-2.session.connected
  "Connected immutable declarations. No resource, worker, or host dependency."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.compound-object :as object]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(deftype Frozen [content]
  Object
  (equals [_ other] (and (instance? Frozen other) (= content (.-content ^Frozen other))))
  (hashCode [_] (hash content))
  (toString [_] (pr-str content)))
(defn frozen [v] (if (instance? Frozen v) v (Frozen. v)))
(defn payload [v] (if (instance? Frozen v) (.-content ^Frozen v) v))

(def declarations-key ::declarations)
(def pending ::pending)

(defn freeze
  "Resolve a finite connected data value. Unknown fields keep a declaration pending.
  Callables and lexical references are opaque. This is serialization, not selection."
  ([network v] (freeze network v #{}))
  ([network v seen]
   (let [v (payload (data/payload v))]
     (cond
       (value/unusable? v) pending
       (object/accessor-network? v)
       (if (contains? seen v) pending
           (let [fields (into {} (map (fn [k] [k (freeze network (data/slot-value network v k) (conj seen v))]))
                              (object/accessor-slot-keys v))]
             (if (some #{pending} (vals fields)) pending fields)))
       :else v))))

(defn thaw [v]
  (if (and (map? v) (not (record? v)))
    (object/as-accessor-network (update-vals v thaw)) v))

(defn connector [kind]
  (operator/operator-closure
   {:name kind
    :compiler-activate
    (fn [_ _ _ arguments output]
      {:effects [(gur/bind-name [declarations-key] output
                                {:kind kind :arguments arguments :receipt output})]})}))

(defn declarations [network]
  (->> (get (net/network-dict-entry network [:network :name-bindings]) [declarations-key])
       vals
       (sort-by (comp str :receipt))
       (keep (fn [{:keys [arguments] :as declaration}]
               (let [values (mapv #(freeze network (net/network-cell-strongest network %)) arguments)]
                 (when-not (some #{pending} values)
                   (assoc declaration :values values)))))))
