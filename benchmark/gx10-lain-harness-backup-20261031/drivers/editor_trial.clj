(ns lain.editor-trial
  "Single-trial owner and read-only editor. Lain alone selects domain effects.
  SUBMIT admits one assignment; pump/poll deliver effects and receipts. No replay on restart."
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [graph.lain-editor.protected-server :as protected]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.runtime.display :as display]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-history :as history]
            [propagators.semantic-trace.retention :as retention]
            [propagators.semantic-trace.observation :as observation]
            [propagators.experimental.visualization.extension :as visualization]
            [examples.lain.subagents.harness-session :as hs]))

(load-file "/home/lain/experiments/lain-harness-flash-once-20261027/drivers/flash_once.clj")
(require '[lain.flash-once :as trial])

(def run-root (io/file (or (System/getenv "LAIN_EDITOR_TRIAL_ROOT")
                          "/home/lain/experiments/gx10-blazux-lain-test-20261031/editor-trial")))
(def history-source
  (str "(load-lain "
       (pr-str (str (hs/declaration-root) "/examples/lain/subagents/composition.lain"))
       ")"))
(def task "Read /app/task.txt and answer it. The file contains two integers; write their sum to /app/answer.txt.")

(defn write-json! [name value]
  (let [target (io/file run-root name) tmp (io/file run-root (str name ".tmp"))]
    (io/make-parents target)
    (spit tmp (json/generate-string value {:pretty true}))
    (java.nio.file.Files/move (.toPath tmp) (.toPath target)
      (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

(def observation-extensions
  [[retention/extension {:outbox-id (ids/boundary-outbox-id)}]
   [visualization/extension {:outbox-id (ids/boundary-outbox-id)}]
   [display/extension {}] [history/extension {}] [observation/extension {}]])

(defn run-once! [owner]
  (let [start (System/currentTimeMillis)]
    (trial/submit! owner task)
    (loop [round 0]
      (when-not (trial/memory-ok?)
        (throw (ex-info "RAM reserve below 20 percent" {:round round})))
      (hs/pump! owner)
      (hs/poll! owner (str "editor-poll-" round))
      (let [assessment (trial/assessed? owner)]
        (write-json! "status.json" {:phase (if assessment "assessed" "running")
                                   :round round :elapsed-ms (- (System/currentTimeMillis) start)
                                   :mem-available-gib (trial/mem-available-gib)
                                   :assessment assessment})
        (cond
          assessment (do (write-json! "artifacts.json" (hs/artifacts owner)) assessment)
          (> (- (System/currentTimeMillis) start) 3600000)
          (throw (ex-info "One hour trial deadline exhausted" {}))
          :else (do (Thread/sleep 4000) (recur (inc round))))))))

(defn -main [& _]
  (trial/check-bridge-configuration!)
  (io/make-parents (io/file run-root "status.json"))
  ;; This durable create is deliberately never removed: restarting cannot replay a mutation.
  (when-not (.createNewFile (io/file run-root "OWNER-CLAIMED"))
    (throw (ex-info "Trial already claimed; inspect receipts before any retry" {})))
  (when-not (trial/memory-ok?) (throw (ex-info "RAM reserve below 20 percent" {})))
  (trial/require-candidate-classpath!)
  (let [bridge (trial/start-bridge) owner (atom nil) server (atom nil)]
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable (fn []
        (when-let [h @owner] (try (hs/close! h) (catch Throwable _ nil)))
        (trial/stop-bridge bridge)
        (when-let [s @server] ((:close s))))))
    (try
      (reset! owner (hs/open! trial/sid
                     (assoc (trial/live-session-config bridge (trial/harbor-callbacks))
                            :extensions observation-extensions)))
      (runtime/refresh-trace-subscriptions! (:session @owner))
      (reset! server (protected/start! (protected/configuration (System/getenv))))
      (let [registration (editor/observe! (:editors @server)
                            {:pane "gx10-main" :session (:session @owner)
                             :sessionId trial/sid
                             :source (str ";; Live MAIN harness; read-only observer.\n" history-source)})]
        (editor/detach! (:editors @server) (:detached @server)
          {:pane "gx10-main" :generation (:generation registration)}))
      (write-json! "status.json" {:phase "ready" :session trial/sid :history-capacity 10})
      (loop [waiting 0]
        (when (> waiting 3600) (throw (ex-info "No assignment admitted within one hour" {})))
        (if (.exists (io/file run-root "SUBMIT"))
          (run-once! @owner)
          (do (Thread/sleep 1000) (recur (inc waiting)))))
      (write-json! "cleanup.json" (hs/close! @owner))
      (trial/stop-bridge bridge)
      ;; Keep the actual final session inspectable; the editor never owns its cleanup.
      @(promise)
      (catch Throwable error
        (write-json! "failure.json" {:error (ex-message error) :data (ex-data error)})
        (when-let [h @owner]
          (try (write-json! "cleanup.json" (hs/close! h)) (catch Throwable _ nil)))
        (trial/stop-bridge bridge)
        (if @server @(promise) (throw error))))))
