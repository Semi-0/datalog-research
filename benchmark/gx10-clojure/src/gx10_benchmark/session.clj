(ns gx10-benchmark.session
  "Compile the specification first; install native primitives and refine its session."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.structural-value :as structural]
            [gx10-benchmark.primitives :as primitives]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(def gib primitives/gib)
(def memory-reserve primitives/memory-reserve)

(defn source [filename]
  (let [local (io/file filename)
        from-root (io/file "benchmark/gx10-clojure" filename)]
    (cond
      (.isFile local) (slurp local)
      (.isFile from-root) (slurp from-root)
      :else (throw (ex-info "Benchmark source is missing" {:file filename})))))

(defn prepare! []
  (let [relative "src/gx10_benchmark/primitives.clj"
        file (if (.isFile (io/file relative)) (io/file relative)
                 (io/file "benchmark/gx10-clojure" relative))
        imported (str "(load-primitive-environment "
                      (pr-str (.getCanonicalPath file))
                      " :gx10-benchmark.primitives/primitive-bindings)\n\n"
                      (source "workflow.lain"))
        candidate (editor/prepare imported [])
        editors (atom {"benchmark" candidate})]
    (try
      (editor/extend! editors {:pane "benchmark" :generation (:generation candidate)
                               :source (source "refinement.lain")})
      (get @editors "benchmark")
      (catch Throwable error
        (editor/close! editors)
        (throw error)))))

(defn close! [candidate]
  (editor/close-session! (:session candidate)))

(defn binding-value [candidate binding]
  (let [state @(:session candidate)
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) binding)
        reader (structural/network-reader network)]
    (when-not id (throw (ex-info "Missing Lain binding" {:binding binding})))
    (letfn [(visit [v depth]
              (when (> depth 32) (throw (ex-info "Lain value nesting exceeds limit" {})))
              (let [payload (data/payload v)]
                (when (or (value/nothing? payload) (value/contradiction? payload))
                  (throw (ex-info "Unresolved Lain value" {:binding binding})))
                (if (structural/linked? payload)
                  (let [{:keys [items tail]} (structural/linked-value payload reader)]
                    (when-not (and (= "empty" (:kind tail)) (every? :found? items))
                      (throw (ex-info "Incomplete Lain list" {:binding binding})))
                    (mapv #(visit (:value %) (inc depth)) items))
                  payload)))]
      (visit (net/network-cell-strongest network id) 0))))

(defn stages [plan]
  (cond
    (keyword? plan) [plan]
    (and (vector? plan) (= :sequence (first plan)) (seq (rest plan)))
    (vec (mapcat stages (rest plan)))
    :else (throw (ex-info "Invalid lifecycle plan" {:plan plan}))))

(defn continue-queue? [facts]
  (when-not (and (vector? facts) (= 3 (count facts)) (every? boolean? facts))
    (throw (ex-info "Queue admission needs three explicit facts" {:facts facts})))
  (let [candidate (prepare!)
        source (str "(define queue-admission (continue-queue? "
                    (str/join " " facts) "))")]
    (try
      (editor/load-checked! (:session candidate) source)
      (true? (binding-value candidate 'queue-admission))
      (finally (close! candidate)))))

(defn lifecycle []
  (let [candidate (prepare!)]
    (try (stages (binding-value candidate 'result))
         (finally (close! candidate)))))
