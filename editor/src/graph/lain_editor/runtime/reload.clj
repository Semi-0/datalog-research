(ns graph.lain-editor.runtime.reload
  "Plan deferred callable revisions for a live editor owner."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.environment-io :as env-io]
            [propagators.compiler-2.runtime.session.program.source :as program]
            [propagators.compiler-2.session :as session]
            [propagators.compiler-2.model.rest-parameters :as parameters]
            [propagators.ids :as ids]))

(defn prepare
  "Refresh the already committed source snapshots without evaluating a native
  factory. Changes to the import graph require a separate migration."
  [root committed after]
  (let [parts (session/extension-parts committed)
        base "editor/base.lain"
        directory (.toPath (.getCanonicalFile (io/file root)))
        read-source (fn [path]
                      (let [file (.toFile (.normalize (.resolve directory path)))
                            canonical (.getCanonicalFile file)]
                        (when-not (.startsWith (.toPath canonical) directory)
                          (throw (ex-info "Source import escapes the project root" {:file path})))
                        (slurp canonical)))]
    (doseq [[path _ digest] (distinct (vals (:imports parts)))]
      (when-not (= digest (env-io/file-digest (io/file path)))
        (throw (ex-info "A native primitive import changed; its compiled implementation is pinned to the running session"
                        {:unsupportedReload true :file path}))))
    (session/session-extension
      (assoc parts :id "base"
             :files (into {} (map (fn [[path _]] [path (if (= base path) after (read-source path))]))
                          (:files parts))))))

(defn- editable [form]
  (when (and (seq? form) (= 'define (first form)) (= 3 (count form))
             (symbol? (second form)))
    (let [body (nth form 2)]
      (when (or (number? body) (string? body) (boolean? body) (keyword? body)
                (and (seq? body) (#{'network 'quote} (first body))))
        (second form)))))

(defn- declarations [forms]
  (reduce (fn [result form]
            (if-let [name (editable form)]
              (if (contains? result name)
                (throw (ex-info "Live reload requires a unique definition for each edited name" {:symbol name}))
                (assoc result name form))
              result))
          {} forms))

(defn- interface-of [[_ _ body]]
  (when (and (seq? body) (= 'network (first body)))
    (let [{:keys [required rest]} (parameters/parameter-spec (vec (second body)))]
      [(count required) (some? rest)])))

(defn changes [before after]
  (let [prior (session/extension-parts before) updated (session/extension-parts after)
        read-files (fn [parts] (into (sorted-map)
                                 (map (fn [[path src]] [path (program/read-source-forms src)]))
                                 (:files parts)))
        old-files (read-files prior) new-files (read-files updated)
        old-forms (mapcat val old-files) new-forms (mapcat val new-files)
        old (declarations old-forms) new (declarations new-forms)
        deleted (set/difference (set (keys old)) (set (keys new)))
        skeleton (fn [forms]
                   (keep (fn [form]
                           (if-let [name (editable form)]
                             (when (contains? old name) [:binding name])
                             [:installed form])) forms))]
    (when (seq deleted)
      (throw (ex-info "Live reload cannot remove existing bindings" {:unsupportedReload true :symbols deleted})))
    (when-not (and (= (keys old-files) (keys new-files))
                   (every? (fn [file] (= (skeleton (old-files file)) (skeleton (new-files file))))
                           (keys old-files))
                   (= (set (keys (:primitives prior))) (set (keys (:primitives updated)))))
      (throw (ex-info "Live reload supports network and literal definitions; the edit changes installed wiring"
                      {:unsupportedReload true})))
    (doseq [[name form] new
            :when (and (contains? old name) (not= (interface-of form) (interface-of (old name))))]
      (throw (ex-info "Live reload cannot change an installed callable's argument interface"
                      {:unsupportedReload true :symbol name})))
    (loop [changed (into #{} (keep (fn [[name form]] (when (not= form (get old name)) name))) new)]
      ;; Re-capture dependent closures in the same child. Symbol intersection
      ;; can re-capture a shadowing closure unnecessarily, which is harmless.
      (let [more (into changed (for [[name form] new
                                     :when (some changed (program/executable-nodes (nth form 2)))] name))]
        (if (= changed more)
          (filterv #(contains? changed (editable %)) new-forms)
          (recur more))))))

(defn revision [current forms]
  (let [id (str (random-uuid)) frame (ids/new-node-id)
        bindings (mapv (fn [[_ name]]
                         [name (env/cell-binding (basis/stable-node-id :editor-reload frame name))]) forms)
        file (str "editor/revisions/" id ".lain")]
    {:frame frame
     :extension (session/session-extension
                  {:id id :frames {frame {:parent (session/environment current) :bindings bindings}}
                   :target frame :files {file (str/join "\n" (map pr-str forms))}})}))
