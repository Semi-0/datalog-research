(ns lain.completed-editor
  "Restore completed ingress for inspection. Never pumps effects or launches children."
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.harness-session :as harness]
            [graph.lain-editor.protected-server :as protected]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.runtime.display :as display]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as ids]
            [propagators.compiler-2.runtime.operators.network-history :as history]
            [propagators.semantic-trace.retention :as retention]
            [propagators.semantic-trace.observation :as observation]
            [propagators.experimental.visualization.extension :as visualization]))

(def observation-extensions
  [[retention/extension {:outbox-id (ids/boundary-outbox-id)}]
   [visualization/extension {:outbox-id (ids/boundary-outbox-id)}]
   [display/extension {}] [history/extension {}] [observation/extension {}]])

(defn- enum-value [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else (throw (ex-info "Expected a recorded enum" {:value value}))))

(defn- ingress-log [identity rows]
  (facts/from-facts identity
    (mapv (fn [row]
            (let [fields (dissoc row :session :id :order :kind)
                  fields (if (contains? fields :backend)
                           (update fields :backend enum-value)
                           fields)]
              (when-not (= identity (:session row))
                (throw (ex-info "Foreign recorded session" {:id (:id row)})))
              (facts/fact identity (:id row) (:order row)
                (enum-value (:kind row)) fields)))
          rows)))

(defn restore-completed!
  "Rebuild the completed network from authoritative ingress only, with no transports.
   The returned owner must be closed by its caller. Recorded model output stays data."
  [recording extensions]
  (when-not (some #(= :assignment/assessed (enum-value (:kind %)))
                 (get-in recording [:facts :assignments]))
    (throw (ex-info "Recording has no completed assessment" {})))
  (let [identity (:session recording)
        config (update (:configuration recording) :plugin enum-value)
        logs (mapv (fn [[component field]]
                     [component (ingress-log identity (get-in recording [:facts field]))])
                   [[:socket :socket-in] [:pi :pi-in] [:harbor :harbor-in]])
        owner (harness/open! identity {:mode :observe :pi-config config
                                       :extensions extensions})]
    (try
      (doseq [[component log] logs] (harness/observe! owner component log))
      (when-not (seq (facts/ordered-facts
                     (facts/select-kinds (harness/facts-at owner :assignments)
                                         #{:assignment/assessed})))
        (throw (ex-info "Recorded ingress did not derive an assessment"
                 {:assignment-kinds
                  (mapv :kind (facts/ordered-facts (harness/facts-at owner :assignments)))})))
      owner
      (catch Throwable error
        (harness/close! owner)
        (throw error)))))

(defn- read-json [root name]
  (json/parse-string (slurp (io/file root name)) true))

(defn -main [& _]
  (let [root (or (System/getenv "LAIN_COMPLETED_RUN_ROOT")
                 (throw (ex-info "LAIN_COMPLETED_RUN_ROOT is required" {})))
        cleanup (read-json root "cleanup.json")
        status (read-json root "status.json")]
    (when-not (and (= "closed" (:status cleanup))
                   (every? #(= "closed" (:status %)) (:components cleanup))
                   (= "assessed" (:phase status)))
      (throw (ex-info "Only a closed, assessed trial can be restored" {})))
    (let [recording (read-json root "artifacts.json")
          owner (restore-completed! recording observation-extensions)
          server (atom nil)]
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable (fn []
          (harness/close! owner)
          (when-let [s @server] ((:close s))))))
      (try
        (runtime/refresh-trace-subscriptions! (:session owner))
        (reset! server (protected/start! (protected/configuration (System/getenv))))
        (let [registration (editor/observe! (:editors @server)
                             {:pane "completed-main" :session (:session owner)
                              :sessionId (:session recording)
                              :source (str ";; Completed trial restored from recorded ingress.\n"
                                           ";; Observation only; no model or tool execution.\n"
                                           "(load-lain "
                                           (pr-str (str (harness/declaration-root)
                                                        "/examples/lain/subagents/composition.lain"))
                                           ")")})]
          (editor/detach! (:editors @server) (:detached @server)
            {:pane "completed-main" :generation (:generation registration)}))
        (spit (io/file root "viewer-status.json")
          (json/generate-string {:phase "ready" :mode "observe"
                                 :source "completed recorded ingress"
                                 :session (:session recording)
                                 :new-execution false :history-capacity 10}
                                {:pretty true}))
        @(promise)
        (catch Throwable error
          (harness/close! owner)
          (when-let [s @server] ((:close s)))
          (throw error))))))
