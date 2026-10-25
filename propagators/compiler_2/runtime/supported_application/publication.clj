(ns propagators.compiler-2.runtime.supported-application.publication
  "Live candidate gates over immutable returned owners. No source stamping."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.supported-slot :as supported-slot]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.support :as support]
            [propagators.compiler-2.runtime.supported-application.ports :as ports]
            [propagators.gur.flat :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn base [v]
  (if (datum/layer-present? v :base) (datum/layer-value v :base) v))

(defn active? [operator key]
  (and (not (value/unusable? operator))
       (not (value/unusable? (base operator)))
       (= key (ports/candidate-key (base operator)))))

(defn- update-content [operator source enabled?]
  (let [states (support/join (state/states-of operator) (state/states-of source))]
    (if (and enabled?
             (or (not (value/nothing? (base source)))
                 (seq (datum/support-of source))
                 (seq (state/states-of source))))
      (collection/content
       {:base (base source)
        :support (support/combine (datum/support-of operator) (datum/support-of source))
        :premise-state states})
      (collection/content {:premise-state states}))))

(def feedback-scope ::feedback)

(defn own-feedback?
  "A caller write is already present in the candidate's local frame. Avoid
  echoing that same supported publication into the frame's plain input."
  [network config caller]
  (let [sources (get-in (net/network-dict-entry network gur/name-bindings-key)
                        [feedback-scope [config caller]])
        incoming (net/network-cell-strongest network caller)
        operator (net/network-cell-strongest network (:operator config))]
    (boolean
     (some (fn [source]
             (let [local (net/network-cell-strongest network source)
                   content (update-content operator local (active? operator (:key config)))
                   expected (collection/strongest-value content (fn [_ v] v))]
               (and (= (base expected) (base incoming))
                    (= (datum/support-of expected) (datum/support-of incoming))
                    (= (state/states-of expected) (state/states-of incoming)))))
           sources))))

(defn- scalar-gate [config source target]
  (let [{:keys [operator key]} config
        id (gur/stable-node-id [::gate operator key source target])]
    (prop/construct-propagator
     id ::gate
     (fn [_ _ network]
       (let [callable (net/network-cell-strongest network operator)
             current (net/network-cell-strongest network source)
             destination (net/network-cell-strongest network target)
             enabled? (active? callable key)]
         (if (and (:legacy-field? config)
                  (not (datum/layer-present? destination :base))
                  (not (value/unusable? destination)))
           ;; A concrete plain caller field remains a constraint owned by the
           ;; caller. Its scalar merge accepts equality and detects conflict.
           (if (and enabled? (not (value/nothing? (base current))))
             [(message target (base current))]
             [])
           [(message target (update-content callable current enabled?))])))
     [operator source] [target])))

(defn- install-gate [network config source target]
  (let [[id installed] ((scalar-gate config source target)
                        (reduce effects/ensure-cell network [source target]))]
    (effects/register-props installed [id])))

(defn- interface! [network target shape]
  (let [id (gur/stable-node-id [::interface target])
        old (when (contains? (net/net-env network) id)
              (net/network-cell-strongest network id))]
    (cond
      (or (nil? old) (value/nothing? old) (= old shape))
      (effects/seed-cell (effects/ensure-cell network id) id shape)
      (and (= :compound shape) (vector? old) (= :list (first old))) network
      :else
      (throw (ex-info "Supported candidates have incompatible return interfaces"
                      {:target target :expected old :actual shape})))))

(declare publisher)

