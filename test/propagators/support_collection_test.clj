(ns propagators.support-collection-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.spec.alpha :as s]
            [examples.lain.visualization-combinators.support-retraction-demo :as demo]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(def source-ids (zipmap [:A :B :C] (repeatedly 3 ids/new-node-id)))

(defn- premise [source timestamp status]
  {:source (get source-ids source source)
   :timestamp timestamp :premises-status status})

(defn- observation [base & supports]
  (collection/content {:base base :support (set supports)}))

(defn- join [& contents]
  (reduce collection/merge-content value/nothing contents))

(defn- strongest [content]
  (merge/strongest-value content net/empty-net))

(defn- base [content]
  (datum/layer-value (strongest content) :base))

(deftest normalization-and-validation
  (let [a (premise :A 1 :active)
        raw {:base {:unwrapped? false} :support #{a}}
        left (obj/compound-object raw)
        right (obj/compound-object raw)]
    (is (= (collection/content raw) (collection/content left) (collection/content right)))
    (is (s/valid? ::collection/observation raw))
    (is (s/valid? ::collection/evidence #{raw}))
    (is (not (s/valid? ::collection/observation (assoc raw :extra 3))))
    (is (= (:base raw) (base (collection/content left))))
    (is (= 1 (count (:support/observations
                     (join (collection/content left) (collection/content right))))))
    (doseq [invalid [10 {:base 1} {:base 1 :support []}
                     {:base 1 :support #{a} :extra :must-not-disappear}
                     (obj/compound-object {:base 1 :support #{a} :extra 3})
                     {:base 1 :support #{(assoc a :payload :forbidden)}}]]
      (is (thrown? clojure.lang.ExceptionInfo (collection/content invalid))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (collection/merge-content {:support/observations []} value/nothing)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (collection/merge-content (collection/content raw) value/contradiction)))))

(deftest dominance-merge-laws
  (let [values [(observation 10 (premise :A 1 :active))
                (observation 20 (premise :A 2 :active))
                (observation value/nothing (premise :A 3 :retracted))
                (observation 10 (premise :B 1 :active))
                (observation 30 (premise :A 1 :active) (premise :A 2 :active))
                (observation 21 (premise :A 2 :active))
                (observation 30 (premise :A 2 :active) (premise :B 1 :active))
                (observation value/nothing)]]
    (doseq [a values]
      (is (= a (join a a) (join value/nothing a) (join a value/nothing))))
    (doseq [a values b values]
      (is (= (join a b) (join b a))))
    (doseq [a values b values c values]
      (is (= (join (join a b) c) (join a (join b c)))))
    ;; Joint A@2/B@1 evidence now subsumes the B-only observation too.
    (is (= (into #{} (mapcat :support/observations) [(nth values 2) (nth values 6)])
           (:support/observations (apply join values))))))

(deftest freshness-compacts-content-and-rejects-stale-replay
  (let [old (observation 10 (premise :A 1 :active))
        fresh (observation 20 (premise :A 2 :active))
        retained (join old fresh)]
    (is (value/nothing? (strongest value/nothing)))
    (is (value/nothing? (strongest {:support/observations #{}})))
    (is (= 20 (base retained)))
    (is (= (strongest retained) (strongest (join retained old))))
    (is (= #{(premise :A 2 :active)} (datum/support-of (strongest retained))))
    (is (= fresh retained (join fresh old)))
    (is (= retained (merge/cell-merge old fresh net/empty-net)))))

(deftest equal-version-conflicts-and-recovery
  (doseq [[left right]
          [[(observation 10 (premise :A 1 :active))
            (observation 11 (premise :A 1 :active))]
           [(observation 10 (premise :A 1 :active))
            (observation value/nothing (premise :A 1 :retracted))]]]
    (let [conflicted (join left right)
          recovered (join conflicted (observation 12 (premise :A 2 :active)))]
      (is (value/unusable? (strongest conflicted)))
      (is (= (strongest conflicted) (strongest (join right left))))
      (is (= 12 (base recovered)))
      (is (not (value/unusable? (strongest recovered))))
      (is (= 2 (count (:support/observations conflicted))))
      (is (= 1 (count (:support/observations recovered)))))))

(deftest coherent-value-dominates-mixed-versions
  (let [mixed (observation 33 (premise :A 1 :active) (premise :A 2 :active))
        coherent (observation 43 (premise :A 2 :active))]
    (is (= coherent (join mixed coherent) (join coherent mixed)))
    (is (= 43 (base (join mixed coherent))))
    (is (not (value/unusable? (strongest (join mixed coherent)))))))

(deftest dominance-does-not-erase-uncovered-or-concurrent-premises
  (doseq [[left right]
          [[(observation 10 (premise :A 1 :active))
            (observation 20 (premise :B 2 :active))]
           [(observation 30 (premise :A 1 :active) (premise :B 1 :active))
            (observation value/nothing (premise :A 2 :retracted))]
           [(observation 30 (premise :A 2 :active) (premise :B 1 :active))
            (observation 40 (premise :A 1 :active) (premise :B 2 :active))]]]
    (is (= 2 (count (:support/observations (join left right)))))
    (is (= (join left right) (join right left)))))

(deftest retraction-dominates-active-but-stale-active-cannot-return
  (let [active (observation 10 (premise :A 1 :active))
        retracted (observation value/nothing (premise :A 2 :retracted))
        restored (observation 20 (premise :A 3 :active))]
    (is (= retracted (join active retracted) (join retracted active)))
    (is (= restored (join active retracted restored)
           (join restored retracted active)))))

(deftest incompatible-computations-are-not-relabeled
  (let [a1 (premise :A 1 :active)
        a2 (premise :A 2 :active)
        incompatible (observation 30 a1 a2)
        projected (strongest incompatible)]
    (is (= 30 (datum/layer-value projected :base)))
    (is (= #{a1 a2} (datum/support-of projected)))
    (is (value/unusable? projected))
    (let [later (join incompatible (observation 40 (premise :A 3 :active)))]
      (is (= 40 (base later)))
      (is (not (value/unusable? (strongest later))))))
  (let [old (observation 30 (premise :A 1 :active) (premise :B 1 :active))
        advanced (join old (observation value/nothing (premise :A 2 :active)))]
    (is (value/nothing? (base advanced)))
    ;; B belonged only to the stale computation, not the current observation.
    (is (= #{(premise :A 2 :active)}
           (datum/support-of (strongest advanced))))))

(deftest stale-observation-support-does-not-contaminate-current-projection
  (let [c1 (premise :C 1 :active) c2 (premise :C 2 :active)
        a2 (premise :A 2 :retracted) b1 (premise :B 1 :active)
        old (observation value/nothing c1 a2)]
    (doseq [payload [20 value/nothing value/contradiction]
            order [[old (observation payload c2 b1)]
                   [(observation payload c2 b1) old]]]
      (let [content (apply join order)
            result (strongest content)]
        (is (= 2 (count (:support/observations content))))
        (is (= payload (datum/layer-value result :base)))
        (is (= #{c2 b1} (datum/support-of result)))
        (is (= (value/unusable? payload) (value/unusable? result)))
        (is (= result (strongest (join content old))))))))

(deftest no-current-observation-still-carries-invalidation-information
  (let [a1 (premise :A 1 :active) a2 (premise :A 2 :active)
        b1 (premise :B 1 :active) b2 (premise :B 2 :retracted)
        content (join (observation 10 a1 b2) (observation 20 a2 b1))
        result (strongest content)]
    (is (= 2 (count (:support/observations content))))
    (is (value/nothing? (datum/layer-value result :base)))
    (is (= #{a2 b2} (datum/support-of result)))
    (is (value/unusable? result))))

(deftest sources-are-conjunctive-even-for-equal-values
  (let [initial (join (observation 10 (premise :A 1 :active))
                      (observation 10 (premise :B 1 :active)))
        withdrawn (join initial (observation value/nothing (premise :A 2 :retracted)))
        restored (join withdrawn (observation 10 (premise :A 3 :active)))]
    (is (= 10 (base initial) (base withdrawn) (base restored)))
    (is (not (value/unusable? (strongest initial))))
    (is (value/unusable? (strongest withdrawn)))
    (is (= #{(premise :A 2 :retracted) (premise :B 1 :active)}
           (datum/support-of (strongest withdrawn))))
    (is (not (value/unusable? (strongest restored))))))

(deftest independent-source-conflict-retains-dependencies-and-recovers
  (let [a1 (premise :A 1 :active)
        b7 (premise :B 7 :active)
        a2 (premise :A 2 :retracted)
        a3 (premise :A 3 :active)
        left (observation 10 a1)
        right (observation 20 b7)
        conflicted (join left right)
        withdrawn (join conflicted (observation value/nothing a2))
        restored (join withdrawn (observation 20 a3))]
    (is (value/contradiction? (base conflicted)))
    (is (= #{a1 b7} (datum/support-of (strongest conflicted))))
    (is (value/unusable? (strongest conflicted)))
    (is (= conflicted (join right left)))
    (is (= 2 (count (:support/observations conflicted))))
    ;; Retraction resolves the payload conflict, not the conjunctive requirement.
    (is (= 20 (base withdrawn)))
    (is (= #{a2 b7} (datum/support-of (strongest withdrawn))))
    (is (value/unusable? (strongest withdrawn)))
    (is (= withdrawn (join withdrawn left)))
    (is (= 20 (base restored)))
    (is (= #{a3 b7} (datum/support-of (strongest restored))))
    (is (not (value/unusable? (strongest restored))))
    (is (= restored (join restored left)))))

(deftest contradictory-payloads-preserve-incompatible-source-evidence
  (let [a1 (premise :A 1 :active)
        a2 (premise :A 2 :active)
        b7 (premise :B 7 :active)]
    (doseq [[left right expected-support]
            [[(observation 10 a1) (observation 20 b7) #{a1 b7}]
             [(observation 10 a1 a2) (observation 20 b7) #{a1 a2 b7}]]
            order [[left right] [right left]]]
      (let [content (apply join order)
            projected (strongest content)]
        (is (value/contradiction? (datum/layer-value projected :base)))
        (is (= expected-support (datum/support-of projected)))
        (is (value/unusable? projected))
        (is (= 2 (count (:support/observations content))))))))

(deftest combined-support-value-subsumes-same-epoch-source-values
  (let [a (premise :A 1 :active)
        b (premise :B 7 :active)
        x (observation 10 a)
        y (observation 20 b)
        z (observation 30 a b)]
    ;; Requested contract: source-set extension supersedes the single-source
    ;; observations even without newer epochs. Test every arrival order.
    (doseq [order [[x y z] [x z y] [y x z] [y z x] [z x y] [z y x]]]
      (let [result (apply join order)]
        (is (= z result) (str "arrival order: " order))
        (is (= 30 (base result)))
        (is (= #{a b} (datum/support-of (strongest result))))
        (is (not (value/unusable? (strongest result))))))))

(deftest combined-support-with-one-newer-source-subsumes-both-values
  (let [a1 (premise :A 1 :active)
        a2 (premise :A 2 :active)
        b7 (premise :B 7 :active)
        x (observation 10 a1)
        y (observation 20 b7)
        z (observation 30 a2 b7)]
    (doseq [order [[x y z] [x z y] [y x z] [y z x] [z x y] [z y x]]]
      (let [result (apply join order)]
        (is (= z result))
        (is (= 30 (base result)))
        (is (= #{a2 b7} (datum/support-of (strongest result))))
        (is (not (value/unusable? (strongest result))))
        (is (= result (join result x y)))))))

(deftest equal-values-combine-all-source-dependencies
  (let [a (premise :A 1 :active)
        b (premise :B 7 :active)
        c (premise :C 3 :active)
        x (observation 10 a)
        y (observation 10 b)
        z (observation 10 c)]
    (doseq [order [[x y z] [x z y] [y x z] [y z x] [z x y] [z y x]]]
      (let [result (apply join order)]
        (is (= 10 (base result)))
        (is (= #{a b c} (datum/support-of (strongest result))))
        (is (not (value/unusable? (strongest result))))
        (is (= result (apply join (concat order order))))))))

(deftest unusable-bases-retain-support
  (doseq [v [value/nothing value/contradiction
             (value/contradiction-with-provenance #{:reason})]]
    (let [support #{(premise :A 1 :active)}
          projected (strongest (collection/content {:base v :support support}))]
      (is (= v (datum/layer-value projected :base)))
      (is (= support (datum/support-of projected)))
      (is (value/unusable? projected)))))

(deftest support-only-changes-are-observable
  (let [active (strongest (observation 10 (premise :A 1 :active)))
        retracted (strongest (observation 10 (premise :A 2 :retracted)))
        restored (strongest (observation 10 (premise :A 3 :active)))]
    (is (merge/cell-updated? retracted active net/empty-net))
    (is (merge/cell-updated? restored retracted net/empty-net))
    (is (not (merge/cell-updated? active active net/empty-net)))))

(deftest two-computations-preserve-resolvable-injection-cell-id
  (let [start (demo/chain)
        active (demo/publish start 1 :active 10)
        network (:network active)
        source (:source active)]
    (doseq [output [(:middle active) (:output active)]]
      (let [premises (datum/support-of (net/network-cell-strongest network output))]
        (is (= #{source} (set (map :source premises))))
        (doseq [entry premises]
          (is (ids/node-id? (:source entry)))
          (is (not= output (:source entry)))
          (is (= 10 (datum/layer-value
                     (net/network-cell-strongest network (:source entry)) :base))))))))

(deftest live-two-step-withdrawal-and-recovery
  (let [start (demo/chain)
        active (demo/publish start 1 :active 10)
        updated (demo/publish active 2 :active 20)
        withdrawn (demo/publish updated 3 :retracted value/nothing)
        restored (demo/publish withdrawn 4 :active 7)
        source (:source start)]
    (is (= 35 (:base (demo/result active))))
    (is (= 45 (:base (demo/result updated))))
    (is (= 32 (:base (demo/result restored))))
    (is (:usable? (demo/result restored)))
    (let [conflicted (demo/publish active 1 :active 11)
          recovered (demo/publish conflicted 2 :active 20)
          delayed (demo/publish restored 1 :active 10)]
      (is (value/contradiction? (:base (demo/result conflicted))))
      (is (not (:usable? (demo/result conflicted))))
      (is (= #{(premise source 1 :active)} (:support (demo/result conflicted))))
      (is (= 45 (:base (demo/result recovered))))
      (is (:usable? (demo/result recovered)))
      (is (= (demo/result restored) (demo/result delayed))))
    (doseq [id [(:middle start) (:output start)]]
      (let [v (net/network-cell-strongest (:network withdrawn) id)]
        (is (value/unusable? v))
        (is (value/nothing? (datum/layer-value v :base)))
        (is (= #{(premise source 3 :retracted)} (datum/support-of v)))
        (is (= 1 (count (:support/observations
                         (net/network-cell-content (:network restored) id)))))))
    (let [same-base (demo/publish restored 5 :active 7)
          repeated (demo/publish same-base 5 :active 7)
          rerun (nb/run-propagators (:network repeated) (:tasks repeated))]
      (is (= #{(premise source 5 :active)} (:support (demo/result same-base))))
      (doseq [id [(:source start) (:middle start) (:output start)]]
        (is (= (net/network-cell-content (:network same-base) id)
               (net/network-cell-content (:network repeated) id)
               (net/network-cell-content rerun id))))
      (is (= (set (keys (net/net-env (:network start))))
             (set (keys (net/net-env rerun)))))
      (is (= (net/net-graph (:network start)) (net/net-graph rerun))))))
