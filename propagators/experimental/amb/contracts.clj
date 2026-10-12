(ns propagators.experimental.amb.contracts
  "Finite experimental search data. Choice identities are real injection cells."
  (:require [clojure.spec.alpha :as s]
            [propagators.datastructures.support :as support]
            [propagators.ids :as ids]))

(s/def ::source ids/node-id?)
(s/def ::domain (s/and vector? seq #(every? pos-int? %)
                         #(= (count %) (count (set %)))))
(s/def ::index nat-int?)
(s/def ::timestamp pos-int?)
(s/def ::status #{:active :retracted})
(s/def ::handled-request (s/nilable map?))
(s/def ::choice
  (s/and (s/keys :req-un [::source ::domain ::index ::timestamp ::status
                         ::handled-request])
         #(< (:index %) (count (:domain %)))))
(s/def ::literal (s/tuple ::source ::index))
(s/def ::literals (s/coll-of ::literal :kind set?))
(s/def ::context ::support/support)
(s/def ::nogood
  (s/and (s/keys :req-un [::literals ::context])
         #(= #{:literals :context} (set (keys %)))
         #(support/compatible? (:context %))))
(s/def ::knowledge (s/coll-of ::nogood :kind set?))

(defn checked [spec value]
  (if (s/valid? spec value)
    value
    (throw (ex-info "Invalid AMB declaration or state"
                    {:spec spec :value value :explain (s/explain-data spec value)}))))

(defn choices [records]
  (let [records (vec records)]
    (doseq [record records] (checked ::choice record))
    (if (= (count records) (count (set (map :source records))))
      records
      (throw (ex-info "Choice occurrences must have distinct injection cells" {})))))

(defn premise [record]
  {:source (:source record) :timestamp (:timestamp record)
   :premises-status (:status record)})

(defn assignment [records]
  (into #{} (map (juxt :source :index)) records))

(defn initial-choice [source domain]
  (checked ::choice {:source source :domain (checked ::domain (vec domain))
                    :index 0 :timestamp 1 :status :active :handled-request nil}))
