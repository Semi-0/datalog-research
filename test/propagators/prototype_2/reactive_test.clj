(ns propagators.prototype-2.reactive-test
  "Reactive observation lists + TTMS concurrency in L-2."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.ids :as ids]
            [propagators.prototype-2.church :as church]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.support-tag :as stag]
            [propagators.prototype-2.types :as t]))

(defn- seed-env
  "Only reason this exists: TTMS premises need real NodeIds (not quote-able).
  Same network as `comp/run` — just env-extend before compile."
  [state binds]
  (reduce (fn [st [sym val]]
            (let [c (net/new-cell-id (name sym))
                  n (church/tell-cell (:network st) c val)
                  [e n] (lenv/env-extend n (:env st) sym c)]
              (assoc st :network n :env e)))
          state
          binds))

(defn- run-l2
  "Normal L-2 path: compile-program + run-tasks (optional env seeds).
  Returns `[value network result-cell state]`."
  ([forms] (run-l2 {} forms))
  ([binds forms]
   (let [state (seed-env (comp/fresh-state) binds)
         state (comp/compile-program state
                                     (concat (vec comp/l2-reactive-prelude) forms))
         network (ev/run-tasks (:network state))
         state (assoc state :network network)
         result (:result state)]
     [(net/cell-value network result) network result state])))

(defn- continue-l2
  "Compile more forms on an existing state (same network/env); run-tasks."
  [state forms]
  (let [state (comp/compile-program (assoc state :out nil :tail? false :result nil)
                                    (vec forms))
        network (ev/run-tasks (:network state))
        state (assoc state :network network)
        result (:result state)]
    [(net/cell-value network result) network result state]))

(deftest host-tip-extend-project
  (testing "extend twice; tip is newest; project respects dominates?"
    (let [src (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2 :retracted)
          network net/empty-network
          {network :network o1 :cell} (stag/observation-from-values network 10 s1)
          {network :network o2 :cell} (stag/observation-from-values network 10 s2)
          [empty network] (church/empty-list-cell network)
          {:keys [network list] :as m1} (stag/extend-tip network empty o1)
          rx (:list m1)
          {:keys [network list]} (stag/extend-tip network rx o2)
          rx list]
      (is (= 10 (stag/tip-base network rx)))
      (is (= s2 (stag/tip-support network rx)))
      (let [[base support] (stag/project-tip network rx)]
        (is (= 10 base))
        (is (= s2 support))
        (is (stag/dominates? support s1))))))

(deftest l2-compatible-aligned-epochs
  (testing "same source/ts → supported-plus emits sum observation"
    (let [src (ids/new-node-id)
          sa (stag/stamp src 1)
          [v network]
          (run-l2
           {'sa sa 'sb sa}
           '[(define rx (extend () (observation 10 sa)))
             (define ry (extend () (observation 20 sb)))
             (supported-plus rx ry)])]
      (is (plist/church-cons? v))
      (is (= 30 (stag/base-of network (:a v))))
      (is (= (stag/combine sa sa) (stag/support-of network (:a v)))))))

(deftest l2-glitch-hold
  (testing "shared source, mismatched timestamps → empty reactive list"
    (let [src (ids/new-node-id)
          [v]
          (run-l2
           {'sa (stag/stamp src 1)
            'sb (stag/stamp src 2)}
           '[(define rx (extend () (observation 10 sa)))
             (define ry (extend () (observation 20 sb)))
             (supported-plus rx ry)])]
      (is (plist/empty-list? v)))))

(deftest l2-retract-tip-extension
  (testing "retract-tip conses retracted support; dominates older active"
    (let [src (ids/new-node-id)
          sa (stag/stamp src 1)
          sr (stag/stamp src 2 :retracted)
          [v network result]
          (run-l2
           {'sa sa 'sr sr}
           '[(define rx (extend () (observation 10 sa)))
             (retract-tip rx sr)])
          tip-sup (stag/tip-support network result)]
      (is (plist/church-cons? v))
      (is (= sr tip-sup))
      (is (stag/dominates? tip-sup sa))
      (let [[_ projected] (stag/project-tip network result)]
        (is (= sr projected))))))

(deftest l2-amb-join
  (testing "left tip compatible with shared → join left"
    (let [shared-src (ids/new-node-id)
          left-src (ids/new-node-id)
          ss (stag/stamp shared-src 1)
          sl (stag/stamp left-src 1)
          sr-bad (stag/stamp shared-src 2)
          [v network]
          (run-l2
           {'ss ss 'sl sl 'sr sr-bad}
           '[(define shared (extend () (observation 1 ss)))
             (define left (extend () (observation 10 sl)))
             (define right (extend () (observation 100 sr)))
             (amb-join shared left right)])]
      (is (plist/church-cons? v))
      (is (= 11 (stag/base-of network (:a v))))))
  (testing "left glitches; right tip joins"
    (let [shared-src (ids/new-node-id)
          right-src (ids/new-node-id)
          ss (stag/stamp shared-src 1)
          sl-bad (stag/stamp shared-src 2)
          sr (stag/stamp right-src 1)
          [v network]
          (run-l2
           {'ss ss 'sl sl-bad 'sr sr}
           '[(define shared (extend () (observation 1 ss)))
             (define left (extend () (observation 10 sl)))
             (define right (extend () (observation 100 sr)))
             (amb-join shared left right)])]
      (is (plist/church-cons? v))
      (is (= 101 (stag/base-of network (:a v)))))))

