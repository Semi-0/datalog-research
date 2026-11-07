(ns propagators.compiler-2.session
  "Immutable Lain compiler sessions. No host callback or file IO runs here."
  (:require [propagators.cell-evaluator :as cell-evaluator]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.session.supported :as supported]
            [propagators.compiler-2.session.connected :as connected]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.env.binding :as binding]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.inspection.temperature :as temperature]
            [propagators.compiler-2.runtime.session.environment-io :as environment-io]
            [propagators.compiler-2.runtime.session.extension :as native-extension]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.compiler-2.runtime.session.program.source :as source]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.compiler-2.session.operators :as operators]
            [propagators.compiler-2.session.dependencies :as dependency]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.propagator :as prop]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.layered-value :as datum]
            [propagators.relationship-observer :as observer])
  (:import [java.util UUID]))

(defprotocol CompilerSession
  (environment [session])
  (network [session])
  (advance [session inputs]))

(defprotocol SessionExtension
  (extend-session [session extension]))

(defprotocol SessionDependencies
  (dependency-declarations [session]))

(defprotocol OpenSession
  (source-files [session])
  (rerun [session fresh-run-id]))

(defprotocol SessionView
  (session-identity [session])
  (compiler-state [session])
  (session-declarations [session]))

(defprotocol ExtensionDeclaration
  (extension-id [extension])
  (extension-parts [extension]))

(deftype ImmutableLainSession [identity declarations state])
(deftype CompilerExtension [id parts])

;; Deliberately public boundary result, not a mutable execution owner.
(defrecord Turn [session next-inputs requests])
(defn turn [session next-inputs requests]
  (->Turn session (vec next-inputs) (vec requests)))

(extend-type CompilerExtension
  ExtensionDeclaration
  (extension-id [extension] (.-id ^CompilerExtension extension))
  (extension-parts [extension] (.-parts ^CompilerExtension extension)))

