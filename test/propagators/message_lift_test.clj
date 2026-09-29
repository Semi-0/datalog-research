(ns propagators.message-lift-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.message :as message]
            [propagators.propagator :as prop]))

(deftest transforms-only-message-values
  (let [calls (atom [])
        first-message (with-meta (assoc (message/message :a 1) :extra :kept)
                        {:trace :kept})
        declaration {:op :network/declare-cell :id :new}
        second-message {:id :b :value 2 :extra :also-kept}
        lifted (message/lift-message #(do (swap! calls conj %) (inc %)))
        result (lifted [first-message declaration second-message] [] [] nil)]
    (is (= [1 2] @calls))
    (is (= [(assoc first-message :value 2) declaration
            (assoc second-message :value 3)] result))
    (is (= (type first-message) (type (first result))))
    (is (= {:trace :kept} (meta (first result))))
    (is (identical? declaration (second result)))))

(deftest empty-activation-and-errors
  (let [calls (atom 0)
        lifted (message/lift-message #(do (swap! calls inc) %))
        error (ex-info "transform failed" {:reason :test})]
    (is (= [] (lifted [] [] [] nil)))
    (is (= [] (lifted nil [] [] nil)))
    (is (zero? @calls))
    (is (identical? error
                   (try
                     ((message/lift-message (fn [_] (throw error)))
                      [(message/message :a 1)] [] [] nil)
                     (catch Exception e e))))))

(deftest composes-with-existing-activation
  (let [activation (prop/compose-activation
                    (fn [_ _ _] [(message/message :out 2)])
                    (message/lift-message inc)
                    (message/lift-message #(* % 10)))]
    (is (= [(message/message :out 30)] (activation [] [] nil)))))
