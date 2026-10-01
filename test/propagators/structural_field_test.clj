(ns propagators.structural-field-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.cells.merge :as merge]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.structural-field :as field]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(defn- premise [source epoch status]
  {:source source :timestamp epoch :premises-status status})

(defn- observation [base & premises]
  (collection/content {:base base :support (set premises)}))

(defn- execute [n tasks]
  (let [steps (atom 0)
        result (binding [runner/*advance-transform*
                         (fn [advance]
                           (fn [state ks]
                             (if (> (swap! steps inc) 2000)
                               ((:fail ks) (ex-info "Experiment transition limit" {}))
                               (advance state ks))))]
                 (runner/run-network tasks n))]
    (is (= :completed (:status result)) (some-> (:error result) ex-message))
    {:network (runner/completed-network result) :steps @steps}))

(defn- publish [n & patches]
  (let [[tasks seeded] (core/eval-cells patches n)]
    (:network (execute seeded tasks))))

(defn- install [{:keys [network tasks]} installer]
  (let [[id n] (installer network)]
    {:network n :tasks (conj tasks id)}))

(defn- fixture
  ([cycle?] (fixture cycle? field/p:structural-field))
  ([cycle? field-installer]
  (let [[data a b c ab bc ca] (repeatedly 7 ids/new-node-id)
        links (if cycle? [[ab a] [ab b] [bc b] [bc c] [ca c] [ca a]]
                  [[ab a] [ab b] [bc b] [bc c]])
        installed (reduce (fn [state [owner participant]]
                            (install state (obj/p:network-slot :x participant owner)))
                          {:network (nb/install-cells [data a b c ab bc ca]) :tasks []}
                          links)
        installed (install installed (field-installer data :x b))]
    (assoc installed :network (:network (execute (:network installed) (:tasks installed)))
           :data data :peers [a b c] :owner ab))))

(defn- view [n id]
  (let [v (net/network-cell-strongest n id)]
    {:base (datum/layer-value v :base) :support (datum/support-of v)
     :unusable? (value/unusable? v)}))

(defn- check-peers [n peers base support unusable?]
  (doseq [id peers]
    (is (= {:base base :support support :unusable? unusable?} (view n id)))))

(deftest pure-fields-preserve-premises
  (let [a (ids/new-node-id) b (ids/new-node-id)
        pa (premise a 1 :active) pb (premise b 2 :active)
        content (field/field-content (observation 10 pb) #{pa})
        v (merge/strongest-value content net/empty-net)]
    (is (= 10 (datum/layer-value v :base)))
    (is (= #{pa pb} (datum/support-of v)))
    (is (= (observation {:nested 10} pa) (field/field-content {:nested 10} #{pa})))
    (let [bad (field/field-content (observation 10 (premise a 2 :active)) #{pa})
          v (merge/strongest-value bad net/empty-net)]
      (is (value/unusable? v))
      (is (= #{pa (premise a 2 :active)} (datum/support-of v))))))

(deftest struct-lifecycle-through-real-chains-and-cycles
  (doseq [cycle? [false true]]
    (let [{:keys [network data peers tasks owner]} (fixture cycle?)
          frames (reduce (fn [frames [epoch status payload]]
                           (conj frames (publish (peek frames)
                                                (message/message data
                                                  (observation payload (premise data epoch status))))))
                         [network]
                         [[1 :active {:x 10}] [2 :active {:x 20}]
                          [3 :retracted value/nothing] [4 :active value/nothing]
                          [5 :active {:x 7}] [6 :active {}] [7 :active {:x 8}]])
          final (peek frames)]
      (doseq [[n epoch status base] (map vector (rest frames) (range 1 8)
                                       [:active :active :retracted :active :active :active :active]
                                       [10 20 value/nothing value/nothing 7 value/nothing 8])]
        (check-peers n peers base #{(premise data epoch status)} (value/unusable? base))
        (is (= (net/net-graph network) (net/net-graph n))))
      (is (= final (:network (execute final tasks))))
      (is (= final (publish final (message/message data (observation {:x 8} (premise data 7 :active))))))
      (is (= final (publish final (message/message data (observation {:x 10} (premise data 1 :active))))))
      (let [late (ids/new-node-id)
            [id n] ((obj/p:network-slot :x late owner) (nb/ensure-cell final late))
            result (:network (execute n [id]))]
        (check-peers result [late] 8 #{(premise data 7 :active)} false)))))

(deftest reverse-writes-merge-without-modifying-the-struct
  (doseq [index [0 2] same-value? [false true]]
    (let [{:keys [network data peers]} (fixture true)
          writer (nth peers index)
          pa (premise data 1 :active) pb (premise writer 1 :active)
          ready (publish network (message/message data (observation {:x 10} pa)))
          updated (publish ready (message/message writer (observation (if same-value? 10 20) pb)))
          expected (if same-value? 10 value/contradiction)
          withdrawn (publish updated (message/message writer
                                         (observation value/nothing (premise writer 2 :retracted))))
          restored (publish withdrawn (message/message writer (observation 10 (premise writer 3 :active))))]
      (check-peers updated peers expected #{pa pb} (not same-value?))
      (doseq [id peers]
        (is (:unusable? (view withdrawn id)))
        (is (= #{pa (premise writer 2 :retracted)} (:support (view withdrawn id)))))
      (check-peers restored peers 10 #{pa (premise writer 3 :active)} false)
      (is (= (net/network-cell-content ready data) (net/network-cell-content restored data))))))

(deftest nested-fields-compose-through-real-slots
  (let [{:keys [network data peers]} (fixture false)
        [owner left right] (repeatedly 3 ids/new-node-id)
        installed (-> {:network (reduce nb/ensure-cell network [owner left right]) :tasks []}
                      (install (obj/p:network-slot :y left owner))
                      (install (obj/p:network-slot :y right owner))
                      (install (field/p:structural-field (last peers) :y left)))
        n (:network (execute (:network installed) (:tasks installed)))
        field-source (ids/new-node-id)
        n (nb/ensure-cell n field-source)
        pb (premise field-source 1 :active)
        ready (publish n (message/message data
                         (observation {:x {:y (observation 42 pb)}} (premise data 1 :active))))
        withdrawn (publish ready (message/message data
                                  (observation value/nothing (premise data 2 :retracted))))
        restored (publish withdrawn (message/message data
                                  (observation {:x {:y (observation 43 pb)}} (premise data 3 :active))))]
    (check-peers ready [left right] 42 #{pb (premise data 1 :active)} false)
    (doseq [id [left right]] (is (:unusable? (view withdrawn id))))
    (check-peers restored [left right] 43 #{pb (premise data 3 :active)} false)))

(deftest unusable-structs-transport-and-invalid-inputs-fail
  (doseq [payload [value/nothing value/contradiction]]
    (let [{:keys [network data peers]} (fixture false)
          n (publish network (message/message data (observation payload (premise data 1 :active))))]
      (check-peers n peers payload #{(premise data 1 :active)} true)))
  (doseq [payload [{:x 10} (observation 42 (premise (ids/new-node-id) 1 :active))]]
    (let [{:keys [network data]} (fixture false)
          [tasks seeded] (core/eval-cells [(message/message data payload)] network)
          result (runner/run-network tasks seeded)]
      (is (= :failed (:status result)))
      (is (re-find #"Expected (TTMS struct content|supported struct map)" (ex-message (:error result)))))))

(deftest data-before-access-and-batched-freshness
  (doseq [reverse? [false true]]
    (let [[data owner left right] (repeatedly 4 ids/new-node-id)
          seeded (publish (nb/install-cells [data owner left right])
                          (message/message data (observation {:x 10} (premise data 1 :active))))
          installed (-> {:network seeded :tasks []}
                        (install (field/p:structural-field data :x left))
                        (install (obj/p:network-slot :x left owner))
                        (install (obj/p:network-slot :x right owner)))
          n (:network (execute (:network installed) (:tasks installed)))
          patches [(message/message data (observation {:x 20} (premise data 2 :active)))
                   (message/message right (observation 30 (premise data 3 :active)))]
          n (apply publish n (if reverse? (reverse patches) patches))]
      (check-peers n [left right] 30 #{(premise data 3 :active)} false))))

(deftest empty-and-retracted-field-observations
  (let [{:keys [network data peers]} (fixture false)
        b (ids/new-node-id)
        n (nb/ensure-cell network b)
        ready (publish n (message/message data
                         (observation {:x (observation 10 (premise b 1 :active))}
                                      (premise data 1 :active))))
        withdrawn (publish ready (message/message data
                                  (observation {:x (observation value/nothing (premise b 2 :retracted))}
                                               (premise data 2 :active))))
        absent (publish withdrawn (message/message data
                                  (observation {:x {:support/observations #{}}}
                                               (premise data 3 :active))))]
    (check-peers ready peers 10 #{(premise b 1 :active) (premise data 1 :active)} false)
    (check-peers withdrawn peers value/nothing
                 #{(premise b 2 :retracted) (premise data 2 :active)} true)
    (check-peers absent peers value/nothing #{(premise data 3 :active)} true)
    (is (thrown? clojure.lang.ExceptionInfo
                 (field/field-content {:support/observations :invalid} #{})))))

(deftest contradictory-struct-source-recovers-at-newer-version
  (let [{:keys [network data peers]} (fixture false)
        p1 (premise data 1 :active)
        conflict (publish network
                          (message/message data (observation {:x 10} p1))
                          (message/message data (observation {:x 20} p1)))
        restored (publish conflict
                          (message/message data (observation {:x 30} (premise data 2 :active))))]
    (check-peers conflict peers value/contradiction #{p1} true)
    (check-peers restored peers 30 #{(premise data 2 :active)} false)))

(deftest simultaneous-independent-writes-use-ordinary-ttms
  (doseq [reverse? [false true]]
    (let [{:keys [network data peers]} (fixture true)
          writer (last peers)
          pa (premise data 1 :active) pb (premise writer 1 :active)
          patches [(message/message data (observation {:x 10} pa))
                   (message/message writer (observation 20 pb))]
          n (apply publish network (if reverse? (reverse patches) patches))]
      (check-peers n peers value/contradiction #{pa pb} true))))

(defn- state-update [& premises]
  (collection/content {:premise-state (set premises)}))

(deftest stateful-struct-lifecycle-keeps-real-slot-topology
  (doseq [cycle? [false true]
          installer [field/p:structural-field field/p:structural-data-field]]
    (let [{:keys [network data peers tasks]} (fixture cycle? installer)
          ready (publish network (message/message data
                                 (collection/merge-content
                                  (observation {:x 10} (premise data 1 :active))
                                  (state-update))))
          withdrawn (publish ready (message/message data (state-update (premise data 2 :retracted))))
          active (publish withdrawn (message/message data (state-update (premise data 3 :active))))
          recovered (publish active (message/message data (observation {:x 20} (premise data 3 :active))))
          absent (publish recovered (message/message data (observation {} (premise data 4 :active))))
          final (publish absent (message/message data (observation {:x 30} (premise data 5 :active))))]
      (doseq [id peers]
        (is (= 10 (:base (view ready id))))
        (doseq [[n epoch status] [[withdrawn 2 :retracted] [active 3 :active] [absent 4 :active]]]
          (let [v (net/network-cell-strongest n id)]
            (is (value/nothing? (datum/layer-value v :base)))
            (is (= #{(premise data epoch status)} (datum/layer-value v :premise-state)))
            (is (every? #(not (value/unusable? (:base %)))
                        (:support/observations (net/network-cell-content n id))))))
        (is (= 20 (:base (view recovered id))))
        (is (= 30 (:base (view final id)))))
      (is (= (net/net-graph network) (net/net-graph final)))
      (is (= final (:network (execute final tasks))))
      (is (= final (publish final (message/message data (state-update (premise data 2 :retracted)))))))))

(deftest stateful-nested-fields-transport-both-struct-and-field-premises
  (let [{:keys [network data peers]} (fixture true)
        [owner left right field-source] (repeatedly 4 ids/new-node-id)
        installed (-> {:network (reduce nb/ensure-cell network [owner left right field-source]) :tasks []}
                      (install (obj/p:network-slot :y left owner))
                      (install (obj/p:network-slot :y right owner))
                      (install (field/p:structural-field (last peers) :y left)))
        n (:network (execute (:network installed) (:tasks installed)))
        pb (premise field-source 1 :active)
        ready (publish n (message/message data
                          (collection/merge-content
                           (observation {:x {:y (observation 42 pb)}} (premise data 1 :active))
                           (state-update))))
        withdrawn (publish ready (message/message data (state-update (premise field-source 2 :retracted))))
        restored (publish withdrawn (message/message data
                                     (observation {:x {:y (observation 43 (premise field-source 3 :active))}}
                                                  (premise data 2 :active))))]
    (check-peers ready [left right] 42 #{pb (premise data 1 :active)} false)
    (doseq [id [left right]]
      (is (value/nothing? (:base (view withdrawn id))))
      (is (contains? (datum/layer-value (net/network-cell-strongest withdrawn id) :premise-state)
                     (premise field-source 2 :retracted))))
    (check-peers restored [left right] 43 #{(premise field-source 3 :active) (premise data 2 :active)} false)))

(deftest stateful-conflicting-writes-repair-with-fresh-information
  (doseq [reverse? [false true]]
    (let [{:keys [network data peers]} (fixture true)
          writer (last peers)
          pa (premise data 1 :active) pb (premise writer 1 :active)
          patches [(message/message data (collection/merge-content (observation {:x 10} pa) (state-update)))
                   (message/message writer (observation 20 pb))]
          conflict (apply publish network (if reverse? (reverse patches) patches))
          fixed (publish conflict (message/message writer (observation 10 (premise writer 2 :active))))]
      (check-peers conflict peers value/contradiction #{pa pb} true)
      (check-peers fixed peers 10 #{pa (premise writer 2 :active)} false)
      (is (= (net/network-cell-content conflict data) (net/network-cell-content fixed data))))))

(deftest stateful-data-before-access-and-late-participants
  (let [[data owner left right] (repeatedly 4 ids/new-node-id)
        seeded (publish (nb/install-cells [data owner left right])
                        (message/message data
                         (collection/merge-content (observation {:x 10} (premise data 1 :active))
                                                   (state-update))))
        installed (-> {:network seeded :tasks []}
                      (install (field/p:structural-field data :x left))
                      (install (obj/p:network-slot :x left owner)))
        n (:network (execute (:network installed) (:tasks installed)))
        withdrawn (publish n (message/message data (state-update (premise data 2 :retracted))))
        [id n] ((obj/p:network-slot :x right owner) withdrawn)
        late (:network (execute n [id]))
        restored (publish late (message/message data (observation {:x 20} (premise data 3 :active))))]
    (doseq [id [left right]]
      (is (value/nothing? (:base (view late id))))
      (is (= #{(premise data 2 :retracted)}
             (datum/layer-value (net/network-cell-strongest late id) :premise-state))))
    (check-peers restored [left right] 20 #{(premise data 3 :active)} false)))

(deftest ordinary-struct-field-names-are-not-ttms-metadata
  (doseq [payload [{:x 10 :support :domain-support}
                   {:x 10 :base :domain-base :support :domain-support}]]
    (let [{:keys [network data peers]} (fixture false field/p:structural-data-field)
          [tasks seeded] (core/eval-cells
                          [(message/message data
                            (collection/merge-content (observation payload (premise data 1 :active))
                                                      (state-update)))] network)
          result (runner/run-network tasks seeded)]
      (is (= :completed (:status result)) (some-> (:error result) ex-message))
      (if (= :completed (:status result))
        (check-peers (:network result) peers 10 #{(premise data 1 :active)} false)
        (is (some? (:error result)) "Keep the boundary failure inspectable")))))

(deftest structural-data-extension-preserves-nested-domain-keys-and-outer-control
  (doseq [stateful? [false true]]
    (let [{:keys [network data peers]} (fixture true field/p:structural-data-field)
          [owner left right] (repeatedly 3 ids/new-node-id)
          installed (-> {:network (reduce nb/ensure-cell network [owner left right]) :tasks []}
                        (install (obj/p:network-slot :support left owner))
                        (install (obj/p:network-slot :support right owner))
                        (install (field/p:structural-data-field (last peers) :support left)))
          n (:network (execute (:network installed) (:tasks installed)))
          payload {:base value/nothing :support :domain-support
                   :x {:base value/contradiction :support 42}}
          initial (observation payload (premise data 1 :active))
          initial (if stateful? (collection/merge-content initial (state-update)) initial)
          ready (publish n (message/message data initial))
          withdrawal (if stateful?
                       (state-update (premise data 2 :retracted))
                       (observation value/nothing (premise data 2 :retracted)))
          withdrawn (publish ready (message/message data withdrawal))
          restored (publish withdrawn (message/message data
                                       (observation (assoc-in payload [:x :support] 43)
                                                    (premise data 3 :active))))]
      (check-peers ready [left right] 42 #{(premise data 1 :active)} false)
      (doseq [id [left right]]
        (is (value/nothing? (:base (view withdrawn id)))))
      (check-peers restored [left right] 43 #{(premise data 3 :active)} false)
      (is (= (net/net-graph ready) (net/net-graph restored)))
      (is (= (net/network-cell-content ready data)
             (net/network-cell-content (nb/run-propagators ready (:tasks installed)) data))))))

(deftest structural-data-extension-still-blocks-unusable-outer-envelopes
  (doseq [base [value/nothing value/contradiction]
          status [:active :retracted]]
    (let [{:keys [network data peers]} (fixture false field/p:structural-data-field)
          n (publish network (message/message data
                              (collection/merge-content
                               (observation base (premise data 1 status)) (state-update))))]
      (doseq [id peers]
        (is (value/nothing? (:base (view n id))))
        (is (= #{} (:support/observations (net/network-cell-content n id))))
        (is (= #{(premise data 1 status)}
               (datum/layer-value (net/network-cell-strongest n id) :premise-state)))))))

(defn diagnostic
  "Bounded sample, not a performance benchmark. Counts real runner transitions."
  []
  (mapv
   (fn [cycle?]
     (let [{:keys [network data peers tasks]} (fixture cycle?)
           started (System/nanoTime)
           frames (reduce
                   (fn [frames [epoch status payload]]
                     (let [[pending n] (core/eval-cells
                                        [(message/message data (observation payload (premise data epoch status)))]
                                        (:network (peek frames)))]
                       (conj frames (execute n pending))))
                   [{:network network}]
                   [[1 :active {:x 10}] [2 :active {:x 20}]
                    [3 :retracted value/nothing] [4 :active {:x 30}]])
           final (:network (peek frames))
           rerun (execute final tasks)]
       {:cycle? cycle?
        :elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        :transitions (mapv :steps (rest frames))
        :outer-node-growth (- (count (net/net-graph final)) (count (net/net-graph network)))
        :participant-observations (mapv #(count (:support/observations (net/network-cell-content final %))) peers)
        :rerun-transitions (:steps rerun)
        :rerun-unchanged? (= final (:network rerun))}))
   [false true]))
