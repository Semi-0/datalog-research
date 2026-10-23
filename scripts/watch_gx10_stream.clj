;; From the repository root:
;; clojure -M:gx10/watch
;; If Java is not on your Mac's PATH, prefix JAVA_HOME=/opt/homebrew/opt/openjdk.
;; Optional argument: distinct, ordering, events, an absolute remote JSONL path,
;; or - to render JSONL from stdin. Default: follow all three solo tasks.
(ns watch-gx10-stream
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def queue-root (System/getProperty "gx10.stream.root"
                 "/home/lain/experiments/gx10-qwen38-trial-4/solo"))

(defn shell-quote [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn stream-path [task]
  (let [directory (if (= task "ordering") "order" task)]
    (str queue-root "/" directory "/attempt/artifacts/events.jsonl")))

(defn tail-command [selection]
  (let [paths (cond
                (nil? selection) (mapv stream-path ["distinct" "ordering" "events"])
                (#{"distinct" "order" "ordering" "events"} selection) [(stream-path selection)]
                (str/starts-with? selection "/") [selection]
                :else (throw (ex-info "Use distinct, ordering, events, an absolute path, or -" {})))
        socket "/tmp/codex-gx10-ac8b-20261020.sock"
        ssh (cond-> ["ssh" "-o" "ConnectTimeout=10"]
              (.exists (io/file socket)) (into ["-S" socket]))]
    ;; GX10's standalone alias sets this property to avoid a nested SSH session.
    (if (= "true" (System/getProperty "gx10.stream.local"))
      (into ["tail" "-n" "0" "-F" "--"] paths)
      (into ssh ["lain@gx10-ac8b.local"
                 (str "tail -n 0 -F -- " (str/join " " (map shell-quote paths)))]))))

(defn render! [line]
  (cond
    (str/blank? line) nil
    (str/starts-with? line "==>") (println (str "\n" line))
    :else
    (let [event (try
                  (get (json/read-str line) "event")
                  (catch Exception error
                    (binding [*out* *err*]
                      (println "[invalid JSONL]" (.getMessage error)))
                    nil))]
      (case (get event "type")
        "message_update" (when-let [delta (get-in event ["assistantMessageEvent" "delta"])]
                           (print delta))
        "tool_execution_start" (println (str "\n[tool: " (get event "toolName") "]"))
        "tool_execution_end" (println (str "\n[tool finished; error=" (get event "isError") "]"))
        "agent_end" (println "\n[agent ended]")
        nil)))
  (flush))

(defn -main [args]
  (when (> (count args) 1)
    (throw (ex-info "Expected at most one task or path argument" {})))
  (if (= ["-"] (vec args))
    (doseq [line (line-seq (java.io.BufferedReader. *in*))] (render! line))
    (let [process (-> (ProcessBuilder. ^java.util.List (tail-command (first args)))
                      (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                      (.start))
          cleanup (Thread. #(.destroy process))]
      (.addShutdownHook (Runtime/getRuntime) cleanup)
      (try
        (with-open [reader (io/reader (.getInputStream process))]
          (doseq [line (line-seq reader)] (render! line)))
        (let [exit (.waitFor process)]
          (when-not (zero? exit)
            (throw (ex-info "SSH stream ended; rerun to reconnect" {:exit exit}))))
        (finally
          (.destroy process)
          (.removeShutdownHook (Runtime/getRuntime) cleanup))))))

(-main *command-line-args*)
