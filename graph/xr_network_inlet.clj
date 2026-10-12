(ns graph.xr-network-inlet
  "An ordinary TTMS input cell outside the network being inspected."
  (:require [graph.xr-inspection :as inspection]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn inlet []
  (let [source (ids/new-node-id)]
    {:source source :network (nb/install-cell net/empty-net source)}))

(defn- merge-observed-net [old new]
  ;; A completed Net is an immutable observation, not a compound refinement.
  (cond
    (value/nothing? old) new
    (value/nothing? new) old
    (= old new) old
    :else value/contradiction))

(defn publish
  "Inject an explicit source observation, including withdrawal. No scheduler changes."
  [inlet timestamp status base]
  (let [source (:source inlet)
        update (collection/content {:base base
                                    :support #{{:source source :timestamp timestamp
                                                :premises-status status}}})
        [_ network] (core/eval-cell source (message/message source update) (:network inlet))
        content (net/network-cell-content network source)
        projected (collection/strongest-value content merge-observed-net)]
    (assoc inlet :network
           (net/assoc-net-cell network source (cell/cell content projected)))))

(defn current [inlet]
  (net/network-cell-strongest (:network inlet) (:source inlet)))

(defn- same-observation? [a b]
  (or (identical? a b)
      (and (net/net? a) (net/net? b)
           (every? #(identical? (get a %) (get b %))
                   [:graph :env :dict :relationship]))))

(defn observe
  "A read of the same immutable Net does not invent a fresh observation."
  [inlet network]
  (if (same-observation? network (ttms/payload (current inlet)))
    inlet
    (let [timestamps (map :timestamp (datum/support-of (current inlet)))
          timestamp (inc (reduce max 0 timestamps))]
      (publish inlet timestamp :active network))))

(defn context [inlet generation]
  (let [projected (current inlet)]
    {:generation generation
     :status (name (ttms/status projected))
     :datum (inspection/encode
             {:inlet (:network inlet) :source (:source inlet) :projected projected})}))

(defn observe-context
  "Cache only transport bytes' input. TTMS remains the sole observation state."
  [inlet network generation]
  (let [observed (observe inlet network)]
    (if (and (identical? inlet observed)
             (= generation (get-in inlet [:encoded-context :generation])))
      inlet
      (assoc observed :encoded-context (context observed generation)))))
