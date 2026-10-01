(ns propagators.experimental.structural-field
  "Publish supported struct fields into ordinary, persistent slot topology.
  Source data is never rewritten by returning accessor information."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.premise-publication :as publication]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn- data-unusable?
  "Read one explicit TTMS envelope. Domain keys in its base are not layers."
  [v]
  (let [base (datum/layer-value v :base)]
    (or (not (datum/layer-present? v :base))
        (value/nothing? base)
        (value/contradiction? base)
        (support/unusable? (datum/support-of v)))))

(defn- data-content [v]
  (if (data-unusable? v)
    (collection/content {:premise-state (state/states-of v)})
    (collection/content v)))

(defn field-content [field premises]
  (if (collection/content? field)
    (let [normalized (collection/merge-content value/nothing field)
          observations (:support/observations normalized)]
      (if (empty? observations)
        (collection/content {:base value/nothing :support premises})
        (reduce collection/merge-content value/nothing
                (map (fn [observation]
                       (collection/content
                        (update observation :support support/combine premises)))
                     observations))))
    (collection/content {:base field :support premises})))

(defn- stateful-field-content [publish field premises states]
  (let [content (if (collection/content? field)
                  (collection/merge-content value/nothing field)
                  (collection/content {:base field :support #{}}))
        initial (collection/content
                 {:premise-state (support/join states (:support/states content #{}))})]
    (reduce
     (fn [updates observation]
       (collection/merge-content
        updates
        (publish
         (assoc observation :support (support/combine (:support observation) premises)
                            :premise-state states))))
     initial (:support/observations content))))

(defn- extract-field [guard publish source key target]
  (guard
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network source)
           data (datum/layer-value v :base)]
       (if (and (map? data) (not (record? data)))
         [(message/message target
                           (if (datum/layer-present? v :premise-state)
                             (stateful-field-content publish (get data key value/nothing)
                                                     (datum/support-of v)
                                                     (datum/layer-value v :premise-state))
                             (field-content (get data key value/nothing)
                                            (datum/support-of v))))]
         (throw (ex-info "Expected supported struct map"
                         {:source source :base data})))))))

(defn- transport-unusable [unusable? source target]
  (fn [patches _inputs _outputs network]
    (let [content (net/network-cell-content network source)
          v (net/network-cell-strongest network source)]
      (cond
        (datum/layer-present? v :premise-state)
        (publication/transport-states patches [source] [target] network)

        (and (collection/content? content) (unusable? v))
        [(message/message
          target
          (collection/content
           {:base (if (value/contradiction? (datum/layer-value v :base))
                    value/contradiction
                    value/nothing)
            :support (datum/support-of v)}))]
        :else patches))))

(defn- install-field [name guard publish unusable? source key target]
  (let [activate (prop/compose-activation
                  (extract-field guard publish source key target)
                  (transport-unusable unusable? source target))]
    (prop/construct-propagator
     name
     (fn [inputs outputs network]
       (let [content (net/network-cell-content network source)]
         (if (or (value/nothing? content) (collection/content? content))
           (activate inputs outputs network)
           (throw (ex-info "Expected TTMS struct content"
                           {:source source :content content})))))
     [source] [target])))

(defn p:structural-field
  "Installer [network -> [id network]]. The target is an actual slot participant.
  Accept TTMS data or initial nothing; do not silently wrap ordinary updates."
  [source key target]
  (install-field ::structural-field prop/concrete-propagator publication/content
                 value/unusable? source key target))

(defn p:structural-data-field
  "Specialize structural publication for an opaque map inside one TTMS envelope.
  Only outer premises and top-level bottom/conflict gate field extraction.
  State transport stays outside the guard; slot synchronization is unchanged."
  [source key target]
  (let [guard (fn [activate]
                (fn [inputs outputs network]
                  (if (data-unusable? (net/network-cell-strongest network source))
                    []
                    (activate inputs outputs network))))]
    (install-field ::structural-data-field guard data-content data-unusable?
                   source key target)))
