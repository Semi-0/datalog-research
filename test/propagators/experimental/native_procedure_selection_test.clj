(ns propagators.experimental.native-procedure-selection-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [examples.lain.amb.experiment :as amb]
            [examples.lain.amb.native-premise-selection :as selection]
            [examples.lain.amb.native-procedure-pipeline :as pipeline]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.ttms-application-test :as fixture]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- fitting-combinations []
  (vec
   (for [[i start] (map-indexed vector [inc #(+ % 10) #(+ % 3)])
         [j transform] (map-indexed vector [#(* % 2) #(* % %) #(* % 3)])
         [k finish] (map-indexed vector [#(- % 3) #(+ % 7) #(/ % 2)])
         :when (= 42 (finish (transform (start 5))))]
     [(inc i) (inc j) (inc k)])))

(deftest three-procedure-search-matches-exhaustive-oracle
  (let [run (pipeline/run)
        network (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        records (amb/choice-records network)
        definitions (mapv (:sources run)
                          '[definition-ten definition-triple definition-minus-three])
        expected (into (set (map contracts/premise records))
                       (map (fn [source] {:source source :timestamp 1 :premises-status :active}))
                       definitions)
        replay (nb/run-propagators network (get-in run [:compiled :props]))]
    (is (= [[2 3 1]] (fitting-combinations)))
    (is (= :completed (get-in run [:result :status])))
    (is (get-in run [:snapshot :ready?]))
    (is (= [2 3 1] (get-in run [:snapshot :items])))
    (is (= 3 (count records) (count (set (map :source records)))))
    (is (every? #(= :active (:status %)) records))
    (doseq [member (fixture/member-ids network root)]
      (is (= expected (datum/support-of (net/network-cell-strongest network member)))))
    (is (= (net/net-env network) (net/net-env replay)))
    (is (= (net/net-graph network) (net/net-graph replay)))))

(deftest selected-procedure-withdrawal-and-recovery-remain-live
  (let [run (pipeline/run)
        original (get-in run [:result :network])
        root (get-in run [:compiled :cell])
        source (get-in run [:sources 'definition-triple])
        withdrawn (fixture/publish original source 2 :retracted 'definition-triple)
        recovered (fixture/publish withdrawn source 3 :active 'definition-triple)]
    (is (not (:ready? (observation/list-snapshot withdrawn root))))
    (is (= [2 3 1] (:items (observation/list-snapshot recovered root))))
    (is (= (net/net-graph original) (net/net-graph recovered)))))

(deftest impossible-pipeline-target-exhausts
  (let [source (str/replace pipeline/source "(start 5))) 42)" "(start 5))) 999)")
        run (selection/run source pipeline/definition-names)
        network (get-in run [:result :network])]
    (is (not= source pipeline/source))
    (is (= :completed (get-in run [:result :status])))
    (is (not (get-in run [:snapshot :ready?])))
    (is (some #(= {:action :unsatisfiable} %) (amb/cell-bases network)))))
