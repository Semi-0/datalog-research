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
            [propagators.network :as net]
            [propagators.propagator :as prop]
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

(defn root-compound-occurrence [captured]
  (let [captured (require-capture captured)
        graph (capture/scope captured :root)
        occurrences (->> (:semantic-capture/nested-scopes graph)
                         vals distinct (sort-by pr-str) vec)]
    (case (count occurrences)
      0 (contradiction :missing-root
                       {:observation (capture/observation-id captured)})
      1 (first occurrences)
      (contradiction :ambiguous-roots
                     {:observation (capture/observation-id captured)
                      :occurrences occurrences}))))

(defn occurrence-identity [captured occurrence]
  (let [captured (require-capture captured)
        observation (capture/observation-id captured)]
    (if (and (vector? occurrence)
             (= observation (first occurrence)))
      occurrence
      (contradiction :foreign-occurrence
                     {:observation observation :occurrence occurrence}))))

(defn content-of-scope [captured occurrence]
  (let [identity (occurrence-identity captured occurrence)]
    (if (value/contradiction? identity)
      identity
      (or (capture/scope captured identity) value/nothing))))

(defn direct-compound-children [captured occurrence]
  (let [graph (content-of-scope captured occurrence)]
    (cond
      (value/contradiction? graph) graph
      (value/nothing? graph) value/nothing
      :else (linked-value
             (->> (:semantic-capture/nested-scopes graph)
                  vals distinct (sort-by pr-str))))))

(def ^:private traversal-tag ::traversal)
(def ^:private work-tag ::work)

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

(defn- work [occurrence target]
  {:stack.work/type work-tag :occurrence occurrence :target target})

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

(defn- assign-target [state target entry]
  (assoc-in state [:targets target] entry))

(defn- stack-ref-value [identity]
  (object/as-accessor-network {:type :stack-ref :identity identity}))

(declare materialize-target)

(defn- materialize-node [state identity visiting]
  (cond
    (contains? visiting identity) (stack-ref-value identity)
    (not (contains? (:nodes state) identity)) value/nothing
    :else
    (let [{:keys [content children]} (get-in state [:nodes identity])]
      (if (nil? children)
        value/nothing
        (let [materialized (mapv #(materialize-target state % (conj visiting identity))
                                 children)]
          (if (some value/unusable? materialized)
            (or (first (filter value/contradiction? materialized)) value/nothing)
            (object/as-accessor-network
             {:type :stack
              :identity identity
              :content content
              :children (linked-value materialized)})))))))

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
      [current (assoc state :pending (subvec (:pending state) 1) :current current)]
      [(contradiction :empty-traversal {}) state])))

(defn pending-occurrence [candidate]
  (:occurrence (require-work candidate)))

