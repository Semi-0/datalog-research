(ns propagators.compiler-2.runtime.operators.retained-observation
  "Opt-in retained observation composed from ordinary propagators and TTMS cells."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operators]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.retained-network :as retained]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as roots]))

(defn- activate [request-id state-id root callback arguments context output]
  (fn [_inputs _outputs parent]
    (let [request (net/network-cell-strongest parent request-id)
          token (datum/support-of request)
          state (retained/stored (net/network-cell-strongest parent state-id))
          callable (data/payload (net/network-cell-strongest parent callback))]
      (cond
        (value/nothing? request) []
        (= token (obj/slot-value state :observation/request)) []
        (value/nothing? callable) []
        (value/contradiction? callable) [(retained/publish parent output callable)]
        (not (gur/recursive-closure? callable))
        (throw (ex-info "observe-network-retained callback must be a network" {}))
        :else
        (let [result (retained/step parent state callback context root arguments output token)
              messages [(retained/publish parent state-id (:state result))]]
          (if (value/nothing? (:value result))
            (conj messages (retained/publish parent output value/nothing :retracted))
            (conj messages (retained/publish parent output (:value result)))))))))

(defn install
  ([parent inputs output context]
   (install parent inputs output context [(first inputs)]))
  ([parent [root callback & arguments] output context watched-roots]
  (let [id #(gur/stable-node-id [::observer output %])
        request (id :request) state (id :state)
        prepared (reduce declarations/ensure-cell parent [request state])
        [_ registered] (declarations/eval-activation-result
                        [(gur/bind-name retained/storage-scope [output :request] request)
                         (gur/bind-name retained/storage-scope [output :state] state)] prepared)
        before (set (keys (net/net-env registered)))
        [initial watched] ((roots/p:observe-roots watched-roots request output) registered)
        introduced (set/difference (set (keys (net/net-env watched))) before)
        watches (conj (set (filter #(prop/prop? (net/network-env-lookup watched %)) introduced)) initial)
        sample (fn [_inputs _outputs current]
                 [(retained/publish current request (retained/snapshot-view current))])
        decorated (reduce (fn [current watch]
                            (net/assoc-net-prop current watch
                              (assoc (net/network-env-lookup current watch) :activate sample))) watched watches)
        [_ registered] (declarations/eval-activation-result
                        [(gur/bind-name [:relationship/root-observers] [watched-roots request output] output)] decorated)
        [request-control requested] ((prop/construct-propagator
                                       (id :request-control) ::request sample
                                       (into [callback] arguments) [request]) registered)
        [control installed] ((prop/construct-propagator
                               (id :control) ::control
                               (activate request state root callback arguments context output)
                               (into [request state callback] arguments) [state output]) requested)]
    [installed (into (vec watches) [request-control control]) output])))

(defn operator []
  (operators/operator-closure
    {:name 'observe-network-retained
     :application-installer
     (fn [network arguments output context]
       (when (< (count arguments) 2)
         (throw (ex-info "observe-network-retained expects root, callback and optional arguments" {})))
       (install network arguments output context))}))

(defn layered-environment
  "Explicit opt-in for version-aware arithmetic, branching and callable application."
  []
  (require 'propagators.experimental.ttms-application.runtime)
  (extension/extension-bindings primitives/session-extension))
