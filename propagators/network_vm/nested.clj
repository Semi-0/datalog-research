(ns propagators.network-vm.nested
  "Effect-aware nested executor for the experimental network VM.

  A network-valued cell can be addressed as a child VM. Propagators only return
  messages/effects; this executor performs declaration and message effects and
  writes changed child network declarations back through ordinary cell tells."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as msg :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-vm.instructions :as instr]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def root-key [:network-vm.nested :root])
(def cell-index-key [:network-vm.nested :cells])
(def prop-index-key [:network-vm.nested :props])
(def installer-index-key [:network-vm.nested :installers])
(def name-bindings-key instr/name-bindings-key)

(defrecord NetworkDelta [fragment])

(defn network-delta?
  [x]
  (instance? NetworkDelta x))

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:network-vm.nested] parts))
               StandardCharsets/UTF_8))))

(defn vm-net
  ([] (vm-net net/empty-net))
  ([n]
   (net/assoc-net-dict-entry n root-key true)))

(defn- changed-entries
  [before after]
  (into {}
        (keep (fn [[k v]]
                (when (not= v (get before k))
                  [k v])))
        after))

(defn network-delta
  [before after]
  (->NetworkDelta
   (vm-net
    (net/net (changed-entries (net/net-graph before)
                              (net/net-graph after))
             (changed-entries (net/net-env before)
                              (net/net-env after))
             (changed-entries (net/net-dict-or-empty before)
                              (net/net-dict-or-empty after))))))

(defn- net-content
  [content]
  (cond
    (value/nothing? content) (vm-net)
    (evidence/evidence-set? content) (evidence/strongest content)
    (net/net? content) content
    :else value/contradiction))

(defn merge-network-delta-content
  [content delta]
  (let [current (net-content content)
        fragment (:fragment delta)]
    (cond
      (value/contradiction? current) value/contradiction
      (not (named/named-network? fragment)) value/contradiction
      :else (named/join (vm-net current) fragment))))

(defn state
  ([] (state (vm-net)))
  ([n]
   {:net (vm-net n)
    :messages []
    :tasks {}
    :cursor {}
    :children {}
    :pending-child-deltas {}}))

(defn effect
  [target instruction]
  (assoc instruction :target target))

(defn declare-cell
  [target id]
  (effect target (instr/declare-cell id)))

(defn declare-prop
  [target id inputs outputs activate]
  (effect target (instr/declare-prop id inputs outputs activate)))

(defn bind-name
  [target scope name id]
  (effect target (instr/bind-name scope name id)))

(defn tell
  [target cell-id partial-info]
  (effect target (instr/tell cell-id partial-info)))

(defn schedule
  ([target cause prop-ids]
   (schedule target cause prop-ids 0))
  ([target cause prop-ids index]
   (effect target (instr/schedule cause prop-ids index))))

(defn install-topology
  "Effect for installing an existing propagator installer with deterministic ids.

  `install-key` is a stable domain key. Repeating the same key is idempotent.
  The installed propagator ids are recorded as named-network commitments and
  scheduled once when first declared."
  [target install-key installer]
  {:target target
   :network-vm.nested/op :install-topology
   :install-key install-key
   :installer installer})

(defn- instruction-op
  [x]
  (or (:network-vm/op x)
      (:network-vm.nested/op x)
      (:op x)))

(defn- vm-instruction?
  [x]
  (and (map? x) (some? (instruction-op x))))

(defn- target-of
  [x]
  (or (:target x) :self))

(defn- strip-target
  [x]
  (dissoc x :target))

(defn- task-ids
  [tasks]
  (loop [q (tq/into-queue tasks)
         ids []]
    (if (tq/queue-empty? q)
      ids
      (let [[id q*] (tq/pop-task q)]
        (recur q* (conj ids id))))))

(defn- add-task
  [tasks cause prop-ids index]
  (if (empty? prop-ids)
    tasks
    (reduce (fn [m prop-id]
              (update m cause
                      (fn [entry]
                        {:prop-ids (conj (set (:prop-ids entry)) prop-id)
                         :indexes (conj (set (:indexes entry)) index)})))
            tasks
            prop-ids)))

