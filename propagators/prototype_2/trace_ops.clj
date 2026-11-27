(ns propagators.prototype-2.trace-ops
  "L-2 introspection primitives over EdgeChain dependence.

  `origins`  — ultimate upstream cells (EdgeChain origins) as a Church p-list
  `neighbors` — immediate upstream cells (props that write this cell) as a p-list

  Target resolution: if the input cell holds a keyword that exists in topology,
  introspect that id (literal cell refs); otherwise introspect the input cell
  itself (compiled expr / lexical binding)."
  (:require [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(defn resolve-trace-target
  "Cell id to introspect from wired input `cell-in`."
  [network cell-in]
  (let [v (net/cell-value network cell-in)]
    (if (and (keyword? v) (contains? (net/network-topology network) v))
      v
      cell-in)))

(defn upstream-neighbors
  "Immediate upstream cell ids: inputs of props that list `cell-id` as an output."
  [network cell-id]
  (->> (net/network-value network)
       (keep (fn [[_ entry]]
               (when (and (t/propagator-value? entry)
                          (some #{cell-id} (:outputs entry)))
                 (:input-ids entry))))
       (apply concat)
       distinct
       vec))

(defn materialize-id-list
  "Install a Church p-list of keyword/ids into `network`. Returns `[list-cell network]`."
  [network ids]
  (if-not (seq ids)
    (let [out (net/new-cell-id "trace-empty")
          network (net/ensure-cell network out)
          [_ network] (session/tell network out plist/empty-list)]
      [out network])
    (let [[tail network] (materialize-id-list network (rest ids))
          head (net/new-cell-id "trace-id")
          network (net/ensure-cell network head)
          [_ network] (session/tell network head (first ids))
          {:keys [network list]} (church/install-cons network head tail)]
      [list network])))

(defn install-origins-prop
  "When `cell-in` is concrete, write EdgeChain origins of the target as a p-list."
  [network cell-in out]
  (let [expand
        (fn [n]
          (let [target (resolve-trace-target n cell-in)
                ids (sort-by str (ev/origins-of n target))
                [list-cell n] (materialize-id-list n ids)]
            (prim/install-id n list-cell out)))]
    (ctor/pending-compound-constructor network expand [cell-in] [out])))

(defn install-neighbors-prop
  "When `cell-in` is concrete, write immediate upstream neighbors as a p-list."
  [network cell-in out]
  (let [expand
        (fn [n]
          (let [target (resolve-trace-target n cell-in)
                ids (upstream-neighbors n target)
                [list-cell n] (materialize-id-list n ids)]
            (prim/install-id n list-cell out)))]
    (ctor/pending-compound-constructor network expand [cell-in] [out])))
