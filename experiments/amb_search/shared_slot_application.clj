(ns experiments.amb-search.shared-slot-application
  "Isolated experiment for audited primitive base/support/state applications.
  Layer publication binds a result-cell reference, rather than synchronizing
  that cell through a compound slot's avatar network. No shared kernel changes."
  (:require [clojure.set :as set]
            [experiments.amb-search.cell-slots :as slots]
            [propagators.application :as application]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.ids :as ids]
            [propagators.layered.dispatcher :as dispatcher]
            [propagators.layered.procedure :as layer]
            [propagators.layered.runtime :as runtime]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]
            [propagators.stdlib.support :as support]
            [propagators.stdlib.supported-procedure :as supported]))

(def ^:dynamic *declaring-slots* false)

(defn audited-fixture
  "Mark from-primitive closures in a caller-audited, pure benchmark fixture.
  The marker survives procedure/base wrapping; ordinary evaluation ignores it."
  [construct]
  (let [from-primitive supported/from-primitive]
    (with-redefs [supported/from-primitive
                  (fn [primitive] (assoc (from-primitive primitive) ::audited-base true))]
      (construct))))

(defn- eligible? [{:keys [procedures]}]
  (and (get-in procedures [:base ::audited-base])
       (set/subset? (set (keys procedures)) #{:base :support :premise-state})
       (or (not (contains? procedures :support)) (= support/procedure (:support procedures)))
       (or (not (contains? procedures :premise-state)) (= state/procedure (:premise-state procedures)))))

(defn- install-layer [ports state [layer procedure]]
  (if (value/nothing? procedure)
    state
    (let [result (ids/new-node-id)
          prepared (nb/install-cell (:net state) result)
          declared (binding [*declaring-slots* true]
                     (dispatcher/declare-layer procedure (assoc ports :layer-result-id result) prepared))
          bound? (some? (slots/slot-cell-id declared (:live-result-id ports) layer))]
      (cond-> (assoc state :net declared)
        bound? (update :results conj result)))))

(defn- install-assembly [network bank output inputs]
  (let [[id installed]
        ((prop/construct-propagator
          :layered/assemble
          (fn [_inputs _outputs current]
            [(message/message output (slots/snapshot current bank))])
          inputs [output]) network)]
    [[id] installed]))

(defn build [{:keys [ports cell-specs procedures]}]
  (application/build-branch-application
   {:cell-specs cell-specs
    :install-branches
    (fn [network frame]
      (let [ports (assoc ports :live-result-id (:result-bank-id frame))
            original (set (keys (net/net-env network)))
            declared (reduce (partial install-layer ports) {:net network :results []}
                             (sort-by (comp pr-str key) procedures))
            installed (:net declared)
            tasks (->> (net/net-env installed)
                       (keep (fn [[id entry]]
                               (when (and (not (contains? original id)) (prop/prop? entry)) id)))
                       (sort-by pr-str) vec)]
        {:net installed :branch-prop-ids tasks :results (:results declared)}))
    :reducer-install
    (fn [{:keys [results result-bank-id reduced-out-id]}]
      (fn [network] (install-assembly network result-bank-id reduced-out-id results)))}))

(defn with-result-cell-slots
  "Run only in an isolated JVM: temporary var redefinitions are process-wide.
  Unmarked or custom layer procedures use the original dispatcher/publication."
  [run]
  (let [original-build dispatcher/build
        original-publish layer/publish]
    (with-redefs [dispatcher/build (fn [prepared]
                                     (if (eligible? prepared) (build prepared) (original-build prepared)))
                  layer/publish (fn [network layer output bank]
                                  (if *declaring-slots*
                                    (slots/bind-slot network bank layer output)
                                    (original-publish network layer output bank)))]
      (run))))

(defn- declared-layer-cells [network object-id]
  (merge-with merge (object/slot-declarations-for network object-id)
                    (object/accessor-declarations-for network object-id)))

(defn- audited-layer-tree? [network declarations]
  (and (seq declarations)
       (set/subset? (set (keys declarations)) #{:base :support :premise-state})
       (every? #(= 1 (count %)) (vals declarations))
       (when-let [id (first (keys (:base declarations)))]
         (::audited-base (net/network-cell-strongest network id)))))

(defn with-direct-layer-slots
  "Read unambiguous cells of marked procedure objects without slot sync.
  All other objects, including ambiguous declarations, keep materialization."
  [run]
  (let [original runtime/materialize-layered-cell-value]
    (with-redefs [runtime/materialize-layered-cell-value
                  (fn [network object-id]
                    (let [declarations (declared-layer-cells network object-id)]
                      (if (audited-layer-tree? network declarations)
                        (let [referenced (reduce-kv
                                          (fn [current slot declarations]
                                            (slots/bind-slot current object-id slot (first (keys declarations))))
                                          network declarations)]
                          (slots/snapshot referenced object-id))
                        (original network object-id))))]
      (run))))

(defn with-shared-slots [run]
  (with-direct-layer-slots #(with-result-cell-slots run)))
