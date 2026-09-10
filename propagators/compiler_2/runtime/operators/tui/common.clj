(ns propagators.compiler-2.runtime.operators.tui.common
  "Shared TUI operator target lookup and effect request helpers."
  (:require [propagators.compiler-2.runtime.boundary :as boundary]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.compiler-2.operators.block-premise :as premise]
            [propagators.datastructures.event :as event]
            [propagators.gur.flat :as fvm]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn effect-slot-key [effect-id]
  (runtime-ids/effect-slot-key effect-id))

(defn effect-tick
  [network]
  (let [dict (net/net-dict-or-empty network)
        program-epoch (long (or (:program/epoch dict) 0))
        commit-tick (long (or (:runtime/commit-tick dict) 0))]
    (+ (* program-epoch 1000000000) commit-tick)))

(defn declared-slot-cell-id
  "Return the cell named by one declared accessor slot.

  A live accessor names the cell through a parent route. An accessor projected
  across a GUR boundary retains the same relation as a source-slot NodeId."
  [network object-id slot-key]
  (let [object-value
        (cond
          (contains? (net/net-env network) object-id)
          (net/network-cell-strongest network object-id)

          :else
          value/nothing)
        parent-id
        (->> (get (obj/accessor-declarations-for network object-id) slot-key)
             keys
             (sort-by pr-str)
             first)]
    (cond
      (ids/node-id? parent-id)
      parent-id

      (and (obj/accessor-network? object-value)
           (obj/accessor-source-slot-present? object-value slot-key))
      (let [source-value (obj/accessor-source-slot-value object-value slot-key)]
        (cond
          (ids/node-id? source-value)
          source-value

          :else
          nil))

      :else
      nil)))

(defn linked-object-id
  "Resolve a slot cell to the compound object it names."
  [network cell-id]
  (cond
    (not (ids/node-id? cell-id))
    nil

    :else
    (let [cell-value (net/network-cell-strongest network cell-id)]
      (cond
        (ids/node-id? cell-value)
        cell-value

        (obj/accessor-network? cell-value)
        cell-id

        :else
        nil))))

(defn instance-block-head-id
  [network instance-id]
  (let [instance-value (net/network-cell-strongest network instance-id)
        resolved-instance-id
        (cond
          (ids/node-id? instance-value)
          instance-value

          :else
          instance-id)
        blocks-id (declared-slot-cell-id network
                                         resolved-instance-id
                                         :instance/blocks)]
    (cond
      (ids/node-id? blocks-id)
      (linked-object-id network blocks-id)

      :else
      nil)))

