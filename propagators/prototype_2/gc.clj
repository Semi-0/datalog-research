(ns propagators.prototype-2.gc
  "Independent mark/sweep over completed EdgeChain topology.

  At equilibrium: mark completed propagators (built + boundary satisfied) and
  completed cells that are not retained, then sweep them from the network.

  Retain set keeps live answers / roots. Default retain is empty — callers
  (runner done-hook) should pass result cells they still need.

  Long-lived Church fns / open p-lists: `live-roots` expands retain through
  `:env` spines and cons ports so curried network handles survive collect.

  No coupling to evaluate: runner constructor plugs `collect` into `:done`."
  (:require [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(defn completed-propagator?
  "True when prop is wired and every boundary port is concrete."
  [network prop-id]
  (let [v (net/lookup-value network prop-id)
        n (net/lookup-node network prop-id)]
    (boolean
     (and (t/propagator-value? v)
          n
          (t/topology-built? n)
          (net/boundary-satisfied? network (:input-ids v) (:outputs v))))))

(defn completed-cell?
  "True when cell is concrete and its published chain (if any) is complete.

  Cells with no published dependence are treated complete once concrete
  (told roots / literals)."
  [network cell-id]
  (let [v (net/lookup-value network cell-id)]
    (boolean
     (and (t/cell? v)
          (not (t/nothing? (t/cell-content v)))
          (let [dep (session/dependence-of network cell-id)]
            (if dep
              (session/chain-complete? network cell-id)
              true))))))

(defn props-touching
  "Propagator ids that list `cell-id` as an intended input or output."
  [network cell-id]
  (into #{}
        (keep (fn [[prop-id entry]]
                (when (and (t/propagator-value? entry)
                           (or (some #{cell-id} (:input-ids entry))
                               (some #{cell-id} (:outputs entry))))
                  prop-id)))
        (net/network-value network)))

(defn live-roots
  "Expand `retain` through Church p-list / church-fn spines.

  Closures are L-2 lists `(cons 'church-fn (cons params (cons body bound)))`;
  walking cons cells + keyword value-cell refs keeps curry/capture bindings live."
  [network retain]
  (loop [front (vec retain)
         seen (set retain)]
    (if-not (seq front)
      seen
      (let [id (peek front)
            front (pop front)
            v (net/cell-value network id)
            more
            (cond
              (plist/church-cons? v)
              (filterv #(and (keyword? %) (not (contains? seen %)))
                       [(:a v) (:b v) (:visitor v)])

              (and (keyword? v)
                   (contains? (net/network-topology network) v)
                   (not (contains? seen v)))
              [v]

              :else nil)]
        (recur (into front more) (into seen more))))))

(defn mark-completed
  "Return #{ids} of completed props + completed cells not in live retain.

  A completed cell is only marked when every touching prop is also marked
  (or none touch it) — so live pending work keeps its ports."
  ([network] (mark-completed network #{}))
  ([network retain]
   (let [retain (live-roots network retain)
         marked-props
         (into #{}
               (keep (fn [[id _]]
                       (when (completed-propagator? network id) id)))
               (net/network-value network))
         marked-cells
         (into #{}
               (keep (fn [[id v]]
                       (when (and (t/cell? v)
                                  (not (contains? retain id))
                                  (completed-cell? network id)
                                  (let [touch (props-touching network id)]
                                    (or (empty? touch)
                                        (every? marked-props touch))))
                         id)))
               (net/network-value network))]
     (into marked-props marked-cells))))

(defn sweep
  "Remove `marked` ids from topology, value, name dict, and listeners index."
  [network marked]
  (let [marked (set marked)
        old-value (net/network-value network)
        topology (reduce dissoc (net/network-topology network) marked)
        value (reduce dissoc old-value marked)
        name (into {}
                   (remove (fn [[_ id]] (contains? marked id)))
                   (net/network-name network))
        ;; Drop swept props from listener sets; drop swept cells as keys.
        listeners
        (reduce (fn [lis id]
                  (let [entry (get old-value id)]
                    (cond
                      (t/propagator-value? entry)
                      (reduce (fn [lis cell-id]
                                (let [next (disj (get lis cell-id #{}) id)]
                                  (if (seq next)
                                    (assoc lis cell-id next)
                                    (dissoc lis cell-id))))
                              lis
                              (:input-ids entry))

                      (contains? lis id)
                      (dissoc lis id)

                      :else lis)))
                (net/network-listeners network)
                marked)]
    (net/network topology value name (net/network-queue network) listeners)))

(defn collect
  "Mark completed topology then sweep. Independent of the evaluator.

  `retain` — cell ids to keep (answers / live roots); expanded via `live-roots`."
  ([network] (collect network #{}))
  ([network retain]
   (sweep network (mark-completed network retain))))
