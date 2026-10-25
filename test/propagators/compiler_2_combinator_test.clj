(ns propagators.compiler-2-combinator-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.compiler-2.runtime.operators.bounded-history :as history]
            [propagators.datastructures.compound-object :as obj]
            [propagators.message :refer [message]]
            [propagators.helpers.task-queue :as queue]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.stdlib.source-publication :as publication]))

(def library-source
  "(load-lain \"propagators/compiler_2/runtime/lain/combinator.lain\")\n")

(defn checked-load! [session source]
  (when (seq source)
    (let [result (loader/load-source! session source)]
      (when-let [error (:error (:load-result result))]
        (throw (ex-info "Lain test source failed"
                        {:source source
                         :error (-> (select-keys error [:message :form :data])
                                    (update :data dissoc :known))})))))
  session)

(defn session [source]
  (-> (loader/load-session-from-source "0")
      (checked-load! library-source)
      (checked-load! source)))

(defn binding-value [session name]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)]
    (when-not id
      (throw (ex-info "Missing test binding" {:binding name})))
    (let [candidate (net/network-cell-strongest network id)]
      (if (and (net/net? candidate)
               (contains? (obj/public-slot-keys candidate) :base))
        (obj/slot-strongest candidate :base)
        candidate))))

(defn publish! [session name source revision candidate]
  (let [state @session
        network (:program/net state)
        id (env/resolve-binding-id network (:program/env state) name)
        update (publication/active source revision candidate)
        entry (merge/merge-cell-entry (net/network-lookup-cell network id) update network)
        updated (net/assoc-net-cell network id entry)
        tasks (queue/enqueue-all queue/empty-queue (nb/neighbor-propagator-ids updated id))
        settled (runner/completed-network (runner/run-network tasks updated))]
    (swap! session assoc :program/net settled)
    session))

(defn reevaluate! [session]
  (let [network (:program/net @session)
        tasks (queue/enqueue-all
               queue/empty-queue
               (for [[id entry] (net/net-env network)
                     :when (not (cell/cell? entry))]
                 id))
        settled (runner/completed-network (runner/run-network tasks network))]
    (swap! session assoc :program/net settled)
    settled))

