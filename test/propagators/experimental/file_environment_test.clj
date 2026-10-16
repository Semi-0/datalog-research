(ns propagators.experimental.file-environment-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.live-environment.files :as files]
            [examples.lain.live-environment.retirement :as retirement]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.model.env :as env]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.premise-state :as premise-state]))

(defn- path [name]
  (files/canonical-path (str "/tmp/lain-file-experiment/" name ".lain")))
(defn- replace-source [session name source]
  (files/replace-file! session {:file (path name) :source source}))
(defn- payload [session name] (data/payload (files/value-at session name)))

(defn- independent-props [state generation]
  (into {}
        (keep (fn [[id {:keys [lifetime activation]}]]
                (when (and (= #{generation} lifetime) activation) [id activation])))
        (get-in state [::files/ledger :nodes])))

(defn- assert-live-graph [network]
  (let [ids (set (keys (:env network)))]
    (is (= ids (set (keys (:graph network)))))
    (is (every? #(every? ids (concat (:inputs %) (:outputs %))) (vals (:graph network))))))

(deftest scalar-replacement-preserves-the-dependent-file
  (let [session (runtime/new-session)
        first-load (replace-source session "a" "(define x 10)")
        _ (replace-source session "b" "(define y (+ x 1))")
        before @session
        b-entry (get-in before [::files/files (path "b")])
        b-props (independent-props before (:generation b-entry))
        changed (replace-source session "a" "(define x 20)")]
    (is (= 11 (payload (atom before) 'y)))
    (is (= 21 (payload session 'y)))
    (is (= (:exports first-load) (:exports changed)))
    (is (= b-entry (get-in @session [::files/files (path "b")])))
    (is (seq b-props))
    (is (seq (:retired changed)))
    (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired changed)))
    (is (every? (fn [[id activation]]
                  (identical? activation (prop/prop-f (get (:env (:program/net @session)) id))))
                b-props))
    (is (not (retirement/references? (:retired changed) (:dict (:program/net @session)))))
    (assert-live-graph (:program/net @session))))

(deftest failed-replacements-are-atomic
  (let [session (runtime/new-session)
        _ (replace-source session "a" "(define x 10)")
        before @session]
    (is (thrown? Exception (replace-source session "a" "(define x")))
    (is (identical? before @session))
    (is (thrown? Exception
                 (replace-source session "a" "(define x ((network [v] v) 1 2))")))
    (is (identical? before @session))
    (is (= 10 (payload session 'x)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Stale file revision"
                          (files/replace-file! session
                                               {:file (path "a") :source "(define x 20)"
                                                :expected-revision 0})))
    (is (identical? before @session))))

(deftest unchanged-source-and-conflicting-owners
  (let [session (runtime/new-session)
        _ (replace-source session "a" "(define x 10)")
        before @session
        same (replace-source session "a" "(define x 10)")]
    (is (= :unchanged (:status same)))
    (is (identical? before @session))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"conflicts"
                          (replace-source session "c" "(define x 99)")))
    (is (identical? before @session))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"File preparation wrote an imported public cell"
                          (replace-source session "c" "(-> 99 x)")))
    (is (identical? before @session))))

(deftest removed-export-withdraws-and-can-be-restored
  (let [session (runtime/new-session)
        a (replace-source session "a" "(define x 10) (define z 20)")
        _ (replace-source session "b" "(define total (+ x z))")
        changed (replace-source session "a" "(define x 30)")
        removed (files/remove-file! session (path "a"))]
    (is (= :replaced (:status changed)))
    (is (value/unusable? (files/value-at session 'z)))
    (is (value/unusable? (files/value-at session 'total)))
    (is (= :removed (:status removed)))
    (is (value/unusable? (files/value-at session 'x)))
    (let [restored (replace-source session "a" "(define x 2) (define z 3)")]
      (is (= (:exports a) (:exports restored)))
      (is (= 5 (payload session 'total)))
      (assert-live-graph (:program/net @session)))))

(deftest repeated-replacement-removes-private-generations
  (let [session (runtime/new-session)]
    (replace-source session "a" "(define x 0)")
    (replace-source session "b" "(define y (+ x 1))")
    (let [counts (mapv (fn [n]
                         (let [receipt (replace-source session "a" (str "(define x " n ")"))]
                           (is (= (inc n) (payload session 'y)))
                           (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired receipt)))
                           (count (:env (:program/net @session)))))
                       (range 1 6))]
      (is (apply = counts))
      (assert-live-graph (:program/net @session)))))

(deftest ^:retirement-gap callable-definition-and-caller-can-each-be-replaced
  (let [session (runtime/new-session)
        a (replace-source session "a" "(define f (network [v] (+ v 1)))")
        _ (replace-source session "b" "(define answer (f 10))")
        before @session
        changed (replace-source session "a" "(define f (network [v] (+ v 2)))")]
    (is (= 11 (payload (atom before) 'answer)))
    (is (= 12 (payload session 'answer)))
    (is (= (:exports a) (:exports changed)))
    (is (seq (:reused changed)))
    (is (every? #(= (get-in before [:program/net :env %])
                    (get-in @session [:program/net :env %])) (:reused changed)))
    (is (some (fn [id]
                (> (count (get-in before [::files/ledger :nodes id :lifetime])) 1))
              (:retired changed)))
    (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired changed)))
    (replace-source session "b" "(define answer (f 20))")
    (is (= 22 (payload session 'answer)))
    (assert-live-graph (:program/net @session))))

(deftest ^:retirement-gap returned-networks-survive-file-lifecycle
  (let [session (runtime/new-session)
        _ (replace-source session "a" "(define make-adder (network [a] (network [b] (+ a b))))")
        _ (replace-source session "b" "(define g (make-adder 10)) (define answer (g 20))")]
    (is (= 30 (payload session 'answer)))
    (replace-source session "a"
                    "(define make-adder (network [a] (network [b] (+ (+ a b) 1))))")
    (is (= 31 (payload session 'answer)))
    (replace-source session "b" "(define g (make-adder 7)) (define answer (g 5))")
    (is (= 13 (payload session 'answer)))
    (files/remove-file! session (path "a"))
    (is (value/unusable? (files/value-at session 'answer)))
    (replace-source session "a"
                    "(define make-adder (network [a] (network [b] (+ (+ a b) 2))))")
    (is (= 14 (payload session 'answer)))
    (assert-live-graph (:program/net @session))))

(deftest consumer-can-arrive-before-its-producer
  (let [session (runtime/new-session)
        loaded (replace-source session "b" "(define answer (+ late-value 1))")
        before @session]
    (is (value/unusable? (files/value-at session 'answer)))
    (replace-source session "a" "(define late-value 10)")
    (is (= 11 (payload session 'answer)))
    (is (= (:revision loaded) (get-in @session [::files/files (path "b") :revision])))
    (is (= (get-in before [::files/files (path "b") :environment])
           (get-in @session [::files/files (path "b") :environment])))))

(deftest independent-sessions-and-unrelated-files-remain-isolated
  (let [first-session (runtime/new-session)
        second-session (runtime/new-session)]
    (replace-source first-session "a" "(define x 10)")
    (replace-source second-session "a" "(define x 100)")
    (replace-source first-session "c" "(define unrelated 55)")
    (replace-source first-session "b" "(define answer (+ x unrelated))")
    (let [second-before @second-session
          c-before (get-in @first-session [::files/files (path "c")])]
      (replace-source first-session "a" "(define x 20)")
      (is (= 75 (payload first-session 'answer)))
      (is (= 100 (payload second-session 'x)))
      (is (identical? second-before @second-session))
      (is (= c-before (get-in @first-session [::files/files (path "c")]))))))
