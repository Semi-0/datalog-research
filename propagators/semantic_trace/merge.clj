(ns propagators.semantic-trace.merge
  "Tracer-owned derivation from independently addressable fragment cells."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur :as gur]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.semantic-trace.capture :as capture]
            [propagators.stdlib.premise-state :as state]))

(defn inbox-id [output] (gur/stable-node-id [::inbox output]))
(defn fragment-cell-id [inbox slot] (gur/stable-node-id [::fragment inbox slot]))

(defn fragment-evidence
  "Keep each supported fragment opaque to ordinary cell merging."
  [captured dependencies states]
  (let [base (cond
               (capture/fragment? captured) {::capture captured}
               (value/nothing? captured) captured
               (value/contradiction? captured) captured
               :else (throw (ex-info "Expected a capture fragment" {:captured captured})))]
    (collection/content {:base base :support dependencies :premise-state states})))

(defn contribution-capture [projection]
  (let [base (datum/layer-value projection :base)]
    (cond
      (value/nothing? base) base
      (value/contradiction? base) base
      (and (map? base) (capture/fragment? (::capture base))) (::capture base)
      :else (throw (ex-info "Invalid capture contribution" {:base base})))))

(defn- inbox-entry [cell projection]
  {:cell cell :projection projection})

(defn- entry-cell [entry] (:cell entry))

(defn contributions [network inbox]
  (let [slots (net/network-cell-strongest network inbox)]
    (mapv #(net/network-cell-strongest
            network (entry-cell (object/accessor-source-slot-value slots %)))
          (sort-by pr-str (object/accessor-slot-keys slots)))))

(defn joined-groups
  "Project against current source states, then join only matching observations."
  ([contributions] (joined-groups contributions #{}))
  ([contributions external-states]
   (let [states (apply support/join external-states (map state/states-of contributions))
         eligible (keep (fn [input]
                          (let [projected (collection/strongest-value
                                           (collection/content
                                            {:base (datum/layer-value input :base)
                                             :support (datum/support-of input)
                                             :premise-state states})
                                           (fn [left right]
                                             (cond
                                               (value/nothing? left) right
                                               (= left right) left
                                               :else value/contradiction)))
                                base (contribution-capture projected)]
                            (when-not (value/nothing? base)
                              [base (datum/support-of projected)])))
                        contributions)]
     {:states states
      :conflicts (filterv (fn [[captured _]] (value/contradiction? captured)) eligible)
      :groups
      (reduce (fn [groups [captured dependencies]]
                (update groups (capture/observation-id captured)
                        (fn [previous]
                          {:capture (capture/join (get previous :capture value/nothing) captured nil)
                           :support (support/combine (get previous :support #{}) dependencies)})))
              {} (remove (fn [[captured _]] (value/contradiction? captured)) eligible))})))

(defn joined-observation
  "Derive historical refinement only from its own frozen observation's slots."
  [network inbox observation]
  (get-in (joined-groups
           (filterv #(let [base (contribution-capture %)]
                       (and (capture/fragment? base)
                            (= observation (capture/observation-id base))))
                    (contributions network inbox)))
          [:groups observation :capture]))

(defn- without-publisher [entries output]
  (set (remove #(= output (:source %)) entries)))

(defn next-revision [previous output]
  (inc (reduce max 0 (keep (fn [premise]
                            (when (= output (:source premise)) (:timestamp premise)))
                          (state/states-of previous)))))

(defn activation [inbox output]
  (fn [_inputs _outputs network]
    (let [previous (net/network-cell-strongest network output)
          {:keys [groups states conflicts]} (joined-groups
                                            (contributions network inbox)
                                            (without-publisher (state/states-of previous) output))
          base (if (seq conflicts)
                 (value/contradiction-with-provenance
                  #{{:semantic-capture/error :conflicting-contribution}})
                 (case (count groups)
                   0 value/nothing
                   1 (:capture (first (vals groups)))
                   (value/contradiction-with-provenance
                    #{{:semantic-capture/error :incomparable-observations
                       :observations (set (keys groups))}})))
          dependencies (apply support/combine
                              (concat (map :support (vals groups)) (map second conflicts)))]
      (if (and (capture/equivalent? base (datum/layer-value previous :base))
               (= dependencies (without-publisher (datum/support-of previous) output))
               (= states (without-publisher (state/states-of previous) output)))
        []
        (let [premise {:source output :timestamp (next-revision previous output)
                       :premises-status :active}]
          [(message/message output
                            (collection/content
                             {:base base :support (conj dependencies premise)
                              :premise-state (conj states premise)}))])))))

(defn- publish-fragment [inbox slot cell]
  (fn [_inputs _outputs network]
    ;; The slot is a derived wake-up signal. The merge always reads authoritative
    ;; fragment cells, including their current retractions and conflicts.
    [(message/message
      inbox
      (object/as-accessor-network
       {slot (inbox-entry cell (net/network-cell-strongest network cell))}))]))

(defn submit
  "Declare an independent fragment cell and its ordinary inbox publisher.
  All contributions schedule one merge propagator through the compound inbox."
  [output slot supported-fragment]
  (let [inbox (inbox-id output)
        cell (fragment-cell-id inbox slot)]
    [(patch/declare-cell inbox)
     (patch/declare-cell output)
     (patch/declare-cell cell)
     (patch/declare-propagator
      (gur/stable-node-id [::merge output]) ::merge
      [inbox] [output] (activation inbox output))
     (patch/declare-propagator
      (gur/stable-node-id [::publish-fragment output slot]) ::publish-fragment
      [cell] [inbox] (publish-fragment inbox slot cell))
     (message/message cell supported-fragment)]))
