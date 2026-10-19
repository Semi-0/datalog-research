(ns experiments.amb-search.cell-slots
  "Experiment: slot paths reference existing cells in one immutable network."
  (:require [propagators.cells.cell :as cell]
            [propagators.datastructures.compound-object.core :as object]
            [propagators.network :as net]))

(def ^:private slots-key ::slots)

(defn slot-cell-id [network owner slot]
  (get-in (net/network-dict-entry network slots-key) [owner slot]))

(defn slot-keys [network owner]
  (set (keys (get (net/network-dict-entry network slots-key) owner))))

(defn bind-slot
  "Bind a path once. Multiple paths may refer to the same actual cell."
  [network owner slot cell-id]
  (when-not (cell/cell? (net/network-lookup-cell network cell-id))
    (throw (ex-info "Slot target must be an existing cell" {:cell cell-id})))
  (when-let [existing (slot-cell-id network owner slot)]
    (when-not (= existing cell-id)
      (throw (ex-info "Slot rebinding requires a separate protocol"
                      {:owner owner :slot slot :existing existing :target cell-id}))))
  (net/update-net-dict-entry network slots-key assoc-in [owner slot] cell-id))

(defn resolve-slot
  "Return the cell itself by ID, without installing a forwarding propagator."
  [network owner slot]
  (if-let [id (slot-cell-id network owner slot)]
    [id network]
    (throw (ex-info "Unknown cell slot" {:owner owner :slot slot}))))

(defn snapshot
  "Materialize an ordinary immutable compound only at the publication boundary."
  [network owner]
  (object/map-compound-object
   (into {} (map (fn [slot]
                   (let [[id _] (resolve-slot network owner slot)]
                     [slot (net/network-cell-strongest network id)])))
         (slot-keys network owner))))
