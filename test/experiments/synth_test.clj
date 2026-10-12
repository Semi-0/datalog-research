(ns experiments.synth-test
  (:require [clojure.test :refer [deftest is]]
            [experiments.synth.audio :as audio]
            [experiments.synth.runtime :as synth]
            [propagators.experimental.visualization.data :as data]
            [examples.lain.overtone-synth.main :as main]
            [graph.xr-server :as server]
            [graph.xr-network-inlet :as inlet]
            [graph.xr-runtime :as xr]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.datastructures.support-collection :as collection]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.visualizer :as visualizer]))

(def source (slurp main/default-file))

(defn observe [session symbol]
  (data/payload
   (net/network-cell-strongest (:program/net session) (synth/binding-id session symbol))))

(defn backend []
  (let [notes (atom []) clear-count (atom 0) close-count (atom 0)]
    {:notes notes :clears clear-count :closes close-count
     :play-note! #(swap! notes conj %)
     :clear! #(swap! clear-count inc)
     :close #(swap! close-count inc)}))

(deftest unsafe-notes-are-rejected
  (let [note {:voice :lead :tick 1 :frequency 440 :duration 0.18 :amplitude 0.08}]
    (is (= :ready (:status (audio/normalize-note nil [note]))))
    (doseq [bad [(assoc note :amplitude 2) (assoc note :frequency Double/NaN)
                 (assoc note :tick 0) (assoc note :duration 0) nil]]
      (is (= :invalid (:status (audio/normalize-note nil [bad])))))))

(deftest live-publication-encodes-full-functional-network
  (let [session (atom (synth/tick (synth/candidate source (backend))))
        observed (atom (inlet/inlet))
        payload (#'server/latest-effect-payload session observed)
        again (#'server/latest-effect-payload session observed)
        children (get-in payload [:views 0 :children])]
    (is (= ["graph" "graph" "cell-window" "cell-history"] (mapv :type children)))
    (is (seq (get-in children [0 :graph :edges])))
    (is (seq (get-in children [3 :samples])))
    (is (= "supported" (get-in children [2 :strongest :kind])))
    (is (re-find #"frequency" (get-in children [2 :strongest :current])))
    (is (= 1 (count (get-in children [2 :strongest :support]))))
    (is (= "ready" (get-in payload [:context :status])))
    (is (identical? (:context payload) (:context again)))))

(deftest clock-publishes-audio-and-reusable-views
  (let [backend (backend)
        initial (synth/candidate source backend)
        before (count @(:notes backend))
        first (synth/tick initial)
        second (synth/tick first)
        rerun (effects/drain-environment-effects second)
        log-id (synth/binding-id second 'activation-log)
        view (visualizer/resolve-view (:program/net second)
               (visualizer/cell-history-declaration :test log-id log-id))]
    (is (zero? before))
    (is (= [1 2] (mapv :tick @(:notes backend))))
    (is (= (audio/note-hz 2) (:frequency (observe second 'note))))
    (is (seq (:edges (observe second 'dataflow))))
    (is (every? (set (map str (vals (:nodes (observe second 'dataflow)))))
                ["clock" "melody" "envelope" "voice" "output"]))
    (is (seq (:edges (observe second 'melody-body))))
    (is (seq (:synth/history second)))
    (is (= (:synth/history second) (:view/samples view)))
    (is (= (:environment/effects second) (:environment/effects rerun)))
    (is (= (net/net-graph (:program/net first)) (net/net-graph (:program/net second))))))

(deftest late-stale-and-retracted-control-inputs
  (let [backend (backend)
        initial (synth/candidate source backend)
        id (synth/binding-id initial 'pulse)
        commit (fn [state update]
                 (input/commit-runtime-input state {:runtime/input :cell-message :cell-id id :update update}))
        active (synth/tick initial)
        withdrawn (commit active (collection/content
                                  {:premise-state #{{:source id :timestamp 2 :premises-status :retracted}}}))
        stale (commit withdrawn (synth/source-update id 1 1))
        recovered (commit stale (synth/source-update id 3 3))]
    (is (value/unusable? (net/network-cell-strongest (:program/net withdrawn)
                           (synth/binding-id withdrawn 'note))))
    (is (= [1 3] (mapv :tick @(:notes backend))))
    (is (= 3 (:tick (observe recovered 'note))))))

(deftest reload-forks-generation-without-candidate-audio
  (let [backend (backend)
        session (atom (synth/tick (synth/candidate source backend)))
        previous (:environment/generation @session)]
    (is (= 1 (count @(:notes backend))))
    (is (= :reloaded (synth/reload! session source)))
    (is (= 1 (count @(:notes backend))))
    (is (= 1 @(:clears backend)))
    (is (not= previous (:environment/generation @session)))
    (is (= :stale (synth/tick-generation! session previous)))
    (is (= :advanced (synth/tick-generation! session (:environment/generation @session))))
    (is (= [1 1] (mapv :tick @(:notes backend))))
    (let [good @session]
      (is (thrown? Exception (synth/reload! session "(network)")))
      (is (identical? good @session)))))

(deftest audio-failure-delivers-a-receipt
  (let [backend (assoc (backend) :play-note! (fn [_] (throw (ex-info "Audio unavailable" {}))))
        session (synth/tick (synth/candidate source backend))]
    (is (some #(= :failed (:status %)) (vals (:environment/effects session))))))

(deftest candidate-with-ungated-audio-is-rejected-without-effects
  (let [backend (backend)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Candidate must wait"
          (synth/candidate
           "(define pulse) (define activation-log)
            (io:audio (note-data :lead 440 1 0.18 0.08))" backend)))
    (is (empty? @(:notes backend)))))

(deftest failed-start-releases-backend
  (let [backend (backend)]
    (is (thrown? Exception
          (main/start! "/private/tmp/lain-synth-file-does-not-exist.lain" {:backend backend})))
    (is (= 1 @(:closes backend)))))

(deftest history-cap-is-explicit
  (let [samples (mapv #(hash-map :sample/time-ms % :sample/tick %) (range 5000))
        history (synth/history {} samples 5000)]
    (is (= 4096 (count (:history/samples history))))
    (is (= 904 (:history/dropped history)))
    (is (= 5000 (:history/dropped (synth/history
                                  {:synth/history samples} [] 50000))))))

(defn wait-until [pred]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (cond
        (pred) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

(deftest external-file-edit-replaces-the-complete-environment
  (let [file (java.io.File/createTempFile "lain-synth-reload-" ".lain")
        backend (backend)]
    (try
      (spit file source)
      (let [app (main/start! file {:backend backend :port 0 :clock? false})
            live (:session app)
            generation (:environment/generation @live)]
        (try
          (is (empty? @(:notes backend)))
          (spit file (clojure.string/replace source "(note-hz tick)" "(+ 100 (note-hz tick))"))
          (is (wait-until #(not= generation (:environment/generation @live))))
          (is (empty? @(:notes backend)))
          (is (empty? @(:errors app)))
          (synth/tick-generation! live (:environment/generation @live))
          (is (= (+ 100 (audio/note-hz 1)) (:frequency (first @(:notes backend)))))
          (let [good @live]
            (spit file "(network)")
            (is (wait-until #(seq @(:errors app))))
            (is (identical? good @live)))
          (finally ((:close app)) ((:close app)))))
      (is (= 1 @(:closes backend)))
      (finally (.delete file)))))
