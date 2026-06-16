(ns propagators.explain.llm
  "Local LLM adapters for evidence-backed explanations."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def default-model
  (or (System/getenv "PROPAGATORS_EXPLAIN_MODEL")
      "qwen3.5:9b"))

(defn explain-with-ollama
  ([prompt]
   (explain-with-ollama prompt {}))
  ([prompt {:keys [model]
            :or {model default-model}}]
   (let [{:keys [exit out err]} (shell/sh "ollama" "run" model :in prompt)]
     (if (zero? exit)
       {:ok? true
        :model model
        :explanation (str/trim out)}
       {:ok? false
        :model model
        :error (str/trim err)
        :exit exit}))))
