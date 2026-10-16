(ns examples.lain.live-environment.callable
  "Experimental stable callable boundary. No compiler or GUR changes."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur.flat :as gur]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.supported-procedure :as supported]))

(def result-procedure
  (supported/from-primitive
   (prop/primitive-propagator ::result
                             (fn [_version _current result] result))))

(defn- version-context [context selected]
  (let [version (vec (sort-by pr-str (datum/support-of selected)))
        key [::application (:call-id context) version]]
    (assoc context
           :version-id (gur/stable-node-id [key :version])
           :function-id (gur/stable-node-id [key :function])
           :result-id (gur/stable-node-id [key :result]))))

(defn- declare-version [network context selected]
  (let [{:keys [source-id argument-ids output-id version-id function-id result-id]}
        context
        support (datum/support-of selected)
        prepared (-> (effects/begin network)
                     (effects/ensure-cell result-id)
                     (effects/ensure-cell function-id)
                     (effects/ensure-cell version-id)
                     (effects/seed-cell function-id (datum/layer-value selected :base))
                     (effects/seed-cell version-id
                                        (collection/content
                                         {:base true :support support
                                          :premise-state support})))
        [_ installed]
        (effects/eval-activation-result
         (gur/apply-closure-effect function-id
                                   (into [(:context-id context)] argument-ids)
                                   result-id)
         prepared)
        [published props _]
        (supported/install result-procedure installed
                           [version-id source-id result-id] output-id)]
    (effects/result (effects/register-props published props))))

(defn activation
  "Evaluate a supported call boundary as ordinary declaration/message patches."
  [context network]
  (let [selected (net/network-cell-strongest network (:source-id context))
        states (state/states-of selected)
        state-patch (message/message (:output-id context)
                                     (collection/content {:premise-state states}))]
    (cond
      (value/unusable? selected)
      [state-patch]

      (not (and (datum/layer-present? selected :base)
                (gur/recursive-closure? (datum/layer-value selected :base))))
      (throw (ex-info "Live callable requires a supported flat-GUR callable"
                      {:source-id (:source-id context)}))

      :else
      (let [version (version-context context selected)]
        (if (contains? (net/net-env network) (:version-id version))
          [state-patch]
          (update (declare-version network version selected)
                  :messages conj state-patch))))))

(defn operator
  "Keep the public callable raw; version its private applications and results.
  The source is an actual TTMS injection cell, not a synthetic premise label."
  [source-id]
  (operator/operator-closure
   {:name [::callable source-id]
    :application-installer
    (fn [network arguments output context-id]
      (let [id (ids/new-node-id)
            context {:call-id id :source-id source-id
                     :context-id context-id
                     :argument-ids (vec arguments) :output-id output}
            prepared (reduce effects/ensure-cell network
                             (into [source-id output context-id] arguments))
            [prop-id installed]
            ((prop/construct-propagator
              id ::callable
              (fn [_inputs _outputs current] (activation context current))
              (into [source-id] arguments) [output]) prepared)]
        [(effects/register-props installed [prop-id]) [prop-id] output]))}))
