(ns examples.lain.subagents.harbor-extension
  "Pure Harbor/evaluation propagators plus an explicitly invoked Harbor boundary.
  FactLog values are bounded domain leaves, never materialized actor graphs."
  (:require [examples.lain.subagents.assignment-model :as assignments]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.primitives :as primitives]
            [propagators.compiler-2.runtime.session.extension :as extension]))

(def trial-kinds
  #{:assignment/submitted :assignment/reordered :assignment/cancel-requested
    :runtime/requested :runtime/started :runtime/uncertain
    :runtime/completed :runtime/cancelled :runtime/failed
    :harbor/context :harbor/assessed})
(def result-kinds #{:runtime/completed :runtime/cancelled :runtime/failed})

(defn trial-facts [assignment-facts]
  (facts/select-kinds assignment-facts trial-kinds))

(defn incorporate-results [trials results]
  (facts/merge-logs trials (facts/select-kinds results result-kinds)))

(defn current-offer [assignment-facts trials]
  (assignments/next-offer
   (assignments/derive-schedule (facts/merge-logs (trial-facts assignment-facts) trials))))

(defn- request-id [operation assignment-id]
  (str "harbor/" (name operation) "/" (pr-str assignment-id)))

(defn preparation-requests [assignment-facts trials]
  (let [combined (facts/merge-logs (trial-facts assignment-facts) trials)
        schedule (assignments/derive-schedule combined)
        session (facts/session-id combined)]
    (facts/from-facts
     session
     (mapv (fn [{:keys [assignment-id task]}]
             (facts/fact session (request-id :prepare assignment-id) 0
                         :harbor/prepare-requested
                         {:assignment-id assignment-id :task task}))
           (assignments/preparation-inputs schedule)))))

(defn grading-requests [trials assessments]
  (let [combined (facts/merge-logs trials assessments)
        schedule (assignments/derive-schedule combined)
        session (facts/session-id combined)]
    (facts/from-facts
     session
     (mapv (fn [{:keys [assignment-id task context result]}]
             (facts/fact session (request-id :grade assignment-id) 1
                         :harbor/grade-requested
                         {:assignment-id assignment-id :task task
                          :context context :result result}))
           (assignments/grading-inputs schedule)))))

(defn context-facts [trials observations]
  ;; Merge validates ownership, while retaining observations for late submissions.
  (facts/merge-logs (facts/empty-log (facts/session-id trials))
                   (facts/select-kinds observations #{:harbor/context})))

(defn assessment-facts [trials observations]
  (facts/merge-logs (facts/empty-log (facts/session-id trials))
                   (facts/select-kinds observations #{:harbor/assessed})))

(defn- status-fact [session entry]
  (let [evidence (:evidence entry)
        status (if (= :completed (:status entry)) :awaiting-assessment (:status entry))]
    (facts/fact session (str "evaluation/status/" (pr-str [(:assignment-id entry) status (:id evidence)]))
                (:order evidence) :assignment/status
                {:assignment-id (:assignment-id entry) :status status})))

(defn- assessment-update [session entry]
  (let [evidence (:evidence entry)]
    (facts/fact session (str "evaluation/assessment/" (pr-str (:id evidence)))
                (:order evidence) :assignment/assessed
                {:assignment-id (:assignment-id entry)
                 :result (:result entry) :assessment (:assessment entry)})))

(defn assignment-updates [trials assessments]
  (let [combined (facts/merge-logs trials assessments)
        session (facts/session-id combined)
        schedule (assignments/derive-schedule combined)
        entries (assignments/assignment-infos schedule)
        rejected (mapv (fn [{:keys [assignment-id event-ids order reason]}]
                         (facts/fact session (str "evaluation/rejected/" (pr-str event-ids))
                                     order :assignment/rejected
                                     {:assignment-id assignment-id :event-ids event-ids
                                      :reason reason}))
                       (assignments/rejections schedule))]
    (facts/from-facts session
                      (concat (map #(status-fact session %) entries)
                              (map #(assessment-update session %)
                                   (filter #(= :assessed (:status %)) entries))
                              rejected))))

(defn request-eligible?
  "Check durable intent against current facts before the outside boundary claims it."
  [request current-facts]
  (facts/from-facts (facts/session-id current-facts) [request])
  (let [schedule (assignments/derive-schedule current-facts)
        entry (assignments/assignment-info schedule (:assignment-id request))]
    (case (:kind request)
      :harbor/prepare-requested
      (boolean (and entry (not (assignments/terminal-statuses (:status entry)))
                    (not (contains? entry :context)) (= (:task request) (:task entry))))
      :harbor/grade-requested
      (boolean (and (= :completed (:status entry))
                    (= (:task request) (:task entry))
                    (= (:context request) (:context entry))
                    (= (:result request) (:result entry))))
      false)))

(defn interpret-request!
  "Invoke one admitted Harbor callback and return an observation template.
  The shared outside boundary owns modes, claims, receipts, identity and ordering.
  This function is never called from a propagator activation."
  [session callbacks request]
  (facts/from-facts session [request])
  (let [id (:assignment-id request)]
    (when-not (and (string? id) (seq id) (string? (:task request)) (seq (:task request)))
      (throw (ex-info "Harbor request needs assignment identity and task" {:request-id (:id request)})))
    (case (:kind request)
      :harbor/prepare-requested
      (let [prepare (:prepare callbacks)]
        (when-not (ifn? prepare)
          (throw (ex-info "No Harbor preparation interpreter is installed" {})))
        (let [context (prepare (select-keys request [:session :id :assignment-id :task]))]
          (when-not (map? context)
            (throw (ex-info "Harbor preparation must return a context map" {:request-id (:id request)})))
          {:kind :harbor/context :assignment-id id :context context}))
      :harbor/grade-requested
      (let [grade (:grade callbacks)]
        (when-not (and (map? (:context request)) (contains? request :result))
          (throw (ex-info "Harbor grading needs prepared context and runner result" {:request-id (:id request)})))
        (when-not (ifn? grade)
          (throw (ex-info "No Harbor grading interpreter is installed" {})))
        (let [assessment (grade (select-keys request [:session :id :assignment-id :task :context :result]))]
          (when-not (map? assessment)
            (throw (ex-info "Harbor grading must return an assessment map" {:request-id (:id request)})))
          {:kind :harbor/assessed :assignment-id id :assessment assessment}))
      (throw (ex-info "Unsupported Harbor effect request" {:kind (:kind request)})))))

(defn interpret!
  "Shared outside-boundary callback. Disposal is explicit and session-scoped."
  [callbacks request]
  (if (= :dispose (:operation request))
    (if-let [dispose! (:dispose! callbacks)]
      (let [receipt (dispose! (:session request))]
        (if (map? receipt) {:observations [] :receipt receipt} []))
      [])
    [(interpret-request! (:session request) callbacks request)]))

(defn- primitive-binding [session symbol arity inputs output kind f]
  [symbol (primitives/leaf-operator
           symbol {:arity arity :inputs inputs :input-kinds (vec (repeat (count inputs) :facts))
                   :output output :session session :kind kind} f)])

(defn session-extension [session]
  (facts/empty-log session)
  (extension/extension-bundle
   {:id ::harbor
    :bindings [(primitive-binding session 'evaluation:derive-trials 2 [0] 1 :facts trial-facts)
               (primitive-binding session 'evaluation:incorporate-results 3 [0 1] 2 :facts incorporate-results)
               (primitive-binding session 'evaluation:select-pending-assignment 2 [0 1] nil :value current-offer)
               (primitive-binding session 'evaluation:derive-assignment-updates 2 [0 1] nil :facts assignment-updates)
               (primitive-binding session 'harbor:prepare 3 [0 1] 2 :facts preparation-requests)
               (primitive-binding session 'harbor:grade 3 [0 1] 2 :facts grading-requests)
               (primitive-binding session 'harbor:observe-context 3 [0 1] 2 :facts context-facts)
               (primitive-binding session 'harbor:observe-assessment 3 [0 1] 2 :facts assessment-facts)]
    :effects []}))