(defn discovered-occurrence? [state identity]
  (boolean (some #{identity} (:discovered (require-traversal state)))))

(defn register-occurrence [state candidate identity content]
  (let [state (require-traversal state)
        candidate (require-work candidate)
        registered (-> state
                       (assoc-in [:nodes identity] {:content content :children nil})
                       (assign-target (:target candidate) {:kind :node :identity identity})
                       (update :discovered conj identity)
                       (assoc :current candidate :current-identity identity :staged []))]
    [(object/as-accessor-network
      {:type :stack :identity identity :content content :children value/nothing})
     registered]))

(defn connect-reference [state candidate reference]
  (let [state (require-traversal state)
        candidate (require-work candidate)
        identity (accessor-value reference :identity)]
    (-> state
        (assign-target (:target candidate) {:kind :ref :identity identity})
        (assoc :current nil :current-identity nil :staged []))))

(defn connect-child-outputs [state identity children]
  (let [state (require-traversal state)
        children (linked-items children)]
    (if (value/unusable? children)
      children
      (let [targets (mapv (fn [index _] [:child identity index])
                          (range) children)
            staged (mapv work children targets)]
        (-> state
            (assoc-in [:nodes identity :children] targets)
            (assoc :staged staged))))))

(defn enqueue-child-work [state identity _children]
  (let [state (require-traversal state)]
    (when-not (= identity (:current-identity state))
      (throw (ex-info "Child work identity does not match registration"
                      {:expected (:current-identity state) :actual identity})))
    (-> state
        (update :pending into (:staged state))
        (assoc :current nil :current-identity nil :staged []))))

(defn finish-traversal [state]
  (let [state (require-traversal state)]
    (if (empty? (:pending state)) state value/nothing)))

(defn traversal-result [state]
  (materialize-target (require-traversal state) :root #{}))

(defn stack-field [slot candidate]
  (when-not (object/accessor-network? candidate)
    (throw (ex-info "Expected a materialized stack value" {:value candidate})))
  (accessor-value candidate slot))

(defn- stack-kind [candidate]
  (when (object/accessor-network? candidate)
    (accessor-value candidate :type)))

(defn- stack-entry-identity [candidate]
  (when (contains? #{:stack :stack-ref} (stack-kind candidate))
    (accessor-value candidate :identity)))

(defn stack-shape? [candidate]
  (letfn [(valid-entry? [entry]
            (case (stack-kind entry)
              :stack (valid-stack? entry)
              :stack-ref (not (value/unusable? (stack-entry-identity entry)))
              false))
          (valid-stack? [node]
            (if-not (= :stack (stack-kind node))
              false
              (let [identity (accessor-value node :identity)
                    content (accessor-value node :content)
                    children (linked-items (accessor-value node :children))]
                (cond
                  (or (value/unusable? identity) (value/unusable? content)) false
                  (value/unusable? children) false
                  :else (every? valid-entry? children)))))]
    (valid-stack? candidate)))

(defn scope-content? [captured identity content]
  (let [expected (content-of-scope captured identity)]
    (if (value/unusable? expected) expected (= expected content))))

(defn direct-children? [captured identity children]
  (let [expected (direct-compound-children captured identity)
        actual (linked-items children)]
    (cond
      (value/unusable? expected) expected
      (value/unusable? actual) actual
      :else
      (= (linked-items expected)
         (mapv stack-entry-identity actual)))))

(defn reference-integrity? [root]
  (letfn [(walk-entry [entry seen]
            (case (stack-kind entry)
              :stack (walk-node entry seen)
              :stack-ref (let [identity (stack-entry-identity entry)]
                           [(and (not (value/unusable? identity))
                                 (contains? seen identity))
                            seen])
              [false seen]))
          (walk-node [node seen]
            (let [identity (stack-entry-identity node)
                  children (linked-items (accessor-value node :children))]
              (cond
                (or (value/unusable? identity)
                    (value/unusable? children)
                    (contains? seen identity)) [false seen]
                :else
                (reduce (fn [[valid? discovered] child]
                          (if-not valid?
                            [false discovered]
                            (let [[child-valid? next-seen]
                                  (walk-entry child discovered)]
                              [child-valid? next-seen])))
                        [true (conj seen identity)]
                        children))))]
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
                 (let [primitive (supported-primitive
                                  name
                                  (if (= 1 output-count)
                                    f
                                    #(nth (apply f %&) index)))
                       [id next-network] ((apply primitive (conj inputs output)) current)]
                   [next-network (conj installed-props id)]))
               [network []] outputs)]
          [(declarations/register-props installed props) props (peek outputs)])))}))

(defn bindings []
  [['stack:struct (struct-operator)]
   ['stack:root-compound-occurrence
    (capture-reader 'stack:root-compound-occurrence 1 root-compound-occurrence)]
   ['stack:occurrence-identity
    (capture-reader 'stack:occurrence-identity 2 occurrence-identity)]
   ['stack:content-of-scope
    (capture-reader 'stack:content-of-scope 2 content-of-scope)]
   ['stack:direct-compound-children
    (capture-reader 'stack:direct-compound-children 2 direct-compound-children)]
   ['stack:begin-traversal
    (supported-operator 'stack:begin-traversal 2 1 begin-traversal)]
   ['stack:traversal-empty?
    (supported-operator 'stack:traversal-empty? 1 1 traversal-empty?)]
   ['stack:take-pending
    (supported-operator 'stack:take-pending 1 2 take-pending)]
   ['stack:pending-occurrence
    (supported-operator 'stack:pending-occurrence 1 1 pending-occurrence)]
   ['stack:discovered-occurrence?
    (supported-operator 'stack:discovered-occurrence? 2 1 discovered-occurrence?)]
   ['stack:register-occurrence
    (supported-operator 'stack:register-occurrence 4 2 register-occurrence)]
   ['stack:connect-reference
    (supported-operator 'stack:connect-reference 3 1 connect-reference)]
   ['stack:connect-child-outputs
    (supported-operator 'stack:connect-child-outputs 3 1 connect-child-outputs)]
   ['stack:enqueue-child-work
    (supported-operator 'stack:enqueue-child-work 3 1 enqueue-child-work)]
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
   ['stack:direct-children?
    (supported-operator 'stack:direct-children? 3 1 direct-children?)]
   ['stack:reference-integrity?
    (supported-operator 'stack:reference-integrity? 1 1 reference-integrity?)]
   ['stack.raw:root-compound-occurrence
    (raw-operator 'stack.raw:root-compound-occurrence 1 1 root-compound-occurrence)]
   ['stack.raw:occurrence-identity
    (raw-operator 'stack.raw:occurrence-identity 2 1 occurrence-identity)]
   ['stack.raw:content-of-scope
    (raw-operator 'stack.raw:content-of-scope 2 1 content-of-scope)]
   ['stack.raw:direct-compound-children
    (raw-operator 'stack.raw:direct-compound-children 2 1 direct-compound-children)]
   ['stack.raw:begin-traversal
    (raw-operator 'stack.raw:begin-traversal 2 1 begin-traversal)]
   ['stack.raw:traversal-empty?
    (raw-operator 'stack.raw:traversal-empty? 1 1 traversal-empty?)]
   ['stack.raw:take-pending (raw-operator 'stack.raw:take-pending 1 2 take-pending)]
   ['stack.raw:pending-occurrence
    (raw-operator 'stack.raw:pending-occurrence 1 1 pending-occurrence)]
   ['stack.raw:discovered-occurrence?
    (raw-operator 'stack.raw:discovered-occurrence? 2 1 discovered-occurrence?)]
   ['stack.raw:register-occurrence
    (raw-operator 'stack.raw:register-occurrence 4 2 register-occurrence)]
   ['stack.raw:connect-reference
    (raw-operator 'stack.raw:connect-reference 3 1 connect-reference)]
   ['stack.raw:connect-child-outputs
    (raw-operator 'stack.raw:connect-child-outputs 3 1 connect-child-outputs)]
   ['stack.raw:enqueue-child-work
    (raw-operator 'stack.raw:enqueue-child-work 3 1 enqueue-child-work)]
   ['stack.raw:finish-traversal
    (raw-operator 'stack.raw:finish-traversal 1 1 finish-traversal)]
   ['stack.raw:traversal-result
    (raw-operator 'stack.raw:traversal-result 1 1 traversal-result)]
   ['stack.raw:stack-slot (raw-operator 'stack.raw:stack-slot 2 1 stack-field)]
   ['stack.raw:stack-shape? (raw-operator 'stack.raw:stack-shape? 1 1 stack-shape?)]
   ['stack.raw:scope-content? (raw-operator 'stack.raw:scope-content? 3 1 scope-content?)]
   ['stack.raw:direct-children? (raw-operator 'stack.raw:direct-children? 3 1 direct-children?)]
   ['stack.raw:reference-integrity?
    (raw-operator 'stack.raw:reference-integrity? 1 1 reference-integrity?)]])

(def extension
  (extension/extension-bundle
   {:id ::stack-primitives :bindings (bindings) :effects []}))
