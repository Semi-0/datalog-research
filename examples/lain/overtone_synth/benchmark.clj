(ns examples.lain.overtone-synth.benchmark
  (:require [clojure.edn :as edn]
            [examples.lain.overtone-synth.main :as main]
            [experiments.synth.runtime :as synth]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.network :as net]
            [propagators.visualizer :as visualizer]
            [graph.xr-runtime :as xr]))

(defn view-size [session]
  (let [declarations (keep #(when (= :xr/present-view (:boundary/kind %))
                              (get-in % [:boundary/payload :view]))
                           (effects/outbox-effects (:program/net session)))]
    (count (pr-str (mapv #(xr/view->json (visualizer/resolve-view (:program/net session) %))
                        declarations)))))

(defn measure [source ticks record?]
  (let [initial (synth/candidate source (main/silent-backend))
        warm (synth/tick initial record?)
        started (System/nanoTime)
        final (nth (iterate #(synth/tick % record?) warm) ticks)
        elapsed (/ (- (System/nanoTime) started) 1e6)]
    {:ticks ticks :recording record? :elapsed-ms elapsed :ms-per-tick (/ elapsed ticks)
     :initial-topology (count (net/net-env (:program/net warm)))
     :final-topology (count (net/net-env (:program/net final)))
     :activation-records (count (:synth/history final))
     :dropped-records (:synth/dropped final)
     :projected-view-chars (view-size final)}))

(defn -main [& args]
  (let [ticks (if (seq args) (edn/read-string (first args)) 200)
        source (slurp main/default-file)]
    (when-not (and (integer? ticks) (pos? ticks))
      (throw (ex-info "Benchmark tick count must be positive" {:ticks ticks})))
    (prn (measure source ticks false))
    (prn (measure source ticks true))))
