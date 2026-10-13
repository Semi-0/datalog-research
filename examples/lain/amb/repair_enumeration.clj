(ns examples.lain.amb.repair-enumeration
  "Finite-nary adaptation of premise repair/resolution, plus a separate all-answer
  consumer. Logical failures and request-scoped answer exclusions stay distinct."
  (:require [examples.lain.amb.enumeration-data :as data]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.primitives :as amb]
            [propagators.experimental.amb.publication :as publication]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(defn p:choice-records [controls output]
  (prop/construct-propagator
   ::choice-records
   (publication/lift
    (prop/concrete-propagator
     (fn [_ _ network]
       [(publication/snapshot-message network output
                                      (contracts/choices (mapv #(observation/cell-base network %) controls)))])))
   controls [output]))

(defn collectable-answer [network question cells]
  (let [snapshot (observation/list-snapshot network (:choices cells))
        output (net/network-cell-strongest network (get-in question [:cells :output]))
        valid (net/network-cell-strongest network (get-in question [:cells :valid]))
        records (if (:ready? snapshot) (contracts/choices (:items snapshot)) [])
        tuple (observation/base output)
        expected (mapv #(nth (:domain %) (:index %)) records)]
    (if (and (:ready? snapshot) (seq records) (every? #(= :active (:status %)) records)
             (not (value/unusable? output)) (not (value/unusable? valid))
             (true? (observation/base valid)) (= expected tuple)
             (observation/current-support? network (datum/support-of output))
             (observation/current-support? network (datum/support-of valid)))
      {:tuple tuple :assignment (contracts/assignment records)}
      nil)))

(defn p:record-answer [question cells]
  (prop/construct-propagator
   ::record-answer
   (publication/lift
    (prop/concrete-propagator
     (fn [_ _ network]
       (if-let [{:keys [tuple assignment]} (collectable-answer network question cells)]
         (let [answers (data/retained-set network (:answers cells))
               blockers (data/retained-set network (:blockers cells))
               context (datum/support-of (net/network-cell-strongest network (:request cells)))
               blocker (contracts/checked ::contracts/nogood {:literals assignment :context context})]
           (if (contains? answers tuple)
             []
             [(publication/snapshot-message network (:answers cells) (conj answers tuple))
              (publication/snapshot-message network (:blockers cells) (conj blockers blocker))]))
         []))))
   [(get-in question [:cells :valid]) (get-in question [:cells :output]) (:choices cells) (:request cells)]
   [(:answers cells) (:blockers cells)]))

(defn p:search-exclusions [cells]
  (prop/construct-propagator
   ::search-exclusions
   (publication/lift
    (prop/concrete-propagator
     (fn [_ _ network]
       (let [records (contracts/choices (:items (observation/list-snapshot network (:choices cells))))
             logical (data/retained-set network (:logical cells))
             blockers (data/retained-set network (:blockers cells))
             inputs {:logical logical :blockers blockers
                     :domains (mapv #(select-keys % [:source :domain]) records)}
             previous (observation/cell-base network (:resolution cells))]
         (if (= inputs (:inputs previous))
           []
           (let [closed (learning/closure (into logical blockers) records)]
             [(publication/snapshot-message network (:resolution cells) {:inputs inputs :knowledge closed})
              (publication/snapshot-message network (:search cells) closed)]))))))
   [(:logical cells) (:blockers cells) (:choices cells)]
   [(:resolution cells) (:search cells)]))

(defn extend-question
  "Install solver control onto the same immutable question, not a new predicate.
  This installs existing AMB/learn/backtrack unchanged, plus three observers."
  [question]
  (if (and (nil? (:kind question)) (= 3 (count (:sources question))))
    (let [keys [:domain :repairs :choices :logical :blockers :request :answers :resolution :search]
          cells (zipmap keys (repeatedly ids/new-node-id))
          controls (vec (repeatedly 3 ids/new-node-id))
          declared (reduce nb/ensure-cell (:network question) (concat (vals cells) controls))
          seeded (reduce (fn [network [key v]] (data/seed-snapshot network (get cells key) v)) declared
                         [[:domain (:domain question)] [:blockers #{}] [:answers #{}] [:request :enumerate-all]])
          installers (concat
                      (map #(amb/p:amb (:domain cells) (:repairs cells) %1 %2) controls (:sources question))
                      [(p:choice-records controls (:choices cells))
                       (amb/p:learn (get-in question [:cells :failure]) (:choices cells) (:logical cells))
                       (p:record-answer question cells)
                       (p:search-exclusions cells)
                       (amb/p:backtrack (:choices cells) (:search cells) (:repairs cells))])
          built (reduce (fn [{:keys [network tasks]} installer]
                          (let [[id installed] (installer network)]
                            {:network installed :tasks (tq/enqueue tasks id)}))
                        {:network seeded :tasks (:tasks question)} installers)]
      (assoc built :question question :enumeration-cells cells :controls controls))
    (throw (ex-info "Repair enumeration expects the three-source TTMS question" {}))))

(defn answer [built network]
  (let [cells (:enumeration-cells built) repair (observation/cell-base network (:repairs cells))]
    (case (:action repair)
      :unsatisfiable {:status :completed :answers (vec (sort (data/retained-set network (:answers cells))))
                       :logical-nogoods (data/retained-set network (:logical cells))
                       :answer-exclusions (data/retained-set network (:blockers cells))
                       :search-exclusions (data/retained-set network (:search cells))}
      :idle (throw (ex-info "Repair enumeration quiesced without exhausting its query" {}))
      :withdraw (throw (ex-info "Repair enumeration stopped during withdrawal" {}))
      :choose (throw (ex-info "Repair enumeration stopped during activation" {}))
      (throw (ex-info "Unknown repair enumeration action" {:repair repair})))))

(defn run [built]
  (let [result (runner/run-network (:tasks built) (:network built))]
    (case (:status result)
      :completed (assoc (answer built (:network result)) :network (:network result))
      :failed (throw (:error result))
      (throw (ex-info "Unknown repair enumeration runtime result" {:status (:status result)})))))
