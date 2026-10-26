(ns propagators.how-to-think-in-lain-examples-test
  "Executes every example in propagators/doc/how-to-think-in-lain-examples.md.

  Each ```lain block is compiled in a real compiler-2 session and each
  `;; => name value` line asserts the projected value of a binding.  The second
  test keeps the companion document aligned with the section list of
  propagators/doc/how-to-think-in-lain.md."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.lain-example-runner :as runner]
            [tools.lain-examples-doc :as examples]))

(def guide-path "propagators/doc/how-to-think-in-lain.md")
(def examples-path examples/default-path)

(defn- section-titles [path]
  (->> (slurp path)
       (str/split-lines)
       (keep #(when-let [m (re-matches #"^## (\d+)\. (.*)$" (str/trim %))]
                [(Integer/parseInt (nth m 1)) (nth m 2)]))
       (into {})))

(deftest every-example-block-runs-and-matches-its-assertions
  (let [{:keys [pass fail results]} (examples/check examples-path)
        failures (remove #(= "pass" (:status %)) results)]
    (doseq [r failures]
      (testing (:section r)
        (is (= "pass" (:status r)) (pr-str (or (:failures r) (:detail r))))))
    (is (zero? fail))
    (is (<= 30 pass) "the companion document should cover every guide section")
    (is (<= 50 (reduce + (map :checked results)))
        "the companion document should assert documented results")))

(deftest every-guide-section-has-a-worked-example
  (let [guide (section-titles guide-path)
        examples (section-titles examples-path)]
    (doseq [[n title] (sort-by key guide)]
      (testing (str "section " n " — " title)
        (is (contains? examples n)
            (str "missing worked example for section " n ": " title))))))

(deftest projection-conventions-are-stable
  (let [session (doto (runner/new-session)
                  (runner/load-source!
                   "(load-lain \"propagators/compiler_2/runtime/lain/combinator.lain\")
                    (define open-cell)
                    (define pending (+ open-cell 1))
                    (define clash)
                    (-> 1 clash)
                    (-> 2 clash)
                    (define closed (list 1 2 3))
                    (define open-tail)
                    (define prefix (cons 9 open-tail))"))]
    (is (= :unresolved (runner/project session 'pending)))
    (is (= :contradiction (runner/project session 'clash)))
    (is (= [1 2 3] (runner/project session 'closed)))
    (is (= [9 :unresolved] (runner/project session 'prefix)))))
