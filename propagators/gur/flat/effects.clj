(ns propagators.gur.flat.effects
  "Kernel-applied declaration effects for the flat network VM experiment."
  (:require [propagators.cells.cell :as cell]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :as msg]
            [propagators.network :as net]
            [propagators.network-vm.instructions :as instr]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def root-key [:gur.flat :root])
(def cell-index-key [:gur.flat :cells])
(def prop-index-key [:gur.flat :props])
(def name-bindings-key instr/name-bindings-key)

(def ^:private bounded-effect-ops
  #{:declare-cell :declare-prop :bind-name})

(defn stable-node-id
  [parts]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:gur.flat] parts))
               StandardCharsets/UTF_8))))

(defn vm-net
  ([] (vm-net net/empty-net))
  ([n]
   (net/assoc-net-dict-entry n root-key true)))

(defn instruction-op
  [x]
  (or (:network-vm/op x)
      (:gur.flat/op x)
      (:op x)))

(defn effect?
  [x]
  (and (map? x) (contains? bounded-effect-ops (instruction-op x))))

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
      (net/assoc-net-dict-entry [:gur.flat/cell id] id)
      (net/update-net-dict-entry cell-index-key (fnil conj #{}) id)))

(defn- ensure-cell
  [n id]
  (if (contains? (net/net-env n) id)
    n
    (net/install-net n (cell/construct-cell id))))

(defn- commit-prop
  [n id]
  (-> n
      (net/assoc-net-dict-entry [:gur.flat/prop id] id)
      (net/update-net-dict-entry prop-index-key (fnil conj #{}) id)))

(defn- ensure-indexed-cell
  [n id]
  (-> n
      (ensure-cell id)
      (commit-cell id)))

(defn- ensure-indexed-cells
  [n ids]
  (reduce ensure-indexed-cell n (distinct ids)))

(defn- effect-result
  [tasks n]
  {:tasks tasks :net n :messages []})

(defn- apply-declare-cell
  [tasks n {:keys [id]}]
  (effect-result tasks (ensure-indexed-cell n id)))

(defn- install-prop-once
  [n id inputs outputs activate]
  (let [already? (contains? (net/net-env n) id)
        n0 (ensure-indexed-cells n (concat inputs outputs))
        [_ n1] (if already?
                 [id n0]
                 ((prop/construct-propagator id activate inputs outputs) n0))]
    {:already? already?
     :net (commit-prop n1 id)}))

(defn- apply-declare-prop
  [tasks n {:keys [id inputs outputs activate]}]
  (let [{:keys [already? net]} (install-prop-once n id inputs outputs activate)]
    (effect-result (if already? tasks (tq/enqueue tasks id)) net)))

(defn- apply-bind-name
  [tasks n instruction]
  (effect-result
   tasks
   (net/update-net-dict-entry n
                              name-bindings-key
                              #(assoc-in (or % {})
                                         [(:scope instruction)
                                          (:name instruction)]
                                         (:id instruction)))))

(defn apply-effect
  [tasks n instruction]
  (let [op (instruction-op instruction)]
    (case op
      :declare-cell
      (apply-declare-cell tasks n instruction)

      :declare-prop
      (apply-declare-prop tasks n instruction)

      :bind-name
      (apply-bind-name tasks n instruction)

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
