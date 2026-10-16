(ns propagators.experimental.ttms-application.publication
  "Live candidate gates and stable returned-list ports. No source stamping."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.support :as support]
            [propagators.experimental.ttms-application.ports :as ports]
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
    (if (and enabled? (not (value/nothing? source))
             (not (value/nothing? (base source))))
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
             current (net/network-cell-strongest network source)]
         [(message target (update-content callable current (active? callable key)))]))
     [operator source] [target])))

(defn- install-gate [network config source target]
  (let [[id installed] ((scalar-gate config source target)
                        (reduce effects/ensure-cell network [source target]))]
    (effects/register-props installed [id])))

(defn- interface! [network target shape]
  (let [id (gur/stable-node-id [::interface target])
        old (when (contains? (net/net-env network) id)
              (net/network-cell-strongest network id))]
    (if (or (nil? old) (value/nothing? old) (= old shape))
      (effects/seed-cell (effects/ensure-cell network id) id shape)
      (throw (ex-info "Supported candidates have incompatible return interfaces"
                      {:target target :expected old :actual shape})))))

(declare publisher)

(defn- source-list [network source]
  (loop [id source members [] watched #{} seen #{}]
    (if (contains? seen id)
      (throw (ex-info "Cyclic supported return interface" {:source source}))
      (let [v (net/network-cell-strongest network id)
            head (obj/existing-slot-cell-id network :car id)
            tail (obj/existing-slot-cell-id network :cdr id)
            watched (conj watched id)]
        (cond
          (and head tail) (recur tail (conj members head) watched (conj seen id))
          (= :compiler-2/list-empty v) {:members members :watched watched :ready? true}
          (value/contradiction? (base v)) {:scalar? true :watched watched :ready? true}
          (or head tail (value/unusable? v) (obj/accessor-network? v))
          {:members members :watched watched :ready? false}
          :else {:scalar? true :watched watched :ready? true})))))

(defn- install-list [network config target members]
  (let [network (interface! network target [:list (count members)])
        tails (into [target] (map #(gur/stable-node-id [::tail target %])
                                 (range 1 (inc (count members)))))
        network (effects/seed-cell (effects/ensure-cell network (peek tails))
                                   (peek tails) :compiler-2/list-empty)]
    (reduce (fn [network [i source]]
              (let [head (gur/stable-node-id [::member target i])
                    ready (reduce effects/ensure-cell network [head (tails i) (tails (inc i))])
                    known? (get-in (net/network-dict-entry ready gur/name-bindings-key)
                                   [::list-ports [target i]])
                    declared (if known?
                               ready
                               (let [[ids installed] ((obj/p:cons head (tails (inc i)) (tails i)) ready)
                                     recorded (effects/register-props installed (flatten [ids]))
                                     [_ marked] (effects/eval-activation-result
                                                 (gur/bind-name ::list-ports [target i] head) recorded)]
                                 marked))
                    [gate-id gated] ((publisher config source head) declared)]
                (effects/register-props gated [gate-id])))
            network (map-indexed vector members))))

(defn- publish-effects [config source target network]
  (let [operator (net/network-cell-strongest network (:operator config))
        layout (source-list network source)
        prepared (effects/begin network)
        prepared
        (cond
          (not (active? operator (:key config))) prepared
          (not (:ready? layout)) prepared
          (:scalar? layout) (install-gate (interface! prepared target :scalar) config source target)
          :else (install-list prepared config target (:members layout)))
        ;; Watches are declared topology; late list tails can complete the interface.
        prepared (reduce (fn [n watch]
                           (let [id (gur/stable-node-id [::watch config source target watch])
                                 installer (prop/construct-propagator
                                            id ::watch
                                            (fn [_ _ current] (publish-effects config source target current))
                                            [watch] [target])
                                 [id installed] (installer n)]
                             (effects/register-props installed [id])))
                         prepared (:watched layout))]
    (effects/result prepared)))

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
