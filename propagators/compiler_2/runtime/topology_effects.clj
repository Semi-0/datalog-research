(ns propagators.compiler-2.runtime.topology-effects
  "Translate an additively compiled network fragment into bounded effects."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.gur.flat :as fvm]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def declaration-scope :compiler-2/runtime-declarations)
(def topology-result-scope [:compiler-2 :topology-results])

(defn topology-result-ids
  "Explicit non-value result declarations, not readiness inferred from content."
  [network]
  (set (vals (get (net/network-dict-entry network fvm/name-bindings-key)
                  topology-result-scope {}))))

(defn declared?
  "True when one delayed topology declaration has already been committed."
  [network declaration-key]
  (boolean
   (get-in (net/network-dict-entry network fvm/name-bindings-key)
           [declaration-scope declaration-key])))

(defn- prop-entry? [[_ entry]] (prop/prop? entry))

(defn- cell-diff [base compiled]
  (let [existing (net/net-env base)]
    (reduce-kv
     (fn [result id entry]
       (let [old (get existing id)]
         (if (or (not (cell/cell? entry)) (identical? entry old))
           result
           (let [content (cell/cell-content entry)
                 old-content (if (cell/cell? old) (cell/cell-content old) nil)
                 declared (if (contains? existing id)
                            result
                            (update result :effects conj (fvm/declare-cell id)))]
             (if (and (not (value/nothing? content)) (not= content old-content))
               (update declared :messages conj (message id content))
               declared)))))
     {:effects [] :messages []}
     (net/net-env compiled))))

(defn new-cell-effects [base compiled]
  (:effects (cell-diff base compiled)))

(defn changed-cell-messages [base compiled]
  (:messages (cell-diff base compiled)))

(defn- unpublished-frame [published env-id frame]
  (let [attributes
        (reduce
         (fn [pending attribute]
           (let [id (get frame attribute)]
             (if (and id (not= id (get published [:frame env-id attribute])))
               (assoc pending attribute id)
               pending)))
         {}
         [:scope/source-id :scope/chain-id :parent-id])
        bindings
        (into {}
              (keep (fn [[sym ids]]
                      (let [missing (filterv #(not= % (get published [:binding env-id sym %])) ids)]
                        (if (seq missing) [sym missing] nil))))
              (:bindings frame))
        current
        (into {} (remove (fn [[sym id]]
                           (= id (get published [:current-binding env-id sym]))))
              (:current-bindings frame))]
    (let [with-bindings (if (seq bindings) (assoc attributes :bindings bindings) attributes)]
      (if (seq current) (assoc with-bindings :current-bindings current) with-bindings))))

(defn- lexical-name-effects [base compiled]
  (let [published (get (net/network-dict-entry base fvm/name-bindings-key)
                       env/lexical-topology-scope {})
        frames (:frames (net/network-dict-entry compiled env/lexical-topology-key))
        pending (into {}
                      (keep (fn [[id frame]]
                              (let [missing (unpublished-frame published id frame)]
                                (if (seq missing) [id missing] nil))))
                      frames)]
    (env/lexical-topology-effects
     (net/assoc-net-dict-entry compiled env/lexical-topology-key {:frames pending}))))

(defn- declare-prop-effect [compiled prop-id entry]
  (if-let [node (get (net/net-graph compiled) prop-id)]
    (fvm/declare-prop prop-id
                      (prop/prop-name entry)
                      (vec (:inputs node))
                      (vec (:outputs node))
                      (prop/prop-f entry))
    (throw (ex-info "compiled topology prop has no graph node"
                    {:prop-id prop-id}))))

(defn prop-effects [base compiled prop-ids]
  (->> prop-ids
       distinct
       (keep (fn [prop-id]
               (let [entry (get (net/net-env compiled) prop-id)]
                 (when (and (prop-entry? [prop-id entry])
                            (not (contains? (net/net-env base) prop-id)))
                   (declare-prop-effect compiled prop-id entry)))))
       vec))

(defn- topology-result-effects
  "Export observation records only; execution markers remain runtime-owned."
  [base compiled]
  (let [before (get (net/network-dict-entry base fvm/name-bindings-key) topology-result-scope)]
    (vec (for [[key id] (get (net/network-dict-entry compiled fvm/name-bindings-key) topology-result-scope)
               :when (not= id (get before key))]
           (fvm/bind-name topology-result-scope key id)))))

(defn network-diff
  [base compiled prop-ids]
  (let [{:keys [effects messages]} (cell-diff base compiled)]
    {:effects (into (lexical-name-effects base compiled)
                    (concat effects (prop-effects base compiled prop-ids)
                            (topology-result-effects base compiled)))
     :messages messages}))

(defn declare-once
  "Return bounded effects for one delayed topology declaration.

  `build` receives the current network and returns a compiled network fragment
  plus the propagator ids that belong to the declaration. The marker is
  committed by the same runtime that commits the topology effects."
  [network declaration-key marker-id build]
  (if (declared? network declaration-key)
    {:effects [] :messages []}
    (let [{compiled :net prop-ids :props} (build network)]
      (update (network-diff network compiled prop-ids)
              :effects conj
              (fvm/bind-name declaration-scope declaration-key marker-id)))))
