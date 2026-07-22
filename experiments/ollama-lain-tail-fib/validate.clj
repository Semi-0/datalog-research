(ns validate-ollama-lain-tail-fib
  (:require [clojure.java.io :as io]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.network :as net]))

(def forbidden-patterns
  [#"\(\s*recur\b"
   #"\(\s*def-recursive\b"
   #"\(\s*loop\b"
   #"\(\s*if\b"
   #"\(\s*cond\b"
   #"\(\s*fn\b"])

(defn- recursive-call-count
  [name form]
  (count (filter #(and (seq? %) (= name (first %)))
                 (tree-seq coll? seq form))))

(defn- tail-call-under-when?
  [name form]
  (boolean
   (some (fn [node]
           (and (seq? node)
                (= 'when (first node))
                (let [tail-form (last (drop 2 node))]
                  (and (seq? tail-form)
                       (= name (first tail-form))))))
         (tree-seq coll? seq form))))

(defn- recursive-definitions
  [forms]
  (keep (fn [form]
          (when (and (seq? form) (= 'def-net (first form)))
            (let [name (second form)
                  calls (recursive-call-count name form)]
              (when (pos? calls)
                {:name name
                 :calls calls
                 :tail-call-under-when? (tail-call-under-when? name form)}))))
        forms))

(defn- load-attempt
  [path]
  (let [session (runtime/new-session)
        client-id (str "ollama-validator-" (random-uuid))]
    (runtime/handle-command! session
                             {:op :tui/register
                              :client-id client-id
                              :mode :versioned-premise})
    (let [response
          (runtime/handle-command!
           session
           {:op :tui/commit-version
            :commit-id (str (random-uuid))
            :client-id client-id
            :index 0
            :expected-version nil
            :text (str "(load-lain " (pr-str path) " 0)")})
          network (:program/net @session)
          result-id (env/resolve-binding-id network (:program/env @session)
                                            'fib-result)]
      {:load-ok? (:ok response)
       :load-response (when-not (:ok response) response)
       :result (when result-id
                 (net/network-cell-strongest network result-id))
       :runtime-errors (:runtime/errors @session)})))

(defn validate-attempt
  [path]
  (try
    (let [source (slurp (io/file path))
          forms (loader/source-forms source)
          recursive (vec (recursive-definitions forms))
          forbidden (filterv #(re-find % source) forbidden-patterns)
          runtime-result (load-attempt (.getCanonicalPath (io/file path)))]
      (merge {:path path
              :parse-ok? true
              :form-count (count forms)
              :forbidden? (boolean (seq forbidden))
              :recursive-definitions recursive
              :single-tail-recursion?
              (boolean (some #(and (= 1 (:calls %))
                                   (:tail-call-under-when? %))
                             recursive))}
             runtime-result
             {:pass? (and (empty? forbidden)
                          (some #(and (= 1 (:calls %))
                                      (:tail-call-under-when? %))
                                recursive)
                          (:load-ok? runtime-result)
                          (= 55 (:result runtime-result))
                          (empty? (:runtime-errors runtime-result)))}))
    (catch Throwable t
      {:path path
       :parse-ok? false
       :pass? false
       :error (.getMessage t)})))

(doseq [path *command-line-args*]
  (prn (validate-attempt path)))