(defn- source-list [network source]
  (loop [id source members [] owners [] watched #{} seen #{}]
    (if (contains? seen id)
      (throw (ex-info "Cyclic supported return interface" {:source source}))
      (let [v (net/network-cell-strongest network id)
            head (obj/existing-slot-cell-id network :car id)
            tail (obj/existing-slot-cell-id network :cdr id)
            watched (conj watched id)]
        (cond
          (and head tail)
          (recur tail (conj members head) (conj owners id) watched (conj seen id))
          (= :compiler-2/list-empty (base v))
          {:members members :owners owners :watched watched :ready? true}
          (value/contradiction? (base v)) {:scalar? true :watched watched :ready? true}
          (or head tail (value/unusable? (base v)) (obj/accessor-network? (base v)))
          {:members members :owners owners :watched watched :ready? false}
          :else {:scalar? true :watched watched :ready? true})))))

(defn- validate-legacy-interface! [network source target]
  (let [current (net/network-cell-strongest network target)
        interface-id (gur/stable-node-id [::interface target])
        recorded (when (contains? (net/net-env network) interface-id)
                   (net/network-cell-strongest network interface-id))
        target-layout (when (and (not (datum/layer-present? current :base))
                                 (obj/accessor-network? current))
                        (source-list network target))
        shape (cond
                (and (vector? recorded) (= :list (first recorded))) recorded
                (and (:ready? target-layout) (not (:scalar? target-layout)))
                [:list (count (:members target-layout))]
                :else nil)]
    (when shape
      (let [actual (source-list network source)]
        (when (and (:ready? actual)
                   (not (value/unusable? (base (net/network-cell-strongest network source))))
                   (or (:scalar? actual)
                       (not= (second shape) (count (:members actual)))))
          (throw (ex-info "Supported candidates have incompatible return interfaces"
                          {:target target :expected shape
                           :actual (if (:scalar? actual)
                                     :scalar
                                     [:list (count (:members actual))])})))
        {:shape shape :watched (:watched actual) :owners (:owners actual)
         :targets (:members (or target-layout (source-list network target)))}))))

(defn- install-legacy-fields [network config target legacy]
  (reduce
   (fn [current [owner field]]
     (let [port (gur/stable-node-id [::legacy-field config target owner field])
           projection (prop/construct-propagator
                       (gur/stable-node-id [::legacy-projection config target owner field])
                       ::legacy-projection
                       (supported-slot/activation :car port owner) [owner] [port])
           [id installed] (projection (effects/ensure-cell current port))]
       (install-gate (effects/register-props installed [id])
                     (assoc config :legacy-field? true) port field)))
   network (map vector (:owners legacy) (:targets legacy))))

(defn- publish-effects [config source target network]
  (let [operator (net/network-cell-strongest network (:operator config))
        current (net/network-cell-strongest network source)
        candidate (base current)
        legacy (when (active? operator (:key config))
                 (validate-legacy-interface! network source target))
        prepared (effects/begin network)
        prepared (if-let [shape (:shape legacy)]
                   (interface! prepared target shape)
                   prepared)
        ;; The owner contains immutable live accessor references. Publishing it
        ;; directly keeps an open tail open and leaves field availability to the
        ;; canonical accessor gates instead of reconstructing a closed layout.
        prepared (if (and (active? operator (:key config))
                          (not (value/unusable? candidate)))
                   (interface! prepared target
                               (if (or (obj/accessor-network? candidate)
                                       (= :compiler-2/list-empty candidate))
                                 :compound
                                 :scalar))
                   prepared)
        ;; Only a genuinely closed legacy target imposes a length contract.
        ;; Its bounded source-prefix watches revalidate later tail topology.
        prepared (reduce (fn [n watch]
                           (let [id (gur/stable-node-id [::watch config source target watch])
                                 installer (prop/construct-propagator
                                            id ::watch
                                            (fn [_ _ current] (publish-effects config source target current))
                                            [watch] [target])
                                 [id installed] (installer n)]
                             (effects/register-props installed [id])))
                         prepared (:watched legacy))]
    (effects/result
     (cond
       legacy (install-legacy-fields prepared config target legacy)
       ;; An inactive candidate has no claim on a caller's plain owner. The
       ;; publisher's operator watch installs its field gates if selected later.
       (and (not (active? operator (:key config)))
            (obj/accessor-network? (net/network-cell-strongest network target))) prepared
       :else (install-gate prepared config source target)))))

(defn publisher [config source target]
  (prop/construct-propagator
   (gur/stable-node-id [::publisher config source target]) ::publisher
   (fn [_ _ network] (publish-effects config source target network))
   [(:operator config) source] [target]))

(defn boundary [config caller-targets context scope source target]
  ;; Returned aliases are published by the canonical member gates. Publishing
  ;; them back into a read input would change ownership of plain caller data.
  (case (second scope)
    :caller []
    :defined-input
    (let [target (get caller-targets target target)
          prepared (effects/begin (:network context))
          old (get-in (net/network-dict-entry prepared gur/name-bindings-key)
                      [feedback-scope [config target]] #{})
          [_ prepared] (effects/eval-activation-result
                        (gur/bind-name feedback-scope [config target] (conj old source)) prepared)
          [id installed] ((publisher config source target)
                          (reduce effects/ensure-cell prepared [source target]))]
      (:effects (effects/result (effects/register-props installed [id]))))))
