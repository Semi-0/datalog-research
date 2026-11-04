(ns graph.lain-editor.workspace.file-bridge
  "Explicit adapters between file/editor commands and existing runtime owners."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [graph.lain-editor.workspace.file-service :as files]
            [graph.lain-editor.workspace.commands :as commands]
            [graph.lain-editor.runtime.session :as runtime]))

(defn- captured [service {:keys [path version]}]
  (let [snapshot (files/read-file service path)]
    (when-not (:exists snapshot) (throw (ex-info "Source file no longer exists" {:path path})))
    (when (and version (not= version (:version snapshot)))
      (throw (ex-info "Source file changed; inspect it before extending" {:path path :conflict true})))
    snapshot))

(defn editor-command! [registry service {:keys [op] :as request}]
  (let [root (:root service)]
    (case op
      "editor/list" (commands/command! registry root {:op "workspaces"})
      "editor/status" (commands/command! registry root (assoc request :op "editor-status"))
      "editor/open-file" (commands/command! registry root (assoc request :op "load-file"))
      "editor/extend-code"
      (do
        (when-not (and (string? (:source request)) (not (str/blank? (:source request))))
          (throw (ex-info "Extension source is required" {})))
        (commands/command! registry root (assoc request :op "editor-command" :action "extend-code")))
      "editor/extend-file"
      (let [snapshot (captured service request)]
        (commands/command! registry root
                           (assoc request :op "editor-command" :action "extend-file"
                                  :version (:version snapshot) :source (:source snapshot))))
      "editor/extend-primitives"
      (let [snapshot (captured service request)]
        (when-not (and (str/ends-with? (:path request) ".clj")
                       (string? (:entry request)) (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_!?*+.-]+" (:entry request)))
          (throw (ex-info "Primitive environment requires a .clj file and namespace/entry name" {})))
        (commands/command! registry root
                           (assoc request :op "editor-command" :action "extend-primitives"
                                  :version (:version snapshot))))
      (throw (ex-info "Unknown editor bridge operation" {:op op})))))

(defn extend-file! [editors service {:keys [entry revision] :as request}]
  (let [snapshot (captured service request)
        source (if entry
                 (do
                   (when-not (and (str/ends-with? (:path request) ".clj")
                                  (string? entry) (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_!?*+.-]+" entry))
                     (throw (ex-info "Invalid primitive entry" {})))
                   (pr-str (list 'load-primitive-environment (:path request) (keyword entry) (or revision 0))))
                 (:source snapshot))]
    (let [loader (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))]
      (.addURL loader (.toURL (.toURI (io/file (str (:root service))))))
      (with-bindings {clojure.lang.Compiler/LOADER loader}
        (binding [runtime/*source-root* (str (:root service))
                  runtime/*source-check* #(captured service (assoc request :version (:version snapshot)))]
          (assoc (runtime/extend! editors (assoc request :source source))
                 :fileSnapshot (select-keys snapshot [:path :version])))))))
