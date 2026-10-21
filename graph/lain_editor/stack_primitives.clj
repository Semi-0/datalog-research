(ns graph.lain-editor.stack-primitives
  "Opt-in generic struct construction and pure frozen-capture readers.

  These operators expose only capability-free capture facts. They never acquire
  another scope or consult a newer runtime; acquisition remains at the retained
  semantic-capture boundary."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.capture :as capture]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- contradiction [kind data]
  (value/contradiction-with-provenance
   #{(assoc data :stack/error kind)}))

(defn linked-value
  "Create a capability-free compiler-2 linked-list value."
  [items]
  (if-let [[item & more] (seq items)]
    (object/as-accessor-network
     {:car item :cdr (linked-value more)})
    :compiler-2/list-empty))

(defn- sole-parent-id [network collection-id slot-key]
  (or (object/existing-slot-cell-id network slot-key collection-id)
      (throw (ex-info "Struct field list is incomplete or ambiguous"
                      {:collection collection-id :slot slot-key}))))

(defn- list-member-ids
  [network root-id]
  (loop [collection-id root-id members [] seen #{}]
    (when (contains? seen collection-id)
      (throw (ex-info "Struct field list is cyclic" {:collection root-id})))
    (let [candidate (net/network-cell-strongest network collection-id)]
      (cond
        (= :compiler-2/list-empty candidate)
        members

        (value/unusable? candidate)
        (throw (ex-info "Struct field list is not yet available"
                        {:collection collection-id}))

        :else
        (let [head (sole-parent-id network collection-id :car)
              tail (sole-parent-id network collection-id :cdr)]
          (recur tail (conj members head) (conj seen collection-id)))))))

(defn- field-binding
  [network entry-id]
  (let [members (list-member-ids network entry-id)]
    (when-not (= 2 (count members))
      (throw (ex-info "Struct fields must be key/value pairs"
                      {:field entry-id :member-count (count members)})))
    (let [[key-id value-id] members
          key (net/network-cell-strongest network key-id)]
      (when (value/unusable? key)
        (throw (ex-info "Struct field key must be available" {:field entry-id})))
      {:key key :value-id value-id})))

(defn struct-operator
  "Construct a named-field compound whose fields remain independently live."
  []
  (operator/operator-closure
   {:name 'stack:struct
    :application-installer
    (fn [network arguments output _context]
      (when-not (contains? #{1 2} (count arguments))
        (throw (ex-info "stack:struct expects fields and an optional explicit output"
                        {:actual-arguments (count arguments)})))
      (let [selected-output (if (= 2 (count arguments))
                              (second arguments)
                              output)
            fields (mapv #(field-binding network %)
                         (list-member-ids network (first arguments)))
            prepared (declarations/ensure-cell network selected-output)
            [installed props]
            (reduce
             (fn [[current installed-props] {:keys [key value-id]}]
               (let [[id next-network]
                     ((object/p:slot key value-id selected-output)
                      (declarations/ensure-cell current value-id))]
                 [next-network (conj installed-props id)]))
             [prepared []]
             fields)]
        [(declarations/register-props installed props) props selected-output]))}))

(defn- capture-reader [name arity read]
  (let [primitive
        (prop/primitive-propagator
         name
         (fn [& arguments]
           (cond
             (some value/contradiction? arguments)
             (value/contradiction-with-provenance
              (mapcat value/contradiction-provenance
                      (filter value/contradiction? arguments)))

             (some value/nothing? arguments)
             value/nothing

             :else
             (try
               (apply read arguments)
               (catch clojure.lang.ExceptionInfo error
                 (contradiction :invalid-reader-input
                                {:operation name
                                 :reason (ex-message error)}))))))]
    (operator/operator-closure
     {:name name
      :application-installer
      (fn [network arguments fallback-output _context]
        (when-not (contains? #{arity (inc arity)} (count arguments))
          (throw (ex-info (str name " received the wrong number of inputs")
                          {:expected-inputs arity :actual-arguments (count arguments)})))
        (let [explicit-output? (= (inc arity) (count arguments))
              inputs (if explicit-output? (pop (vec arguments)) (vec arguments))
              output (if explicit-output? (peek (vec arguments)) fallback-output)
              [installed props selected]
              (supported/install (supported/from-primitive primitive)
                                 network inputs output)]
          [(declarations/register-props installed props) props selected]))})))

(defn- require-capture [candidate]
  (when-not (capture/fragment? candidate)
    (throw (ex-info "Expected a frozen semantic capture" {})))
  candidate)

(defn root-compound-node [captured]
  (let [captured (require-capture captured)
        graph (capture/scope captured :root)
        nodes (->> (:semantic-capture/nested-scopes graph)
                   keys distinct (sort-by pr-str) vec)]
    (case (count nodes)
      0 (contradiction :missing-root
                       {:observation (capture/observation-id captured)})
      1 (first nodes)
      (contradiction :ambiguous-roots
                     {:observation (capture/observation-id captured)
                      :nodes nodes}))))

(defn compound-occurrence [captured compound-node]
  (let [captured (require-capture captured)
        occurrences (->> (capture/scopes captured)
                         vals
                         (keep #(get (:semantic-capture/nested-scopes %) compound-node))
                         distinct
                         (sort-by pr-str)
                         vec)]
    (case (count occurrences)
      0 (contradiction :missing-compound-node
                       {:observation (capture/observation-id captured)
                        :node compound-node})
      1 (first occurrences)
      (contradiction :ambiguous-compound-node
                     {:observation (capture/observation-id captured)
                      :node compound-node
                      :occurrences occurrences}))))

(defn occurrence-identity [captured occurrence]
  (let [captured (require-capture captured)
        observation (capture/observation-id captured)]
    (if (and (vector? occurrence)
             (= observation (first occurrence)))
      occurrence
      (contradiction :foreign-occurrence
                     {:observation observation :occurrence occurrence}))))

(defn- captured-scope [captured occurrence]
  (let [identity (occurrence-identity captured occurrence)]
    (if (value/contradiction? identity)
      identity
      (or (capture/scope captured identity) value/nothing))))

(defn content-of-scope
  "Return one canonical graph plane. Expansion membership remains a separate
  frozen fact and is intentionally not embedded in the plane content."
  [captured occurrence]
  (let [scope (captured-scope captured occurrence)]
    (cond
      (value/unusable? scope) scope
      :else (trace/graph-union
             (dissoc scope :semantic-capture/nested-scopes)))))

(defn- expansion-request [source occurrence]
  (object/as-accessor-network
   {:type :stack-expansion-request
    :source source
    :occurrence occurrence}))

(defn direct-compound-expansions [captured occurrence]
  (let [graph (captured-scope captured occurrence)]
    (cond
      (value/contradiction? graph) graph
      (value/nothing? graph) value/nothing
      :else (linked-value
             (->> (:semantic-capture/nested-scopes graph)
                  (sort-by (comp pr-str key))
                  (mapv (fn [[source child]]
                          (expansion-request source child))))))))

(def ^:private traversal-tag ::traversal)
(def ^:private work-tag ::work)
(def ^:private pending-step-tag ::pending-step)

(declare transport-base)

(defn- accessor-value [candidate slot]
  (let [source (object/accessor-source-slot-value candidate slot)]
    (if-not (value/nothing? source)
      source
      (if-let [id (net/network-dict-entry
                   candidate
                   (object/internal-metadata-key :accessor slot :canonical-cell))]
        (net/network-cell-strongest candidate id)
        value/nothing))))

(defn- linked-items [candidate]
  (loop [current candidate items [] seen #{}]
    (cond
      (= :compiler-2/list-empty current) items
      (value/unusable? current) current
      (contains? seen current)
      (contradiction :cyclic-list {:value candidate})
      (not (object/accessor-network? current))
      (contradiction :invalid-list {:value current})
      :else
      (recur (accessor-value current :cdr)
             (conj items (accessor-value current :car))
             (conj seen current)))))

(defn- work
  ([occurrence target] (work occurrence nil target))
  ([occurrence source target]
   {:stack.work/type work-tag
    :occurrence occurrence
    :source source
    :target target}))

(defn- traversal [root pending]
  {:stack.traversal/type traversal-tag
   :root root
   :pending (vec pending)
   :discovered []
   :nodes {}
   :targets {}
   :current nil
   :staged []})

(defn- require-traversal [candidate]
  (when-not (= traversal-tag (:stack.traversal/type candidate))
    (throw (ex-info "Expected stack traversal state" {:value candidate})))
  candidate)

(defn- require-work [candidate]
  (when-not (= work-tag (:stack.work/type candidate))
    (throw (ex-info "Expected stack traversal work" {:value candidate})))
  candidate)

(defn- pending-step [work remaining]
  {:stack.pending-step/type pending-step-tag
   :work work
   :remaining remaining})

(defn- require-pending-step [candidate]
  (when-not (= pending-step-tag (:stack.pending-step/type candidate))
    (throw (ex-info "Expected a pending traversal step" {:value candidate})))
  candidate)

(defn- assign-target [state target entry]
  (assoc-in state [:targets target] entry))

(defn- stack-ref-value [identity]
  (object/as-accessor-network {:type :stack-ref :identity identity}))

(defn- stack-expansion-value [source target]
  (object/as-accessor-network
   {:type :stack-expansion :source source :target target}))

(declare materialize-target)

(defn- materialize-node [state identity visiting]
  (cond
    (contains? visiting identity) (stack-ref-value identity)
    (not (contains? (:nodes state) identity)) value/nothing
    :else
    (let [{:keys [graph expansions]} (get-in state [:nodes identity])]
      (if (nil? expansions)
        value/nothing
        (let [materialized
              (mapv (fn [{:keys [source target]}]
                      (let [target-value
                            (materialize-target state target (conj visiting identity))]
                        (if (value/unusable? target-value)
                          target-value
                          (stack-expansion-value source target-value))))
                    expansions)]
          (if (some value/unusable? materialized)
            (or (first (filter value/contradiction? materialized)) value/nothing)
            (object/as-accessor-network
             {:type :stack
              :identity identity
              :graph graph
              :expansions (linked-value materialized)})))))))

(defn- materialize-target [state target visiting]
  (let [{:keys [kind identity]} (get-in state [:targets target])]
    (case kind
      :node (materialize-node state identity visiting)
      :ref (stack-ref-value identity)
      value/nothing)))

(defn begin-traversal [_observation root]
  (traversal root [(work root :root)]))

(defn traversal-empty? [state]
  (empty? (:pending (require-traversal state))))

(defn take-pending [state]
  (let [state (require-traversal state)
        current (first (:pending state))]
    (if current
      (pending-step
       current
       (assoc state :pending (subvec (:pending state) 1) :current current))
      (pending-step (contradiction :empty-traversal {}) state))))

(defn pending-work [step]
  (:work (require-pending-step step)))

(defn pending-remaining [step]
  (:remaining (require-pending-step step)))

(defn pending-occurrence [candidate]
  (:occurrence (require-work candidate)))

(defn discovered-occurrence? [state identity]
  (boolean (some #{identity} (:discovered (require-traversal state)))))

(defn register-occurrence [state candidate identity graph]
  (let [state (require-traversal state)
        candidate (require-work candidate)
        registered (-> state
                       (assoc-in [:nodes identity] {:graph graph :expansions nil})
                       (assign-target (:target candidate) {:kind :node :identity identity})
                       (update :discovered conj identity)
                       (assoc :current candidate :current-identity identity :staged []))]
    registered))

(defn connect-reference [state candidate identity]
  (let [state (require-traversal state)
        candidate (require-work candidate)]
    (-> state
        (assign-target (:target candidate) {:kind :ref :identity identity})
        (assoc :current nil :current-identity nil :staged []))))

(defn connect-expansion-outputs [state identity expansions]
  (let [state (require-traversal state)
        expansions (linked-items expansions)]
    (if (value/unusable? expansions)
      expansions
      (let [requests
            (mapv (fn [index expansion]
                    {:source (accessor-value expansion :source)
                     :occurrence (accessor-value expansion :occurrence)
                     :target [:expansion identity index]})
                  (range) expansions)
            staged (mapv (fn [{:keys [source occurrence target]}]
                           (work occurrence source target))
                         requests)]
        (-> state
            (assoc-in [:nodes identity :expansions]
                      (mapv #(select-keys % [:source :target]) requests))
            (assoc :staged staged))))))

(defn enqueue-expansion-work [state identity _expansions]
  (let [state (require-traversal state)]
    (when-not (= identity (:current-identity state))
      (throw (ex-info "Expansion work identity does not match registration"
                      {:expected (:current-identity state) :actual identity})))
    (-> state
        (update :pending into (:staged state))
        (assoc :current nil :current-identity nil :staged []))))

(defn finish-traversal [state]
  (let [state (require-traversal state)]
    (if (empty? (:pending state)) state value/nothing)))

(defn traversal-result [state]
  (materialize-target (require-traversal state) :root #{}))

(defn stack-field
  "Read one public stack field. With an owning network, live struct fields may
  be represented by outer parent cells; detached materialized values continue
  to read their capability-free source slots."
  ([slot candidate]
   (when-not (object/accessor-network? candidate)
     (throw (ex-info "Expected a materialized stack value" {:value candidate})))
   (accessor-value candidate slot))
  ([network slot candidate]
   (let [direct (stack-field slot candidate)]
     (if-not (value/nothing? direct)
       direct
       (let [values (->> (object/accessor-parent-ids candidate slot)
                         (filter #(contains? (net/net-env network) %))
                         (map #(transport-base
                                (net/network-cell-strongest network %)))
                         (remove value/nothing?)
                         distinct
                         vec)]
         (case (count values)
           0 value/nothing
           1 (first values)
           (contradiction :conflicting-live-field
                          {:slot slot :values values})))))))

(defn- stack-kind [candidate]
  (when (object/accessor-network? candidate)
    (accessor-value candidate :type)))

(defn- stack-entry-identity [candidate]
  (when (contains? #{:stack :stack-ref} (stack-kind candidate))
    (accessor-value candidate :identity)))

(defn stack-shape? [candidate]
  (letfn [(valid-target? [entry]
            (case (stack-kind entry)
              :stack (valid-stack? entry)
              :stack-ref (not (value/unusable? (stack-entry-identity entry)))
              false))
          (valid-expansion? [entry]
            (and (= :stack-expansion (stack-kind entry))
                 (not (value/unusable? (accessor-value entry :source)))
                 (valid-target? (accessor-value entry :target))))
          (valid-stack? [node]
            (if-not (= :stack (stack-kind node))
              false
              (let [identity (accessor-value node :identity)
                    graph (accessor-value node :graph)
                    expansions (linked-items (accessor-value node :expansions))]
                (cond
                  (or (value/unusable? identity) (value/unusable? graph)) false
                  (value/unusable? expansions) false
                  :else (every? valid-expansion? expansions)))))]
    (valid-stack? candidate)))

(defn- nested-contradictions [candidate]
  (cond
    (value/contradiction? candidate) [candidate]
    (map? candidate) (mapcat nested-contradictions (vals candidate))
    (sequential? candidate) (mapcat nested-contradictions candidate)
    (set? candidate) (mapcat nested-contradictions candidate)
    :else []))

(defn- combined-contradiction [candidates]
  (when-let [conflicts (seq (nested-contradictions candidates))]
    (value/contradiction-with-provenance
     (mapcat value/contradiction-provenance conflicts))))

(defn scope-content? [captured identity content]
  (let [expected (content-of-scope captured identity)
        expected-conflict (combined-contradiction expected)
        actual-conflict (combined-contradiction content)]
    (cond
      (value/unusable? expected) expected
      expected-conflict expected-conflict
      actual-conflict actual-conflict
      :else (= expected content))))

(defn direct-expansions? [captured identity expansions]
  (let [expected (direct-compound-expansions captured identity)
        actual (linked-items expansions)]
    (cond
      (value/unusable? expected) expected
      (value/unusable? actual) actual
      :else
      (= (mapv (fn [entry]
                 [(accessor-value entry :source)
                  (accessor-value entry :occurrence)])
               (linked-items expected))
         (mapv (fn [entry]
                 [(accessor-value entry :source)
                  (stack-entry-identity (accessor-value entry :target))])
               actual)))))

(defn reference-integrity? [root]
  (letfn [(walk-entry [entry seen]
            (case (stack-kind entry)
              :stack (walk-node entry seen)
              :stack-ref (let [identity (stack-entry-identity entry)]
                           [(and (not (value/unusable? identity))
                                 (contains? seen identity))
                            seen])
              [false seen]))
          (walk-expansion [entry seen]
            (if (or (not= :stack-expansion (stack-kind entry))
                    (value/unusable? (accessor-value entry :source)))
              [false seen]
              (walk-entry (accessor-value entry :target) seen)))
          (walk-node [node seen]
            (let [identity (stack-entry-identity node)
                  expansions (linked-items (accessor-value node :expansions))]
              (cond
                (or (value/unusable? identity)
                    (value/unusable? expansions)
                    (contains? seen identity)) [false seen]
                :else
                (reduce (fn [[valid? discovered] expansion]
                          (if-not valid?
                            [false discovered]
                            (let [[child-valid? next-seen]
                                  (walk-expansion expansion discovered)]
                              [child-valid? next-seen])))
                        [true (conj seen identity)]
                        expansions))))]
    (first (walk-node root #{}))))

(defn- supported-primitive [name f]
  (prop/primitive-propagator
   name
   (fn [& arguments]
     (cond
       (some value/contradiction? arguments)
       (value/contradiction-with-provenance
        (mapcat value/contradiction-provenance
                (filter value/contradiction? arguments)))
       (some value/nothing? arguments) value/nothing
       :else
       (try
         (apply f arguments)
         (catch clojure.lang.ExceptionInfo error
           (contradiction :invalid-traversal-input
                          {:operation name :reason (ex-message error)})))))))

(defn- transport-base [candidate]
  (let [unscoped (scope-source/unwrap candidate)]
    (cond
      (not= candidate unscoped)
      (transport-base unscoped)

      (tms/distributed-value? candidate)
      (transport-base
       (-> candidate
           tms/strongest-distributed-value
           tms/distributed-base-value))

      (and (datum/layer-present? candidate :base)
           (datum/layer-present? candidate :support))
      (transport-base (datum/layer-value candidate :base))

      :else
      candidate)))

(defn- primitive-result [name f arguments]
  (let [arguments (mapv transport-base arguments)]
    (cond
      (some value/contradiction? arguments)
      (value/contradiction-with-provenance
       (mapcat value/contradiction-provenance
               (filter value/contradiction? arguments)))

      (some value/nothing? arguments)
      value/nothing

      :else
      (try
        (apply f arguments)
        (catch clojure.lang.ExceptionInfo error
          (contradiction :invalid-traversal-input
                         {:operation name :reason (ex-message error)}))))))

(defn- support-version [contents]
  (->> contents
       (filter tms/distributed-value?)
       (mapcat tms/distributed-supports)
       (map (juxt tms/premise tms/support-source tms/support-kind-value))
       set))

(defn- raw-dependency-messages
  [name f input-ids output-id network]
  (let [contents (mapv #(net/network-cell-content network %) input-ids)
        arguments (mapv #(net/network-cell-strongest network %) input-ids)
        result (primitive-result name f arguments)
        distributed? (some tms/distributed-value? contents)
        update (if distributed?
                 (if (value/unusable? result)
                   (tms/distributed-state-update contents)
                   (or (tms/distributed-result-update
                        [name input-ids output-id (support-version contents)]
                        result contents)
                       result))
                 result)]
    (if (some? update) [(message output-id update)] [])))

(defn- install-raw-dependency
  [name f network inputs output]
  ((prop/construct-propagator
    name
    (fn [_inputs _outputs current]
      (raw-dependency-messages name f inputs output current))
    inputs [output])
   network))

(defn- supported-operator [name input-count output-count f]
  (operator/operator-closure
   {:name name
    :application-installer
    (fn [network arguments fallback-output _context]
      (let [arguments (vec arguments)
            implicit? (and (= 1 output-count) (= input-count (count arguments)))
            expected (+ input-count output-count)]
        (when-not (or implicit? (= expected (count arguments)))
          (throw (ex-info (str name " received the wrong number of arguments")
                          {:inputs input-count :outputs output-count
                           :actual (count arguments)})))
        (let [inputs (subvec arguments 0 input-count)
              outputs (if implicit?
                        [fallback-output]
                        (subvec arguments input-count))
              [installed props]
              (reduce-kv
               (fn [[current installed-props] index output]
                 (let [primitive (supported-primitive
                                  name
                                  (if (= 1 output-count)
                                    f
                                    #(nth (apply f %&) index)))
                       [next-network next-props _]
                       (supported/install (supported/from-primitive primitive)
                                          current inputs output)]
                   [next-network (into installed-props next-props)]))
               [network []]
               outputs)]
          [(declarations/register-props installed props)
           props
           (peek outputs)])))}))

(defn- raw-operator
  "Install an unlayered operation for a versioned closure wrapper. The wrapper's
  premise boundary owns support publication, avoiding nested supported values."
  [name input-count output-count f]
  (operator/operator-closure
   {:name name
    :application-installer
    (fn [network arguments fallback-output _context]
      (let [arguments (vec arguments)
            implicit? (and (= 1 output-count) (= input-count (count arguments)))
            expected (+ input-count output-count)]
        (when-not (or implicit? (= expected (count arguments)))
          (throw (ex-info (str name " received the wrong number of arguments")
                          {:inputs input-count :outputs output-count
                           :actual (count arguments)})))
        (let [inputs (subvec arguments 0 input-count)
              outputs (if implicit? [fallback-output] (subvec arguments input-count))
              [installed props]
              (reduce-kv
               (fn [[current installed-props] index output]
                 (let [operation (if (= 1 output-count)
                                   f
                                   #(nth (apply f %&) index))
                       [id next-network]
                       (install-raw-dependency name operation current inputs output)]
                   [next-network (conj installed-props id)]))
               [network []] outputs)]
          [(declarations/register-props installed props) props (peek outputs)])))}))

(defn bindings []
  [['stack:struct (struct-operator)]
   ['stack:root-compound-node
    (capture-reader 'stack:root-compound-node 1 root-compound-node)]
   ['stack:compound-occurrence
    (capture-reader 'stack:compound-occurrence 2 compound-occurrence)]
   ['stack:occurrence-identity
    (capture-reader 'stack:occurrence-identity 2 occurrence-identity)]
   ['stack:content-of-scope
    (capture-reader 'stack:content-of-scope 2 content-of-scope)]
   ['stack:direct-compound-expansions
    (capture-reader 'stack:direct-compound-expansions 2 direct-compound-expansions)]
   ['stack:begin-traversal
    (supported-operator 'stack:begin-traversal 2 1 begin-traversal)]
   ['stack:traversal-empty?
    (supported-operator 'stack:traversal-empty? 1 1 traversal-empty?)]
   ['stack:take-pending
    (supported-operator 'stack:take-pending 1 1 take-pending)]
   ['stack:pending-work
    (supported-operator 'stack:pending-work 1 1 pending-work)]
   ['stack:pending-remaining
    (supported-operator 'stack:pending-remaining 1 1 pending-remaining)]
   ['stack:pending-occurrence
    (supported-operator 'stack:pending-occurrence 1 1 pending-occurrence)]
   ['stack:discovered-occurrence?
    (supported-operator 'stack:discovered-occurrence? 2 1 discovered-occurrence?)]
   ['stack:register-occurrence
    (supported-operator 'stack:register-occurrence 4 1 register-occurrence)]
   ['stack:connect-reference
    (supported-operator 'stack:connect-reference 3 1 connect-reference)]
   ['stack:connect-expansion-outputs
    (supported-operator 'stack:connect-expansion-outputs 3 1 connect-expansion-outputs)]
   ['stack:enqueue-expansion-work
    (supported-operator 'stack:enqueue-expansion-work 3 1 enqueue-expansion-work)]
   ['stack:finish-traversal
    (supported-operator 'stack:finish-traversal 1 1 finish-traversal)]
   ['stack:traversal-result
    (supported-operator 'stack:traversal-result 1 1 traversal-result)]
   ['stack:stack-slot
    (supported-operator 'stack:stack-slot 2 1 stack-field)]
   ['stack:stack-shape?
    (supported-operator 'stack:stack-shape? 1 1 stack-shape?)]
   ['stack:scope-content?
    (supported-operator 'stack:scope-content? 3 1 scope-content?)]
   ['stack:direct-expansions?
    (supported-operator 'stack:direct-expansions? 3 1 direct-expansions?)]
   ['stack:reference-integrity?
    (supported-operator 'stack:reference-integrity? 1 1 reference-integrity?)]
   ['stack.raw:root-compound-node
    (raw-operator 'stack.raw:root-compound-node 1 1 root-compound-node)]
   ['stack.raw:compound-occurrence
    (raw-operator 'stack.raw:compound-occurrence 2 1 compound-occurrence)]
   ['stack.raw:occurrence-identity
    (raw-operator 'stack.raw:occurrence-identity 2 1 occurrence-identity)]
   ['stack.raw:content-of-scope
    (raw-operator 'stack.raw:content-of-scope 2 1 content-of-scope)]
   ['stack.raw:direct-compound-expansions
    (raw-operator 'stack.raw:direct-compound-expansions 2 1 direct-compound-expansions)]
   ['stack.raw:begin-traversal
    (raw-operator 'stack.raw:begin-traversal 2 1 begin-traversal)]
   ['stack.raw:traversal-empty?
    (raw-operator 'stack.raw:traversal-empty? 1 1 traversal-empty?)]
   ['stack.raw:take-pending (raw-operator 'stack.raw:take-pending 1 1 take-pending)]
   ['stack.raw:pending-work (raw-operator 'stack.raw:pending-work 1 1 pending-work)]
   ['stack.raw:pending-remaining
    (raw-operator 'stack.raw:pending-remaining 1 1 pending-remaining)]
   ['stack.raw:pending-occurrence
    (raw-operator 'stack.raw:pending-occurrence 1 1 pending-occurrence)]
   ['stack.raw:discovered-occurrence?
    (raw-operator 'stack.raw:discovered-occurrence? 2 1 discovered-occurrence?)]
   ['stack.raw:register-occurrence
    (raw-operator 'stack.raw:register-occurrence 4 1 register-occurrence)]
   ['stack.raw:connect-reference
    (raw-operator 'stack.raw:connect-reference 3 1 connect-reference)]
   ['stack.raw:connect-expansion-outputs
    (raw-operator 'stack.raw:connect-expansion-outputs 3 1 connect-expansion-outputs)]
   ['stack.raw:enqueue-expansion-work
    (raw-operator 'stack.raw:enqueue-expansion-work 3 1 enqueue-expansion-work)]
   ['stack.raw:finish-traversal
    (raw-operator 'stack.raw:finish-traversal 1 1 finish-traversal)]
   ['stack.raw:traversal-result
    (raw-operator 'stack.raw:traversal-result 1 1 traversal-result)]
   ['stack.raw:stack-slot (raw-operator 'stack.raw:stack-slot 2 1 stack-field)]
   ['stack.raw:stack-shape? (raw-operator 'stack.raw:stack-shape? 1 1 stack-shape?)]
   ['stack.raw:scope-content? (raw-operator 'stack.raw:scope-content? 3 1 scope-content?)]
   ['stack.raw:direct-expansions?
    (raw-operator 'stack.raw:direct-expansions? 3 1 direct-expansions?)]
   ['stack.raw:reference-integrity?
    (raw-operator 'stack.raw:reference-integrity? 1 1 reference-integrity?)]])

(def extension
  (extension/extension-bundle
   {:id ::stack-primitives :bindings (bindings) :effects []}))
