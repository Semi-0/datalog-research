(ns propagators.experimental.file-premise-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [examples.lain.amb.experiment :as amb]
            [examples.lain.amb.file-premise-selection :as example]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.handlers :as handlers]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.file-premise.compiler :as file-compiler]
            [propagators.experimental.file-premise.files :as files]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def loaded (delay (example/loaded-world)))
(def solved (delay (files/query @loaded example/target-source)))

(defn- answer [world]
  (net/network-cell-strongest (:net world) (get-in world [:compiled :cell])))

(defn- premise [world alias epoch]
  {:source (get-in world [:files alias :premise-id])
   :timestamp epoch :premises-status :active})

(deftest selects-file-with-exact-dependencies
  (let [world @solved
        records (amb/choice-records (:net world))
        expected (conj (set (map contracts/premise records))
                       (premise world 'ten 1) (premise world 'goal 1))
        failed {:literals #{[(:source (first records)) 0]}
                :context #{(premise world 'one 1) (premise world 'goal 1)}}]
    (is (s/valid? ::files/world world))
    (is (= 2 (observation/base (answer world))))
    (is (= 1 (count records)))
    (is (= expected (datum/support-of (answer world))))
    (testing "learning keeps the losing procedure file and the target as context"
      (is (some #(and (set? %) (contains? % failed)) (amb/cell-bases (:net world)))))
    (testing "same export name stays isolated in separate file frames"
      (is (not= (env/binding-id (get-in world [:files 'one :exports 'procedure]))
                (env/binding-id (get-in world [:files 'ten :exports 'procedure])))))
    (testing "all definitions in a file share its source cell"
      (doseq [[alias descriptor] (:files world)
              [_ binding] (:exports descriptor)]
        (is (= #{(premise world alias 1)}
               (datum/support-of (net/network-cell-strongest (:net world)
                                                           (env/binding-id binding)))))))))

(deftest delayed-nested-networks-retain-file-compiler
  (let [compilers (atom {})
        events (atom [])
        factory file-compiler/file-compiler
        compile-network handlers/compile-network]
    (with-redefs [file-compiler/file-compiler
                  (fn [source-id]
                    (let [compiled (factory source-id)]
                      (swap! compilers assoc compiled source-id)
                      compiled))
                  handlers/compile-network
                  (fn [compile-k state expression k]
                    (swap! events conj (get @compilers (:compiler state)))
                    (compile-network compile-k state expression k))]
      (let [world (example/run)
            source-id (get-in world [:files 'ten :premise-id])]
        ;; helper, procedure, and the network declared inside procedure's body.
        (is (= 3 (get (frequencies @events) source-id)))
        (is (= 2 (observation/base (answer world))))))))

(deftest availability-is-revisable-with-stable-topology
  (doseq [alias '[ten goal]]
    (let [world @solved
          withdrawn (files/publish world alias 2 :retracted)
          recovered (files/publish withdrawn alias 3 :active)
          stale (files/publish recovered alias 1 :retracted)
          replay (nb/run-propagators (:net stale) (get-in stale [:compiled :props]))]
      (is (value/nothing? (observation/base (answer withdrawn))))
      (is (= 2 (observation/base (answer recovered))))
      (is (contains? (datum/support-of (answer recovered)) (premise world alias 3)))
      (is (= (answer recovered) (answer stale)))
      (is (= (net/net-graph (:net world)) (net/net-graph (:net recovered))))
      (is (= (net/net-env (:net stale)) (net/net-env replay)))
      (is (= (net/net-graph (:net stale)) (net/net-graph replay)))))
  (let [world (files/publish @solved 'one 2 :retracted)]
    (is (= 2 (observation/base (answer world))))))

(deftest impossible-target-exhausts
  (let [source (str/replace example/target-source "(goal/target procedure)"
                            "(= (procedure 5) 999)")
        world (files/query @loaded source)]
    (is (not= source example/target-source))
    (is (value/nothing? (observation/base (answer world))))
    (is (some #(= {:action :unsatisfiable} %) (amb/cell-bases (:net world))))))

(defn- with-source-file [text f]
  (let [file (java.io.File/createTempFile "file-premise-test-" ".lain")]
    (try
      (spit file text)
      (f (.getCanonicalPath file))
      (finally (.delete file)))))

(deftest failed-loads-preserve-world
  (let [world @loaded
        prior-net (:net world)]
    (doseq [text ["" "(" "42" "(define x 1)"
                  "(define x (network (a) a)) (define x (network (a) a))"
                  "(define a/x (network (a) a))"]]
      (with-source-file text
        (fn [path]
          (is (thrown? Exception (files/load-file world path 'invalid)))
          (is (identical? prior-net (:net world))))))
    (is (thrown-with-msg? Exception #"alias already loaded"
                         (files/load-file world (example/fixture-path "goal") 'one)))
    (is (thrown-with-msg? Exception #"path already loaded"
                         (files/load-file world (example/fixture-path "one") 'other)))
    (is (thrown? Exception (files/load-file world "/no-such-file-premise.lain" 'missing)))
    (is (thrown? Exception (files/load-file world (example/fixture-path "one") 'bad/alias)))
    (is (thrown? Exception (files/publish world 'missing 2 :retracted)))
    (is (thrown? Exception (files/publish world 'one 0 :active)))
    (is (thrown? Exception (files/publish world 'one 2 :unknown)))
    (is (thrown? Exception (files/query world "(")))
    (is (identical? prior-net (:net world)))
    (is (= 2 (observation/base (answer @solved))))))

(deftest default-compiler-remains-ordinary
  (let [compiled (compiler/compile-source "(network (x) (+ x 1))")]
    (is (empty? (datum/support-of
                 (net/network-cell-strongest (:net compiled) (:cell compiled)))))))
