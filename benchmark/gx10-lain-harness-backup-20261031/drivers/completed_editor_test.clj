(ns lain.completed-editor-test
  "Acceptance against a supplied completed JSON artifact; no inference or tool calls."
  (:require [clojure.test :refer [deftest is run-tests]]
            [cheshire.core :as json]
            [examples.lain.subagents.harness-session :as harness]
            [examples.lain.subagents.facts :as facts]
            [lain.completed-editor :as viewer]))

(def ^:dynamic *recording* nil)

(deftest completed-restoration
  (let [owner (viewer/restore-completed! *recording* viewer/observation-extensions)]
    (try
      (let [artifact (harness/artifacts owner)
            assessed (facts/ordered-facts
                       (facts/select-kinds (harness/facts-at owner :assignments)
                                           #{:assignment/assessed}))
            expected (filter #(= "assignment/assessed" (:kind %))
                             (get-in *recording* [:facts :assignments]))]
        (is (= :observe (:mode artifact)))
        (is (= 1 (count assessed)))
        (is (= (mapv :assignment-id expected) (mapv :assignment-id assessed)))
        (is (= (mapv :assessment expected) (mapv :assessment assessed)))
        (is (empty? (:harbor-receipts artifact)))
        (is (empty? (:socket-receipts artifact)))
        (is (empty? (get-in artifact [:pi-receipts :trials])))
        (is (empty? (get-in artifact [:pi-receipts :polls]))))
      (finally (harness/close! owner))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no completed assessment"
        (viewer/restore-completed!
          (assoc-in *recording* [:facts :assignments] []) [])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Foreign recorded session"
        (viewer/restore-completed!
          (assoc-in *recording* [:facts :socket-in 0 :session] "foreign") []))))

(defn -main [artifact-path]
  (binding [*recording* (json/parse-string (slurp artifact-path) true)]
    (let [result (run-tests 'lain.completed-editor-test)]
      (shutdown-agents)
      (System/exit
        (if (and (pos? (:test result))
                 (zero? (+ (:fail result) (:error result))))
          0
          1)))))
