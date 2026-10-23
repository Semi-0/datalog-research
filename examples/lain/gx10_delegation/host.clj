(ns gx10-delegation.host
  "Interpret the public Lain workflow. Effects are supplied explicitly by the caller."
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [graph.json :as json]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.structural-value :as structural]
            [propagators.compiler-2.model.env :as env]
            [propagators.cells.value :as value]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]))

(defn materialize [network value]
  (let [reader (structural/network-reader network)]
    (letfn [(visit [value depth]
              (when (> depth 32)
                (throw (ex-info "Workflow exceeds nesting limit" {:depth depth})))
              (let [payload (data/payload value)]
                (when (or (value/nothing? payload) (value/contradiction? payload))
                  (throw (ex-info "Unresolved or contradictory workflow value" {})))
                (if (structural/linked? payload)
                  (let [{:keys [items tail]} (structural/linked-value payload reader)]
                    (when-not (and (= "empty" (:kind tail)) (every? :found? items))
                      (throw (ex-info "Workflow is incomplete" {:tail tail})))
                    (mapv #(visit (:value %) (inc depth)) items))
                  (if (or (keyword? payload) (string? payload) (number? payload)
                          (true? payload) (false? payload))
                    payload
                    (throw (ex-info "Unsupported or unresolved workflow value" {}))))))]
      (visit value 0))))

(defn binding-value [candidate binding]
  (let [state @(:session candidate)
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) binding)]
    (when-not id
      (throw (ex-info "Missing Lain binding" {:binding binding})))
    (materialize network (net/network-cell-strongest network id))))

(def allowed-operations
  #{:pi/plan :model/verify-unloaded :pi/smoke :pi/parallel
    :patch/collect :pi/integrate :evaluate})

