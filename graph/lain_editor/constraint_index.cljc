(ns graph.lain-editor.constraint-index
  "Pure maintenance of supported constraint outcomes and acceptance evidence.

  The index is authoritative derived state for a live development session.  It
  is changed only by explicit deltas or by withdrawal of a premise on which an
  outcome or evidence record depends; checkpoint inspection only reads it."
  #?(:clj (:require [graph.lain-editor.constraint-checkpoint :as checkpoint])))

(def outcome-statuses
  #{:satisfied :violated :pending :contradictory :inactive :withdrawn})

(defn- validate-outcome! [{:keys [constraint/id specification/revision status required?]
                           :as outcome}]
  (when-not (and (some? id) (some? revision)
                 (contains? outcome-statuses status) (boolean? required?))
    (throw (ex-info "Invalid constraint outcome" {:outcome outcome})))
  #?(:clj (checkpoint/constraint-outcome outcome)
     :cljs outcome))

(defn- ensure-revision! [revision outcome]
  (when-not (= revision (:specification/revision outcome))
    (throw (ex-info "Constraint outcome revision mismatch"
                    {:specification-revision revision :outcome outcome})))
  (validate-outcome! outcome))

(defn create
  [revision outcomes membership-complete? evidence]
  (let [validated (mapv #(ensure-revision! revision %) outcomes)
        ids (mapv :constraint/id validated)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Constraint outcome IDs must be unique" {:ids ids})))
    {:revision revision
     :order ids
     :outcomes (into {} (map (juxt :constraint/id identity)) validated)
     :membership-complete? (boolean membership-complete?)
     :evidence (into {} (map (juxt :evidence/id identity)) evidence)}))

(defn outcomes [{:keys [order outcomes]}]
  (mapv outcomes order))

(defn evidence [index]
  (->> (:evidence index) vals (sort-by (comp str :evidence/id)) vec))

(defn- depends-on? [premise-id record]
  (contains? (set (:dependencies record)) premise-id))

(defn invalidate-premise
  "Withdraw support derived from `premise-id`, retaining diagnostic identity.
  Returns the next index and the IDs whose support was invalidated."
  [index premise-id]
  (if-not premise-id
    {:index index :invalidated-outcomes [] :invalidated-evidence []}
    (let [outcome-ids (->> (outcomes index)
                           (filter #(and (depends-on? premise-id %)
                                         (not (contains? #{:inactive :withdrawn}
                                                         (:status %)))))
                           (mapv :constraint/id))
          evidence-ids (->> (evidence index)
                            (filter #(and (depends-on? premise-id %)
                                          (not= :invalidated (:status %))))
                            (mapv :evidence/id))
          index' (reduce (fn [current id]
                           (update-in current [:outcomes id]
                                      #(-> %
                                           (assoc :status :pending
                                                  :reason :support-withdrawn)
                                           (dissoc :support))))
                         index outcome-ids)
          index' (reduce (fn [current id]
                           (assoc-in current [:evidence id :status] :invalidated))
                         index' evidence-ids)]
      {:index index'
       :invalidated-outcomes outcome-ids
       :invalidated-evidence evidence-ids})))

(defn- upsert-outcome [index outcome]
  (let [outcome (ensure-revision! (:revision index) outcome)
        id (:constraint/id outcome)]
    (cond-> (assoc-in index [:outcomes id] outcome)
      (not (contains? (:outcomes index) id)) (update :order conj id))))

(defn- withdraw-outcome [index id reason]
  (when-not (contains? (:outcomes index) id)
    (throw (ex-info "Cannot withdraw an unknown constraint" {:constraint/id id})))
  (update-in index [:outcomes id]
             #(assoc % :status :withdrawn :required? false
                       :reason (or reason :obligation-withdrawn))))

(defn apply-deltas
  "Apply explicit membership/outcome/evidence deltas without scanning runtime
  state. Unknown delta operations fail before any external effect is required."
  [index {:keys [outcome-updates evidence-updates membership-complete?]
          :as deltas}]
  (let [next-index
        (reduce (fn [current {:keys [op outcome constraint-id reason] :as update}]
                  (case op
                    :upsert (upsert-outcome current outcome)
                    :withdraw (withdraw-outcome current constraint-id reason)
                    (throw (ex-info "Unknown constraint outcome update" {:update update}))))
                index (or outcome-updates []))
        next-index
        (reduce (fn [current {:keys [op evidence-id record] :as update}]
                  (case op
                    :upsert (assoc-in current [:evidence (:evidence/id record)] record)
                    :withdraw (update current :evidence dissoc evidence-id)
                    (throw (ex-info "Unknown evidence update" {:update update}))))
                next-index (or evidence-updates []))]
    (if (contains? deltas :membership-complete?)
      (assoc next-index :membership-complete? (boolean membership-complete?))
      next-index)))

(defn changed-outcomes [before after]
  (let [before-map (:outcomes before)
        after-map (:outcomes after)]
    (->> (concat (:order before) (:order after))
         distinct
         (filter #(not= (get before-map %) (get after-map %)))
         vec)))
