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
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as roots]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.merge :as merge]
            [propagators.semantic-trace.retention :as retention]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as state]))

(defn same-root-evidence? [left right]
  (and (capture/fragment? left) (capture/fragment? right)
       (= (capture/support left) (capture/support right))
       (= (dissoc (capture/scope left :root) :semantic-capture/nested-scopes :graph/modifiers)
          (dissoc (capture/scope right :root) :semantic-capture/nested-scopes :graph/modifiers))))

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

(defn acquisition-id [output] (gur/stable-node-id [::acquisition output]))

(defn- without-source [premises source]
  (set (remove #(= source (:source %)) premises)))

(defn- previous-fragment [network output previous]
  (when (capture/fragment? (data/payload previous))
    (let [observation (capture/observation-id (data/payload previous))]
      (net/network-cell-strongest
       network (merge/fragment-cell-id (merge/inbox-id output) [observation :root])))))

(defn- transport-direction [output previous direction]
  (when (and (capture/fragment? (data/payload previous))
             (seq (state/states-of direction)))
    ;; Transport live state to publication. Frozen contribution cells remain
    ;; available to historical refinement under their original premises.
    [(message/message output (collection/content {:premise-state (state/states-of direction)}))]))

(defn- acquire [network root output direction-input previous declaration-receipt]
  (let [acquisition (acquisition-id output)
        epoch (merge/next-revision previous acquisition)
        source (observation/frozen-snapshot
                network {:output output :observation/id [output epoch]})
        captured (acquire-root source root (data/payload direction-input) [output epoch])
        dependencies (support/combine (capture/support captured) (datum/support-of direction-input))
        states (apply support/join (state/states-of direction-input)
                      (map state/states-of (vals (:values (capture/scope captured :root)))))
        previous-fragment (previous-fragment network output previous)
        unchanged? (and (or (nil? declaration-receipt)
                            (= true (net/network-cell-strongest network declaration-receipt)))
                        (same-root-evidence? (data/payload previous) captured)
                        (= dependencies (without-source (datum/support-of previous-fragment) acquisition))
                        (= states (without-source (state/states-of previous-fragment) acquisition)))]
    (when-not unchanged?
      (let [premise {:source acquisition :timestamp epoch :premises-status :active}]
        [(when declaration-receipt
           [(patch/declare-cell declaration-receipt) (message/message declaration-receipt true)])
         (patch/declare-cell acquisition)
         ;; This dedicated source remembers the frozen observation identity. Its
         ;; result dependencies belong to the fragment, not this sampling cursor.
         (message/message acquisition (collection/content {:base captured :support #{premise}}))
         (merge/submit output [(capture/observation-id captured) :root]
                       (merge/fragment-evidence captured (conj dependencies premise) (conj states premise)))
         (retention/request source captured output)]))))

(defn activation
  ([root output direction-cell] (activation root output direction-cell nil))
  ([root output direction-cell declaration-receipt]
   (fn [_inputs _outputs network]
     (let [direction-input (net/network-cell-strongest network direction-cell)
           direction (data/payload direction-input)
           previous (net/network-cell-strongest network (acquisition-id output))]
       (cond
         (value/nothing? direction) (transport-direction output previous direction-input)
         (value/contradiction? direction)
         (merge/submit output ::direction-conflict
                       (collection/content {:base direction :support (datum/support-of direction-input)
                                            :premise-state (state/states-of direction-input)}))
         :else (acquire network root output direction-input previous declaration-receipt))))))

(defn- selected-keys [network root output]
  (try
    ((roots/connected-root-node-keys [root] #{output}) network)
    (catch clojure.lang.ExceptionInfo error
      (if (= "Application output names require graph connections" (ex-message error))
        #{[[:outer] root]}
        (throw error)))))

(declare observe-declarations)

(defn- decorate-activation [activate root output direction-cell]
  (if (contains? (::observed (meta activate)) output)
    activate
    (with-meta
      (prop/compose-activation activate (observe-declarations root output direction-cell))
      {::observed (conj (::observed (meta activate) #{}) output)})))

(defn- watch [root output direction-cell role trigger]
  (patch/declare-propagator
   (gur/stable-node-id [::watch output role trigger]) roots/observer-name
   (if trigger [trigger] []) [output] (activation root output direction-cell)))

(defn- tracer-declaration? [effect]
  (and (= :network/declare-propagator (:op effect))
       (contains? #{roots/observer-name ::merge/merge ::merge/publish-fragment} (:name effect))))

(defn- public-watches [network root output direction-cell]
  (into []
        (comp (keep (fn [[path id :as key]]
                      (when (and (= [:outer] path) (cell/cell? (roots/node-entry network key)))
                        (watch root output direction-cell :child id))))
              (remove #(contains? (net/net-env network) (:id %))))
        (selected-keys network root output)))

(defn- topology-watch [root output direction-cell declarations]
  (let [signature (vec (sort-by pr-str
                                (set (map #(select-keys % [:op :id :name :scope :inputs :outputs :child-ids])
                                          declarations))))
        id (gur/stable-node-id [::topology output signature])
        receipt (gur/stable-node-id [::declaration-receipt id])
        acquire (activation root output direction-cell receipt)]
    (patch/declare-propagator
     id roots/observer-name [] [output]
     (fn [inputs outputs network]
       [(acquire inputs outputs network)
        (public-watches network root output direction-cell)]))))

(defn observe-declarations
  "Compose onto descendants and sample each declaration batch once.
  The installed topology selects public cell watches; compiler-private cells
  are not independent semantic sampling boundaries."
  [root output direction-cell]
  (fn [result _inputs _outputs _network]
    (let [{:keys [messages effects]} (patch/normalize-activation-return result)
          topology (filterv #(and (or (patch/network-declaration? %)
                                      (= :compound/accessor-declaration (:op %)))
                                  (not (tracer-declaration? %))) effects)]
      (if (empty? topology)
        result
        {:messages messages
         :effects
         (conj (mapv (fn [effect]
                       (if (and (= :network/declare-propagator (:op effect))
                                (not (tracer-declaration? effect)))
                         (update effect :activate decorate-activation root output direction-cell)
                         effect)) effects)
               (topology-watch root output direction-cell topology))}))))

(defn- observed-keys [network root output]
  (loop [pending (seq (selected-keys network root output)) seen #{}]
    (if-let [node (first pending)]
      (if (or (contains? seen node) (roots/observer-node? network node))
        (recur (next pending) seen)
        (recur (concat (next pending) (relationship/children (net/net-relationship network) node))
               (conj seen node)))
      seen)))

(defn prepare-observed
  "Compose onto current parents and descendants at source-load boundaries."
  [root output direction-cell network]
  (reduce
   (fn [current [path id :as key]]
     (let [entry (roots/node-entry current key)]
       (cond
         (not= [:outer] path) current
         (prop/prop? entry)
         (net/assoc-net-prop current id
                             (assoc entry :activate
                                    (decorate-activation (prop/prop-f entry) root output direction-cell)))
         :else current)))
   network (observed-keys network root output)))

(defn install-observer [network root direction-cell output]
  (let [prepare (partial prepare-observed root output direction-cell)
        prepared (prepare (reduce builder/ensure-cell network [root direction-cell output]))
        [initial installed] ((roots/p:observe-roots [root] output) prepared)
        activate (activation root output direction-cell)
        installed (reduce (fn [current [id entry]]
                            (if (and (prop/prop? entry)
                                     (= roots/observer-name (prop/prop-name entry))
                                     (not (contains? (net/net-env prepared) id)))
                              (net/assoc-net-prop current id (assoc entry :activate activate))
                              current)) installed (net/net-env installed))
        effects [(watch root output direction-cell :direction direction-cell)
                 (gur/bind-name [:relationship/root-observers]
                                [[root] output output prepare] output)]
        [_ installed] (declarations/eval-activation-result effects installed)]
    [installed [initial (:id (first effects))] output]))

(defn observe-operator []
  (operator/operator-closure
   {:name 'observe-semantic-captured
    :application-installer
    (fn [network arguments output _context]
      (when-not (= 2 (count arguments))
        (throw (ex-info "observe-semantic-captured expects root and direction" {})))
      (install-observer network (first arguments) (second arguments) output))}))

(def extension
  (extension/extension-bundle
   {:id ::capture :bindings [['observe-semantic-captured (observe-operator)]] :effects []}))
