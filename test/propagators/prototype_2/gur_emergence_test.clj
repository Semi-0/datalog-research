(ns propagators.prototype-2.gur-emergence-test
  "Does GUR-shaped behavior fall out of prototype-2?

  From how-to-think-in-lain §21 and list.md:

  - GUR = recursive *network application*, not a special form.
  - Tail position reuses the caller's result cell (control-flow); it is not a
    memory/GC guarantee.
  - Cons relates head/tail cells: an available head can propagate while the
    tail stays open; car/cdr are bidirectional syncs (wake either side).

  These tests probe what already emerges from lazy compounds + bi-sync +
  Church p-lists — without a full accumulating-GUR runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.types :as t]))

(defn- wake
  [network]
  (ev/run-tasks network))

(defn- compound-count
  [network]
  (count (filter t/compound-f? (vals (p2/network-value network)))))

(defn- built-compound-count
  [network]
  (count (filter (fn [[id entry]]
                   (and (t/compound-f? entry)
                        (t/topology-built? (p2/lookup-node network id))))
                 (p2/network-value network))))

;; ---------------------------------------------------------------------------
;; GUR · tail call (reuse caller out)
;; ---------------------------------------------------------------------------

(deftest gur-tail-call-reuses-caller-out
  (testing "nested tail applications share one result cell (GUR-shaped fall-through)"
    (let [out :gur-out
          state (comp/compile-program
                 (assoc (comp/fresh-state) :out out :tail? true)
                 '[(define (id x) x)
                   (define (wrap x) (id x))
                   (wrap 7)])
          network (wake (:network state))]
      ;; program result *is* the shared out — no stack of return cells
      (is (= out (:result state)))
      (is (= 7 (p2/cell-value network out)))
      ;; wrap's body is a tail call to id into the same out
      (is (pos? (built-compound-count network))))))

(deftest gur-tail-call-is-not-a-memory-guarantee
  (testing "docs: tail position ≠ reclaiming frames; each apply still installs topology"
    (let [[_ network] (comp/run
                        '(define (id x) x)
                        '(define (twice x) (id (id x)))
                        '(twice 1))
          compounds (compound-count network)]
      ;; two id applications ⇒ more than one compound application frame retained
      (is (>= compounds 2)
          "tail reuse of out does not imply a single compound frame"))))

;; ---------------------------------------------------------------------------
;; GUR · linked list · bi-directional wake
;; ---------------------------------------------------------------------------

(deftest gur-list-head-before-tail
  (testing "available car propagates while cdr spine stays open (partial p-list)"
    (let [{:keys [network a b list]} (church/install-cons p2/empty-network :a :b)
          [t network] (p2/tell network a "head")
          network (p2/run-tasks network t)
          [t network] (church/install-car network a b :car-out)
          network (p2/run-tasks network t)]
      (is (= "head" (p2/cell-value network :car-out)))
      (is (plist/p-list-partial? network list)
          "open cdr ⇒ partial, not complete")
      (is (not (plist/p-list-completed? network list)))
      ;; close the list later — bi-directional spine completion
      (let [[t network] (p2/tell network b plist/empty-list)
            network (p2/run-tasks network t)]
        (is (plist/p-list-completed? network list))))))

(deftest gur-list-bidirectional-wake
  (testing "car bi-sync: tell either side wakes the other (slot-sync shaped)"
    ;; forward: a → car-out
    (let [{:keys [network a b]} (church/install-cons p2/empty-network :a :b)
          [t network] (p2/tell network a 1)
          [t network] (let [[t' n] (p2/tell network b plist/empty-list)]
                        [(into t t') n])
          network (p2/run-tasks network t)
          [t network] (church/install-car network a b :car-out)
          network (p2/run-tasks network t)]
      (is (= 1 (p2/cell-value network :car-out)))
      ;; reverse wake: a fresh pair where car-out is told first, then bi-sync fills a
      (let [{:keys [network a b]} (church/install-cons p2/empty-network :a2 :b2)
            [t network] (p2/tell network b plist/empty-list)
            network (p2/run-tasks network t)
            [t network] (church/install-car network a b :car-out)
            network (p2/run-tasks network t)
            ;; a still nothing; tell the car port — bi-sync should wake a
            [t network] (p2/tell network :car-out 99)
            network (p2/run-tasks network t)]
        (is (= 99 (p2/cell-value network a))
            "telling car-out wakes cons cell a (bi-directional)")
        (is (= 99 (p2/cell-value network :car-out)))))))

(deftest gur-list-cdr-bidirectional-wake
  (testing "cdr bi-sync wakes both ways on the tail cell"
    (let [{:keys [network a b]} (church/install-cons p2/empty-network :a :b)
          [t network] (p2/tell network a 0)
          network (p2/run-tasks network t)
          [t network] (church/install-cdr network a b :cdr-out)
          network (p2/run-tasks network t)
          [t network] (p2/tell network :cdr-out plist/empty-list)
          network (p2/run-tasks network t)]
      (is (plist/empty-list? (p2/cell-value network b)))
      (is (plist/empty-list? (p2/cell-value network :cdr-out))))))

(deftest gur-compiler-cons-car-without-full-materialization
  (testing "compiled (car (cons 5 ())) resolves without host list materialization"
    (let [[v] (comp/run '(car (cons 5 ())))]
      (is (= 5 v)))))
