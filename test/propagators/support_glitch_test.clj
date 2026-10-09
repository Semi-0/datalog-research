(ns propagators.support-glitch-test
  "Enumerate legal outer task orders; use production activation and patch evaluation."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.layered.procedure :as layer-procedure]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.support :as support]))

(defn- supported [source v epoch]
  (collection/content
   {:base v :support #{{:source source :timestamp epoch :premises-status :active}}}))

(defn- snapshot [network id]
  (let [v (net/network-cell-strongest network id)]
    {:base (datum/layer-value v :base)
     :support (datum/support-of v)
     :usable? (not (value/unusable? v))}))

(defn- addition [network]
  (let [base-procedure (layer-procedure/base base/plus-closure)
        [procedure base-id support-id] (repeatedly 3 ids/new-node-id)
        prepared (-> network
                     (nb/install-cell base-id base-procedure base-procedure)
                     (nb/install-cell support-id support/procedure support/procedure))
        base-layer (layered/install-layered-procedure! prepared procedure :base base-id)
        support-layer (layered/install-layered-procedure!
                       (:net base-layer) procedure :support support-id)]
    [procedure (:net support-layer)]))

(defn- install-applications [network procedure cells]
  (reduce
   (fn [[network labels] [label arguments output]]
     (let [[id installed] ((layered/p:apply-layered procedure arguments output) network)
           application (net/network-env-lookup installed id)
           activate (prop/compose-activation
                     (prop/prop-f application)
                     (message/lift-message collection/content))]
       [(net/assoc-net-prop installed id (assoc application :activate activate))
        (assoc labels id label)]))
   [network {}]
   [[:left [(:source cells) (:one cells)] (:left cells)]
    [:right [(:source cells) (:two cells)] (:right cells)]
    [:join [(:left cells) (:right cells)] (:join cells)]]))

(defn- diamond []
  (let [cells (zipmap [:source :one :two :left :right :join :published]
                      (repeatedly 7 ids/new-node-id))
        declared (reduce nb/install-cell net/empty-net (vals cells))
        bottom (collection/content {:base value/nothing :support #{}})
        seeded (reduce
                (fn [n [k v]]
                  (second (core/eval-cell (cells k) (message/message (cells k) v) n)))
                declared
                [[:source (supported (:source cells) 10 1)] [:one 1] [:two 2]
                 [:left bottom] [:right bottom] [:join bottom] [:published bottom]])
        [procedure n1] (addition seeded)
        [n2 labels] (install-applications n1 procedure cells)
        [consumer n3]
        ((prop/construct-propagator
          :test/concrete-consumer
          (prop/concrete-propagator
           (fn [_inputs _outputs network]
             [(message/message
               (:published cells)
               (collection/content (net/network-cell-strongest network (:join cells))))]))
          [(:join cells)] [(:published cells)]) n2)
        labels (assoc labels consumer :consumer)]
    {:network (nb/run-propagators n3 (keys labels))
     :cells cells :labels labels :consumer consumer}))

(defn- queued-ids [queue]
  (loop [queue queue result #{}]
    (if-let [[id remaining] (tq/pop-task queue)]
      (recur remaining (conj result id))
      result)))

(defn- step [state selected {:keys [consumer cells labels]}]
  (let [evaluate
        (fn [id network {:keys [success fail]}]
          (runner/evaluate-propagator
           id network
           {:fail fail
            :success
            (fn [evaluated network]
              (let [next-state (success evaluated network)]
                (if (= consumer id)
                  (update next-state :publications into
                          (mapcat (comp :support/observations message/message-value)
                                  (:activation-result evaluated)))
                  next-state)))}))
        advance (constructor/network-iterator-constructor
                 {:tasks-empty? empty?
                  :take-task (fn [_network tasks]
                               (assert (contains? tasks selected))
                               [selected (disj tasks selected)])
                  :add-tasks (fn [pending new-tasks] (into pending (queued-ids new-tasks)))}
                 evaluate runner/evaluate-patches)
        after (advance state {:continue identity
                              :done (fn [_] (throw (ex-info "Unexpected empty step" {})))
                              :fail (fn [error] (throw error))})]
    (-> after
        (update :steps conj (labels selected))
        (update :views conj (snapshot (:network after) (:join cells))))))

(defn- all-completions [state fixture]
  (cond
    (empty? (:tasks state))
    [(assoc (dissoc state :network :tasks)
            :published (snapshot (:network state) (get-in fixture [:cells :published])))]

    (> (count (:steps state)) 8)
    (throw (ex-info "Diamond failed to quiesce within the acyclic task bound"
                    {:steps (:steps state)}))

    :else
    (mapcat #(all-completions (step state % fixture) fixture)
            (sort-by (comp pr-str (:labels fixture)) (:tasks state)))))

(deftest diamond-mixed-version-safety-and-eventual-publication
  (let [{:keys [network cells] :as fixture} (diamond)
        expected-support #{{:source (:source cells) :timestamp 2 :premises-status :active}}
        expected {:base 43 :support expected-support}
        [tasks updated] (core/eval-cell (:source cells)
                                        (message/message (:source cells)
                                                         (supported (:source cells) 20 2))
                                        network)
        completions (vec (all-completions
                          {:network updated :tasks (queued-ids tasks)
                           :steps [] :views [] :publications []} fixture))
        mixed (filter #(= 33 (:base %)) (mapcat :views completions))]
    (testing "the initial concrete consumer really published the old coherent result"
      (is (= 23 (:base (snapshot network (:published cells))))))
    (testing "both branch orders and an intentionally early join were explored"
      (is (= 8 (count completions)))
      (is (= #{:left :right} (set (map (comp first :steps) completions))))
      (is (seq mixed))
      (is (some #(= [:left :join :consumer] (vec (take 3 (:steps %)))) completions))
      (is (some #(= [:right :join :consumer] (vec (take 3 (:steps %)))) completions)))
    (testing "mixed computations retain both epochs and cannot activate the consumer"
      (doseq [v mixed]
        (is (= #{1 2} (set (map :timestamp (:support v)))))
        (is (false? (:usable? v))))
      (is (every? #(every? #{expected} (:publications %)) completions)
          (pr-str (mapv #(select-keys % [:steps :publications]) completions))))
    (testing "every legal schedule must eventually publish the coherent new result"
      (let [unsettled (filterv #(not= (assoc expected :usable? true)
                                    (last (:views %))) completions)
            unpublished (filterv #(not (some #{expected} (:publications %))) completions)]
        (is (empty? (mapv :steps unsettled))
            "Mixed-version evidence must not poison the coherent result at the same epoch")
        (is (empty? (mapv :steps unpublished))
            "The concrete consumer must eventually publish 43, not remain at 23")))))
