(ns propagators.semantic-trace.graph-facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.gur :as gur]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as ttms]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.semantic-trace.fact-queries :as queries]
            [meander.epsilon :as m]
            [propagators.semantic-trace.high-level :as high-level]
            [propagators.semantic-trace.tracer-modifiers :as modifiers]))

(defn graph []
  (facts/from-graph :observation :root
                    {:semantic-trace/graph true
                     :nodes {:u "user" :x "internal" :r "runtime"}
                     :node-kinds {:u :cell :x :propagator :r :cell}
                     :values {:u 1 :r 2}
                     :edges [[:u :x] [:x :r]]} #{}))

(deftest source-adapters-join-colliding-qualified-assertions
  (let [g (facts/from-graph :observation :root
                            {:semantic-trace/graph true :nodes {:a "A"}
                             :values {:a 7} :graph/facts {:node/value #{[:a]}}} #{})
        id (facts/assertion-id :observation :root :node/value [:a])
        content (get (facts/assertions g) id)]
    (is (= #{7 true} (into #{} (map #(datum/layer-value % :base))
                           (ttms/observations content))))
    (is (value/contradiction? (facts/base-value (facts/current g id))))))

(defn apply-modifiers [g declarations]
  (high-level/derive-view (modifiers/apply-modifiers g declarations)))

(defn permutations [xs]
  (if (empty? xs) [[]]
      (for [x xs rest (permutations (remove #{x} xs))] (cons x rest))))

(deftest modifiers-obey-information-laws
  (let [g (graph)
        ds [(modifiers/declaration :high-level)
            (modifiers/declaration :with-values)
            (modifiers/selection ["user" "runtime"])]
        expected (apply-modifiers g ds)]
    (doseq [order (permutations ds)]
      (let [prefixes (reductions #(apply-modifiers %1 [%2]) g order)]
        (doseq [[a b] (partition 2 1 prefixes)]
          (is (facts/subsumes? b a)))
        (is (facts/equivalent? expected (last prefixes)))
        (is (= (facts/declarations expected) (facts/declarations (last prefixes))))))
    (is (facts/equivalent? expected (apply-modifiers expected ds)))
    (is (= (:nodes g) (:nodes expected)) "Selection retains hidden source facts")
    (is (= (:values g) (:values expected)))
    (is (= #{:u :r} (set (keys (:nodes (high-level/display-graph expected))))))
    (is (= [[:u :r]] (:edges (high-level/display-graph expected))))))

(deftest selector-union-and-captured-identity
  (let [g (graph)
        a (apply-modifiers g [(modifiers/selection ["user" "runtime" "user"])])
        b (apply-modifiers g [(modifiers/selection ["runtime"])
                                       (modifiers/selection ["user"])])
        capture (capture/fragment {:observation :observation :scopes {:root g}})
        modified (apply-modifiers capture [(modifiers/declaration :with-values)])]
    (is (facts/equivalent? a b))
    (is (= (capture/observation-id capture) (capture/observation-id modified)))
    (is (identical? (facts/assertions g)
                    (facts/assertions (capture/root-graph capture))))))

(deftest ttms-revisions-and-independent-evidence
  (let [active (fn [s revision status] {:source (gur/stable-node-id [::test s]) :timestamp revision
                                          :premises-status (if (= :inactive status) :retracted status)})
        p1 (active :p 1 :active)
        q1 (active :q 1 :active)
        g (-> (graph)
              (facts/claim :test/fact [:a] true #{p1} #{p1})
              (facts/claim :test/fact [:a] true #{q1} #{q1}))
        p2 (active :p 2 :inactive)
        withdrawn (facts/claim g :test/fact [:a] value/nothing #{p2} #{p2})
        q2 (active :q 2 :inactive)
        gone (facts/claim withdrawn :test/fact [:a] value/nothing #{q2} #{q2})]
    (is (facts/subsumes? withdrawn g))
    (is (contains? (get (facts/relations withdrawn) :test/fact) [:a]))
    (is (not (contains? (get (facts/relations gone) :test/fact) [:a])))
    (is (= (facts/assertions gone)
           (facts/assertions (facts/claim gone :test/fact [:a] true #{p1} #{p1}))))))

(deftest transformations-require-valid-witnesses
  (let [g (graph) id (facts/assertion-id :observation :root :graph/edge [:u :x])]
    (is (thrown? Exception (facts/derive g {:predicate :derived :tuple [:u] :base true})))
    (is (thrown? Exception (facts/derive g {:predicate :derived :tuple [:u] :base true
                                           :contributors #{[:foreign :root :graph/edge [:u :x]]}})))
    (is (contains? (facts/relations (facts/derive g {:predicate :derived :tuple [:u]
                                                   :base true :contributors #{id}}))
                   :derived))))

(defn premise [source revision status]
  {:source (gur/stable-node-id [::evidence source])
   :timestamp revision :premises-status status})

(defn empty-graph [] (facts/from-graph :observation :root {} #{}))

(deftest derived-facts-retain-alternative-justifications
  (let [p (premise :p 1 :active) q (premise :q 1 :active)
        id (facts/assertion-id :observation :root :edge [:a :b])
        result-id (facts/assertion-id :observation :root :derived [:a :b])
        g (-> (empty-graph)
              (facts/claim :edge [:a :b] true #{p} #{p})
              (facts/claim :edge [:a :b] true #{q} #{q}))
        derived (facts/derive g {:predicate :derived :tuple [:a :b] :base true :contributors #{id}})
        withdrawn (facts/update-premise-state derived #{(premise :p 2 :retracted)})
        gone (facts/update-premise-state withdrawn #{(premise :q 2 :retracted)})]
    (is (= 2 (count (ttms/observations (get (facts/assertions derived) result-id)))))
    (is (true? (facts/base-value (facts/current withdrawn result-id))))
    (is (value/nothing? (facts/base-value (facts/current gone result-id))))
    (is (= #{#{id}} (facts/contributor-evidence withdrawn result-id)))
    (is (empty? (facts/contributor-evidence gone result-id)))
    (is (thrown? Exception (facts/derive gone {:predicate :invalid :tuple [] :base true :contributors #{id}})))))

(deftest positive-query-withdrawal-cleans-recursive-cycles
  (let [p (premise :p 1 :active) q (premise :q 1 :active)
        g (-> (empty-graph)
              (facts/claim :edge [:a :b] true #{p} #{p})
              (facts/claim :edge [:b :a] true #{q} #{q}))
        rules [{:head [:reachable [:x :y]] :body [[:edge [:x :y]]]}
               {:head [:reachable [:x :z]] :body [[:edge [:x :y]] [:reachable [:y :z]]]}]
        derived (queries/query g rules)
        withdrawn (queries/query (facts/update-premise-state derived #{(premise :p 2 :retracted)}) rules)
        gone (queries/query (facts/update-premise-state withdrawn #{(premise :q 2 :retracted)}) rules)]
    (is (= #{[:a :a] [:a :b] [:b :a] [:b :b]} (:reachable (facts/relations derived))))
    (is (= #{[:b :a]} (:reachable (facts/relations withdrawn))))
    (is (empty? (:reachable (facts/relations gone))))
    (is (not (contains? (facts/source-relations derived) :reachable)))
    (is (thrown? Exception (queries/query g rules 1)))))

(deftest meander-rewrites-must-return-contributor-receipts
  (let [g (facts/claim (empty-graph) :edge [:a :b] true #{} #{})
        id (facts/assertion-id :observation :root :edge [:a :b])
        rewritten (queries/transform
                   g (fn [edb]
                       (m/match edb
                         {:edge ?pairs}
                         (mapv (fn [tuple] {:predicate :reshaped :tuple tuple :base true
                                            :contributors #{id}}) ?pairs)
                         _ [])))]
    (is (= #{[:a :b]} (:reshaped (facts/relations rewritten))))
    (is (= #{#{id}} (facts/contributor-evidence rewritten
                     (facts/assertion-id :observation :root :reshaped [:a :b]))))
    (is (thrown? Exception (queries/transform g (constantly [{:predicate :invalid :tuple [] :base true}]))))))

(deftest completeness-is-explicit-supported-and-withdrawable
  (let [p (premise :completeness 1 :active)
        closed (facts/complete (empty-graph) :edge #{p} #{p})
        complete-id (facts/assertion-id :observation :root :scope/complete [:edge])
        receipt {:predicate :absent :tuple [:a :b] :absent [:edge [:a :b]] :completeness complete-id}
        derived (facts/derive-absence closed receipt)
        withdrawn (facts/update-premise-state derived #{(premise :completeness 2 :retracted)})
        recovered (facts/complete withdrawn :edge #{(premise :completeness 3 :active)}
                                 #{(premise :completeness 3 :active)})]
    (is (= #{[:a :b]} (:absent (facts/relations derived))))
    (is (empty? (:absent (facts/relations withdrawn))))
    (is (thrown? Exception (facts/derive-absence (empty-graph) receipt)))
    (is (thrown? Exception (facts/derive-absence withdrawn receipt)))
    (is (thrown? Exception (facts/claim closed :edge [:a :b] true #{} #{})))
    (is (= #{[:a :b]} (:absent (facts/relations (facts/derive-absence recovered receipt)))))
    (is (thrown? Exception (facts/derive-absence
                           (facts/claim withdrawn :edge [:a :b] true #{} #{}) receipt)))))

(deftest modifier-evidence-is-independent-and-commutative
  (let [d (modifiers/declaration :with-values)
        p (premise :p 1 :active) q (premise :q 1 :active)
        a (ttms/content {:base d :support #{p} :premise-state #{p}})
        b (ttms/content {:base d :support #{q} :premise-state #{q}})
        x (apply-modifiers (graph) (modifiers/normalize-supported [d d] [a b]))
        y (apply-modifiers (graph) (modifiers/normalize-supported [d d] [b a]))
        p-gone (facts/update-premise-state x #{(premise :p 2 :retracted)})
        both-gone (facts/update-premise-state p-gone #{(premise :q 2 :retracted)})]
    (is (facts/equivalent? x y))
    (is (facts/enabled? p-gone :with-values))
    (is (not (facts/enabled? both-gone :with-values)))
    (is (facts/subsumes? both-gone x))))

(deftest same-revision-conflicts-are-retained-and-recover
  (let [p1 (premise :p 1 :active) p2 (premise :p 2 :active)
        id (facts/assertion-id :observation :root :test/value [:a])
        conflict (-> (empty-graph)
                     (facts/claim :test/value [:a] 1 #{p1} #{p1})
                     (facts/claim :test/value [:a] 2 #{p1} #{p1}))
        recovered (facts/claim conflict :test/value [:a] 3 #{p2} #{p2})]
    (is (value/contradiction? (facts/base-value (facts/current conflict id))))
    (is (= 2 (count (ttms/observations (get (facts/assertions conflict) id)))))
    (is (= 3 (facts/base-value (facts/current recovered id))))
    (is (= (facts/assertions recovered)
           (facts/assertions (facts/claim recovered :test/value [:a] 1 #{p1} #{p1}))))))

(deftest generated-modifier-joins-are-associative-and-inflationary
  (let [h (modifiers/declaration :high-level) v (modifiers/declaration :with-values)
        groups [[] [h] [v] [(modifiers/selection [])]
                [(modifiers/selection ["user"]) ]
                [(modifiers/selection ["runtime"]) ]
                [(modifiers/selection ["runtime" "user" "user"])]]
        indices (mapv #(modifiers/normalize-supported % (repeat (count %) value/nothing)) groups)
        source (graph)]
    (doseq [a indices b indices c indices]
      (let [left (modifiers/join (modifiers/join a b) c)
            right (modifiers/join a (modifiers/join b c))
            evaluated (apply-modifiers source left)
            prefixes (reductions apply-modifiers source [a b c])]
        (is (facts/equivalent? evaluated (apply-modifiers source right)))
        (is (facts/equivalent? evaluated (last prefixes)))
        (doseq [[before after] (partition 2 1 prefixes)]
          (is (facts/subsumes? after before)))))))

(deftest selection-grows-without-invalidating-path-facts
  (let [a (apply-modifiers (graph) [(modifiers/declaration :high-level)
                                    (modifiers/selection ["user" "runtime"])])
        b (apply-modifiers a [(modifiers/selection ["internal"])])]
    (is (facts/subsumes? b a))
    (is (contains? (:view/path (facts/relations b)) [:u :r #{:x}]))
    (is (= #{[:u :x] [:x :r]} (set (:edges (high-level/display-graph b)))))
    (is (= #{:u :x :r} (set (keys (:nodes (high-level/display-graph b))))))
    (is (= #{[:u :x] [:x :r]} (:graph/edge (facts/relations b))))
    (is (thrown? Exception (high-level/derive-view
                            (modifiers/apply-modifiers (graph)
                              [(modifiers/declaration :high-level)
                               (modifiers/selection ["user" "runtime"])]) 1)))))

(deftest captured-values-preserve-bottom-withdrawal-and-recovery
  (let [p1 (premise :value 1 :active) p2 (premise :value 2 :active)
        base (facts/from-graph :observation :root
               {:semantic-trace/graph true :nodes {:u "user"} :node-kinds {:u :cell}} #{})
        pending (apply-modifiers (facts/claim base :node/value [:u] value/nothing #{p1} #{p1})
                                 [(modifiers/declaration :with-values)])
        id (facts/assertion-id :observation :root :view/value [:u])
        ready (high-level/derive-view (facts/claim pending :node/value [:u] 42 #{p2} #{p2}))
        withdrawn (high-level/derive-view (facts/update-premise-state ready #{(premise :value 3 :retracted)}))
        recovered (high-level/derive-view
                   (facts/claim withdrawn :node/value [:u] 43 #{(premise :value 4 :active)}
                                #{(premise :value 4 :active)}))]
    (is (value/nothing? (facts/base-value (facts/current pending id))))
    (is (seq (datum/support-of (facts/current pending id))))
    (is (= 42 (facts/base-value (facts/current ready id))))
    (is (value/nothing? (facts/base-value (facts/current withdrawn id))))
    (is (seq (datum/layer-value (facts/current withdrawn id) :premise-state)))
    (is (= 43 (facts/base-value (facts/current recovered id))))))

(deftest values-are-not-realized-or-reconstructed
  (let [opaque (delay (throw (ex-info "Must remain opaque" {})))
        g (facts/from-graph :observation :root
            {:semantic-trace/graph true :nodes {:u "user"} :values {:u opaque}} #{})
        viewed (apply-modifiers g [(modifiers/declaration :with-values)])
        id (facts/assertion-id :observation :root :view/value [:u])]
    (is (not (realized? opaque)))
    (is (identical? opaque (facts/base-value (facts/current viewed id))))
    (is (identical? opaque (get (:values (facts/current-graph viewed)) :u)))
    (is (not (realized? opaque)))))