(defn- obs-bases
  "Walk result observation list → vector of bases (stops at open/empty)."
  [network rx-cell]
  (loop [id rx-cell
         acc []]
    (let [v (net/cell-value network id)]
      (cond
        (or (t/nothing? v) (plist/empty-list? v)) acc
        (plist/church-cons? v)
        (recur (:b v) (conj acc (stag/base-of network (:a v))))
        :else acc))))

(deftest l2-supported-map-plus-aligned
  (testing "recursive map over two full spines"
    (let [a (ids/new-node-id)
          b (ids/new-node-id)
          sa (stag/stamp a 1)
          sb (stag/stamp b 1)
          [_ network result]
          (run-l2
           {'sa sa 'sb sb}
           '[(define xs (cons (observation 1 sa)
                              (cons (observation 10 sa) ())))
             (define ys (cons (observation 2 sb)
                              (cons (observation 20 sb) ())))
             (supported-map-plus xs ys)])]
      (is (= [3 30] (obs-bases network result))))))

(deftest l2-supported-map-lifted-binop
  (testing "supported-map lifts an arbitrary Church fn over a list of spines"
    (let [a (ids/new-node-id)
          b (ids/new-node-id)
          sa (stag/stamp a 1)
          sb (stag/stamp b 1)
          [_ network result]
          (run-l2
           {'sa sa 'sb sb}
           '[(define (mul a b) (* a b))
             (define xs (cons (observation 2 sa)
                              (cons (observation 3 sa) ())))
             (define ys (cons (observation 5 sb)
                              (cons (observation 7 sb) ())))
             (supported-map mul (cons xs (cons ys ())))])]
      (is (= [10 21] (obs-bases network result))))))

(deftest l2-supported-map-ternary
  (testing "three spines via xss list + add3"
    (let [a (ids/new-node-id)
          b (ids/new-node-id)
          c (ids/new-node-id)
          sa (stag/stamp a 1)
          sb (stag/stamp b 1)
          sc (stag/stamp c 1)
          [_ network result]
          (run-l2
           {'sa sa 'sb sb 'sc sc}
           '[(define xs (cons (observation 1 sa) ()))
             (define ys (cons (observation 2 sb) ()))
             (define zs (cons (observation 3 sc) ()))
             (supported-map add3 (cons xs (cons ys (cons zs ()))))])]
      (is (= [6] (obs-bases network result))))))

(deftest l2-supported-map-skips-glitch-pair
  (testing "incompatible pair skipped; later compatible pair emitted"
    (let [src (ids/new-node-id)
          other (ids/new-node-id)
          s1 (stag/stamp src 1)
          s2 (stag/stamp src 2)
          so (stag/stamp other 1)
          [v network result]
          (run-l2
           {'s1 s1 's2 s2 'so so}
           '[(define xs (cons (observation 1 s1)
                              (cons (observation 10 so) ())))
             (define ys (cons (observation 2 s2)
                              (cons (observation 20 so) ())))
             (supported-map-plus xs ys)])]
      ;; first pair glitches (src@1 vs src@2); second pair ok → only 30
      (is (= [30] (obs-bases network result))))))

(deftest l2-open-next-map-two-rounds
  (testing "L-2 open/next grow spines outside-in; map sees round1 then round2"
    (let [a (ids/new-node-id)
          b (ids/new-node-id)
          sa (stag/stamp a 1)
          sb (stag/stamp b 1)
          ;; roots stay map-able; cursors advance via (define c (next c v))
          [_ n1 r1 st1]
          (run-l2
           {'sa sa 'sb sb}
           '[(define xs (open))
             (define ys (open))
             (define cx (next xs (observation 1 sa)))
             (define cy (next ys (observation 2 sb)))
             (define out (supported-map add (cons xs (cons ys ()))))
             out])]
      (is (= [3] (obs-bases n1 r1)) "round1 head under open cdr")
      (is (plist/p-list-partial? n1 r1))
      (let [[_ n2] (continue-l2 st1
                                '[(define cx (next cx (observation 10 sa)))
                                  (define cy (next cy (observation 20 sb)))
                                  (fill cx ())
                                  (fill cy ())
                                  out])]
        (is (= [3 30] (obs-bases n2 r1)) "round2 after next+close; same out cell")
        (is (plist/p-list-completed? n2 r1))))))