(deftest factory-configuration-distinguishes-unknown-from-contradictory-information
  (doseq [factory ["slot" "history"]]
    (testing factory
      (let [unknown (session
                     (str "(define configuration)
                           (define answer (" factory " configuration))"))
            contradictory (session
                           (str "(define answer (" factory " :bool4/contradiction))"))]
        (is (value/nothing? (binding-value unknown 'answer)))
        (is (value/contradiction? (binding-value contradictory 'answer))
            "Configuration failure must remain visible instead of becoming an unresolved factory")))))

(deftest importing-the-library-installs-its-primitive-extension
  (let [loaded (session "(define answer (|> 3 (network [x] (+ x 1))))")]
    (is (= 4 (binding-value loaded 'answer)))))

(deftest structs-declare-connected-partial-endpoints
  (let [loaded (session
                "(def-cells incoming feedback untouched)
                 (define object (struct :incoming incoming :feedback feedback
                                        :untouched untouched))
                 (define read-input ((slot :incoming) object))
                 (-> 42 ((slot :feedback) object))")]
    (is (= #{:incoming :feedback :untouched}
           (set (obj/accessor-slot-keys (binding-value loaded 'object)))))
    (is (value/nothing? (binding-value loaded 'read-input)))
    (is (= 42 (binding-value loaded 'feedback)))
    (is (value/nothing? (binding-value loaded 'untouched)))
    (publish! loaded 'incoming ::input 0 1)
    (is (= 1 (binding-value loaded 'read-input)))
    (publish! loaded 'incoming ::input 1 2)
    (is (= 2 (binding-value loaded 'read-input)))
    (let [before (count (net/net-env (:program/net @loaded)))
          settled (reevaluate! loaded)]
      (is (= before (count (net/net-env settled))))
      (is (= 42 (binding-value loaded 'feedback))))))

(deftest nested-structs-preserve-writeback-to-the-original-cell
  (let [loaded (session
                "(define result)
                 (define framework (struct :harbor (struct :observations result)))
                 (-> 7 (|> framework (slot :harbor) (slot :observations)))
                 (define empty (struct))")]
    (is (= 7 (binding-value loaded 'result)))
    (is (empty? (obj/accessor-slot-keys (binding-value loaded 'empty))))))

(deftest malformed-struct-interfaces-are-rejected
  (doseq [source ["(struct :one)" "(struct 1 2)" "(struct :one 1 :one 2)"]]
    (is (thrown? Exception (session source)) source)))

(deftest unary-pipes-and-composition-have-explicit-direction
  (doseq [[description source expected]
          [["An empty pipe is an identity endpoint"
            "(define answer (|> 3))" 3]
           ["Pipe stages run from left to right"
            "(define increment (network [x] (+ x 1)))
             (define double (network [x] (* x 2)))
             (define answer (|> 3 increment double))" 8]
           ["Composition applies its rightmost stage first"
            "(define increment (network [x] (+ x 1)))
             (define double (network [x] (* x 2)))
             (define answer ((compose increment double) 3))" 7]]]
    (testing description
      (is (= expected (binding-value (session source) 'answer))))))

(deftest a-pipe-remains-connected-to-a-deferred-stage
  (let [loaded (session "(define stage) (define answer (|> 3 stage))")]
    (is (value/nothing? (binding-value loaded 'answer)))
    (let [update (loader/load-source! loaded
                  "(-> (network [x] (+ x 2)) stage)")]
      (is (nil? (:error (:load-result update))))
      (is (= 5 (binding-value loaded 'answer))))))

(deftest protocol-calls-share-partial-owner-slots-with-the-caller
  (let [loaded (session
                "(define write-protocol
                   (network [framework]
                     (-> 42 (|> framework (slot :answer)))
                     (list framework)))
                 (define framework)
                 (write-protocol framework)
                 (define answer (p:slot :answer framework))")]
    (is (= 42 (binding-value loaded 'answer))
        "Returning a protocol owner must preserve its public slot connections even when the call result is unused")))

(deftest composed-slot-endpoints-preserve-partial-owner-writeback
  (doseq [[endpoint canonical]
          [["((slot :answer) framework)" "(p:slot :answer framework)"]
           ["(|> framework (slot :answer))" "(p:slot :answer framework)"]
           ["((compose (slot :answer)) framework)" "(p:slot :answer framework)"]
           ["(|> framework (slot :nested) (slot :answer))"
            "(p:slot :answer (p:slot :nested framework))"]
           ["((compose (slot :answer) (slot :nested)) framework)"
            "(p:slot :answer (p:slot :nested framework))"]]]
    (testing endpoint
      (let [loaded (session
                    (str "(define framework)
                          (define unseen (|> framework (slot :unseen)))
                          (-> 42 " endpoint ")
                          (define selected " endpoint ")
                          (define canonical " canonical ")"))]
        (is (= 42 (binding-value loaded 'selected)))
        (is (= 42 (binding-value loaded 'canonical))
            "The slot view writes into the same unresolved owner")
        (is (value/nothing? (binding-value loaded 'unseen))
            "An unrelated unresolved slot does not block the selected endpoint")))))

(deftest accessor-endpoints-can-share-information-between-partial-components
  (let [loaded (session
                "(define position-of (slot :position))
                 (def-cells left right)
                 (<-> (position-of left) (position-of right))
                 (-> 42 (position-of left))
                 (define answer (position-of right))")]
    (is (= 42 (binding-value loaded 'answer)))))

(deftest switched-transformations-are-directed-and-wait-for-evidence
  (doseq [[predicate expected]
          [["true" 7] ["false" ::waiting]]]
    (let [loaded (session
                  (str "(define predicate (network [x] " predicate "))
                        (define answer (|> 7 (switch predicate)))"))
          answer (binding-value loaded 'answer)]
      (if (= ::waiting expected)
        (is (value/nothing? answer))
        (is (= expected answer)))))
  (let [loaded (session
                "(define predicate)
                 (define answer (|> 7 (switch predicate)))")]
    (is (value/nothing? (binding-value loaded 'answer)))))

(deftest predicate-gates-react-to-new-information
  (let [loaded (session
                "(define permission)
                 (define predicate (network [x] permission))
                 (define answer (|> 7 (switch predicate)))")]
    (is (value/nothing? (binding-value loaded 'answer)))
    (let [update (loader/load-source! loaded "(-> true permission)")]
      (is (nil? (:error (:load-result update))))
      (is (= 7 (binding-value loaded 'answer))))))

(deftest the-existing-two-argument-switch-remains-a-directed-gate
  (is (= 7 (binding-value (session "(define answer (switch 7 true))") 'answer)))
  (is (value/nothing? (binding-value (session "(define answer (switch 7 false))") 'answer))))

(deftest a-nonboolean-switch-predicate-is-an-inspectable-failure
  (let [loaded (session "")
        update (loader/load-source! loaded
                 "(define predicate (network [x] 42))
                  (define answer (|> 7 (switch predicate)))")]
    (is (or (:error (:load-result update))
            (value/contradiction? (binding-value loaded 'answer)))
        "Unsupported predicate values must not be silently treated as truthy or filtered out")))

(deftest harbor-transformer-wrappers-return-facts-without-an-extra-list
  (doseq [[wrapper leaf fact]
          [['harbor:trial-assessments 'harbor:observe-assessment :assessment]
           ['harbor:preparation-requests 'harbor:prepare :preparation-request]
           ['harbor:grading-requests 'harbor:grade :grading-request]]]
    (testing wrapper
      (let [loaded (session
                    (str "(load-lain \"examples/lain/subagents/evaluation-framework.lain\")
                          (define " leaf "
                            (network [left right output]
                              (-> " (pr-str fact) " output)
                              (list output)))
                          (define answer (" wrapper " :left :right))"))]
        (is (= fact (binding-value loaded 'answer))
            "The wrapper's final expression is its fact value, not a list constructor")))))

(defn copy-test-port [name source-index destination-index]
  (operator/propagator-operator
   {:name name
    :input-selector (fn [arguments _fallback _context] [(nth arguments source-index)])
    :output-selector (fn [arguments fallback]
                       (if (some? destination-index) (nth arguments destination-index) fallback))
    :messages (fn [network {:keys [inputs outputs]}]
                [(message (first outputs) (net/network-cell-strongest network (first inputs)))])}))

(defn synthetic-policy-extension []
  ;; Controlled policies exercise the declared connections, not Harbor scheduling.
  (extension/extension-bundle
   {:id ::synthetic-policies
    :bindings [['evaluation:assignment-trials (copy-test-port ::trials 0 1)]
               ['evaluation:runnable-assignments (copy-test-port ::runnable 1 nil)]
               ['harbor:prepare (copy-test-port ::prepare 0 2)]
               ['harbor:observe-assessment (copy-test-port ::assessment 1 2)]
               ['evaluation:assessment-updates (copy-test-port ::feedback 1 nil)]]
    :effects []}))

(deftest the-evaluation-framework-has-explicit-independent-interfaces
  (let [loaded (session
                "(load-lain \"examples/lain/subagents/evaluation-framework.lain\")
                 (define framework (make-evaluation-framework))
                 (define harbor (evaluation:harbor framework))
                 (define pending (on-going-assignment framework))
                 (define requests (harbor:requests harbor))")]
    (is (= #{:assignments :on-going-assignment :assignment-result :harbor}
           (set (obj/accessor-slot-keys (binding-value loaded 'framework)))))
    (is (= #{:requests :observations}
           (set (obj/accessor-slot-keys (binding-value loaded 'harbor)))))
    (is (value/nothing? (binding-value loaded 'pending)))
    (is (value/nothing? (binding-value loaded 'requests)))))

(deftest assignment-order-and-result-feedback-share-the-declared-ports
  (let [loaded (-> (loader/load-session-from-source
                   "0" {:extensions [(synthetic-policy-extension)]})
                  (checked-load! library-source)
                  (checked-load!
                   "(load-lain \"examples/lain/subagents/evaluation-framework.lain\")
                    (define order)
                    (define results)
                    (define framework (make-evaluation-framework))
                    (define harbor (evaluation:harbor framework))
                    (<-> (struct :order order :results results) (assignments framework))
                    (define scheduled ((slot :order) (on-going-assignment framework)))
                    (define requested ((slot :order) (harbor:requests harbor)))
                    (define completed ((slot :results) (assignments framework)))"))]
    (publish! loaded 'order ::user-order 0 [:a :b])
    (is (= [:a :b] (binding-value loaded 'scheduled)))
    (is (= [:a :b] (binding-value loaded 'requested)))
    (publish! loaded 'order ::user-order 1 [:b :a])
    (is (= [:b :a] (binding-value loaded 'scheduled)))
    (is (= [:b :a] (binding-value loaded 'requested)))
    (checked-load! loaded
      "(-> (struct :results :a-complete) (harbor:observations harbor))")
    (is (= :a-complete (binding-value loaded 'completed)))
    (is (= [:b :a] (binding-value loaded 'requested))
        "Feedback enriches results without replacing the current user order")))

(deftest addition-constraint-supports-each-justified-direction
  (doseq [[known expected]
          [["(-> 2 a) (-> 3 b)" ['c 5]]
           ["(-> 5 c) (-> 3 b)" ['a 2]]
           ["(-> 5 c) (-> 2 a)" ['b 3]]]]
    (let [loaded (session
                  (str "(def-cells a b c)
                        (constraint:+ a b c) " known))]
      (is (= (second expected) (binding-value loaded (first expected)))))))

(deftest predicate-gates-retract-obsolete-supported-truth
  (let [loaded (session
                "(define permission)
                 (define predicate (network [x] permission))
                 (define answer (|> 7 (switch predicate)))")]
    (publish! loaded 'permission ::permission 0 true)
    (is (= 7 (binding-value loaded 'answer)))
    (publish! loaded 'permission ::permission 1 false)
    (is (value/nothing? (binding-value loaded 'answer)))
    (publish! loaded 'permission ::permission 2 true)
    (is (= 7 (binding-value loaded 'answer)))))

(deftest factory-reevaluation-preserves-callable-identities-and-existing-topology
  (let [loaded (session
                "(define position-of (slot :position))
                 (define retain (history 2))
                 (define owner)
                 (define recent (|> 1 retain))
                 (-> 42 (position-of owner))
                 (define answer (position-of owner))")
        network (:program/net @loaded)
        count-before (count (net/net-env network))
        settled (reevaluate! loaded)]
    (is (= count-before (count (net/net-env settled)))
        "Reevaluation must reuse installed calls instead of growing the graph")
    (is (not (value/contradiction? (binding-value loaded 'position-of))))
    (is (not (value/contradiction? (binding-value loaded 'retain))))
    (is (= 42 (binding-value loaded 'answer)))
    (is (= [1] (binding-value loaded 'recent)))
    (is (= [1] (mapv history/recording-count (history/recorder-states settled))))))

(deftest importing-the-library-in-another-session-preserves-existing-gates
  (let [first-session (session
                       "(define incoming)
                        (define permission)
                        (define predicate (network [x] permission))
                        (define filtered (|> incoming (switch predicate)))
                        (define legacy (switch incoming permission))")]
    (publish! first-session 'permission ::permission 0 true)
    (publish! first-session 'incoming ::incoming 0 7)
    (is (= 7 (binding-value first-session 'filtered)))
    (is (= 7 (binding-value first-session 'legacy)))
    (let [second-session (session "(define independent 0)")]
      (is (= 0 (binding-value second-session 'independent))))
    (reevaluate! first-session)
    (is (= 7 (binding-value first-session 'filtered)))
    (is (= 7 (binding-value first-session 'legacy)))
    (publish! first-session 'incoming ::incoming 1 8)
    (is (= 8 (binding-value first-session 'filtered)))
    (is (= 8 (binding-value first-session 'legacy)))
    (publish! first-session 'permission ::permission 1 false)
    (is (value/nothing? (binding-value first-session 'filtered)))
    (is (value/nothing? (binding-value first-session 'legacy)))
    (publish! first-session 'permission ::permission 2 true)
    (is (= 8 (binding-value first-session 'filtered)))
    (is (= 8 (binding-value first-session 'legacy)))))

(deftest bounded-recordings-deduplicate-stamps-and-retain-real-storage-bounds
  (let [empty (history/new-recording 2)
        first (history/record-value empty :a [:source 0])
        duplicate (history/record-value first :a [:source 0])
        repeated-value (history/record-value first :a [:source 1])
        latest (history/record-value repeated-value :b [:source 2])]
    (is (= [] (history/recording-values empty)))
    (is (= first duplicate) "Reevaluation of the same observation is idempotent")
    (is (= [:a :a] (history/recording-values repeated-value))
        "A new source revision remains a new observation even when its payload is equal")
    (is (= [:a :b] (history/recording-values latest)))
    (is (= 2 (history/recording-count latest)))
    (is (= 3 (history/recording-sequence latest)))
    (is (= [:a] (history/recording-values first)) "Earlier recording snapshots stay immutable")))

(deftest compatible-recording-merges-are-associative-commutative-and-idempotent
  (let [a (history/record-value (history/new-recording 2) :a [:source 0])
        b (history/record-value a :b [:source 1])
        c (history/record-value b :c [:source 2])]
    (is (= a (history/merge-recordings a a)))
    (is (= (history/merge-recordings a c) (history/merge-recordings c a)))
    (is (= (history/merge-recordings (history/merge-recordings a b) c)
           (history/merge-recordings a (history/merge-recordings b c))))
    (is (= [:b :c] (history/recording-values (history/merge-recordings a c))))
    (is (thrown? Exception
                 (history/merge-recordings
                  a (history/record-value (history/new-recording 2) :conflict [:other 0]))))))

(deftest recording-capacity-is-an-explicit-positive-integer
  (doseq [capacity [0 -1 1.5 nil]]
    (is (thrown? Exception (history/new-recording capacity)))))

(deftest incoming-value-history-is-bounded-reactive-and-session-local
  (let [source "(define incoming)
                (define other-incoming)
                (define retain (history 5))
                (define recent (|> incoming retain))
                (define other (|> other-incoming retain))"
        loaded (session source)
        isolated (session source)]
    (is (= [] (binding-value loaded 'recent)))
    (is (= [] (binding-value loaded 'other)))
    (publish! loaded 'incoming ::source 0 1)
    (let [first-snapshot @loaded]
      (is (= [1] (binding-value loaded 'recent)))
      (publish! loaded 'incoming ::source 0 1)
      (is (= [1] (binding-value loaded 'recent)) "Duplicate propagation does not append")
      (publish! loaded 'incoming ::source 1 1)
      (is (= [1 1] (binding-value loaded 'recent)) "A new source revision is observable")
      (doseq [number (range 2 8)]
        (publish! loaded 'incoming ::source number number))
      (publish! loaded 'other-incoming ::other 0 99)
      (is (= [3 4 5 6 7] (binding-value loaded 'recent)))
      (is (= [99] (binding-value loaded 'other)) "Applications of one factory have separate buffers")
      (let [recordings (history/recorder-states (:program/net @loaded))]
        (is (= 2 (count recordings)))
        (is (= #{1 5} (set (map history/recording-count recordings))))
        (is (every? #(<= (history/recording-count %) 5) recordings)
            "The recorder's actual current storage is bounded, not merely its display"))
      (is (= [1] (binding-value (atom first-snapshot) 'recent))
          "Later observations do not mutate the earlier network snapshot")
      (publish! loaded 'incoming ::source 8 value/nothing)
      (is (= [3 4 5 6 7] (binding-value loaded 'recent)) "Unknown information is not a sample")
      (is (= [] (binding-value isolated 'recent)))
      (publish! isolated 'incoming ::source 0 500)
      (is (= [500] (binding-value isolated 'recent)))
      (is (= [3 4 5 6 7] (binding-value loaded 'recent)) "Sessions cannot consume each other's observations"))))