(defn- pending-task
  [{:keys [tasks cursor]}]
  (reduce-kv
   (fn [best cause {:keys [indexes] :as entry}]
     (let [consumed (get cursor cause #{})]
       (reduce (fn [best index]
                 (if (contains? consumed index)
                   best
                   (let [candidate {:cause cause
                                    :index index
                                    :prop-ids (vec (:prop-ids entry))
                                    :sort-key [(pr-str cause) (pr-str index)]}]
                     (if (or (nil? best)
                             (neg? (compare (:sort-key candidate)
                                            (:sort-key best))))
                       candidate
                       best))))
               best
               indexes)))
   nil
   tasks))

(defn pending-task-count
  [vm-state]
  (reduce-kv
   (fn [n cause {:keys [indexes]}]
     (+ n (count (remove (get (:cursor vm-state) cause #{}) indexes))))
   0
   (:tasks vm-state)))

(defn- commit-cell
  [n id]
  (-> n
      (net/assoc-net-dict-entry [:network-vm.nested/cell id] id)
      (net/update-net-dict-entry cell-index-key (fnil conj #{}) id)))

(defn- commit-prop
  [n id]
  (-> n
      (net/assoc-net-dict-entry [:network-vm.nested/prop id] id)
      (net/update-net-dict-entry prop-index-key (fnil conj #{}) id)))

(defn- commit-installer
  [n install-key prop-ids]
  (net/update-net-dict-entry n installer-index-key
                             #(assoc (or %) install-key (set prop-ids))))

(defn- installer-prop-ids
  [result]
  (->> (tree-seq sequential? seq result)
       (filter ids/node-id?)
       vec))

(defn- deterministic-id-fn
  [install-key]
  (let [counter (atom -1)]
    (fn []
      (stable-node-id [:installer install-key (swap! counter inc)]))))

(defn- schedule-cell-tasks
  [vm-state cell-id value tasks]
  (update vm-state :tasks add-task [:tell cell-id] (task-ids tasks) (hash value)))

(defn- schedule-new-prop
  [vm-state prop-id]
  (update vm-state :tasks add-task [:declare-prop prop-id] [prop-id] 0))

(defn- strongest-or-nothing
  [n id]
  (let [entry (get (net/net-env n) id)]
    (if (cell/cell? entry)
      (cell/cell-strongest entry)
      value/nothing)))

(defn- normalize-activation-return
  [ret]
  (cond
    (nil? ret)
    {:messages [] :effects []}

    (and (map? ret) (or (contains? ret :messages)
                        (contains? ret :effects)))
    {:messages (vec (:messages ret))
     :effects (vec (:effects ret))}

    (vm-instruction? ret)
    {:messages []
     :effects [(if (:target ret) ret (effect :self ret))]}

    (msg/message? ret)
    {:messages [ret] :effects []}

    (sequential? ret)
    (reduce (fn [acc x]
              (let [{:keys [messages effects]} (normalize-activation-return x)]
                (-> acc
                    (update :messages into messages)
                    (update :effects into effects))))
            {:messages [] :effects []}
            ret)

    :else
    (throw (ex-info "unknown nested VM activation return"
                    {:return ret}))))

(defn- apply-message
  [vm-state m]
  (update vm-state :messages conj m))

(defn- child-cell-value
  [vm-state child-id]
  (let [v (strongest-or-nothing (:net vm-state) child-id)]
    (if (net/net? v) v (vm-net))))

(defn child-state
  [vm-state child-id]
  (or (get-in vm-state [:children child-id])
      (state (child-cell-value vm-state child-id))))

(defn child-net
  [vm-state child-id]
  (:net (child-state vm-state child-id)))

(defn- ensure-parent-cell
  [vm-state id]
  (update vm-state :net #(-> %
                             (nb/ensure-cell id)
                             (commit-cell id))))

(defn- merge-delta-fragment
  [current fragment]
  (if current
    (named/join current fragment)
    fragment))

(defn- remember-child-delta
  [vm-state child-id before-net after-net]
  (let [fragment (:fragment (network-delta before-net after-net))]
    (update-in vm-state
               [:pending-child-deltas child-id]
               merge-delta-fragment
               fragment)))

(defn- write-child-net
  [vm-state child-id before-net child]
  (if (= before-net (:net child))
    (assoc-in vm-state [:children child-id] child)
    (-> vm-state
        (assoc-in [:children child-id] child)
        (ensure-parent-cell child-id)
        (remember-child-delta child-id before-net (:net child)))))

(defn- apply-self-instruction
  [vm-state instruction]
  (let [op (instruction-op instruction)
        instruction (strip-target instruction)]
    (case op
      :declare-cell
      (update vm-state :net #(-> %
                                 (nb/ensure-cell (:id instruction))
                                 (commit-cell (:id instruction))))

      :declare-prop
      (let [{:keys [id inputs outputs activate]} instruction
            already? (contains? (net/net-env (:net vm-state)) id)
            n0 (reduce (fn [n cell-id]
                         (-> n
                             (nb/ensure-cell cell-id)
                             (commit-cell cell-id)))
                       (:net vm-state)
                       (distinct (concat inputs outputs)))
            [_ n1] (if already?
                     [id n0]
                     ((prop/construct-propagator id activate inputs outputs) n0))
            vm-state* (assoc vm-state :net (commit-prop n1 id))]
        (if already?
          vm-state*
          (schedule-new-prop vm-state* id)))

      :bind-name
      (update vm-state :net
              net/update-net-dict-entry
              name-bindings-key
              #(assoc-in (or % {}) [(:scope instruction) (:name instruction)]
                         (:id instruction)))

      :tell
      (apply-message vm-state (message (:id instruction) (:value instruction)))

      :schedule
      (update vm-state :tasks add-task
              (:cause instruction)
              (:prop-ids instruction)
              (:index instruction))

      :install-topology
      (let [{:keys [install-key installer]} instruction
            installed (get (net/network-dict-entry (:net vm-state)
                                                   installer-index-key)
                           install-key)]
        (if installed
          vm-state
          (let [[ids* n*] (with-redefs [ids/new-node-id
                                        (deterministic-id-fn install-key)]
                            (installer (:net vm-state)))
                prop-ids (installer-prop-ids ids*)
                n** (reduce commit-prop
                            (commit-installer (vm-net n*) install-key prop-ids)
                            prop-ids)]
            (update (assoc vm-state :net n**)
                    :tasks add-task
                    [:install-topology install-key]
                    prop-ids
                    0))))

      (throw (ex-info "unknown nested network VM instruction"
                      {:instruction instruction})))))

(defn apply-effect
  [vm-state effect-value]
  (let [target (target-of effect-value)
        instruction (strip-target effect-value)]
    (cond
      (= :self target)
      (apply-self-instruction vm-state instruction)

      (and (vector? target) (= :cell (first target)))
      (let [child-id (second target)
            vm-state* (ensure-parent-cell vm-state child-id)
            child (child-state vm-state* child-id)
            before-net (:net child)
            child* (apply-self-instruction child instruction)]
        (write-child-net vm-state* child-id before-net child*))

      :else
      (throw (ex-info "unknown nested VM effect target"
                      {:effect effect-value})))))

(defn apply-effects
  [vm-state effects]
  (reduce apply-effect vm-state effects))

(defn apply-instruction
  [vm-state instruction]
  (apply-effect vm-state (effect :self instruction)))

(defn apply-instructions
  [vm-state instructions]
  (reduce apply-instruction vm-state instructions))

(defn- advance-message
  [vm-state]
  (let [m (first (:messages vm-state))
        remaining (subvec (vec (:messages vm-state)) 1)
        [tasks n*] (core/eval-cell* (net/net-dict-or-empty (:net vm-state))
                                    m
                                    (:net vm-state))
        child-id (:id m)
        v* (strongest-or-nothing n* child-id)
        vm-state* (-> vm-state
                      (assoc :net n*
                             :messages remaining)
                      (schedule-cell-tasks child-id (:value m) tasks))]
    (if (and (net/net? v*) (contains? (:children vm-state*) child-id))
      (assoc-in vm-state* [:children child-id :net] (vm-net v*))
      vm-state*)))

(defn- apply-activation-result
  [vm-state ret]
  (let [{:keys [messages effects]} (normalize-activation-return ret)]
    (-> vm-state
        (update :messages into messages)
        (apply-effects effects))))

(defn- advance-task
  [vm-state {:keys [cause index prop-ids]}]
  (let [n (:net vm-state)
        graph (net/net-graph n)
        env (net/net-env n)]
    (-> (reduce (fn [state prop-id]
                  (let [node (graph/get-node graph prop-id)
                        inputs (graph/node-input-ids node)
                        outputs (graph/node-output-ids node)
                        f (prop/prop-f (net/env-get env prop-id))]
                    (apply-activation-result state (f inputs outputs (:net state)))))
                vm-state
                prop-ids)
        (update-in [:cursor cause] (fnil conj #{}) index))))

(declare temperature advance)

(defn- hot-child-entry
  [vm-state]
  (->> (:children vm-state)
       (filter (fn [[_ child]] (pos? (temperature child))))
       (sort-by (fn [[id _]] (pr-str id)))
       first))

(defn- pending-child-delta-entry
  [vm-state]
  (->> (:pending-child-deltas vm-state)
       (sort-by (fn [[id _]] (pr-str id)))
       first))

(defn- advance-child
  [vm-state]
  (let [[child-id child] (hot-child-entry vm-state)
        before-net (:net child)
        child* (advance child)]
    (write-child-net vm-state child-id before-net child*)))

(defn- flush-child-delta
  [vm-state]
  (let [[child-id fragment] (pending-child-delta-entry vm-state)]
    (-> vm-state
        (update :pending-child-deltas dissoc child-id)
        (ensure-parent-cell child-id)
        (apply-message (message child-id (->NetworkDelta fragment))))))

(defn temperature
  [vm-state]
  (+ (count (:messages vm-state))
     (pending-task-count vm-state)
     (count (:pending-child-deltas vm-state))
     (reduce + 0 (map temperature (vals (:children vm-state))))))

(defn cold?
  [vm-state]
  (zero? (temperature vm-state)))

(defn advance
  [vm-state]
  (cond
    (seq (:messages vm-state))
    (advance-message vm-state)

    (pending-task vm-state)
    (advance-task vm-state (pending-task vm-state))

    (hot-child-entry vm-state)
    (advance-child vm-state)

    (pending-child-delta-entry vm-state)
    (flush-child-delta vm-state)

    :else
    vm-state))

(defn run-until-cold
  ([vm-state] (run-until-cold vm-state 4096))
  ([vm-state max-steps]
   (loop [remaining max-steps
          current vm-state]
     (cond
       (cold? current) current
       (zero? remaining) (throw (ex-info "nested network VM exceeded step budget"
                                         {:max-steps max-steps
                                          :temperature (temperature current)}))
       :else (recur (dec remaining) (advance current))))))

(defn strongest-or-nothing-in
  [vm-state id]
  (strongest-or-nothing (:net vm-state) id))
