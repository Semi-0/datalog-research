(ns graph.vijual.layout.stress-clearance
  "Guarded geometric refinement around a stress-majorization step.
  Accept proposals only when the full stress + preferences objective decreases."
  (:require [graph.vijual.layout.stress-conflicts :as conflicts]
            [graph.vijual.layout.stress-preferences :as preferences]
            [graph.vijual.layout.stress-symmetry :as symmetry]))

(def empty-metrics
  {:symmetry-corrections 0 :symmetry-proposals 0 :conflict-energy-evaluations 0 :conflict-checks 0 :overlap-corrections 0 :overlap-backtracks 0
   :edge-overlaps 0 :edge-crossings 0 :node-edge-conflicts 0 :node-overlaps 0 :edge-node-overlaps 0})

(defn geometry-metrics [config geometry]
  (if (and (some? config) (pos? (:overlap-weight config)))
    (assoc (select-keys (conflicts/analyze config geometry)
                        [:edge-overlaps :edge-crossings :node-edge-conflicts :node-overlaps
                         :edge-node-overlaps]) :conflict-checks 1)
    empty-metrics))

(defn- copy-geometry [geometry]
  (assoc geometry :xs (aclone ^doubles (:xs geometry)) :ys (aclone ^doubles (:ys geometry))))

(defn- copy-into! [source destination]
  (doseq [key [:xs :ys]]
    (System/arraycopy (get source key) 0 (get destination key) 0
                      (alength ^doubles (get source key)))))

(defn- total-energy [packed config geometry analysis]
  (+ (preferences/base-energy packed geometry) (preferences/shape-energy config geometry)
     (symmetry/energy config geometry) (:penalty analysis)))

(defn- blend! [old proposed destination amount]
  (doseq [key [:xs :ys]]
    (let [^doubles a (get old key) ^doubles b (get proposed key)
          ^doubles out (get destination key) t (double amount)]
      (dotimes [i (alength a)]
        (aset-double out i (+ (aget a i) (* t (- (aget b i) (aget a i)))))))))

(defn- guarded-step [packed config old proposed old-analysis next-analysis]
  (let [old-energy (total-energy packed config old old-analysis)
        next-energy (total-energy packed config proposed next-analysis)
        fallback {:geometry old :analysis old-analysis :energy old-energy :evaluations 2 :backtracks 0}]
    (if (<= next-energy old-energy)
      (assoc fallback :geometry proposed :analysis next-analysis :energy next-energy)
      (loop [amount 0.5 remaining 8 count 0]
        (if (zero? remaining)
          (assoc fallback :evaluations (+ 2 count) :backtracks count)
          (let [candidate (copy-geometry old)
                _ (blend! old proposed candidate amount)
                analysis (conflicts/analyze config candidate)
                energy (total-energy packed config candidate analysis)]
            (if (< energy old-energy)
              {:geometry candidate :analysis analysis :energy energy
               :evaluations (+ 3 count) :backtracks (inc count)}
              (recur (* 0.5 amount) (dec remaining) (inc count)))))))))

(defn- evaluate-proposals [packed config best proposals correction-key]
  (let [source (:geometry best)]
    (reduce
     (fn [best moves]
       (let [candidate (copy-geometry source)
             ^doubles xs (:xs candidate) ^doubles ys (:ys candidate)
             _ (doseq [[vertex [dx dy]] moves]
                 (aset-double xs vertex (+ (aget xs vertex) dx))
                 (aset-double ys vertex (+ (aget ys vertex) dy)))
             analysis (conflicts/analyze config candidate)
             energy (total-energy packed config candidate analysis)
             best (update best :evaluations inc)]
         (if (< energy (:energy best))
           (assoc best :geometry candidate :analysis analysis :energy energy correction-key true)
           best)))
     best proposals)))

(defn- escape-conflict [packed config best pinned?]
  (let [proposals (for [moves (conflicts/proposals (get-in best [:analysis :worst]) pinned?)
                        scale [1.0 0.5 0.25 0.125]]
                    (mapv (fn [[vertex [dx dy]]] [vertex [(* scale dx) (* scale dy)]]) moves))]
    (evaluate-proposals packed config best proposals :corrected?)))

(defn- balance [packed config best pinned?]
  (let [proposals (vec (symmetry/proposals config (:geometry best) pinned?))]
    (assoc (evaluate-proposals packed config best proposals :symmetry-corrected?)
           :symmetry-proposals (count proposals))))

(defn- max-displacement [old candidate]
  (let [^doubles ax (:xs old) ^doubles ay (:ys old)
        ^doubles bx (:xs candidate) ^doubles by (:ys candidate)]
    (reduce (fn [maximum i]
              (max maximum (Math/hypot (- (aget ax i) (aget bx i)) (- (aget ay i) (aget by i)))))
            0.0 (range (alength ax)))))

(defn finish-step!
  "Keep pins and input geometry immutable. The output buffer is owned by caller.
  A deterministic clearance proposal breaks collinear traps; eight backtracking
  trials bound rejection work. Conflicts can remain when constraints compete."
  [packed config old candidate pinned? displacement]
  (if (and (some? config)
           (or (pos? (:overlap-weight config))
               (and (pos? (:mirror-weight config)) (seq (:mirrors config)))))
    (let [before (conflicts/analyze config old)
          after (conflicts/analyze config candidate)]
      (if (and (empty? (:mirrors config))
               (zero? (:penalty before)) (zero? (:penalty after)))
        {:displacement displacement :metrics (assoc empty-metrics :conflict-checks 2)}
        (let [guarded (guarded-step packed config old candidate before after)
              cleared (escape-conflict packed config guarded pinned?)
              best (balance packed config cleared pinned?)]
          (copy-into! (:geometry best) candidate)
          {:displacement (max-displacement old candidate)
           :metrics (merge empty-metrics
                           (select-keys (:analysis best) [:edge-overlaps :edge-crossings
                                                        :node-edge-conflicts :node-overlaps
                                                        :edge-node-overlaps])
                           {:conflict-energy-evaluations (:evaluations best)
                            :conflict-checks (:evaluations best)
                            :overlap-backtracks (:backtracks best)
                            :overlap-corrections (if (:corrected? best) 1 0)
                            :symmetry-corrections (if (:symmetry-corrected? best) 1 0)
                            :symmetry-proposals (:symmetry-proposals best)})})))
    {:displacement displacement :metrics empty-metrics}))
