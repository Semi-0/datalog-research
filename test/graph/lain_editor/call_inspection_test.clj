(ns graph.lain-editor.call-inspection-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.inspection :as inspection]))

(def source (slurp "examples/lain/semantic_tracing/06-recursion.lain"))

(defn root-request [editors pane]
  {:pane pane :generation (:generation (get @editors pane))
   :display {:cell "@display" :mode "graph" :direction "both"}})

(deftest recursive-report-shows-real-arguments-branches-and-return
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source source})
      (let [request (root-request editors "a")
            graph (:graph (inspection/inspect editors request))
            call (first (filter #(= "sum-to" (:label %)) (:nodes graph)))
            session (:session (get @editors "a")) before (:program/net @session)
            report (:call (inspection/inspect editors (assoc request :node (:id call) :chase true)))
            rows (:occurrences report) same (filter :sameCallable rows)
            base (first (filter :returnsInput rows))]
        (is (= [["4" "0"] ["3" "4"] ["2" "7"] ["1" "9"] ["0" "10"]]
               (mapv #(mapv :value (:arguments %)) same)))
        (is (every? #(= ["n" "acc"] (mapv :name (:arguments %))) same))
        (is (= ["10" "10" "10" "10" "10"] (mapv :return same)))
        (is (= ["else" "else" "else" "else" "then"]
               (mapv #(some (comp :branch :selection) (:operations %)) same)))
        (is (= 5 (count (set (map :id same)))))
        (is (= {:name "acc" :value "10"} (:returnsInput base)))
        (is (false? (:truncated report)))
        (is (identical? before (:program/net @session)))
        (let [body (:graph (inspection/inspect editors (assoc request :parent (:id base))))]
          (is (some #(= "acc" (:label %)) (:nodes body)))
          (is (= #{"10"} (set (map :value (filter #(= "cell" (:kind %)) (:nodes body))))))
          (let [kinds (into {} (map (juxt :id :kind)) (:nodes body))]
            (is (every? #(= #{"cell" "propagator"} #{(kinds (:from %)) (kinds (:to %))})
                        (:edges body)))))
        (is (some #(and (= "input" (:label %)) (= "4" (:value %))) (:nodes graph))))
      (finally (runtime/close! editors)))))

(deftest base-only-and-bounded-occurrences
  (let [editors (atom {})]
    (try
      (doseq [input [0 18]]
        (runtime/run! editors {:pane "a" :source (str/replace source "(define input 4)" (str "(define input " input ")"))})
        (let [request (root-request editors "a") graph (:graph (inspection/inspect editors request))
              call (first (filter #(= "sum-to" (:label %)) (:nodes graph)))
              report (:call (inspection/inspect editors (assoc request :node (:id call) :chase true)))]
          (if (zero? input)
            (do (is (= 1 (count (filter :sameCallable (:occurrences report)))))
                (is (= "0" (get-in report [:occurrences 0 :return])))
                (is (some :returnsInput (:occurrences report))))
            (do (is (:truncated report))
                (is (<= (count (:occurrences report)) 64))
                (is (every? #(<= (:depth %) 32) (:occurrences report)))))))
      (finally (runtime/close! editors)))))

(deftest primitive-call-and-cell-selection
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source "(define input 4) (define result (+ input 1))"})
      (let [request (assoc-in (root-request editors "a") [:display :cell] "result")
            graph (:graph (inspection/inspect editors request))
            call (first (filter #(= "+" (:label %)) (:nodes graph)))
            cell (first (filter #(= "result" (:label %)) (:nodes graph)))
            report (:call (inspection/inspect editors (assoc request :node (:id call) :chase true)))]
        (is (= "5" (get-in report [:occurrences 0 :return])))
        (is (= ["4" "1"] (mapv :value (get-in report [:occurrences 0 :arguments]))))
        (is (nil? (:call (inspection/inspect editors (assoc request :node (:id cell)))))))
      (finally (runtime/close! editors)))))

(deftest equal-result-does-not-imply-returning-an-input
  (let [editors (atom {})
        computed (-> source (str/replace "(define input 4)" "(define input 0)")
                     (str/replace #"\(network \[n acc\]\s+acc\)"
                                  "(network [n acc]\n         (+ acc 0))"))]
    (is (str/includes? computed "(+ acc 0)"))
    (try
      (runtime/run! editors {:pane "a" :source computed})
      (let [request (root-request editors "a") graph (:graph (inspection/inspect editors request))
            call (first (filter #(= "sum-to" (:label %)) (:nodes graph)))
            report (:call (inspection/inspect editors (assoc request :node (:id call) :chase true)))]
        (is (= "0" (get-in report [:occurrences 0 :return])))
        (is (not-any? :returnsInput (:occurrences report))))
      (finally (runtime/close! editors)))))