(defn- bounded [session operation]
  (let [{:keys [max-steps timeout-ms]} (:limits (session-declarations session))
        steps (volatile! 0)
        deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))]
    (binding [cell-evaluator/*evaluate-cell*
              (get (merge (get-in (session-declarations session) [:options :evaluation])
                          (:evaluation (session-declarations session)))
                   :cell-evaluator cell-evaluator/*evaluate-cell*)
              runner/*iterator-factory*
              (get (merge (get-in (session-declarations session) [:options :evaluation])
                          (:evaluation (session-declarations session)))
                   :iterator-factory runner/*iterator-factory*)
              runner/*advance-transform*
              (runner/compose-advance-transforms
               runner/*advance-transform*
               (fn [advance]
                 (fn [state continuations]
                   (when (or (> (vswap! steps inc) max-steps)
                             (> (System/nanoTime) deadline))
                     (throw (ex-info "Compiler session propagation budget exhausted"
                                     {:steps @steps :max-steps max-steps
                                      :timeout-ms timeout-ms})))
                   (advance state continuations))))]
      (operation))))

(defn- bindings-map [bindings]
  (cond
    (nil? bindings) {}
    (map? bindings) (into {} bindings)
    (sequential? bindings)
    (reduce (fn [result [sym declaration :as pair]]
              (when-not (and (= 2 (count pair)) (symbol? sym))
                (throw (ex-info "Invalid primitive binding" {:binding pair})))
              (when (contains? result sym)
                (throw (ex-info "Duplicate primitive binding" {:symbol sym})))
              (assoc result sym declaration))
            {} bindings)
    :else (throw (ex-info "Primitive bindings must be a map or ordered pairs" {}))))

(defn session-extension
  "Snapshot declared values. Files are supplied contents, never paths to load."
  [{:keys [id primitives dependencies files imports target support-cell cells frames targets supports derivations scoped-primitives interfaces evaluation]}]
  (when (and support-cell (nil? target))
    (throw (ex-info "Definition support requires a lexical target" {})))
  (when (nil? id)
    (throw (ex-info "Compiler extension requires an id" {})))
  (when-not (or (nil? dependencies) (map? dependencies))
    (throw (ex-info "Dependency declarations must be a map" {})))
  (doseq [[id capability] dependencies]
    (when-not (satisfies? dependency/Dependency capability)
      (throw (ex-info "Dependency must implement Dependency" {:dependency id}))))
  (when-not (or (nil? files) (map? files))
    (throw (ex-info "Files must map identities to source strings" {})))
  (let [primitives (bindings-map primitives)
        files (into {} files)]
    (when-not (every? symbol? (keys primitives))
      (throw (ex-info "Primitive binding names must be symbols" {})))
    (doseq [[file text] files]
      (when-not (and (string? file) (string? text))
        (throw (ex-info "Invalid source file declaration" {:file file})))
      (source/read-source-forms text))
    (CompilerExtension. id {:primitives (if target {} primitives)
                            :scoped-primitives (merge scoped-primitives
                                                      (when target (into {} (map (fn [[sym implementation]]
                                                                                   [[target sym] implementation])) primitives)))
                            :evaluation (into {} evaluation)
                            :dependencies (into {} dependencies)
                            :files files :imports (into {} imports)
                            :cells (into {} cells) :frames (into {} frames) :derivations (into {} derivations) :interfaces (into {} interfaces)
                            :targets (merge targets (when target (zipmap (keys files) (repeat target))))
                            :supports (merge supports (when support-cell (zipmap (keys files) (repeat support-cell))))})))

(defn- join-additions [existing additions kind]
  (reduce-kv
   (fn [result id declaration]
     (if (contains? result id)
       (if (= (get result id) declaration)
         result
         (throw (ex-info "Conflicting monotone extension" {:kind kind :id id})))
       (assoc result id declaration)))
   existing additions))

(defn- join-parts [base additions]
  (reduce (fn [result kind]
            (assoc result kind (join-additions (get base kind {})
                                               (get additions kind {}) kind)))
          base [:primitives :dependencies :files :imports :cells :frames :targets :supports :derivations :scoped-primitives :interfaces :evaluation]))

(defn cell-input
  "An ordinary domain update; its support and payload are not coerced."
  [cell-id update]
  (when-not (ids/node-id? cell-id)
    (throw (ex-info "Cell input requires a cell identity" {:cell-id cell-id})))
  {:runtime/input :cell-message :cell-id cell-id :update update})

(defn normalize-input
  "Accept the two established explicit message forms, reject ambiguous payloads."
  [input]
  (cond
    (and (map? input) (= :cell-message (:runtime/input input))
         (contains? input :update))
    (cell-input (:cell-id input) (:update input))

    (and (map? input) (#{:connected :admission} (:runtime/input input))) input

    (message/message? input)
    (cell-input (message/message-id input) (message/message-value input))

    :else (throw (ex-info "Expected an ordinary cell message" {:input input}))))

(defn binding-cell [session sym]
  (when-not (symbol? sym)
    (throw (ex-info "Binding name must be a symbol" {:symbol sym})))
  (let [binding (env/resolve-binding (network session) (environment session) sym)
        id (env/binding-id binding)]
    (when-not (ids/node-id? id)
      (throw (ex-info "Unknown compiler binding" {:symbol sym})))
    id))

(defn binding-value [session sym]
  (net/network-cell-strongest (network session) (binding-cell session sym)))

(defn scoped-binding-cell [session target sym]
  (or (env/lexical-binding-id (network session) sym target)
      (throw (ex-info "Unknown scoped binding" {:target target :symbol sym}))))

(defn source-result-cell [session file]
  (get-in (compiler-state session) [::source-results file]))

(defn- install-bindings [runtime bindings context]
  (doseq [sym (keys bindings)
          :let [resolved (if (:target context)
                           (env/local-binding-id (:program/net runtime) (:program/env runtime) sym)
                           (env/lexical-binding-id (:program/net runtime) sym (:program/env runtime)))
                definition (or (when-not (:target context) (get-in runtime [::definitions sym]))
                               (some (fn [[key form]]
                                       (when (and (vector? key) (= sym (second key))
                                                  (= resolved (env/local-binding-id (:program/net runtime) (first key) sym)))
                                         form)) (::definitions runtime)))]
          :when (and definition (> (count definition) 2))]
    (throw (ex-info "Conflicting binding: source definition already owns this name"
                    {:symbol sym :definition definition})))
  (if (empty? bindings)
    runtime
    (let [pairs (mapv (fn [[sym declaration]]
                        [sym (operators/compiler-binding sym declaration context)])
                      (sort-by (comp str key) bindings))
          pending (keep (fn [[sym implementation]]
                          (let [descriptor (env/resolve-binding (:program/net runtime)
                                                                (:program/env runtime) sym)
                                id (if (:target context)
                                     (env/local-binding-id (:program/net runtime) (:program/env runtime) sym)
                                     (env/binding-id descriptor))]
                            (when (and (ids/node-id? id)
                                       (value/nothing?
                                        (first (basis/primitive-input-values
                                                (:program/net runtime) [id]))))
                              [sym (if-let [source (binding/binding-id implementation)]
                                     {:cell-id id :alias-id source}
                                     {:cell-id id :update implementation})])))
                        pairs)
          pending-by-name (into {} pending)
          aliases (mapv (fn [[sym implementation]]
                          (if-let [reserved (get pending-by-name sym)]
                            [sym (binding/cell-binding (:cell-id reserved))]
                            [sym implementation])) pairs)
          declared (env/declare-bindings (:program/net runtime) (:program/env runtime) aliases)
          connected (reduce (fn [{:keys [net props]} [_ {:keys [cell-id alias-id]}]]
                              (if alias-id
                                (let [[p n] ((prop/construct-propagator
                                              [:session-alias alias-id cell-id]
                                              (fn [_ _ network] (basis/mono-sync-messages network alias-id cell-id))
                                              [alias-id] [cell-id]) net)]
                                  {:net n :props (conj (vec props) p)})
                                {:net net :props props})) declared pending)
          settled (runner/completed-network
                   (runner/run-network (:props connected) (:net connected)))]
      ;; Reuse the reserved value cell: existing GUR applications already watch it.
      (input/apply-program-updates (assoc runtime :program/net settled)
                                   (mapv second (filter #(contains? (second %) :update) pending))))))

(defn- install-scoped-bindings [runtime bindings context]
  (reduce (fn [runtime [target entries]]
            (when (= :unknown (:status (env/lexical-binding-status (:program/net runtime) '__scope_probe target)))
              (throw (ex-info "Unknown lexical primitive target" {:target target})))
            (let [root (:program/env runtime)
                  local (into {} (map (fn [[[scope name] implementation]] [name implementation])) entries)]
              (-> (install-bindings (assoc runtime :program/env target) local (assoc context :target target))
                  (assoc :program/env root))))
          runtime (group-by (comp first key) bindings)))

(defn- definition-name [form]
  (when (and (seq? form) (= 'define (first form)))
    (let [name (second form)]
      (if (seq? name) (first name) name))))

(declare compile-file)

(defn- compile-declaration [runtime file form]
  (let [target (get-in runtime [::catalog :targets file])
        support-cell (get-in runtime [::catalog :supports file])
        name (definition-name form)
        key (if target [target name] name)
        prior (get-in runtime [::definitions key])]
    (when (and prior (not= prior form) (> (count prior) 2))
      (throw (ex-info "Conflicting source definition" {:file file :symbol name :target target})))
    (if (and name (= prior form) (nil? support-cell))
      runtime
      (let [candidate
            (if target
              (do
                (when-not (and (ids/node-id? target)
                               (not= :unknown (:status (env/lexical-binding-status (:program/net runtime) '__scope_probe target))))
                  (throw (ex-info "Unknown lexical target" {:target target})))
                (when (and support-cell
                           (or (not (contains? (net/net-env (:program/net runtime)) support-cell))
                               (value/unusable? (net/network-cell-strongest (:program/net runtime) support-cell))
                               (empty? (datum/support-of (net/network-cell-strongest (:program/net runtime) support-cell)))))
                  (throw (ex-info "Definition support is unavailable" {:support-cell support-cell})))
                (let [network (:program/net runtime)
                      declared (if (and name (not (env/local-binding-id network target name)))
                                 (env/declare-bindings network target [[name (env/cell-binding
                                                                              (basis/stable-node-id :session-local target name))]])
                                 {:net network :props []})
                      compiled (compiler/compile-form form target
                                                      {:net (:net declared) :environment-props (:props declared)
                                                       :seed [:session-source target file form]
                                                       :reuse-existing-bindings? true
                                                       :compiler (when support-cell (supported/compiler support-cell))})
                      network (runner/completed-network (runner/run-network (:props compiled) (:net compiled)))]
                  (-> runtime
                      (assoc :program/net network :compiled compiled)
                      (assoc-in [::source-results file] (:cell compiled))
                      (update :environment/source-ledger (fnil conj [])
                              {:file file :form form :target target :support-cell support-cell}))))
              (let [{candidate :state error :error}
                    (environment-io/compile-hidden-form runtime "compiler-session" form {:file file})]
                (when error (throw (ex-info (str "Compiler extension failed: " (:message error)) error)))
                candidate))]
        (cond-> candidate name (assoc-in [::definitions key] form))))))

(defn- install-cells-and-frames [runtime parts]
  (let [network (reduce-kv (fn [network id content]
                             (when-not (ids/node-id? id) (throw (ex-info "Invalid cell id" {:id id})))
                             (when (contains? (net/net-env network) id)
                               (throw (ex-info "Cell already exists" {:id id})))
                             (nb/ensure-cell network id))
                           (:program/net runtime) (:cells parts))
        network (:program/net (input/apply-program-updates
                               (assoc runtime :program/net network)
                               (mapv (fn [[id content]] {:cell-id id :update content}) (:cells parts))))]
    (loop [network network pending (:frames parts)]
      (if (empty? pending)
        (assoc runtime :program/net
               (reduce-kv
                (fn [network id {:keys [base sources expected]}]
                  (when (contains? (net/net-env network) id) (throw (ex-info "Derived cell already exists" {:id id})))
                  (doseq [source sources]
                    (when-not (contains? (net/net-env network) source)
                      (throw (ex-info "Unknown support source" {:source source}))))
                  (let [guard (basis/stable-node-id :context-validity id)
                        [network sources]
                        (if (seq expected)
                          (let [installer (prop/construct-propagator
                                           [:context-validity id]
                                           (fn [_ _ current]
                                             (let [valid? (every? (fn [[source expected-base]]
                                                                    (let [v (net/network-cell-strongest current source)
                                                                          base (if (datum/layer-present? v :base)
                                                                                 (datum/layer-value v :base) v)]
                                                                      (and (not (value/unusable? v)) (= expected-base base)))) expected)
                                                   update (publication/next-source-datum guard
                                                                                         (net/network-cell-strongest current guard)
                                                                                         :context-valid (if valid? :active :retracted))]
                                               [(message/message guard (collection/content update))]))
                                           (vec (keys expected)) [guard])
                                [p network] (installer (nb/ensure-cell network guard))]
                            [(runner/completed-network (runner/run-network [p] network)) (conj (vec sources) guard)])
                          [network sources])
                        make (requiring-resolve 'propagators.experimental.ttms-primitives/scalar-operator)
                        install (requiring-resolve 'propagators.compiler-2.model.operator-value/operator-install)
                        op (make [:extension-support id]
                                 (prop/primitive-propagator [:extension-support id] (fn [& _] base)))
                        [network props _] ((install op) network (vec sources) id)]
                    (runner/completed-network (runner/run-network props network))))
                network (:derivations parts)))
        (let [ready (filter (fn [[_ frame]]
                              (and (contains? (net/net-env network) (:parent frame))
                                   (not= :unknown (:status (env/lexical-binding-status network '__scope_probe (:parent frame)))))) pending)]
          (when (empty? ready) (throw (ex-info "Unknown or cyclic frame parents" {:frames (keys pending)})))
          (recur
           (reduce (fn [network [id {:keys [parent bindings]}]]
                     (when (contains? (net/net-env network) id) (throw (ex-info "Frame already exists" {:id id})))
                     (let [declared (env/declare-child network parent id bindings)]
                       (runner/completed-network (runner/run-network (:props declared) (:net declared)))))
                   network ready)
           (apply dissoc pending (map first ready))))))))

(defn- compile-file-form [runtime file form]
  (let [head (when (seq? form) (first form))]
    (case head
      load-lain
      (let [imported (second form)
            text (get-in runtime [::catalog :files imported])]
        (when-not (string? text)
          (throw (ex-info "Source import must be snapshotted before compilation"
                          {:file file :import imported})))
        (compile-file runtime [imported text]))
      load-primitive-environment
      (let [[_ module entry revision] form]
        (when-not (contains? (get-in runtime [::catalog :imports]) [module entry (or revision 0)])
          (throw (ex-info "Primitive import must be prepared before compilation"
                          {:file file :module module :entry entry})))
        runtime)
      (compile-declaration runtime file form))))

(defn- compile-file [runtime [file text]]
  (cond
    (contains? (::compiled-files runtime) file) runtime
    (contains? (::compiling-files runtime) file)
    (throw (ex-info "Cyclic source import" {:file file}))
    :else
    (-> (reduce (fn [current form] (compile-file-form current file form))
                (update runtime ::compiling-files (fnil conj #{}) file)
                (source/read-source-forms text))
        (update ::compiling-files disj file)
        (update ::compiled-files (fnil conj #{}) file))))

(defn- refresh-observers [runtime]
  ;; Source publication must finish observer wiring while still pure. Host
  ;; delivery remains post-commit; do not use the IO-performing loader adapter.
  (let [[root-ids prepared] (observer/refresh-roots (:program/net runtime))
        ids (into (vec root-ids) (vals (get (net/network-dict-entry prepared [:network :name-bindings]) [:session :samples])))
        [observed network] (temperature/run-propagators runtime :relationship/refresh prepared ids)]
    (assoc observed :program/net network)))

(defn- prepare-extension [session extension]
  (when-not (satisfies? ExtensionDeclaration extension)
    (throw (ex-info "Expected a compiler extension declaration" {})))
  (let [identity (session-identity session)
        before (session-declarations session)
        parts (extension-parts extension)
        prior (get-in before [:extensions (extension-id extension)])
        joined (join-parts before parts)
        declarations (assoc-in joined [:extensions (extension-id extension)] parts)]
    (when (and prior (not= prior parts))
      (throw (ex-info "Extension identity already declared" {:id (extension-id extension)})))
    (if (= (select-keys before [:primitives :dependencies :files :imports :cells :frames :targets :supports :derivations :scoped-primitives :interfaces :evaluation])
           (select-keys joined [:primitives :dependencies :files :imports :cells :frames :targets :supports :derivations :scoped-primitives :interfaces :evaluation]))
      (if prior
        session
        (ImmutableLainSession. identity declarations (compiler-state session)))
      (let [new-bindings (apply dissoc (:primitives parts) (keys (:primitives before)))
            new-files (apply dissoc (:files parts) (keys (:files before)))
            candidate (-> (compiler-state session)
                          (assoc ::catalog joined)
                          (install-cells-and-frames {:cells (apply dissoc (:cells parts) (keys (:cells before)))
                                                     :frames (apply dissoc (:frames parts) (keys (:frames before)))
                                                     :derivations (apply dissoc (:derivations parts) (keys (:derivations before)))})
                          (install-bindings new-bindings
                                            {:run-id (:run-id identity)
                                             :dependency-ids (set (keys (:dependencies joined)))})
                          (install-scoped-bindings
                           (apply dissoc (:scoped-primitives parts) (keys (:scoped-primitives before)))
                           {:run-id (:run-id identity)
                            :dependency-ids (set (keys (:dependencies joined)))})
                          (#(reduce compile-file % (sort-by key new-files)))
                          refresh-observers
                          effects/record-boundary-effects)]
        (ImmutableLainSession. identity declarations candidate)))))

(defn- advance-cell-session [session inputs]
  (let [inputs (mapv normalize-input inputs)
        before (compiler-state session)
        tick (inc (get before :runtime/commit-tick 0))
        prepared (-> before
                     (assoc :runtime/commit-tick tick)
                     (input/assoc-program-commit-tick tick)
                     (input/apply-program-updates
                      (mapv #(select-keys % [:cell-id :update]) inputs))
                     refresh-observers
                     effects/record-boundary-effects)
        current (cond-> prepared
                  (get-in (session-declarations session) [:options :record-inputs?])
                  (update :runtime/inputs (fnil into []) inputs))
        errors (drop (count (:runtime/errors before)) (:runtime/errors current))]
    (when (seq errors)
      (throw (ex-info "Compiler session propagation failed" {:errors (vec errors)})))
    (turn (ImmutableLainSession. (session-identity session)
                                 (session-declarations session) current)
          [] (effects/outbox-effects (:program/net current)))))

(defn admission-input
  "An immutable source admission declaration, produced by a Lain network.
  A response identity groups a FIFO stream; rejection stops its remaining forms."
  [declaration receipt-id response-id]
  (when-not (and (map? declaration) (:id declaration) (ids/node-id? receipt-id)
                 (some? response-id))
    (throw (ex-info "Invalid admission input" {})))
  {:runtime/input :admission :declaration declaration
   :receipt-id receipt-id :response-id response-id})

(defn- advance-admission [session {:keys [declaration receipt-id response-id]}]
  (when-not (contains? (net/net-env (network session)) receipt-id)
    (throw (ex-info "Admission receipt must be declared and wired before submission"
                    {:receipt-id receipt-id})))
  (let [id (:id declaration)
        prior (get-in (compiler-state session) [::admissions id])
        stopped? (contains? (::stopped-responses (compiler-state session)) response-id)
        [candidate receipt]
        (cond
          prior
          (if (= declaration (:declaration prior))
            [session (:receipt prior)]
            [session {:id id :status :rejected :message "Conflicting admission identity"}])
          stopped? [session {:id id :status :skipped :message "Response stopped after failed form"}]
          :else
          (try
            (when-not (= 1 (count (:files declaration)))
              (throw (ex-info "Admission requires one source file" {})))
            (when-not (= 1 (count (source/read-source-forms (first (vals (:files declaration))))))
              (throw (ex-info "Admission requires one complete form" {})))
            [(prepare-extension session (session-extension declaration))
             {:id id :status :admitted}]
            (catch Exception error
              [session {:id id :status :rejected :message (ex-message error)}])))
        runtime (cond-> (compiler-state candidate)
                  (nil? prior) (assoc-in [::admissions id]
                                         {:declaration declaration :receipt receipt})
                  (= :rejected (:status receipt))
                  (update ::stopped-responses (fnil conj #{}) response-id))
        candidate (ImmutableLainSession. (session-identity candidate)
                                         (session-declarations candidate) runtime)]
    (advance-cell-session candidate
                          [(cell-input receipt-id
                                       (collection/content
                                        (publication/next-source-datum receipt-id
                                                                       (net/network-cell-strongest (network candidate) receipt-id)
                                                                       receipt :active)))])))

(defn publication-input [cell-id base]
  {:runtime/input :publication :cell-id cell-id :base base})

(defn interfaces
  "Read static and Lain-connected interface declarations without advancing a session."
  [session]
  (merge-with (fn [a b]
                (when-not (= a b) (throw (ex-info "Conflicting session interface declarations" {})))
                a)
              (:interfaces (session-declarations session))
              (connected/interfaces (network session))))

(defn interface-cell
  "Resolve a declared interface against compiled Lain topology. No cells are created."
  [session interface]
  (let [{:keys [cell binding field reference?]} (get (interfaces session) interface)
        v (when binding (connected/freeze (network session) (binding-value session binding)))
        resolved (cond cell cell field (get v field) reference? v binding (binding-cell session binding))]
    (when-not (and (ids/node-id? resolved) (contains? (net/net-env (network session)) resolved))
      (throw (ex-info "Session interface is unresolved" {:interface interface})))
    resolved))

(defn- publish-value [session cell-id base]
  (advance-cell-session session
                        [(cell-input cell-id (collection/content
                                              (publication/next-source-datum cell-id
                                                                             (net/network-cell-strongest (network session) cell-id)
                                                                             base :active)))]))

(defn- advance-connected [session {:keys [kind values receipt token]}]
  (if (contains? (::connected (compiler-state session)) token)
    (turn session [] [])
    (let [result
          (case kind
            :admission
            (if (= :idle (first values))
              (publish-value session receipt {:id :idle :status :idle})
              (advance-admission session {:declaration (first values)
                                          :response-id (second values) :receipt-id receipt}))
            :feedback
            (let [[target expected next-state] values
                  current (connected/freeze (network session)
                                            (net/network-cell-strongest (network session) target))
                  status (cond
                           (not (and (nat-int? expected) (nat-int? (:version next-state)))) :invalid-version
                           (and (= current next-state) (= expected (:version current))) :unchanged
                           (and (= current next-state) (= (inc expected) (:version next-state))) :replayed
                           (not= expected (:version current)) :stale-version
                           (not= (inc expected) (:version next-state)) :invalid-version
                           :else :accepted)
                  candidate (if (= status :accepted)
                              (:session (publish-value session target next-state)) session)]
              (publish-value candidate receipt {:status status :version (:version next-state)})))
          candidate (:session result)]
      (turn (ImmutableLainSession. (session-identity candidate)
                                   (session-declarations candidate)
                                   (update (compiler-state candidate) ::connected (fnil conj #{}) token)) [] []))))

(defn connected-inputs
  "Collect declarations made available by a settled immutable network."
  [current]
  (keep (fn [{:keys [receipt values] :as declaration}]
          (let [token [receipt values]]
            (when-not (contains? (::connected (compiler-state current)) token)
              (assoc declaration :runtime/input :connected :token token))))
        (connected/declarations (network current))))

(defn- advance-session [session inputs]
  (let [result
        (reduce (fn [result batch]
                  (let [current (:session result)]
                    (case (:runtime/input (first batch))
                      :connected (reduce (fn [turn input] (advance-connected (:session turn) input)) result batch)
                      :publication (reduce (fn [turn input] (publish-value (:session turn) (:cell-id input) (:base input))) result batch)
                      :admission (reduce (fn [turn input] (advance-admission (:session turn) input)) result batch)
                      (advance-cell-session current batch))))
                (advance-cell-session session [])
                (partition-by :runtime/input inputs))
        current (:session result)
        pending (connected-inputs current)]
    (turn current pending (effects/outbox-effects (network current)))))

(declare create-session)

(extend-type ImmutableLainSession
  CompilerSession
  (environment [session] (:program/env (.-state ^ImmutableLainSession session)))
  (network [session] (:program/net (.-state ^ImmutableLainSession session)))
  (advance [session inputs] (bounded session #(advance-session session inputs)))
  SessionView
  (session-identity [session] (.-identity ^ImmutableLainSession session))
  (compiler-state [session] (.-state ^ImmutableLainSession session))
  (session-declarations [session] (.-declarations ^ImmutableLainSession session))
  SessionExtension
  (extend-session [session extension]
    ;; Imported evaluation declarations apply while the importing file compiles,
    ;; as well as to later publications. Conflicts fail before any evaluation.
    (let [configured (ImmutableLainSession.
                      (session-identity session)
                      (join-parts (session-declarations session)
                                  {:evaluation (:evaluation (extension-parts extension))})
                      (compiler-state session))]
      (bounded configured #(prepare-extension session extension))))
  SessionDependencies
  (dependency-declarations [session] (:dependencies (session-declarations session)))
  OpenSession
  (source-files [session] (:files (session-declarations session)))
  (rerun [session fresh-run-id]
    (when (= fresh-run-id (:run-id (session-identity session)))
      (throw (ex-info "Rerun requires a fresh run identity" {})))
    (let [identity (assoc (session-identity session) :run-id fresh-run-id)
          fresh (create-session
                 (assoc identity
                        :native-extensions (:bootstrap (session-declarations session))
                        :runtime-options (:options (session-declarations session))
                        :propagation-options (:limits (session-declarations session))))
          parts (select-keys (session-declarations session) [:primitives :dependencies :files :imports :cells :frames :targets :supports :derivations :scoped-primitives :interfaces :evaluation])]
      (extend-session fresh (session-extension (assoc parts :id ::rerun))))))

(defn create-session
  "Create an independent compiler session. Explicit identities support reproducible callers."
  ([] (create-session {:id (UUID/randomUUID) :run-id (UUID/randomUUID)}))
  ([{:keys [id run-id native-extensions runtime-options propagation-options] :as options}]
   (when-not (and id run-id)
     (throw (ex-info "Session and run identities are required"
                     {:identity (select-keys options [:id :run-id])})))
   (let [bootstrap (vec native-extensions)
         limits (merge {:max-steps 100000 :timeout-ms 60000} propagation-options)
         _ (when-not (every? pos-int? (vals (select-keys limits [:max-steps :timeout-ms])))
             (throw (ex-info "Positive propagation limits required" {:limits limits})))
         initial (-> (state/empty-state)
                     (assoc :runtime/options (or runtime-options {}))
                     (update :program/net nb/ensure-cell (runtime-ids/boundary-outbox-id)))
         prepared (reduce (fn [current extension]
                            (native-extension/install-session-extension
                             current extension {:outbox-id (runtime-ids/boundary-outbox-id)}))
                          initial bootstrap)]
     (ImmutableLainSession. (select-keys options [:id :run-id])
                            {:primitives {} :dependencies {} :files {} :imports {} :extensions {}
                             :bootstrap bootstrap :options (or runtime-options {}) :limits limits}
                            prepared))))

(defn with-compiler-state
  "Host inspection/boundary adapter. Domain state remains an immutable value."
  [session runtime-state]
  (when-not (and (map? runtime-state) (net/network? (:program/net runtime-state)))
    (throw (ex-info "Expected compiler runtime state" {})))
  (ImmutableLainSession. (session-identity session) (session-declarations session) runtime-state))
