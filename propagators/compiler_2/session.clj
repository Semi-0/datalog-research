(ns propagators.compiler-2.session
  "Immutable Lain compiler sessions. No host callback or file IO runs here."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.env.binding :as binding]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
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
            [propagators.runner :as runner])
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
    (binding [runner/*advance-transform*
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
  [{:keys [id primitives dependencies files imports]}]
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
    (CompilerExtension. id {:primitives primitives
                            :dependencies (into {} dependencies)
                            :files files :imports (into {} imports)})))

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
          base [:primitives :dependencies :files :imports]))

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

(defn- install-bindings [runtime bindings context]
  (doseq [sym (keys bindings)
          :let [definition (get-in runtime [::definitions sym])]
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
                                id (env/binding-id descriptor)]
                            (when (and (ids/node-id? id)
                                       (value/nothing?
                                        (first (basis/primitive-input-values
                                                (:program/net runtime) [id]))))
                              [sym {:cell-id id :update implementation}])))
                        pairs)
          pending-by-name (into {} pending)
          aliases (mapv (fn [[sym implementation]]
                          (if-let [reserved (get pending-by-name sym)]
                            [sym (binding/cell-binding (:cell-id reserved))]
                            [sym implementation])) pairs)
          declared (env/declare-bindings (:program/net runtime) (:program/env runtime) aliases)
          settled (runner/completed-network
                   (runner/run-network (:props declared) (:net declared)))]
      ;; Reuse the reserved value cell: existing GUR applications already watch it.
      (input/apply-program-updates (assoc runtime :program/net settled)
                                   (mapv second pending)))))

(defn- definition-name [form]
  (when (and (seq? form) (= 'define (first form)))
    (let [name (second form)]
      (if (seq? name) (first name) name))))

(declare compile-file)

(defn- compile-declaration [runtime file form]
  (let [name (definition-name form)
        prior (get-in runtime [::definitions name])]
    (when (and prior (not= prior form) (> (count prior) 2))
      (throw (ex-info "Conflicting source definition" {:file file :symbol name})))
    (if (and name (= prior form))
      runtime
      (let [{candidate :state error :error}
            (environment-io/compile-hidden-form runtime "compiler-session" form {:file file})]
        (when error (throw (ex-info "Compiler extension failed" error)))
        (cond-> candidate name (assoc-in [::definitions name] form))))))

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
    (if (= (select-keys before [:primitives :dependencies :files :imports])
           (select-keys joined [:primitives :dependencies :files :imports]))
      (if prior
        session
        (ImmutableLainSession. identity declarations (compiler-state session)))
      (let [new-bindings (apply dissoc (:primitives parts) (keys (:primitives before)))
            new-files (apply dissoc (:files parts) (keys (:files before)))
            candidate (-> (compiler-state session)
                          (assoc ::catalog joined)
                          (install-bindings new-bindings
                                            {:run-id (:run-id identity)
                                             :dependency-ids (set (keys (:dependencies joined)))})
                          (#(reduce compile-file % (sort-by key new-files)))
                          effects/record-boundary-effects)]
        (ImmutableLainSession. identity declarations candidate)))))

(defn- advance-session [session inputs]
  (let [inputs (mapv normalize-input inputs)
        before (compiler-state session)
        tick (inc (get before :runtime/commit-tick 0))
        prepared (-> before
                     (assoc :runtime/commit-tick tick)
                     (input/apply-program-updates
                      (mapv #(select-keys % [:cell-id :update]) inputs))
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
  (extend-session [session extension] (bounded session #(prepare-extension session extension)))
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
          parts (select-keys (session-declarations session) [:primitives :dependencies :files :imports])]
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
