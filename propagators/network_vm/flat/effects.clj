(ns propagators.network-vm.flat.effects
  "Kernel-applied declaration effects for the flat network VM experiment."
  (:require [propagators.cells.cell :as cell]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as msg :refer [message]]
            [propagators.network :as net]
            [propagators.network-vm.instructions :as instr]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def root-key [:network-vm.flat :root])
(def cell-index-key [:network-vm.flat :cells])
(def prop-index-key [:network-vm.flat :props])
(def installer-index-key [:network-vm.flat :installers])
(def name-bindings-key instr/name-bindings-key)

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:network-vm.flat] parts))
               StandardCharsets/UTF_8))))

(defn vm-net
  ([] (vm-net net/empty-net))
  ([n]
   (net/assoc-net-dict-entry n root-key true)))

(defn instruction-op
  [x]
  (or (:network-vm/op x)
      (:network-vm.flat/op x)
      (:op x)))

(defn effect?
  [x]
  (and (map? x) (some? (instruction-op x))))

(defn install-topology
  [install-key installer]
  {:network-vm.flat/op :install-topology
   :install-key install-key
   :installer installer})

(defn normalize-activation-return
  [ret]
  (cond
    (nil? ret)
    {:messages [] :effects []}

    (and (map? ret) (or (contains? ret :messages)
                        (contains? ret :effects)))
    {:messages (vec (:messages ret))
     :effects (vec (:effects ret))}

    (effect? ret)
    {:messages [] :effects [ret]}

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
    (throw (ex-info "unknown kernel effect activation return"
                    {:return ret}))))

(defn- commit-cell
  [n id]
  (-> n
      (net/assoc-net-dict-entry [:network-vm.flat/cell id] id)
      (net/update-net-dict-entry cell-index-key (fnil conj #{}) id)))

(defn- ensure-cell
  [n id]
  (if (contains? (net/net-env n) id)
    n
    (net/install-net n (cell/construct-cell id))))

(defn- commit-prop
  [n id]
  (-> n
      (net/assoc-net-dict-entry [:network-vm.flat/prop id] id)
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

(defn- enqueue-all
  [tasks prop-ids]
  (tq/enqueue-all tasks prop-ids))

(defn apply-effect
  [tasks n instruction]
  (let [op (instruction-op instruction)]
    (case op
      :declare-cell
      {:tasks tasks
       :net (-> n
                (ensure-cell (:id instruction))
                (commit-cell (:id instruction)))
       :messages []}

      :declare-prop
      (let [{:keys [id inputs outputs activate]} instruction
            already? (contains? (net/net-env n) id)
            n0 (reduce (fn [n* cell-id]
                         (-> n*
                             (ensure-cell cell-id)
                             (commit-cell cell-id)))
                       n
                       (distinct (concat inputs outputs)))
            [_ n1] (if already?
                     [id n0]
                     ((prop/construct-propagator id activate inputs outputs) n0))
            n2 (commit-prop n1 id)]
        {:tasks (if already? tasks (tq/enqueue tasks id))
         :net n2
         :messages []})

      :bind-name
      {:tasks tasks
       :net (net/update-net-dict-entry n
                                       name-bindings-key
                                       #(assoc-in (or % {})
                                                  [(:scope instruction)
                                                   (:name instruction)]
                                                  (:id instruction)))
       :messages []}

      :tell
      {:tasks tasks
       :net n
       :messages [(message (:id instruction) (:value instruction))]}

      :schedule
      {:tasks (enqueue-all tasks (:prop-ids instruction))
       :net n
       :messages []}

      :install-topology
      (let [{:keys [install-key installer]} instruction
            installed (get (net/network-dict-entry n installer-index-key)
                           install-key)]
        (if installed
          {:tasks tasks :net n :messages []}
          (let [[ids* n*] (with-redefs [ids/new-node-id
                                        (deterministic-id-fn install-key)]
                            (installer n))
                prop-ids (installer-prop-ids ids*)
                n** (reduce commit-prop
                            (commit-installer (vm-net n*) install-key prop-ids)
                            prop-ids)]
            {:tasks (enqueue-all tasks prop-ids)
             :net n**
             :messages []})))

      (throw (ex-info "unknown kernel effect instruction"
                      {:instruction instruction})))))

(defn apply-effects
  [tasks n effects]
  (reduce (fn [{:keys [tasks net messages]} effect]
            (let [{tasks* :tasks net* :net messages* :messages}
                  (apply-effect tasks net effect)]
              {:tasks tasks*
               :net net*
               :messages (into messages messages*)}))
          {:tasks tasks
           :net n
           :messages []}
          effects))
