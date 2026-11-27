(ns propagators.prototype-2.curry-test
  "L-2 linked-list application: curry, network, apply, open-tail, GC retain."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.gc :as gc]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.session :as session]))

(deftest linked-curry-then-apply
  (testing "(f 1) curries; (g 2) completes via linked arg spines"
    (let [[v] (comp/run
               '(define (f a b) (+ a b))
               '(define g (f 1))
               '(g 2))]
      (is (= 3 v)))))

(deftest linked-apply-form
  (testing "(apply f (cons 1 (cons 2 ()))) uses an explicit arg list"
    (let [[v] (comp/run
               '(define (f a b) (+ a b))
               '(apply f (cons 1 (cons 2 ()))))]
      (is (= 3 v)))))

(deftest network-curried-object
  (testing "(network (state) (network (msg) (msg state))) long-lived handle"
    (let [[v] (comp/run
               '(define obj (network (state) (network (msg) (msg state))))
               '(define (add1 x) (+ x 1))
               '(define (add10 x) (+ x 10))
               '(define o (obj 10))
               '(define r1 (o add1))
               '(o add10))]
      (is (= 20 v)))))

(deftest open-arg-tail-stays-pending
  (testing "open cdr on arg list leaves apply pending; closing finishes"
    (let [[_ network state]
          (comp/run '(define (f a b) (+ a b)) '(define fref f))
          f-cell (:result state)
          open (keyword "open-cdr")
          a (keyword "arg-a")
          network (p2/ensure-cells network [open a])
          [_ network] (session/tell network a 1)
          network (p2/run-tasks network)
          {:keys [network list]} (church/install-cons network a open)
          out (keyword "open-app-out")
          network (p2/ensure-cell network out)
          network (#'comp/install-fn-apply-list network f-cell list out)
          network (p2/run-tasks network)
          props (into []
                      (keep (fn [[id v]]
                              (when (and (p2/compound-f? v)
                                         (= out (p2/propagator-output v)))
                                id)))
                      (p2/network-value network))
          still-open? (some #(not (p2/topology-built? (p2/lookup-node network %)))
                            props)]
      (is (p2/nothing? (p2/cell-value network out)))
      (is (true? still-open?))
      (let [b (keyword "arg-b")
            nil-c (keyword "arg-nil2")
            network (p2/ensure-cells network [b nil-c])
            [_ network] (session/tell network b 2)
            [_ network] (session/tell network nil-c plist/empty-list)
            network (p2/run-tasks network)
            {:keys [network list]} (church/install-cons network b nil-c)
            network (p2/install-id network list open)
            network (p2/run-tasks network)]
        (is (= 3 (p2/cell-value network out)))))))

(deftest curry-survives-gc-with-live-roots
  (testing "retain curried handle → live-roots keep env; re-apply after collect"
    (let [state (comp/compile-program
                 (comp/fresh-state)
                 '[(define (f a b) (+ a b))
                   (define g (f 1))
                   g])
          network (p2/run-tasks (:network state))
          g-cell (:result state)
          gval (p2/cell-value network g-cell)
          _ (is (comp/church-fn? gval))
          _ (is (= ['b] (:params gval)))
          swept (gc/collect network #{g-cell})
          g2 (p2/cell-value swept g-cell)
          _ (is (comp/church-fn? g2))
          _ (is (some? (p2/lookup-value swept (:env g2))))
          ;; apply g to (cons 2 ()) on swept network
          two (keyword "two")
          nil-c (keyword "nil3")
          out (keyword "g-out")
          swept (p2/ensure-cells swept [two nil-c out])
          [_ swept] (session/tell swept two 2)
          [_ swept] (session/tell swept nil-c plist/empty-list)
          swept (p2/run-tasks swept)
          {:keys [network list]} (church/install-cons swept two nil-c)
          network (#'comp/install-fn-apply-list network g-cell list out)
          network (p2/run-tasks network)]
      (is (= 3 (p2/cell-value network out))))))
