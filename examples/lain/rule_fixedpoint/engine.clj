(ns examples.lain.rule-fixedpoint.engine
  "A fixed feedback topology: sources -> DB -> matches -> consequences -> DB.
  Backward demand restricts correlated bindings; it never manufactures facts."
  (:require [examples.lain.relational-datalog.proof-relation :as relation]
            [examples.lain.relational-datalog.runtime :as runtime]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.evidence :as evidence]
            [examples.lain.rule-fixedpoint.matching :as matching]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.routing :as routing]
            [propagators.cells.value :as value]
            [propagators.datastructures.support-collection :as collection]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- add-prop [world installer]
  (let [[id network] (installer (:network world))]
    (assoc world :network network :tasks (tq/enqueue (:tasks world) id))))

(defn- source-publisher [source target]
  (prop/construct-propagator
   ::source
   (fn [_ _ network]
     (let [content (net/network-cell-content network source)]
       (if (seq (evidence/retained-sources content))
         [(message/message target (relation/relation {source content}))] [])))
   [source] [target]))

(defn- products [groups]
  ;; A finite join of proof alternatives, not recursive rule execution.
  (reduce (fn [rows group] (for [row rows entry group] (conj row entry))) [[]] groups))

(defn- proofs-for [request binding]
  (mapv (fn [[[_ terms] proofs]]
          (let [tuple (mapv binding terms)]
            (filter #(= tuple (:base %)) (vals proofs))))
        (map vector (get-in request [:rule :body]) (:proofs request))))

(defn- consequences [network request bindings]
  (let [rule (:rule request) authority (:authority request)
        representatives (zipmap (:domain request) (:domain request))]
    (into {}
          (for [candidate bindings :let [binding (update-vals candidate representatives)]
                parents (products (proofs-for request binding))
                :let [premises (evidence/grounded network authority parents)]
                :when premises]
            [(evidence/proof-id rule binding premises)
             {:tuple (model/project rule binding) :premises premises}]))))

(defn- request [network descriptor previous]
  (let [{:keys [rule relations]} descriptor
        proofs (mapv #(evidence/live network (get relations (first %))) (:body rule))
        signatures (mapv evidence/signatures proofs)
        previous-signatures (:signatures previous)
        authority-state (evidence/source-states network [(:authority descriptor)])
        demand (channels/base network (:demand-cell descriptor))
        strategy (channels/base network (:strategy-cell descriptor))]
    (merge descriptor
           {:variables (model/variables rule) :proofs proofs :signatures signatures
            :authority-state authority-state :demand demand :strategy strategy
            :tables (mapv #(into #{} (map :base) (vals %)) proofs)
            :deltas (mapv #(evidence/changed-tuples %1 %2)
                          (or previous-signatures (repeat (count proofs) {})) signatures)
            :initial? (or (nil? previous-signatures)
                          (not= authority-state (:authority-state previous))
                          (not= demand (:demand previous))
                          (not= strategy (:strategy previous)))})))

(defn- match-activation [descriptor progress output]
  (fn [_ _ network]
    (let [old (channels/base network progress)
          old (if (map? old) old {}) req (request network descriptor old)]
      (if (= (select-keys req [:signatures :demand :authority-state :strategy])
             (select-keys old [:signatures :demand :authority-state :strategy]))
        []
        (let [req (assoc req :indexes (matching/refresh-indexes req old))
              {:keys [bindings probes]} ((:match descriptor) req)
              emitted (merge (:produced old) (consequences network req bindings))
              next-state {:signatures (:signatures req) :demand (:demand req) :produced emitted
                          :authority-state (:authority-state req)
                          :strategy (:strategy req)
                          :tables (:tables req) :indexes (:indexes req)
                          :bindings bindings
                          :calls (inc (or (:calls old) 0))
                          :probes (+ (or (:probes old) 0) probes)
                          :candidates (+ (or (:candidates old) 0) (count bindings))}]
          (into (channels/snapshot network progress next-state)
                (channels/snapshot network output emitted)))))))

(defn- head-publisher [input target]
  (prop/construct-propagator
   ::consequences
   (fn [_ _ network]
     (let [frames (channels/base network input)
           proofs (if (map? frames)
                    (into {} (keep (fn [[id {:keys [tuple premises]}]]
                                     (if (evidence/current? network premises)
                                       [id (evidence/derived tuple premises)] nil))) frames)
                    {})]
       (if (seq proofs) [(message/message target (relation/relation proofs))] [])))
   [input] [target]))

(defn- install-rule [world rule]
  (let [relations (get-in world [:cells :relations])
        authority (get-in world [:cells :authorities (:id rule)])
        [progress output] (repeatedly 2 ids/new-node-id)
        descriptor (merge (select-keys (:options world) [:domain :demand :match])
                          {:rule rule :relations relations :authority authority
                           :demand-cell (get-in world [:cells :demands (:id rule)])
                           :strategy-cell (get-in world [:cells :strategy])
                           :query (get-in world [:options :queries (:id rule)])})
        prepared (update world :network #(reduce nb/ensure-cell % [progress output]))
        matcher (prop/construct-propagator
                 ::match (match-activation descriptor progress output)
                 (into [authority (:demand-cell descriptor) (:strategy-cell descriptor)]
                       (map #(get relations (first %)) (:body rule))) [progress output])]
    (-> prepared
        (assoc-in [:cells :progress (:id rule)] progress)
        (add-prop matcher)
        (add-prop (head-publisher output (get relations (first (:head rule))))))))

(defn prepare
  "Build once. Source declarations are {name {:predicate p :tuple [a b]}}.
  No source fact is published until `inject` is called."
  [rules options sources]
  (let [rules (model/checked-catalog rules)
        domain (model/finite-domain options sources)
        _ (if (every? #(or (nil? (:guard %))
                           (and (:match options) (get-in options [:queries (:id %)]))) rules)
            nil (throw (ex-info "Guarded rules require the shared compiled query adapter (solvers/attach)" {})))
        predicates (distinct (concat (map :predicate (vals sources))
                                     (mapcat #(map first (cons (:head %) (:body %))) rules)))
        relations (zipmap predicates (repeatedly ids/new-node-id))
        authorities (zipmap (map :id rules) (repeatedly ids/new-node-id))
        demands (zipmap (map :id rules) (repeatedly ids/new-node-id))
        source-ids (zipmap (keys sources) (repeatedly ids/new-node-id))
        routing-id (ids/new-node-id)
        strategy-id (ids/new-node-id)
        cells (concat [routing-id strategy-id] (vals demands) (vals relations) (vals authorities) (vals source-ids))
        initial (reduce (fn [network id]
                          (nb/install-cell network id relation/empty-relation
                                           (relation/strongest relation/empty-relation network)))
                        (nb/install-cells cells) (vals relations))
        initial (reduce (fn [network id]
                          (nb/install-cell network id
                                           (collection/content {:premise-state #{}}) value/nothing))
                        initial (concat (vals source-ids) (vals authorities)))
        world {:network initial :tasks tq/empty-queue :rules rules :source-specs sources
               :options (assoc (merge {:demand {} :strategy :indexed-full
                                      :strategies #{:indexed-full :indexed-delta}} options) :domain domain)
               :cells {:relations relations :authorities authorities :demands demands
                       :sources source-ids :routing routing-id :strategy strategy-id}}
        world (assoc-in world [:options :match]
                        (or (:match options) #(matching/indexed (:strategy %) %)))
        world (channels/tell world strategy-id
                             (evidence/observation strategy-id 1 :active (get-in world [:options :strategy])))
        demanded (reduce (fn [w [rule id]]
                           (channels/tell w id (evidence/observation id 1 :active
                                                                    (get-in options [:demands rule] (get options :demand {})))))
                         world demands)
        seeded (reduce (fn [w [rule id]]
                         (channels/tell w id (evidence/observation id 1 :active {:rule rule})))
                       demanded authorities)
        published (reduce (fn [w [name id]]
                            (add-prop w (source-publisher id (get relations (:predicate (get sources name))))))
                          seeded source-ids)]
    (reduce install-rule
            (add-prop published (routing/installer (concat (vals source-ids) (vals authorities))
                                                 (vec (vals relations)) routing-id)) rules)))

(defn inject [world source tuple version]
  (let [id (get-in world [:cells :sources source])]
    (if (and id (or (value/nothing? tuple) (value/contradiction? tuple)
                    (and (vector? tuple) (= 2 (count tuple))
                         (every? #(contains? (set (get-in world [:options :domain])) %) tuple))))
      (channels/tell world id (evidence/observation id (:timestamp version) (:status version) tuple))
      (throw (ex-info "Expected a declared source and tuple within the fixed domain" {:source source :tuple tuple})))))

(defn seed [world]
  (reduce (fn [w [source {:keys [tuple]}]]
            (inject w source tuple {:timestamp 1 :status :active})) world (:source-specs world)))

(defn rule-state [world rule version]
  (let [id (get-in world [:cells :authorities rule])]
    (if id
      (channels/tell world id (evidence/observation id (:timestamp version) (:status version)
                                                  (if (= :active (:status version)) {:rule rule} value/nothing)))
      (throw (ex-info "Unknown rule authority" {:rule rule})))))

(defn select-strategy [world selected]
  (if (contains? (set (get-in world [:options :strategies])) selected)
    (let [id (get-in world [:cells :strategy]) updates (channels/snapshot (:network world) id selected)]
      (if (empty? updates) world (channels/tell world id (:value (first updates)))))
    (throw (ex-info "Strategy was not prepared in this evaluator environment" {:strategy selected}))))

(defn restrict [world rule binding]
  (let [id (get-in world [:cells :demands rule])
        declaration (first (filter #(= rule (:id %)) (:rules world)))]
    (if (and id (every? (set (second (:head declaration))) (keys binding)))
      (let [updates (channels/snapshot (:network world) id binding)]
        (if (empty? updates) world (channels/tell world id (:value (first updates)))))
      (throw (ex-info "Demand must bind the selected rule's declared head variables" {:rule rule :binding binding})))))

(defn run [world]
  (let [result (runtime/run (:network world) (:tasks world)
                            (merge {:evaluate-patches relation/evaluate-patches
                                    :max-steps 1000000 :max-ms 30000}
                                   (select-keys (:options world) [:max-steps :max-ms :evaluate-propagator])))]
    (merge world result {:tasks tq/empty-queue})))

(defn facts [world predicate]
  (:facts (relation/view (:network world) (get-in world [:cells :relations predicate]))))

(defn metrics [world]
  (let [progress (map #(channels/base (:network world) %) (vals (get-in world [:cells :progress])))
        views (map #(relation/view (:network world) %) (vals (get-in world [:cells :relations])))]
    {:steps (:steps world) :matcher-calls (reduce + 0 (keep :calls progress))
     :join-probes (reduce + 0 (keep :probes progress))
     :candidates (reduce + 0 (keep :candidates progress))
     :proofs (reduce + 0 (map #(count (:proofs %)) views))
     :topology (count (net/net-env (:network world)))}))
