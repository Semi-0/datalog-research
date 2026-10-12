(ns propagators.experimental.amb.learning
  "Logical no-good learning and finite-domain resolution, not TTMS dominance."
  (:require [clojure.set :as set]
            [propagators.datastructures.support :as support]
            [propagators.experimental.amb.contracts :as contracts]))

(defn subsumes? [a b]
  (and (= (:context a) (:context b))
       (set/subset? (:literals a) (:literals b))))

(defn minimize [knowledge]
  (let [knowledge (set knowledge)]
    (into #{} (remove (fn [n]
                       (some #(and (not= n %) (subsumes? % n)) knowledge)))
          knowledge)))

(defn applicable? [nogood context]
  (set/subset? (:context nogood) context))

(defn blocked? [knowledge assignment context]
  (boolean (some #(and (applicable? % context)
                      (set/subset? (:literals %) assignment)) knowledge)))

(defn certificate->nogood
  "Reject stale choice versions; external premises remain exact conditions."
  [certificate records current-context]
  (let [records (contracts/choices records)
        by-source (into {} (map (juxt :source identity)) records)
        premises (:support certificate)]
    (if (and (= :failed (:status certificate)) (support/support? premises)
             (not (support/unusable? premises)))
      (let [choice-premises (filter #(contains? by-source (:source %)) premises)
            context (set (remove #(contains? by-source (:source %)) premises))]
        (if (and (set/subset? context current-context)
                 (every? #(= % (contracts/premise (by-source (:source %))))
                         choice-premises))
          {:literals (into #{} (map #(let [record (by-source (:source %))]
                                      [(:source record) (:index record)]))
                           choice-premises)
           :context context}
          nil))
      nil)))

(defn- consistent-literals? [literals]
  (every? #(= 1 (count %)) (vals (group-by first literals))))

(defn- combine-reasons [a b]
  (let [literals (set/union (:literals a) (:literals b))
        context (set/union (:context a) (:context b))]
    (if (and (consistent-literals? literals) (support/compatible? context))
      {:literals literals :context context}
      nil)))

(defn- reasons-for [knowledge source index]
  (into #{} (keep #(if (contains? (:literals %) [source index])
                    (update % :literals disj [source index])
                    nil)) knowledge))

(defn resolve-choice [knowledge record]
  (reduce
   (fn [partial index]
     (let [reasons (reasons-for knowledge (:source record) index)]
       (if (empty? reasons)
         (reduced #{})
         (minimize (keep identity (for [a partial b reasons]
                                   (combine-reasons a b)))))))
   #{{:literals #{} :context #{}}}
   (range (count (:domain record)))))

(defn closure [knowledge records]
  (loop [known (minimize knowledge)]
    (let [next (minimize (into known (mapcat #(resolve-choice known %) records)))]
      (if (= known next) known (recur next)))))

(defn learn [knowledge certificate records context]
  (let [nogood (certificate->nogood certificate records context)]
    (if nogood
      (closure (conj (contracts/checked ::contracts/knowledge knowledge) nogood)
               records)
      knowledge)))
