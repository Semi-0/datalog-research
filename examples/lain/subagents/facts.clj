(ns examples.lain.subagents.facts
  "Immutable, session-owned event facts. Domain modules validate their own kinds.")

(defrecord FactLog [session entries])

(defn- identity! [label candidate]
  (when-not (and (string? candidate) (seq candidate))
    (throw (ex-info "Fact identity must be a nonempty string" {label candidate})))
  candidate)

(defn fact [session id order kind fields]
  (identity! :session session)
  (identity! :id id)
  (when-not (and (integer? order) (not (neg? order)) (keyword? kind) (map? fields))
    (throw (ex-info "Invalid fact envelope" {:order order :kind kind})))
  (when (some #(contains? fields %) [:session :id :order :kind])
    (throw (ex-info "Fact fields cannot override identity" {})))
  (assoc fields :session session :id id :order order :kind kind))

(defn empty-log [session]
  (->FactLog (identity! :session session) {}))

(defn log? [candidate] (instance? FactLog candidate))

(defn- checked [log]
  (when-not (log? log)
    (throw (ex-info "Expected an immutable fact log" {:type (type log)})))
  log)

(defn session-id [log] (:session (checked log)))

(defn ordered-facts [log]
  (vec (sort-by (juxt :order :id) (vals (:entries (checked log))))))

(defn accumulate [log incoming]
  (checked log)
  (reduce
   (fn [current candidate]
     (when-not (map? candidate)
       (throw (ex-info "Fact must be a map" {:type (type candidate)})))
     (let [{:keys [session id order kind]} candidate
           validated (fact session id order kind (dissoc candidate :session :id :order :kind))]
       (when-not (= (session-id current) session)
         (throw (ex-info "Foreign session fact" {:expected (session-id current) :actual session})))
       (if-let [prior (get (:entries current) id)]
         (if (= prior validated)
           current
           (throw (ex-info "Conflicting fact identity" {:session session :id id})))
         (assoc-in current [:entries id] validated))))
   log incoming))

(defn from-facts [session incoming]
  (accumulate (empty-log session) incoming))

(defn merge-logs [left right]
  (when-not (= (session-id left) (session-id right))
    (throw (ex-info "Cannot merge facts from different sessions" {})))
  (accumulate left (ordered-facts right)))

(defn select-kinds [log kinds]
  (when-not (set? kinds)
    (throw (ex-info "Fact kind selection requires a set" {})))
  (from-facts (session-id log) (filter #(contains? kinds (:kind %)) (ordered-facts log))))
