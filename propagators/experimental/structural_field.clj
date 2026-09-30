(ns propagators.experimental.structural-field
  "Publish supported struct fields into ordinary, persistent slot topology.
  Source data is never rewritten by returning accessor information."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

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

(defn- extract-field [source key target]
  (prop/concrete-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network source)
           data (datum/layer-value v :base)]
       (if (and (map? data) (not (record? data)))
         [(message/message target
                           (field-content (get data key value/nothing)
                                          (datum/support-of v)))]
         (throw (ex-info "Expected supported struct map"
                         {:source source :base data})))))))

(defn- transport-unusable [source target]
  (fn [patches _inputs _outputs network]
    (let [content (net/network-cell-content network source)
          v (net/network-cell-strongest network source)]
      (if (and (collection/content? content) (value/unusable? v))
        [(message/message
          target
          (collection/content
           {:base (if (value/contradiction? (datum/layer-value v :base))
                    value/contradiction
                    value/nothing)
            :support (datum/support-of v)}))]
        patches))))

(defn p:structural-field
  "Installer [network -> [id network]]. The target is an actual slot participant.
  Accept TTMS data or initial nothing; do not silently wrap ordinary updates."
  [source key target]
  (let [activate (prop/compose-activation
                  (extract-field source key target)
                  (transport-unusable source target))]
    (prop/construct-propagator
     ::structural-field
     (fn [inputs outputs network]
       (let [content (net/network-cell-content network source)]
         (if (or (value/nothing? content) (collection/content? content))
           (activate inputs outputs network)
           (throw (ex-info "Expected TTMS struct content"
                           {:source source :content content})))))
     [source] [target])))
