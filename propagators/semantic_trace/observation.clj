(ns propagators.semantic-trace.observation
  "Tracer-owned frozen acquisition and immutable observer declarations."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.compiler-2.runtime.operators.network-traversal :as traversal]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.network-patch :as patch]
            [propagators.relationship-observer :as roots]
            [propagators.semantic-trace.capture :as capture]))

(def subscriptions [:semantic-capture/observers])

(defn next-epoch [previous output]
  (inc (reduce max 0 (keep (fn [premise]
                            (when (= output (:source premise)) (:timestamp premise)))
                          (datum/support-of previous)))))

(defn same-root-evidence? [left right]
  (and (capture/fragment? left) (capture/fragment? right)
       (= (capture/support left) (capture/support right))
       (= (dissoc (capture/scope left :root) :semantic-capture/nested-scopes :graph/modifiers)
          (dissoc (capture/scope right :root) :semantic-capture/nested-scopes :graph/modifiers))))

(defn publication-message [output previous captured]
  (message/message
   output
   (capture/publication
    (collection/content
     {:base captured
      :support (support/combine
                (capture/support captured)
                #{{:source output :timestamp (next-epoch previous output)
                   :premises-status :active}})}))))

(defn- acquire-root [source root direction observation-id]
  (try
    (let [graph (traversal/traverse (traversal/semantic-snapshot source root) root direction)]
      (capture/capture-fragment source {:root root :graph graph :scope :root :refinement :initial}))
    (catch clojure.lang.ExceptionInfo error
      (if (= "Application output names require graph connections" (ex-message error))
        (capture/fragment
         {:observation observation-id
          :scopes {:root {:semantic-trace/graph true :nodes {} :edges []
                          :semantic-capture/diagnostics
                          [{:kind :missing :message "Application declaration is incomplete in this observation"
                            :application (:application-id (ex-data error))}]}}
          :provenance [{:capture/refinement :initial :capture/scope :root}]})
        (throw error)))))

(defn activation [root output direction-cell]
  (fn [_inputs _outputs network]
    (let [direction (data/payload (net/network-cell-strongest network direction-cell))]
      (cond
        (value/nothing? direction) []
        (value/contradiction? direction) [(message/message output direction)]
        :else
        (let [previous (net/network-cell-strongest network output)
              epoch (next-epoch previous output)
              source (observation/frozen-snapshot
                      network {:output output :observation/id [output epoch]})
              captured (acquire-root source root direction [output epoch])]
          (if (same-root-evidence? (data/payload previous) captured)
            []
            [(publication-message output previous captured)
             (assoc (capture/retain-source-patch source captured) :publisher output)]))))))

(defn- selected-keys [network root output]
  (try
    ((roots/connected-root-node-keys [root] #{output}) network)
    (catch clojure.lang.ExceptionInfo error
      (if (= "Application output names require graph connections" (ex-message error))
        #{[[:outer] root]}
        (throw error)))))

(defn watch-declarations
  "Declare only missing watches, with the final tracer activation already attached."
  [network root output direction-cell]
  (let [activate (activation root output direction-cell)
        selected (selected-keys network root output)
        triggers (into #{direction-cell}
                       (keep (fn [[path id :as key]]
                               (when (and (= path [:outer])
                                          (cell/cell? (roots/node-entry network key))) id)))
                       selected)
        initial (gur/stable-node-id [::initial output])
        watches (mapv (fn [trigger]
                        (patch/declare-propagator
                         (gur/stable-node-id [::watch output trigger])
                         'observe-semantic-captured [trigger] [output] activate))
                      (sort-by pr-str triggers))]
    (filterv #(not (contains? (net/net-env network) (:id %)))
             (conj watches (patch/declare-propagator initial
                                                    'observe-semantic-captured
                                                    [] [output] activate)))))

(defn refresh-declarations [network]
  (into []
        (mapcat (fn [[root output direction-cell]]
                  (watch-declarations network root output direction-cell)))
        (keys (get (net/network-dict-entry network gur/name-bindings-key) subscriptions))))

(defn observe-operator []
  (operator/operator-closure
   {:name 'observe-semantic-captured
    :application-installer
    (fn [network arguments output _context]
      (when-not (= 2 (count arguments))
        (throw (ex-info "observe-semantic-captured expects root and direction" {})))
      (let [[root direction-cell] arguments
            prepared (reduce builder/ensure-cell network [root direction-cell output])
            watches (watch-declarations prepared root output direction-cell)
            effects (conj watches (gur/bind-name subscriptions
                                                [root output direction-cell] output))
            [_ installed] (declarations/eval-activation-result effects prepared)]
        [installed (mapv :id watches) output]))}))

(def extension
  (extension/extension-bundle
   {:id ::capture :bindings [['observe-semantic-captured (observe-operator)]] :effects []}))
