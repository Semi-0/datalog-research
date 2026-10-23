(ns gx10-benchmark.editor
  "A separate loopback editor; preserves the user's existing editor workspace."
  (:require [graph.lain-editor.server :as server]))

(defn -main [& [port]]
  (let [running (server/start! (Long/parseLong (or port "4790")))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close running)))
    (println (str "GX10 Clojure/Lain harness: http://127.0.0.1:" (:port running)))
    @(promise)))
