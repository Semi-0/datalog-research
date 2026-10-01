(ns examples.lain.visualization-combinators.demo
  (:require [graph.xr-server :as server]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.visualization.extension :as extension]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.experimental.visualization.layered-primitives :as primitives]))

(def options
  {:extensions [extension/ttms-extension ttms/session-extension primitives/ttms-extension]})
(def example "examples/lain/visualization_combinators/chain.lain")

(defn start!
  ([file port] (start! file port server/default-host))
  ([file port host]
  (let [session (loader/load-session-from-file file options)
        http (server/start-server port session host)
        watcher (loader/watch-file! session file
                   (assoc options :on-reload #(println "Reloaded" (:file %))
                                  :on-error #(binding [*out* *err*] (println (ex-message %)))))]
    {:session session :server http
     :close (fn []
              ((:close watcher))
              ((:close http))
              (runtime/stop-clocks! session))})))

(defn -main [& [file port host]]
  (let [running (start! (or file example) (Long/parseLong (or port "45668"))
                        (or host server/default-host))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close running)))
    (println (str "View composition: http://" (get-in running [:server :host])
                  ":" (get-in running [:server :port]) "/relationships"))
    @(promise)))
