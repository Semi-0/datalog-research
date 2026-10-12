(ns experiments.synth.audio
  "Declarative notes and an explicit runtime-owned audio boundary. No boot on import."
  (:require [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.cells.value :as value]
            [propagators.propagator :as prop]
            [propagators.experimental.visualization.data :as data]))

(def pitches [60 64 67 72 67 64 62 59])

(defn note-hz [tick]
  (when-not (and (integer? tick) (pos? tick))
    (throw (ex-info "Tick must be a positive integer" {:tick tick})))
  (* 440.0 (Math/pow 2.0 (/ (- (nth pitches (mod (dec tick) (count pitches))) 69) 12.0))))

(defn finite-range? [value low high]
  (and (number? value) (Double/isFinite (double value)) (<= low value high)))

(defn normalize-note [_ [supported]]
  (let [note (data/payload supported)]
    (if (and (map? note) (keyword? (:voice note))
             (integer? (:tick note)) (pos? (:tick note))
             (finite-range? (:frequency note) 20 20000)
             (finite-range? (:duration note) 0.01 2)
             (finite-range? (:amplitude note) 0 0.2))
      {:status :ready :payload (select-keys note [:voice :tick :frequency :duration :amplitude])}
      {:status :invalid :reason :invalid-note :message "Invalid or unsafe note" :data {:note note}})))

(defn play-note! [_ session request]
  (let [play! (get-in session [:synth/backend :play-note!])]
    (if (ifn? play!)
      (do (play! (:boundary/payload request))
          {:state session :receipt {:status :performed}})
      (throw (ex-info "Synth audio backend is not running" {})))))

(defn scalar-operator [name f]
  (ttms/scalar-operator
   name
   (prop/primitive-propagator
    name
    (fn [& arguments]
      (cond
        (some value/contradiction? arguments)
        (value/contradiction-with-provenance
         (into #{} (mapcat value/contradiction-provenance) arguments))
        (some value/nothing? arguments) value/nothing
        :else (try (apply f arguments)
                   (catch Exception error
                     (value/contradiction-with-provenance
                      #{{:operation name :reason (ex-message error)}}))))))))

(def capability
  (extension/extension-bundle
   {:id ::audio
    :bindings [['note-hz (scalar-operator ::note-hz note-hz)]
               ['note-data (scalar-operator ::note-data
                           (fn [& fields]
                             (if (= 5 (count fields))
                               (let [[voice frequency tick duration amplitude] fields]
                                 {:voice voice :frequency frequency :tick tick
                                  :duration duration :amplitude amplitude})
                               (throw (ex-info "note-data requires five fields" {:fields fields})))))]]
    :effects [{:effect/symbol 'io:audio :effect/arities #{1}
               :boundary/port :environment :boundary/kind :audio/play-note
               :effect/normalize normalize-note
               :effect/identity-parts (juxt :voice :tick)
               :effect/handler-symbol 'experiments.synth.audio/play-note!}]}))
