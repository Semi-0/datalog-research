(ns propagators.prototype-2.amb-test
  "Dependence-directed amb + subworld reactive probes.
  TMS merge / amb repair publish as L-2 observation-spine tips."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.ids :as ids]
            [propagators.prototype-2.amb :as amb]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.support-tag :as stag]
            [propagators.prototype-2.world :as world]))

(defn- seed-env [state binds]
  (reduce (fn [st [sym val]]
            (let [c (net/new-cell-id (name sym))
                  n (church/tell-cell (:network st) c val)
                  [e n] (lenv/env-extend n (:env st) sym c)]
              (assoc st :network n :env e)))
          state
          binds))

(defn- run-l2
  ([forms] (run-l2 {} forms))
  ([binds forms]
   (let [state (seed-env (comp/fresh-state) binds)
         state (comp/compile-program state
                                     (concat (vec comp/l2-reactive-prelude) forms))
         network (ev/run-tasks (:network state))
         state (assoc state :network network)]
     [(net/cell-value network (:result state)) network (:result state) state])))

(deftest host-solve-flips-bad-first-choice
  (testing "first alt fails with its premise; learn+repair selects second"
    (let [result
          (amb/solve [1 2]
                     (fn [chosen support]
                       (if (= chosen 1)
                         {:status :failed :support support}
                         {:status :satisfied :support support :value (* chosen 10)})))]
      (is (= :satisfied (:status result)))
      (is (= 2 (amb/chosen (:choice result))))
      (is (= 20 (:value result)))
      (is (= 1 (count (:knowledge result))) "one nogood for index 0"))))

(deftest host-solve-unsatisfiable
  (testing "all alts fail → unsatisfiable"
    (let [result
          (amb/solve [1 2]
                     (fn [_chosen support]
                       {:status :failed :support support}))]
      (is (= :unsatisfiable (:status result))))))

(deftest l2-backtrack-unless-repairs-choice
  (testing "L-2 backtrack-unless learns nogood and advances choice (rebind; fill skips concrete outs)"
    (let [src (ids/new-node-id)
          decision (amb/initial-choice src [1 2])
          [v]
          (run-l2
           {'decision decision 'knowledge #{}}
           '[(define ok? (eq? (amb-chosen decision) 2))
             (define decision2 (backtrack-unless ok? decision knowledge))
             (amb-chosen decision2)])]
      (is (= 2 v) "repaired choice is second alt"))))

(deftest l2-tms-project-prefers-dominating-tip
  (testing "recursive tms-project picks dominating observation"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          [v network result]
          (run-l2
           {'s1 s1 's2 s2}
           '[(define rx (extend (extend () (observation 10 s1))
                                (observation 20 s2)))
             (tms-project rx)])]
      (is (plist/church-cons? v))
      (is (= 20 (stag/base-of network result)) "newer tip wins")
      (is (= s2 (stag/support-of network result))))))

(deftest l2-tms-scan-exposes-intermediate-merges
  (testing "tms-scan-obs publishes each partial reduce as a reactive tip"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          s3 (stag/stamp src 3)
          [_ network result]
          (run-l2
           {'s1 s1 's2 s2 's3 s3}
           ;; oldest-first so each next stamp dominates the running best
           '[(define rx (cons (observation 1 s1)
                              (cons (observation 2 s2)
                                    (cons (observation 3 s3) ()))))
             (tms-scan-obs rx)])]
      (let [partials
            (loop [id result acc []]
              (let [v (net/cell-value network id)]
                (cond
                  (plist/empty-list? v) acc
                  (plist/church-cons? v)
                  (recur (:b v) (conj acc (stag/base-of network (:a v))))
                  :else acc)))]
        (is (= [1 2 3] partials) "each step's merge state visible on spine")))))

(deftest l2-amb-repair-via-tms-merge-tips
  (testing "amb-repair-step extends choice spine then projects repaired tip"
    (let [src (ids/new-node-id)
          decision (amb/initial-choice src [1 2])
          [v network result]
          (run-l2
           {'decision decision 'knowledge #{}}
           '[(define ok? (eq? (amb-chosen decision) 2))
             (define chosen-obs
               (amb-repair-step () decision knowledge ok?))
             (obs-base chosen-obs)])]
      (is (= 2 v) "projected tip is repaired alternative"))))

