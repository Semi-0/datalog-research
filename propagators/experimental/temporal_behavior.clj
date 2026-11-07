(ns propagators.experimental.temporal-behavior
  "Retained temporal evidence and pure merge-time behavior reduction.
  Semantic time, occurrence identity and evidence revision are independent."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.ids :as ids]
            [propagators.propagator :as prop]))

(def ^:dynamic *instrument* nil)
(def ^:dynamic *reuse* true)

(defn measured! [kind]
  (when *instrument* (swap! *instrument* update kind (fnil inc 0))))

(defn occurrence
  ([id time payload] (occurrence id time 0 payload))
  ([id time revision payload]
   (when-not (and (some? id) (number? time) (nat-int? revision))
     (throw (ex-info "Invalid temporal occurrence" {:id id :time time :revision revision})))
   {:temporal/type :point :id id :time time :revision revision :value payload}))

(defn interval [id start end revision payload]
  (when-not (and (number? end) (< start end))
    (throw (ex-info "Intervals must be nonempty and half-open" {:start start :end end})))
  (assoc (occurrence id start revision payload) :temporal/type :interval :end end))

(defn temporal? [x]
  (and (map? x) (contains? #{:point :interval} (:temporal/type x))
       (some? (:id x)) (number? (:time x)) (nat-int? (:revision x))
       (or (= :point (:temporal/type x))
           (and (number? (:end x)) (< (:time x) (:end x))))))

(defn order-key [record] [(:time record) (pr-str (:id record))])

(defn refine
  "The partial base domain used inside temporal records. Incompatible facts stay
  visible as contradiction; absent map fields and Nothing can be refined."
  [a b]
  (cond
    (value/nothing? a) b
    (value/nothing? b) a
    (= a b) a
    (and (map? a) (map? b)) (merge-with refine a b)
    :else value/contradiction))

(def empty-evidence {:support/observations #{} :support/states #{}})

(defn evidence [x]
  (cond
    (value/nothing? x) empty-evidence
    (collection/content? x)
    (let [observations (collection/observations x)]
      (doseq [{:keys [base]} observations]
        (when-not (or (value/nothing? base) (temporal? base))
          (throw (ex-info "Behavior input requires temporal TTMS base records" {:base base}))))
      {:support/observations (set (remove #(value/nothing? (:base %)) observations))
       :support/states (apply support/join (get x :support/states #{}) (map :support observations))})
    :else (throw (ex-info "Expected retained temporal evidence" {:value x}))))

(defn join-evidence [a b]
  (let [a (evidence a) b (evidence b)
        joined (set/union (:support/observations a) (:support/observations b))
        by-base (group-by :base joined)]
    {:support/observations
     (into #{} (remove (fn [{:keys [base support]}]
                         (some #(and (not= support (:support %))
                                     (set/subset? (:support %) support)) (by-base base)))) joined)
     :support/states (support/join (:support/states a) (:support/states b))}))

(defn- revived-support [original states]
  ;; Premise belief updates do not change event revisions. Retaining the original
  ;; evidence permits premise-only recovery; its current justification names the
  ;; new belief revision, while :selection below keeps the original observation.
  (let [by-source (group-by :source states)
        current (mapcat #(get by-source (:source %) [%]) original)]
    (cond
      (not (support/compatible? original)) original
      (not (support/compatible? (set current))) (set current)
      (every? #(= :active (:premises-status %)) current) (set current))))

(defn- latest-observations [observations]
  (mapcat (fn [[_ xs]]
            (let [revision (apply max (map #(get-in % [:base :revision]) xs))]
              (filter #(= revision (get-in % [:base :revision])) xs)))
          (group-by #(get-in % [:base :id]) observations)))

(defn source-records [input]
  (let [states (:support/states input)]
    (->> (:support/observations input)
         latest-observations
         (group-by #(get-in % [:base :id]))
         (keep
          (fn [[id observations]]
            (let [active (keep (fn [{:keys [base support] :as observation}]
                                 (when-let [current (revived-support support states)]
                                   {:event base :candidate {:base (if (support/compatible? current) (:value base) value/contradiction)
                                                            :support current}
                                    :original observation})) observations)
                  geometries (set (map #(select-keys (:event %) [:time :end :temporal/type]) active))
                  first-event (:event (first (sort-by (comp order-key :event) active)))]
              (when first-event
                (assoc (select-keys first-event [:time :end :temporal/type :revision])
                       :id id
                       :candidates (if (> (count geometries) 1)
                                     #{{:base value/contradiction
                                        :support (apply support/combine (map #(get-in % [:candidate :support]) active))}}
                                     (set (map :candidate active)))
                       :selection {:observations (set observations)
                                   :states (set (filter #(contains? (set (mapcat (fn [o] (map :source (:support o))) observations))
                                                                    (:source %)) states))})))))
         (sort-by order-key) vec)))

(defn project-record [record]
  (let [v (collection/strongest-value {:support/observations (:candidates record)} refine)]
    (-> record
        (dissoc :candidates)
        (assoc :value (datum/layer-value v :base)
               :support (datum/support-of v)
               :alternatives (:candidates record)))))

(defn project
  "Thin TTMS projection of already reduced candidates. Never invokes a callable."
  [slots]
  (let [{:keys [records unavailable-before unavailable-ids presentation status]} (:result slots)
        projected (mapv project-record records)
        ordered (if (= :filo presentation) (vec (reverse projected)) projected)]
    {:records ordered :unavailable-before unavailable-before
     :unavailable-ids unavailable-ids :status (or status (if (seq records) :available :unknown))}))

(defn- prune [old updates window]
  (let [joined (join-evidence (:evidence old value/nothing) updates)
        tombstones (get old :unavailable-ids #{})
        eligible (remove #(contains? tombstones (get-in % [:base :id])) (:support/observations joined))
        latest (latest-observations eligible)
        ordered (sort-by (comp order-key :base)
                         (vals (into {} (map (juxt #(get-in % [:base :id]) identity)) latest)))
        dropped (if window (take (max 0 (- (count ordered) window)) ordered) [])
        tombstones (into tombstones (map #(get-in % [:base :id])) dropped)
        cutoff (last (sort (keep identity [(:unavailable-before old)
                                           (some-> (last dropped) :base order-key)])))
        retained (into #{} (remove #(or (contains? tombstones (get-in % [:base :id]))
                                        (and cutoff (not (pos? (compare (order-key (:base %)) cutoff))))))
                       (if window latest eligible))]
    {:evidence (assoc joined :support/observations retained)
     :unavailable-ids tombstones :unavailable-before cutoff :window window}))

(defn merge-source [content update]
  (let [window (or (:window content) (:window update))
        _ (when (and window (not (pos-int? window)))
            (throw (ex-info "Retained window must be a positive occurrence count" {:window window})))
        old (select-keys content [:evidence :unavailable-ids :unavailable-before :window])
        incoming (:evidence update value/nothing)
        prior (-> content
                  (clojure.core/update :unavailable-ids (fnil set/union #{}) (get update :unavailable-ids #{}))
                  (assoc :unavailable-before
                         (last (sort (keep identity [(:unavailable-before content) (:unavailable-before update)])))))
        state (prune prior incoming window)]
    (cond
      (= state old) content
      (= state (select-keys update [:evidence :unavailable-ids :unavailable-before :window])) update
      :else
      (assoc state :result (assoc (select-keys state [:unavailable-ids :unavailable-before])
                                  :records (source-records (:evidence state)))))))

(defn- join-input [a b]
  (cond
    (or (nil? a) (value/nothing? a)) b
    (value/nothing? b) a
    (= a b) a
    (and (reducer/reducer-cell? a) (reducer/reducer-cell? b))
    (reducer/merge-content a b nil nil)
    :else (join-evidence a b)))

(defn input-result [input]
  (if (reducer/reducer-cell? input)
    (:result (reducer/reducer-slots input))
    {:records (source-records (evidence (or input value/nothing)))}))

(defn- input-evidence [input]
  (if (reducer/reducer-cell? input)
    (let [slots (reducer/reducer-slots input)]
      (or (:evidence slots)
          (reduce join-evidence empty-evidence (map input-evidence (vals (:inputs slots))))))
    (evidence (or input value/nothing))))

(defn- selection-join [& selections]
  {:observations (apply set/union #{} (map :observations (keep identity selections)))
   :states (apply support/join (map :states (keep identity selections)))})

(defn- apply-candidates-cached [invoke argument-candidates prior]
  (let [tuples (reduce (fn [tuples candidates]
                         (for [tuple tuples c candidates] (conj tuple c))) [[]] argument-candidates)]
    (reduce (fn [{:keys [candidates applications]} tuple]
              (let [premises (apply support/combine (map :support tuple))
                    bases (mapv :base tuple)
                    usable? (and (support/compatible? premises)
                                 (not-any? value/unusable? bases))
                    result (cond
                             (not (support/compatible? premises)) value/contradiction
                             (some value/contradiction? bases) value/contradiction
                             (some value/nothing? bases) value/nothing
                             (and *reuse* (contains? prior bases)) (get prior bases)
                             :else (do (measured! :reducer-invocations) (apply invoke bases)))]
                {:candidates (conj candidates {:base result :support premises})
                 :applications (cond-> applications usable? (assoc bases result))}))
            {:candidates #{} :applications {}} tuples)))

(defn- apply-candidates [invoke argument-candidates]
  (:candidates (apply-candidates-cached invoke argument-candidates {})))

(defn- scan-records [records invoke config old-cache input]
  (let [initial (when (contains? config :initial) #{{:base (:initial config) :support #{}}})
        all-evidence (input-evidence input)]
    (loop [remaining records accumulator initial cache [] output []]
      (if-let [record (first remaining)]
        (let [prior (get old-cache (count cache))
              ;; A prefix depends on present AND absent/withdrawn occurrences in
              ;; that semantic region, never on an activation sequence number.
              prefix (filter #(not (pos? (compare (order-key (:base %)) (order-key record))))
                             (:support/observations all-evidence))
              sources (set (mapcat #(map :source (:support %)) prefix))
              selection (selection-join (:selection record)
                                        {:observations (set prefix)
                                         :states (set (filter #(sources (:source %)) (:support/states all-evidence)))})
              key [record accumulator selection]
              reused? (and *reuse* (= key (:key prior)))
              ;; Reuse pure values when only justification changed. Candidate
              ;; support and temporal-selection evidence are always rebuilt.
              application (when (and (not reused?) accumulator)
                            (apply-candidates-cached invoke [accumulator (:candidates record)]
                                                     (:applications prior)))
              next-record (if reused? (:record prior)
                              (assoc record :selection selection :candidates
                                     (if (nil? accumulator) (:candidates record)
                                         (:candidates application))))]
          (recur (next remaining) (:candidates next-record)
                 (conj cache {:key key :record next-record
                              :applications (if reused? (:applications prior) (:applications application))}) (conj output next-record)))
        {:records output :cache cache}))))

(defn- mapped-records [records invoke filter? old-cache]
  (let [cache (mapv (fn [i record]
                      (if (and *reuse* (= record (:key (get old-cache i))))
                        (get old-cache i)
                        (let [candidates (if filter?
                                           (into #{} (mapcat (fn [c]
                                                               (keep (fn [test]
                                                                       (cond
                                                                         (true? (:base test)) c
                                                                         (false? (:base test)) nil
                                                                         :else (assoc c :base (:base test))))
                                                                     (apply-candidates invoke [#{c}]))))
                                                 (:candidates record))
                                           (apply-candidates invoke [(:candidates record)]))]
                          {:key record :record (assoc record :candidates candidates)})))
                    (range) records)]
    {:records (mapv :record (filter #(seq (get-in % [:record :candidates])) cache)) :cache cache}))

(defn covers-time? [record time]
  (if (= :point (:temporal/type record)) (= (:time record) time)
      (and (<= (:time record) time) (or (nil? (:end record)) (< time (:end record))))))

(defn- intersection [a b]
  (let [time (max (:time a) (:time b))]
    (when (and (covers-time? a time) (covers-time? b time))
      (if (or (= :point (:temporal/type a)) (= :point (:temporal/type b)))
        {:temporal/type :point :time time}
        {:temporal/type :interval :time time
         :end (when-let [ends (seq (keep :end [a b]))] (apply min ends))}))))

(defn normalize
  "Coalesce adjacent intervals only when their independent justifications agree."
  [records]
  (reduce (fn [out r]
            (let [previous (peek out)]
              (if (and (= :interval (:temporal/type previous) (:temporal/type r))
                       (= (:end previous) (:time r))
                       (= (:candidates previous) (:candidates r))
                       (= (:selection previous) (:selection r)))
                (conj (pop out) (assoc previous :end (:end r)))
                (conj out r)))) [] records))

(defn calculate [operation inputs config invoke old-cache]
  (let [input (get inputs 0)
        {:keys [records] :as source} (input-result input)
        domains (map input-result (vals inputs))
        computed
        (case operation
          :scan (scan-records records invoke config old-cache input)
          (:map :history-map) (mapped-records records invoke false old-cache)
          :event-filter (mapped-records records invoke true old-cache)
          :hold {:records (filterv #(or (nil? (:end %)) (< (:time %) (:end %)))
                                   (mapv (fn [i r]
                                           (assoc r :temporal/type :interval
                                                  :end (:time (get records (inc i)))
                                                  :selection (selection-join (:selection r)
                                                                             (:selection (get records (inc i))))))
                                         (range) records))}
          :history {:records records :presentation (:order config)}
          :last {:records (if (seq records) [(peek records)] [])}
          :sample (let [time (:time config)
                        unavailable? (and (:unavailable-before source)
                                          (<= time (first (:unavailable-before source))))
                        selected (when-not unavailable? (filter #(covers-time? % time) records))]
                    {:records (if (seq selected)
                                [{:id [:sample time] :revision 0 :temporal/type :point :time time
                                  :selection (apply selection-join (map :selection selected))
                                  :candidates (into #{} (mapcat :candidates) selected)}] [])
                     :status (cond unavailable? :unavailable (seq selected) :available :else :unknown)})
          :lift {:records (vec (for [a records b (:records (input-result (get inputs 1)))
                                     :let [region (intersection a b)] :when region]
                                 (merge region {:id [(:id a) (:id b)] :revision 0
                                                :selection (selection-join (:selection a) (:selection b))
                                                :candidates (apply-candidates invoke [(:candidates a) (:candidates b)])})))})]
    (merge {:domain-ready? (every? :domain-ready? domains)
            :domain-support (apply support/combine (keep :domain-support domains))
            :domain-states (apply support/join (keep :domain-states domains))
            :unavailable-before (last (sort (keep :unavailable-before domains)))
            :unavailable-ids (into #{} (mapcat :unavailable-ids) domains)}
           (update computed :records #(vec (sort-by order-key %))))))

(defn merge-behavior [operation invoke content update]
  (let [inputs (merge-with join-input (:inputs content) (:inputs update))
        config (merge (:config content) (:config update))
        state {:inputs inputs :config config}]
    (cond
      (= state (select-keys content [:inputs :config])) content
      (and (:result update) (= state (select-keys update [:inputs :config]))) update
      (not (:ready? config)) (assoc state :result {:records []} :cache [])
      :else
      (let [result (calculate operation inputs config invoke (:cache content))]
        (assoc state :result (dissoc result :cache) :cache (get result :cache []))))))

(defn template [input-names output-names calculate]
  (let [names (into (vec input-names) output-names)
        ports (zipmap names (repeatedly (count names) ids/new-node-id))
        initial (reduce-kv net/assoc-net-dict-entry (nb/install-cells (vals ports)) ports)
        [_ installed] ((prop/construct-propagator
                        ::template
                        (fn [_ _ network]
                          (let [result (calculate (update-vals (select-keys ports input-names)
                                                               #(net/network-cell-strongest network %)))]
                            (mapv #(message (ports %) (get result %)) output-names)))
                        (mapv ports input-names) (mapv ports output-names)) initial)]
    installed))

(defn networks [merge-state raw?]
  {:merge-net (cond-> (template [:content :update] [:out]
                                (fn [{:keys [content update]}]
                                  {:out (merge-state content update)}))
                raw? (net/assoc-net-dict-entry :reducer/raw-slot :evidence))
   :strongest-net
   (template [:slots] [:out :dependence :epoch]
             (fn [{:keys [slots]}]
               {:out (project slots) :dependence #{}
                ;; A semantic immutable frontier, not an activation counter.
                :epoch (select-keys slots [:evidence :inputs :config :window :unavailable-before :unavailable-ids])}))})

(defn source-networks [] (networks merge-source true))

(defn update-value [id networks slots]
  (reducer/reducer-cell id (:merge-net networks) (:strongest-net networks) slots))
