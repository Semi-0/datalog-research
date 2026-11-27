(ns propagators.prototype-2.layers-test
  "L-2 layer bags: linked-list facets on top of Church p-lists (no core patch)."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.core :as p2]
            [propagators.prototype-2.gc :as gc]
            [propagators.prototype-2.p-list :as plist]))

(defn- run-layers
  [& forms]
  (apply comp/run-with-prelude comp/l2-layers-prelude forms))

(defn- host-layer-get
  "Walk a retained layer-bag cell like L-2 `layer-get` (for post-GC checks)."
  [network lv-cell name]
  (loop [id lv-cell]
    (let [v (p2/cell-value network id)]
      (cond
        (plist/empty-list? v) ::missing
        (plist/church-cons? v)
        (let [binding (p2/cell-value network (:a v))]
          (if-not (plist/church-cons? binding)
            ::bad-binding
            (if (= name (p2/cell-value network (:a binding)))
              (p2/cell-value network (:b binding))
              (recur (:b v)))))
        :else ::not-list))))

(deftest eq?-primitive
  (testing "eq? distinguishes keywords"
    (is (true? (first (comp/run '(eq? :base :base)))))
    (is (false? (first (comp/run '(eq? :base :support)))))))

(deftest layer-assoc-get-base
  (testing "layer bag stores :base and layer-base reads it"
    (let [[v] (run-layers
               '(define lv (layer-assoc () :base 42))
               '(layer-base lv))]
      (is (= 42 v)))))

(deftest layer-shadow-and-support
  (testing "assoc shadows :base; :support still visible"
    (let [[b] (run-layers
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (layer-assoc lv0 :support 99))
               '(define lv2 (layer-assoc lv1 :base 2))
               '(layer-base lv2))
          [s] (run-layers
               '(define lv0 (layer-assoc () :base 1))
               '(define lv1 (layer-assoc lv0 :support 99))
               '(define lv2 (layer-assoc lv1 :base 2))
               '(layer-get lv2 :support))]
      (is (= 2 b))
      (is (= 99 s)))))

(deftest layer-get-missing
  (testing "missing layer name yields empty-list"
    (let [[v] (run-layers
               '(define lv (layer-assoc () :base 1))
               '(layer-get lv :support))]
      (is (plist/empty-list? v)))))

(deftest layer-bag-survives-gc
  (testing "retain layer bag handle; facets remain after collect"
    (let [[_ network state]
          (run-layers
           '(define lv (layer-assoc (layer-assoc () :support 7) :base 3))
           'lv)
          lv-cell (:result state)
          swept (gc/collect network #{lv-cell})]
      (is (plist/church-cons? (p2/cell-value swept lv-cell)))
      (is (= 3 (host-layer-get swept lv-cell :base)))
      (is (= 7 (host-layer-get swept lv-cell :support))))))