(defn validate! [node]
  (when-not (vector? node)
    (throw (ex-info "Expected workflow node" {:node node})))
  (case (first node)
    :sequence
    (do
      (when-not (and (= 2 (count node)) (vector? (second node)) (seq (second node)))
        (throw (ex-info "Invalid sequence" {:node node})))
      (doseq [child (second node)] (validate! child)))
    :with-model
    (do
      (when-not (and (= 3 (count node)) (#{:flash :ornith-nvfp4} (second node)))
        (throw (ex-info "Invalid model scope" {:node node})))
      (validate! (nth node 2)))
    :step
    (let [[_ identity operation arguments] node]
      (when-not (and (= 4 (count node)) (keyword? identity) (allowed-operations operation))
        (throw (ex-info "Invalid operation" {:node node})))
      (when (and (= :pi/parallel operation)
                 (not= [4 [:normalize :project :order :batch]] arguments))
        (throw (ex-info "Worker ownership or concurrency differs from contract" {:arguments arguments}))))
    (throw (ex-info "Unknown workflow variant" {:node node})))
  node)

(defn execute!
  "Dispatch receives an operation and stable workflow path. Only :passed receipts continue.
   The caller owns durable receipts, model admission, RAM supervision and worker isolation."
  [workflow dispatch!]
  (validate! workflow)
  (letfn [(effect! [path operation arguments]
            (let [receipt (dispatch! {:path path :operation operation :arguments arguments})]
              (when-not (= :passed (:outcome receipt))
                (throw (ex-info "Workflow effect did not succeed"
                                {:path path :operation operation :receipt receipt})))
              receipt))
          (run! [path node]
            (case (first node)
              :sequence (mapv (fn [index child] (run! (conj path index) child))
                              (range) (second node))
              :step (effect! path (nth node 2) (nth node 3))
              :with-model
              (let [[_ model body] node]
                ;; Enter must either return a verified admission receipt or clean up its own failure.
                (effect! (conj path :enter) :model/enter model)
                (try
                  (run! (conj path :body) body)
                  (finally
                    (effect! (conj path :exit) :model/exit model))))))]
    (run! [] workflow)))

(defn replay! [workflow]
  (let [events (atom [])]
    (execute! workflow
              (fn [request]
                (let [receipt (assoc request :outcome :passed :mode :simulated)]
                  (swap! events conj receipt)
                  receipt)))
    @events))

(defn admission? [facts]
  (when-not (and (vector? facts) (= 4 (count facts)) (every? boolean? facts))
    (throw (ex-info "Admission facts must be four explicit booleans" {:facts facts})))
  (let [source (str (slurp "examples/lain/gx10_delegation/constraints.lain")
                    "\n(define result (admit-workers? "
                    (clojure.string/join " " facts) "))\n")
        candidate (editor/prepare source [])]
    (try (true? (binding-value candidate 'result))
         (finally (editor/close-session! (:session candidate))))))

(defn wire-value [value]
  (walk/postwalk (fn [v] (if (keyword? v) (subs (str v) 1) v)) value))

(defn run-host! [workflow command]
  (let [process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        writer (io/writer (.getOutputStream process))
        reader (io/reader (.getInputStream process))
        shutdown-hook (Thread. (fn [] (.destroy process)) "gx10-delegation-cleanup")
        facts (atom nil)
        dispatch! (fn [request]
                    (when (#{:pi/smoke :pi/parallel} (:operation request))
                      (when-not (admission? @facts)
                        (throw (ex-info "Lain refused worker admission" {:facts @facts}))))
                    (.write writer (str (json/write-json (wire-value request)) "\n"))
                    (.flush writer)
                    (let [line (.readLine ^java.io.BufferedReader reader)]
                      (when-not line
                        (throw (ex-info "GX10 host ended without a receipt" {:request request})))
                      (let [receipt (json/read-json line)]
                        (when-not (= (json/read-json (json/write-json (wire-value request)))
                                     (:request receipt))
                          (throw (ex-info "GX10 host receipt does not match request" {:request request})))
                        (when (= :model/enter (:operation request))
                          (reset! facts (get-in receipt [:detail :admission])))
                        (assoc receipt :outcome (keyword (:outcome receipt))))))]
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    (try
      (let [paths ["workflow.lain" "constraints.lain" "delegation.lain" "host.clj"]
            sources (into {} (for [path paths]
                               [path (slurp (str "examples/lain/gx10_delegation/" path))]))
            receipt (dispatch! {:path [:provenance] :operation :workflow/provenance
                                :arguments {:workflow workflow :sources sources
                                            :clojure-version (clojure-version)
                                            :java-version (System/getProperty "java.version")}})]
        (when-not (= :passed (:outcome receipt))
          (throw (ex-info "Workflow provenance was not saved" receipt))))
      (execute! workflow dispatch!)
      (let [receipt (dispatch! {:path [:complete] :operation :workflow/complete :arguments nil})]
        (when-not (= :passed (:outcome receipt))
          (throw (ex-info "Workflow completion failed" receipt)))
        receipt)
      (finally
        (try (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
             (catch IllegalStateException _ nil))
        (.close writer)
        (when-not (.waitFor process 300 java.util.concurrent.TimeUnit/SECONDS)
          (.destroy process)
          (throw (ex-info "GX10 host cleanup did not finish; inspect owned services" {})))
        (.close reader)))))

(defn live! [workflow output]
  (run-host! workflow ["python3" "benchmark/gx10-delegation/lain_host.py" output]))

(defn -main [& args]
  (when-not (or (empty? args) (and (= 2 (count args)) (= "--live" (first args))))
    (throw (ex-info "Usage: host.clj [--live NEW_OUTPUT_DIRECTORY]" {})))
  (let [candidate (editor/prepare
                   (slurp "examples/lain/gx10_delegation/delegation.lain") [])]
    (try
      (let [workflow (binding-value candidate 'result)]
        (if (seq args)
          (prn (live! workflow (second args)))
          (prn {:mode :simulated :events (replay! workflow)})))
      (finally
        (editor/close-session! (:session candidate))))))
