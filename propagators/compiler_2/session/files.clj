(ns propagators.compiler-2.session.files
  "Trusted host preparation of a frozen file/primitive declaration bundle.
  Reads occur before the immutable compiler transaction, never during propagation."
  (:require [clojure.java.io :as io]
            [propagators.compiler-2.runtime.session.environment-io :as environment-io]
            [propagators.compiler-2.runtime.session.program.source :as source]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.session.operators :as operators]))

(defn- within-root [root file]
  (let [candidate (environment-io/canonical-file
                   (if (.isAbsolute (io/file file)) (io/file file) (io/file root file)))]
    (when-not (.startsWith (.toPath candidate) (.toPath root))
      (throw (ex-info "Source import escapes its allowed root" {:file file})))
    candidate))

(defn- snapshot-module! [root file entry revision factory-arguments]
  (when-not (qualified-keyword? entry)
    (throw (ex-info "Primitive entry must be a qualified keyword" {:entry entry})))
  (let [canonical (within-root root file)
        digest (environment-io/file-digest canonical)
        entry-symbol (symbol (namespace entry) (name entry))
        arguments (get factory-arguments entry [])
        artifact (cond-> [(.getPath canonical) entry digest]
                   (seq arguments) (conj arguments))
        factory (requiring-resolve entry-symbol)
        declaration (apply (var-get factory) arguments)
        bindings (if (map? declaration) (:bindings declaration) declaration)
        evaluation (when (map? declaration) (:evaluation declaration))]
    (when-not (sequential? bindings)
      (throw (ex-info "Primitive factory must return ordered bindings" {:entry entry})))
    {:id [file entry (or revision 0)]
     :artifact artifact
     :evaluation evaluation
     :bindings (mapv (fn [[sym implementation :as binding]]
                       (when-not (and (= 2 (count binding)) (symbol? sym))
                         (throw (ex-info "Invalid primitive binding" {:entry entry})))
                       [sym (operators/frozen-primitive
                             (conj artifact sym) implementation)])
                     bindings)}))

(defn- join-evaluation [existing additions]
  (reduce-kv (fn [result k v]
               (when (and (contains? result k) (not= (get result k) v))
                 (throw (ex-info "Conflicting imported evaluator" {:key k})))
               (assoc result k v)) (or existing {}) (or additions {})))

(declare snapshot-file!)

(defn- snapshot-form! [root prepared form]
  (if (seq? form)
    (case (first form)
      load-lain
      (let [[_ file] form]
        (when-not (string? file)
          (throw (ex-info "Source import must name a file" {:form form})))
        (snapshot-file! root prepared file))
      load-primitive-environment
      (let [[_ file entry revision] form]
        (if (contains? (:imports prepared) [file entry (or revision 0)])
          prepared
          (let [imported (snapshot-module! root file entry revision (:factory-arguments prepared))]
            (reduce (fn [result [sym binding]]
                      (when (and (contains? (:primitives result) sym)
                                 (not= (get-in result [:primitives sym]) binding))
                        (throw (ex-info "Conflicting prepared primitive" {:symbol sym})))
                      (assoc-in result [:primitives sym] binding))
                    (-> prepared
                        (assoc-in [:imports (:id imported)] (:artifact imported))
                        (update :evaluation join-evaluation (:evaluation imported)))
                    (:bindings imported)))))
      prepared)
    prepared))

(defn- snapshot-file! [root prepared file]
  (if (contains? (:visited prepared) file)
    prepared
    (let [text (if (contains? (:files prepared) file)
                 (get-in prepared [:files file])
                 (slurp (within-root root file)))
          seeded (-> prepared (update :visited conj file) (assoc-in [:files file] text))]
      (reduce (partial snapshot-form! root) seeded (source/read-source-forms text)))))

(defn prepare-extension!
  "Prepare trusted local declarations within root. Native namespaces are required,
  never reloaded or globally rebound. Existing session bindings retain fixed values.
  Inline :files and :read-files can be combined; imports are snapshotted transitively.
  A native factory returns ordered bindings, or {:bindings pairs :evaluation policy}.
  Evaluation declarations are session-local and conflict-checked."
  [{:keys [root id primitives dependencies files read-files factory-arguments target support-cell cells frames imports]
    :or {root "." files {} read-files [] factory-arguments {}}}]
  (when-not (and (map? factory-arguments)
                 (every? (fn [[entry arguments]]
                           (and (qualified-keyword? entry) (vector? arguments))) factory-arguments))
    (throw (ex-info "Factory arguments must map qualified entries to explicit argument vectors" {})))
  (let [root (environment-io/canonical-file root)
        prepared (reduce (partial snapshot-file! root)
                         {:files (into {} files) :primitives (into {} primitives)
                          :imports (into {} imports) :visited #{} :factory-arguments factory-arguments}
                         (concat (sort (keys files)) read-files))]
    (session/session-extension
     {:id id :target target :support-cell support-cell :cells cells :frames frames
      :primitives (:primitives prepared) :dependencies dependencies
      :files (:files prepared) :imports (:imports prepared) :evaluation (:evaluation prepared)})))
