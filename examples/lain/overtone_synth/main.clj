(ns examples.lain.overtone-synth.main
  (:require [clojure.java.io :as io]
            [experiments.synth.runtime :as synth]
            [graph.xr-server :as xr]
            [propagators.compiler-2.runtime.session.state :as state])
  (:import [java.util.concurrent ScheduledExecutorService TimeUnit]))

(def default-file "examples/lain/overtone_synth/synth.lain")

(defn silent-backend []
  {:play-note! (fn [_] nil) :clear! (fn [] nil) :close (fn [] nil)})

(defn watch! [session file on-error]
  (let [executor (state/daemon-executor "lain-synth-file")
        attempted (atom (slurp file))
        task (.scheduleWithFixedDelay
              ^ScheduledExecutorService executor
              ^Runnable (fn []
                          (try
                            (let [source (slurp file)]
                              (when-not (= source @attempted)
                                (reset! attempted source)
                                (synth/reload! session source)))
                            (catch Exception error (on-error error))))
              250 250 TimeUnit/MILLISECONDS)]
    {:close (fn [] (.cancel task false) (.shutdownNow executor))}))

(defn start!
  [file {:keys [backend host port clock?]
         :or {host "127.0.0.1" port 45673 clock? true}}]
  (let [resources (atom [(:close backend)])
        acquire (fn [resource]
                  (swap! resources conj (:close resource))
                  resource)
        cleanup (fn [] (doseq [close (reverse @resources)] (close)))]
    (try
      (let [session (atom (synth/candidate (slurp file) backend))
            errors (atom [])
            report (fn [error]
                     (swap! errors conj {:message (ex-message error) :data (ex-data error)})
                     (binding [*out* *err*] (println "Synth runtime:" (ex-message error))))
            server (acquire (xr/start-server port session host))
            _watcher (acquire (watch! session file report))
            _clock (if clock? (acquire (synth/start-clock! session report)) nil)
            closed (atom false)]
        {:session session :server server :errors errors
         :url (str "http://" host ":" (:port server) "/relationships")
         :close (fn []
                  (when (compare-and-set! closed false true)
                    (locking session
                      (swap! session assoc :synth/closed? true)
                      (cleanup))))})
      (catch Exception error
        (cleanup)
        (throw error)))))

(defn options [arguments]
  (loop [args arguments result {:file default-file :host "127.0.0.1" :port 45673 :silent? false}]
    (if (empty? args)
      result
      (let [[flag value & tail] args]
        (case flag
          "--silent" (recur (rest args) (assoc result :silent? true))
          "--file" (if value (recur tail (assoc result :file value))
                       (throw (ex-info "--file requires a path" {})))
          "--host" (if value (recur tail (assoc result :host value))
                       (throw (ex-info "--host requires an address" {})))
          "--port" (if value (recur tail (assoc result :port (Integer/parseInt value)))
                       (throw (ex-info "--port requires a number" {})))
          (throw (ex-info "Unknown example option" {:option flag})))))))

(defn -main [& arguments]
  (let [{:keys [file silent?] :as opts} (options arguments)
        backend (if silent? (silent-backend)
                    ((requiring-resolve 'experiments.synth.overtone/start!)))
        app (start! (io/file file) (assoc opts :backend backend))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:close app)))
    (println (:url app))
    (println (if silent? "Silent audio backend; clock and visualization are live." "Computer audio is live."))
    @(promise)))