(deftest l2-switch-rx-gates-projected-choice
  (testing "switch-rx keeps value only when condition holds"
    (let [src (ids/new-node-id)
          s (stag/stamp src 1)
          [v network]
          (run-l2
           {'s s}
           '[(define kept (switch-rx true 42 s))
             (define dropped (switch-rx false 42 s))
             (obs-base (tip kept))])]
      (is (= 42 v))
      (let [[empty?]
            (run-l2 {'s s}
                    '[(define dropped (switch-rx false 42 s))
                      (empty-list? dropped)])]
        (is (true? empty?))))))

(deftest pythagorean-first-triple
  (testing "host multi-amb finds [3 4 5] in 1..5"
    (let [r (amb/pythagorean 5)]
      (is (= :satisfied (:status r)))
      (is (= [3 4 5] (:value r)))
      (is (pos? (:steps r)))))
  (testing "n=4 has no triple (host)"
    (let [r (amb/pythagorean 4)]
      (is (= :unsatisfiable (:status r)))))
  (testing "L-2 DDB already at [3 4 5] — tip publish path, no host solve loop"
    (let [d (vec (range 1 6))
          r (amb/pythagorean-l2 5 {:ca (amb/choice-at d 2)
                                   :cb (amb/choice-at d 3)
                                   :cc (amb/choice-at d 4)})]
      (is (= :satisfied (:status r)))
      (is (= [3 4 5] (:value r)))
      (is (= :prototype-2-l2-ddb (:engine r)))))
  (testing "L-2 DDB one repair from [3 4 4] → [3 4 5]"
    (let [d (vec (range 1 6))
          r (amb/pythagorean-l2 5 {:ca (amb/choice-at d 2)
                                   :cb (amb/choice-at d 3)
                                   :cc (amb/choice-at d 3)})]
      (is (= :satisfied (:status r)))
      (is (= [3 4 5] (:value r)))))
  (testing "L-2 DDB unsat on tiny domain"
    (let [r (amb/pythagorean-l2 2)]
      (is (= :unsatisfiable (:status r)))
      (is (nil? (:value r)))))
  (testing "subworld reset: abandon inner worlds instead of reset-choice"
    (let [r (amb/pythagorean-subworlds 5)]
      (is (= :satisfied (:status r)))
      (is (= [3 4 5] (:value r)))
      (is (pos? (:abandoned r)) "failed leaves hibernated")
      (is (>= (:spawned r) (:steps r))))))

(deftest solve-worlds-reactive-observe
  (testing "amb over subworlds: bad a=1 fails target; a=5 satisfies via observe"
    (let [w0 (world/world-from-forms
              '(define a 0)
              '(define (f) (+ a 10)))
          result
          (amb/solve-worlds
           w0 'a [1 5]
           (fn [child support]
             (let [[v child'] (world/observe child '(eq? (f) 15))]
               (if (true? v)
                 {:status :satisfied :support support :value (amb/chosen
                                                              {:domain [1 5]
                                                               :index 1
                                                               :status :active})}
                 {:status :failed :support support}))))]
      (is (= :satisfied (:status result)))
      (is (= 5 (amb/chosen (:choice result))))
      ;; parent still a=0
      (let [[a0] (world/observe w0 'a)]
        (is (= 0 a0))))))

(deftest solve-worlds-parent-bindings-survive
  (testing "failed child does not copy/clobber parent; second child sees shared b"
    (let [w0 (world/world-from-forms
              '(define a 0)
              '(define b 100)
              '(define (f) (+ a b)))
          seen (atom [])
          result
          (amb/solve-worlds
           w0 'a [1 2]
           (fn [child support]
             (let [[vb] (world/observe child 'b)
                   [vf child'] (world/observe child '(f))
                   _ (swap! seen conj {:b vb :f vf})]
               (if (= vf 102)
                 {:status :satisfied :support support :value vf}
                 {:status :failed :support support}))))]
      (is (= :satisfied (:status result)))
      (is (= 2 (amb/chosen (:choice result))))
      (is (= 102 (:value result)))
      (is (every? #(= 100 (:b %)) @seen) "b always from shared parent spine"))))