(defn block-at-slot-id
  [network instance-id index-id slot-key]
  (let [wanted-index (net/network-cell-strongest network index-id)
        first-block-id (instance-block-head-id network instance-id)]
    (loop [block-id first-block-id
           seen #{}]
      (cond
        (not (ids/node-id? block-id))
        nil

        (contains? seen block-id)
        nil

        :else
        (let [index-cell-id (declared-slot-cell-id network
                                                   block-id
                                                   :block/index)
              slot-cell-id (declared-slot-cell-id network block-id slot-key)
              next-cell-id (declared-slot-cell-id network block-id :cdr)
              block-index
              (cond
                (ids/node-id? index-cell-id)
                (net/network-cell-strongest network index-cell-id)

                :else
                value/nothing)
              next-block-id
              (linked-object-id network next-cell-id)]
          (cond
            (= wanted-index block-index)
            slot-cell-id

            :else
            (recur next-block-id (conj seen block-id))))))))

(defn block-at-text-id
  [network instance-id index-id]
  (block-at-slot-id network instance-id index-id :block/text))

(defn block-at-display-id
  [network instance-id index-id]
  (block-at-slot-id network instance-id index-id :block/display))

(defn block-at-boundary-cell-ids
  "Return only the host cells needed to resolve one block by index."
  [network instance-id index-id]
  (cond
    (not (and (ids/node-id? instance-id) (ids/node-id? index-id)))
    []

    :else
    (let [wanted-index (net/network-cell-strongest network index-id)
        instance-value (net/network-cell-strongest network instance-id)
        resolved-instance-id
        (cond
          (ids/node-id? instance-value) instance-value
          :else instance-id)
        blocks-id (declared-slot-cell-id network
                                         resolved-instance-id
                                         :instance/blocks)
        first-block-id
        (linked-object-id network blocks-id)]
    (loop [block-id first-block-id
           seen #{}
           boundary-ids [instance-id index-id blocks-id]]
      (cond
        (not (ids/node-id? block-id))
        (vec (filter ids/node-id? boundary-ids))

        (contains? seen block-id)
        (vec (filter ids/node-id? boundary-ids))

        :else
        (let [index-cell-id (declared-slot-cell-id network
                                                   block-id
                                                   :block/index)
              next-cell-id (declared-slot-cell-id network block-id :cdr)
              block-index
              (cond
                (ids/node-id? index-cell-id)
                (net/network-cell-strongest network index-cell-id)

                :else
                value/nothing)
              extended (into boundary-ids
                             [block-id index-cell-id next-cell-id])]
          (cond
            (= wanted-index block-index)
            (vec (filter ids/node-id? extended))

            (ids/node-id? next-cell-id)
            (recur (linked-object-id network next-cell-id)
                   (conj seen block-id)
                   extended)

            :else
            (vec (filter ids/node-id? extended)))))))))

(def display-boundary-dict-keys
  (conj operator-value/effect-boundary-dict-keys
        premise/binding-contexts-key))

(defn premise-state-boundary-cell-ids
  [network source-id]
  (cond
    (ids/node-id? source-id)
    (->> (premise/binding-contexts network source-id)
         (map :premise/state-cell)
         (filter ids/node-id?)
         vec)

    :else
    []))

(defn tui-write-effect-request
  [effect-id text-id payload epoch]
  (boundary/tui-write-effect-request effect-id text-id payload epoch))

(defn tui-display-effect-request
  [effect-id display-id payload tick]
  (boundary/tui-display-effect-request effect-id display-id payload tick))

(defn scope-distributed-claims
  "Give claims copied into a shared boundary cell a source-local identity.

  Retained applications may reuse an internal claim ID in distinct result
  cells.  Those claims are valid in isolation, but must not collide when their
  histories meet in one TUI display cell."
  [scope source-content contexts]
  (let [context-supports
        (mapv (fn [{:premise/keys [id state-cell]}]
                (tms/support id
                             [:compiler-2/block-premise state-cell]
                             :block-premise))
              contexts)]
    (tms/distributed-content
     (reduce-kv
      (fn [slots slot fact]
        (if (tms/claim? fact)
          (let [claim-id [scope (tms/claim-id fact)]]
            (assoc slots
                   (tms/claim-slot-key claim-id)
                   (tms/claim claim-id
                              (tms/proposition fact)
                              (tms/claim-value fact)
                              (into (tms/support-objects fact)
                                    context-supports))))
          (assoc slots slot fact)))
      {}
      (tms/distributed-slots source-content)))))

(defn forward-distributed-display-update
  "Forward distributed claims together with the latest premise-state facts.

  A source cell can retain the claim produced by an old block version while
  that version's current active/retracted state lives in a separate context
  cell.  Forwarding only the source would leave the display's copied premise
  state stale."
  [claim-scope source-content state-contents contexts]
  (let [forwarded (scope-distributed-claims claim-scope source-content contexts)
        state-update (tms/distributed-state-update
                      (into [source-content] state-contents))]
    (cond
      (value/contradiction? state-update) state-update
      state-update (tms/merge-distributed-content forwarded state-update)
      :else forwarded)))

(defn- event-display-messages
  [display-id content]
  (mapv (fn [fact]
          (message
           display-id
           (if (event/active? fact)
             (event/active-event display-id
                                 (event/source fact)
                                 (event/timestamp fact)
                                 (event/event-value fact))
             (event/retraction-event display-id
                                     (event/source fact)
                                     (event/timestamp fact)))))
        (event/latest-facts content)))

(defn event-display-result
  [display-id source-id]
  (let [prop-id (runtime-ids/stable-node-id
                 :tui :event-block-display display-id source-id)]
    {:effects
     [(fvm/declare-prop
       prop-id :runtime/tui-event-block-display [source-id] [display-id]
       (fn [_inputs _outputs current]
         (let [content (net/network-cell-content current source-id)]
           (if (event/event-content? content)
             (event-display-messages display-id content)
             []))))]
     :messages []}))

(defn supported-display-result
  "Connect a premise-supported source directly to a TUI block display cell.

  Returns nil for raw sources so explicit legacy display effects retain their
  existing outbox behavior."
  [network display-id source-id]
  (let [contexts (vec (premise/binding-contexts network source-id))
        source-content (net/network-cell-content network source-id)
        event-source? (event/protocol-cell? network source-id)]
    (cond
      event-source?
      (event-display-result display-id source-id)

      (seq contexts)
      (let [state-ids (mapv :premise/state-cell contexts)
            inputs (into [source-id] state-ids)
            prop-id (runtime-ids/stable-node-id
                     :tui :block-display display-id source-id)
            activate
            (fn [_inputs _outputs current]
              (let [source-content (net/network-cell-content current source-id)
                    state-contents
                    (mapv #(net/network-cell-content current %) state-ids)
                    update
                    (if (tms/distributed-value? source-content)
                      (forward-distributed-display-update
                       [:tui/block-display display-id source-id]
                       source-content
                       state-contents
                       contexts)
                      (premise/support-update
                       [:tui/block-display display-id source-id]
                       (net/network-cell-strongest current source-id)
                       source-content
                       state-contents
                       contexts))]
                (if update [(message display-id update)] [])))]
        {:effects [(fvm/declare-prop prop-id
                                     :runtime/tui-block-display
                                     inputs [display-id] activate)]
         :messages []})

      :else nil)))

(defn trace-target-value
  [label source-id]
  {:trace/target true
   :trace/symbol label
   :node source-id
   :label label})
