(ns graph.lain-editor.runtime.extension-error-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.runtime.session :as runtime]
            [graph.lain-editor.server :as server]))

(deftest malformed-source-with-extensions-keeps-the-running-pane
  (doseq [[kind bad-source expected]
          [["parser" "(define broken" #"EOF while reading"]
           ["compiler" "(if true 1)" #"if expects condition"]]
          operation ["base" "applied-extension" "draft-extension"]]
    (testing (str kind " " operation)
      (let [editors (atom {})]
        (try
          (let [{:keys [generation]} (runtime/run! editors {:pane "a" :source "(define result 7)"
                                                           :extensions ["(define extra 1)"]})
                original (get @editors "a")
                command (case operation
                          "base" {:op "run" :pane "a" :source bad-source :extensions (:extensions original)}
                          "applied-extension" {:op "run" :pane "a" :source (:source original) :extensions [bad-source]}
                          "draft-extension" {:op "extend" :pane "a" :generation generation :source bad-source})]
            (is (thrown-with-msg? Exception expected (server/command! editors command)))
            (is (identical? original (get @editors "a"))))
          (finally (runtime/close! editors)))))))
