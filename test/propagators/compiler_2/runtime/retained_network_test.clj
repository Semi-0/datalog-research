(ns propagators.compiler-2.runtime.retained-network-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.runtime :as editor]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.retained-network :as retained]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.runner :as runner]
            [propagators.network-builder :as builder]
            [propagators.gur :as gur]
            [propagators.ids :as ids]))

(defn- bounded-transitions [advance]
  (let [steps (atom 0)]
    (fn [state continuations]
      (when (> (swap! steps inc) 25000)
        (throw (ex-info "Retained callback did not reach quiescence within the test bound"
                        {:test/max-transitions 25000})))
      (advance state continuations))))

(use-fixtures :each
  (fn [test]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms runner/*advance-transform* bounded-transitions)]
      (test))))

(def imports "(load-lain \"propagators/compiler_2/runtime/lain/semantic_traversal.lain\")")

(defn- result [pane]
  (data/payload (net/network-cell-strongest (:program/net @(:session pane)) display/cell-id)))

(defn- change!
  ([pane symbol version base] (change! pane symbol version base :active))
  ([pane symbol version base status]
  (let [session (:session pane) state @session
        root (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
    (input/commit-runtime-input! session
      {:runtime/input :cell-message :cell-id root
       :update (membership/content
                 {:base base :support #{{:source root :timestamp version :premises-status status}}
                  :membership #{}})}))))

(deftest retained-callback-reuses-topology-and-observes-current-values
  (let [calls (atom []) step retained/step]
    (with-redefs [retained/step (fn [& args]
                                (let [result (apply step args)]
                                  (swap! calls conj (:compiled? result)) result))]
      (let [pane (editor/prepare
                   (str imports "(def-cells input result) (-> (+ input 1) result)
                         (display (observe-dependents-retained input))") [])]
        (try
          (is (seq (:nodes (result pane))))
          (let [size (count (net/net-env (:program/net @(:session pane))))]
            (reset! calls [])
            (doseq [[version base expected] [[1 7 8] [2 9 10] [3 11 12]]]
              (change! pane 'input version base)
              (let [g (result pane)
                    id (first (for [[id name] (:nodes g) :when (= "result" (str name))] id))]
                (is (= expected (data/payload (get (:values g) id)))))
              (is (= size (count (net/net-env (:program/net @(:session pane)))))))
            (is (seq @calls))
            (is (not-any? true? @calls)))
          (finally (editor/close-session! (:session pane))))))))

(deftest retained-callback-accepts-ordinary-arguments
  (let [pane (editor/prepare
               (str imports "(define input 3) (def-cells offset)
                 (define sample (network (snapshot root n) (+ n 1)))
                 (display (observe-network-retained input sample offset))") [])]
    (try
      (change! pane 'offset 1 41)
      (is (= 42 (result pane)))
      (change! pane 'offset 2 50)
      (is (= 51 (result pane)))
      (change! pane 'offset 3 50 :retracted)
      (is (= :bool4/nothing (result pane)))
      (change! pane 'offset 4 60)
      (is (= 61 (result pane)))
      (finally (editor/close-session! (:session pane))))))

(deftest compilation-hash-has-an-exact-key-guard
  (let [key {:body '(+ x 1)} state (obj/compound-object
                                    {:compilation/hash (hash key) :compilation/key key})]
    (is (retained/reusable? state key))
    (is (not (retained/reusable? state {:body '(* x 2)}))))
  (let [a {:body '(+ x 1)} b {:body '(* x 2)}
        collision (obj/compound-object {:compilation/hash (hash b) :compilation/key a})]
    (is (not (retained/reusable? collision b)))))

(deftest retained-gallery-matches-one-time-observation
  (doseq [file (sort (filter #(str/ends-with? (.getName %) ".lain")
                            (.listFiles (java.io.File. "examples/lain/semantic_tracing"))))]
    (let [source (str/replace (slurp file) "(observe-dependents-retained " "(observe-dependents ")
          one (editor/prepare source [])
          retained (editor/prepare (str/replace source "(observe-dependents " "(observe-dependents-retained ") [])]
      (try
        (let [shape (fn [g]
                      {:nodes (frequencies (map (fn [[id label]]
                                                 [(get (:node-kinds g) id) (str label)]) (:nodes g)))
                       :edges (frequencies (map (fn [[a b]]
                                                 [(str (get (:nodes g) a)) (str (get (:nodes g) b))]) (:edges g)))})]
          (is (= (shape (result one)) (shape (result retained))) (.getName file)))
        (finally
          (editor/close-session! (:session one))
          (editor/close-session! (:session retained)))))))

(deftest snapshot-storage-does-not-retain-other-observers
  (let [a (ids/new-node-id) b (ids/new-node-id)
        parent (-> net/empty-net
                   (builder/install-cell a :private-a :private-a)
                   (builder/install-cell b :private-b :private-b)
                   (net/assoc-net-dict-entry gur/name-bindings-key
                     {retained/storage-scope {[:a :state] a [:b :request] b}}))
        view (retained/snapshot-view parent)]
    (is (= :bool4/nothing (net/network-cell-strongest view a)))
    (is (= :bool4/nothing (net/network-cell-strongest view b)))
    (is (= :private-a (net/network-cell-strongest parent a)))
    (is (= (net/net-graph parent) (net/net-graph view)))))

(deftest retained-path-preserves-existing-semantic-update-contracts
  (require 'propagators.compiler-2.runtime.semantic-traversal-test)
  (let [prepare editor/prepare
        cases '[pending-roots-late-dependents-and-supported-values-refresh
                a-co-input-update-refreshes-the-observation
                flag-dependents-include-branch-values-without-their-producers
                both-directions-retain-the-callable-producing-chain
                compound-calls-stay-at-the-root-abstraction]]
    (with-redefs [editor/prepare
                  (fn [source messages]
                    (prepare (-> source
                                 (str/replace "(observe-dependents " "(observe-dependents-retained ")
                                 (str/replace "(observe-semantic " "(observe-semantic-retained ")) messages))]
      (clojure.test/test-vars
        (map #(ns-resolve 'propagators.compiler-2.runtime.semantic-traversal-test %) cases)))))

(def layered-import
  "(load-primitive-environment
      \"propagators/compiler_2/runtime/operators/retained_observation.clj\"
      :propagators.compiler-2.runtime.operators.retained-observation/layered-environment 0)")

(deftest retained-callback-branching-and-recursion
  (doseq [[definitions expression values]
          [["" "(if (> n 0) (+ n 1) (- n 1))" [[2 3] [-2 -3] [4 5]]]
           ["(define sum-to (network (n acc) ((if (<= n 0) (network (n acc) acc) (network (n acc) (sum-to (- n 1) (+ acc n)))) n acc)))"
            "(sum-to n 0)" [[2 3] [4 10] [1 1]]]]]
    (let [pane (editor/prepare
                 (str imports layered-import definitions "(define root 0) (def-cells n)"
                      "(define sample (network (snapshot root n) " expression "))"
                      "(display (observe-network-retained root sample n))") [])]
      (try
        (is (= :bool4/nothing (result pane))
            "A branch-selected callable waits for its input instead of contradicting")
        (doseq [[index [n expected]] (map-indexed vector values)]
          (change! pane 'n (inc index) n)
          (is (= expected (result pane))))
        (finally (editor/close-session! (:session pane)))))))

(deftest retained-layered-control-flow-with-support-evidence
  (with-redefs [change!
                (fn [pane symbol version base]
                  (let [session (:session pane) state @session
                        root (env/resolve-binding-id (:program/net state) (:program/env state) symbol)]
                    (input/commit-runtime-input! session
                      {:runtime/input :cell-message :cell-id root
                       :update (collection/content
                                 {:base base :support #{{:source root :timestamp version
                                                        :premises-status :active}}})}))) ]
    (retained-callback-branching-and-recursion)))
