(ns gx10-benchmark.main
  "Local controller demonstration; model/backend stages are explicitly simulated."
  (:require [clojure.java.io :as io]
            [gx10-benchmark.controller :as controller]
            [gx10-benchmark.process :as process]
            [gx10-benchmark.session :as session])
  (:import [java.nio.file Files]))

(defn demo-effects []
  (let [ports (into {} (for [stage (remove #{:save-result} controller/expected-stages)]
                         [stage (fn [_] {:ok? true :verified? true :approved? true
                                         :mode :simulated :stage stage})]))]
    (assoc ports
           :execute (fn [_]
                      {:ok? true :mode :simulated
                       :execution {:events [{:type :anti-repeat :action :corrected
                                             :kind :reasoning-repeat :request 1}
                                            {:type :assistant-end :stop :aborted :request 1}
                                            {:type :assistant-end :stop :stop :request 2}
                                            {:type :agent-end}]
                                   :exit-code 0 :final-response? true}})
           :evaluate
           (fn [context]
             (let [directory (io/file (:directory context) "evaluation")
                   _ (.mkdir directory)
                   java (str (System/getProperty "java.home") "/bin/java")
                   program "(require '[clojure.test :as t]) (t/deftest fixture (t/is (= [1 2] (vec (distinct [1 2 1]))))) (let [r (t/run-tests)] (System/exit (+ (:fail r) (:error r))))"
                   receipt (process/run! [java "-cp" (System/getProperty "java.class.path")
                                          "clojure.main" "-e" program] directory {:timeout-ms 120000})
                   text (slurp (:stdout receipt))
                   counts (re-find #"(\d+) assertions.*\n(\d+) failures, (\d+) errors" text)]
               (when-not counts (throw (ex-info "Missing assertion summary" {:receipt receipt})))
               {:ok? true :evaluation {:assertions (Long/parseLong (nth counts 1))
                                      :failures (Long/parseLong (nth counts 2))
                                      :errors (Long/parseLong (nth counts 3))
                                      :exit-code (:exit-code receipt) :expected-assertions 1}
                :evaluation-command receipt})))))

(defn -main [& [command output]]
  (case command
    "plan" (prn (session/lifecycle))
    "demo" (let [parent (if output (io/file output)
                           (.toFile (Files/createTempDirectory "gx10-clojure-demo-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))
                 cells (mapv #(controller/cell "local-demo" % {:mode :simulated :context 131072 :temperature 1.0})
                             ["distinct" "order" "events"])
                 root (io/file parent "queue")
                 results (controller/run-queue! cells root (demo-effects))]
             (prn {:mode :simulated :model-inference false :real-clojure-subprocess true
                   :output (.getCanonicalPath root)
                   :results (mapv #(select-keys % [:outcome :execution :correct? :cleanup-verified?]) results)})
             (shutdown-agents))
    (throw (ex-info "Usage: clojure -M -m gx10-benchmark.main plan|demo [EXISTING_PARENT]" {}))))
