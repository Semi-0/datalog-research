(ns propagators.install-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.install :as i]
            [propagators.network :as net]
            [propagators.network-vm.flat :as fvm]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdprop]))

(defn- node-id
  [& parts]
  (fvm/stable-node-id (into [:install-test] parts)))

(defn- strongest
  [n cell-id]
  (net/network-cell-strongest n cell-id))

(defn- topology-counts
  [n]
  {:graph (count (net/net-graph n))
   :env (count (net/net-env n))
   :dict (count (net/net-dict-or-empty n))
   :props (count (filter prop/prop? (vals (net/net-env n))))})

(deftest dollar-binds-map-and-vector-forms
  (let [x-id (node-id :x)
        y-id (node-id :y)
        ctx (-> (i/context net/empty-net [:binding])
                (i/$ {:x x-id})
                (i/$ [:y] [y-id]))]
    (is (= x-id (i/cell-id ctx :x)))
    (is (= y-id (i/cell-id ctx :y)))))

(deftest unknown-args-auto-create-cells-and-reuse-ids
  (let [ctx (-> (i/context net/empty-net [:auto])
                (i/* :x :y :out))
        x-id (i/cell-id ctx :x)
        x-id* (i/cell-id ctx :x)
        n (i/commit ctx)]
    (is (ids/node-id? x-id))
    (is (= x-id x-id*))
    (is (contains? (net/net-env n) x-id))))

(deftest node-id-args-pass-through
  (let [x-id (node-id :explicit-x)
        out-id (node-id :explicit-out)
        ctx (-> (i/context net/empty-net [:node-id])
                (i/tell x-id 41)
                (i/+ x-id x-id out-id))
        n (i/run ctx)]
    (is (= 82 (strongest n out-id)))))

(deftest arithmetic-and-copy-compute-through-names
  (let [n (-> (i/context net/empty-net [:arithmetic])
              (i/tell :x 2)
              (i/tell :y 3)
              (i/* :x :y :product)
              (i/+ :product :x :sum)
              (i/copy :sum :out)
              (i/run))]
    (is (= 8 (strongest n (i/cell-id (i/context n [:arithmetic]) :out))))))

(deftest live-compound-object-installers-work-through-names
  (let [n (-> (i/context net/empty-net [:compound])
              (i/tell :head 10)
              (i/cons :head :tail :pair)
              (i/car :read-head :pair)
              (i/cdr :read-tail :pair)
              (i/run))
        ctx (i/context n [:compound])]
    (is (= 10 (strongest n (i/cell-id ctx :read-head))))
    (is (value/nothing? (strongest n (i/cell-id ctx :read-tail))))))

(deftest repeated-relation-install-is-idempotent
  (let [ctx (-> (i/context net/empty-net [:idem])
                (i/tell :x 2)
                (i/tell :y 3)
                (i/* :x :y :out))
        n (i/run ctx)
        counts (topology-counts n)
        ctx* (-> (i/context n [:idem])
                 (i/* :x :y :out))
        n* (i/run ctx*)]
    (is (= counts (topology-counts n*)))
    (is (= 6 (strongest n* (i/cell-id (i/context n* [:idem]) :out))))))

(deftest custom-install-works-with-explicit-tag
  (let [n (-> (i/context net/empty-net [:custom])
              (i/tell :x 4)
              (i/install :square stdprop/* :x :x :out)
              (i/run))]
    (is (= 16 (strongest n (i/cell-id (i/context n [:custom]) :out))))))

(deftest dollar-vector-form-validates-counts
  (try
    (i/$ (i/context net/empty-net [:bad]) [:x :y] [(node-id :x)])
    (is false "expected mismatched vector bindings to throw")
    (catch clojure.lang.ExceptionInfo e
      (is (re-find #"same count" (ex-message e))))))

(deftest install-namespace-does-not-call-merge-cell-entry
  (is (not (re-find #"merge-cell-entry"
                    (slurp "propagators/install.clj")))))

(deftest reducer-slot-requires-merge-and-strongest-nets
  (try
    (-> (i/context net/empty-net [:strict-reducer])
        (i/reducer-slot :r net/empty-net :a :value :reducer))
    (is false "expected old one-net reducer-slot shape to throw")
    (catch clojure.lang.ExceptionInfo e
      (is (re-find #"merge net and strongest net" (ex-message e))))))
