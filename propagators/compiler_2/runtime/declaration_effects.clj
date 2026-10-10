(ns propagators.compiler-2.runtime.declaration-effects
  "Immutable effect recording at compiler declaration boundaries."
  (:require [propagators.compiler-2.model.env :as env]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.gur :as gur]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]))

(def buffer-key ::buffer)

(defn discard [network]
  (net/net-with-dict network (dissoc (net/net-dict-or-empty network) buffer-key)))

(defn begin [network]
  (let [base (discard network)]
    (net/assoc-net-dict-entry base buffer-key
      {:base base :effects [] :messages [] :message-positions {} :cells #{} :props #{} :names {} :values {}
       :entries {} :frames {}})))

(defn result [network]
  (if-let [buffer (net/network-dict-entry network buffer-key)]
    (select-keys buffer [:effects :messages])
    (throw (ex-info "Declaration result requires a recording compiler view" {}))))

(defn- update-buffer [network f & args]
  (apply net/update-net-dict-entry network buffer-key f args))

(defn emit-effect [network effect]
  (if-let [{:keys [base cells props names]} (net/network-dict-entry network buffer-key)]
    (let [{:keys [op id scope name]} effect
          previous-name (get-in names [scope name]
                          (get-in (net/network-dict-entry base gur/name-bindings-key) [scope name]))
          known? (case op
                   :network/declare-cell (or (contains? cells id) (contains? (net/net-env base) id))
                   :network/declare-propagator (or (contains? props id) (contains? (net/net-env base) id))
                   :network/bind-name (= id previous-name)
                   :network/extend-propagator-outputs false
                   (throw (ex-info "Unsupported compiler declaration effect" {:effect effect})))]
      (if known?
        network
        (update-buffer network
          (fn [buffer]
            (let [emitted (update buffer :effects conj effect)]
              (case op
                :network/declare-cell (update emitted :cells conj id)
                :network/declare-propagator (update emitted :props conj id)
                :network/bind-name (assoc-in emitted [:names scope name] id)
                :network/extend-propagator-outputs emitted))))))
    network))

(defn emit-message [network msg]
  (if-let [{:keys [base values]} (net/network-dict-entry network buffer-key)]
    (let [id (message/message-id msg)
          candidate (message/message-value msg)
          original (get (net/net-env base) id)
          previous (get values id (if (cell/cell? original) (cell/cell-content original) value/nothing))]
      (if (or (value/nothing? candidate) (= previous candidate))
        network
        (update-buffer network
          (fn [buffer]
            (let [position (get (:message-positions buffer) id)
                  recorded (if (some? position)
                             (assoc-in buffer [:messages position] msg)
                             (-> buffer
                                 (assoc-in [:message-positions id] (count (:messages buffer)))
                                 (update :messages conj msg)))]
              (assoc-in recorded [:values id] candidate))))))
    network))

(defn register-cell [network id]
  (if-let [buffer (net/network-dict-entry network buffer-key)]
    (let [entry (get (net/net-env network) id)]
      (if (or (not (cell/cell? entry)) (identical? entry (get (:entries buffer) id)))
        network
        (-> network
            (emit-effect (gur/declare-cell id))
            (emit-message (message/message id (cell/cell-content entry)))
            (update-buffer assoc-in [:entries id] entry))))
    network))

(defn register-frame [network id]
  (if-let [frame (when (net/network-dict-entry network buffer-key)
                  (get-in (net/network-dict-entry network env/lexical-topology-key) [:frames id]))]
    (if (identical? frame (get-in (net/network-dict-entry network buffer-key) [:frames id]))
      network
      (let [view (net/assoc-net-dict-entry network env/lexical-topology-key {:frames {id frame}})]
        (update-buffer (reduce emit-effect network (env/lexical-topology-effects view))
                       assoc-in [:frames id] frame)))
    network))

(defn register-props [network prop-ids]
  (if (net/network-dict-entry network buffer-key)
    (reduce
      (fn [current id]
        (let [entry (get (net/net-env current) id)
              node (get (net/net-graph current) id)]
          (if (and (prop/prop? entry) node)
            (let [boundary (distinct (concat (:inputs node) (:outputs node)))
                  prepared (reduce register-cell current boundary)
                  framed (reduce register-frame prepared boundary)]
              (emit-effect framed
                (gur/declare-prop id (prop/prop-name entry)
                                  (vec (:inputs node)) (vec (:outputs node)) (prop/prop-f entry))))
            (throw (ex-info "Registered compiler propagator has no declaration" {:id id})))))
      network (distinct prop-ids))
    network))

(defn ensure-cell [network id]
  (register-cell (nb/ensure-cell network id) id))

(defn seed-cell [network id candidate]
  (register-cell (nb/seed-cell network id candidate) id))

(defn install-cell
  ([network id] (register-cell (nb/install-cell network id) id))
  ([network id content strongest]
   (register-cell (nb/install-cell network id content strongest) id)))

(defn eval-activation-result [activation network]
  (let [[tasks installed] (core/eval-activation-result activation network)
        {:keys [effects messages]} (patch/normalize-activation-return activation)]
    [tasks (reduce emit-message (reduce emit-effect installed effects) messages)]))

;; These helpers retain the existing installer contract. Registration records
;; the returned propagators' own declarations, never scans the network.
(def install-propagator nb/install-propagator)
(def neighbor-propagator-ids nb/neighbor-propagator-ids)
(def extend-propagator-outputs nb/extend-propagator-outputs)
(def run-propagators nb/run-propagators)
