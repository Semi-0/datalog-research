(ns experiments.synth.runtime
  "Clock, TTMS publication and resource lifecycle outside the propagator kernel."
  (:require [experiments.synth.audio :as audio]
            [experiments.synth.observation :as observation]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.experimental.visualization.extension :as visualization]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.datastructures.support-collection :as collection]
            [propagators.runner :as runner])
  (:import [java.util.concurrent TimeUnit ScheduledExecutorService]))

(defn binding-id [session symbol]
  (or (env/resolve-binding-id (:program/net session) (:program/env session) symbol)
      (throw (ex-info "Required example binding missing" {:symbol symbol}))))

(defn source-update [id epoch base]
  (let [premises #{{:source id :timestamp epoch :premises-status :active}}]
    (collection/content {:base base :support premises :premise-state premises})))

(defn candidate [source backend]
  (let [session @(loader/load-session-from-source
                 source {:extensions [ttms/session-extension visualization/ttms-extension
                                      audio/capability observation/capability]})
        audio-requests (filter #(= :audio/play-note (:boundary/kind %))
                               (effects/outbox-effects (:program/net session)))]
    (when (seq audio-requests)
      (throw (ex-info "Candidate must wait for clock input before requesting audio" {})))
    (binding-id session 'pulse)
    (binding-id session 'activation-log)
    (assoc session :synth/backend backend :synth/tick 0 :synth/started (System/nanoTime))))

(defn- commit-value [session symbol tick value]
  (let [id (binding-id session symbol)]
    (input/commit-runtime-input session
     {:runtime/input :cell-message :cell-id id :update (source-update id tick value)})))

(defn history [session new-records now]
  (let [old (:synth/history session [])
        all (into old new-records)
        current (filterv #(>= (:sample/time-ms %) (- now 30000)) all)
        samples (vec (take-last 4096 current))]
    {:history/samples samples
     :history/dropped (+ (:synth/dropped session 0) (- (count all) (count samples)))}))

(defn tick
  ([session] (tick session true))
  ([session record?]
   (let [tick (inc (:synth/tick session))
         records (volatile! [])
         transform (if record? (observation/recorder records tick (:synth/started session)) identity)
         advanced (binding [runner/*advance-transform*
                            (runner/compose-advance-transforms runner/*advance-transform* transform)]
                    (commit-value session 'pulse tick tick))
         now (/ (- (System/nanoTime) (:synth/started session)) 1e6)
         snapshot (history session @records now)
         ;; Publication is deliberately outside the recorder scope.
         published (if record?
                     (commit-value advanced 'activation-log tick snapshot)
                     advanced)]
     (assoc published :synth/tick tick
            :synth/history (:history/samples snapshot)
            :synth/dropped (:history/dropped snapshot)))))

(defn tick-generation! [session generation]
  (locking session
    (if (and (not (:synth/closed? @session))
             (= generation (:environment/generation @session)))
      (do (swap! session tick) :advanced)
      :stale)))

(defn reload! [session source]
  (let [backend (:synth/backend @session)
        next (candidate source backend)]
    (locking session
      (if (:synth/closed? @session)
        :closed
        (do ((:clear! backend))
            (reset! session next)
            :reloaded)))))

(defn start-clock! [session on-error]
  (let [executor (state/daemon-executor "lain-synth-clock")
        closed (atom false)
        task (.scheduleWithFixedDelay
              ^ScheduledExecutorService executor
              ^Runnable (fn []
                          (try
                            (let [generation (:environment/generation @session)]
                              (when-not @closed (tick-generation! session generation)))
                            (catch Exception error (on-error error))))
              250 250 TimeUnit/MILLISECONDS)]
    {:close (fn []
              (when (compare-and-set! closed false true)
                (.cancel task false)
                (.shutdownNow executor)))}))
