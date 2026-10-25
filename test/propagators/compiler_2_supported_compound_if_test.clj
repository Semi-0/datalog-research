(ns propagators.compiler-2-supported-compound-if-test
  "Production acceptance for supported compound selection; no runtime overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2-combinator-test :as fixture]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.supported-application.publication :as publication]
            [propagators.compiler-2.runtime.supported-application.ports :as ports]
            [propagators.datastructures.compound-object :as obj]
            [propagators.network-builder :as nb]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.experimental.visualization.data :as data]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(defn bounded [f]
  (let [steps (atom 0)
        deadline (+ (System/nanoTime) 60000000000)]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms
               runner/*advance-transform*
               (fn [advance]
                 (fn [state continuations]
                   (when (or (> (swap! steps inc) 100000)
                             (> (System/nanoTime) deadline))
                     (throw (ex-info "Acceptance exceeded propagation budget" {:steps @steps})))
                   (advance state continuations))))]
      (f))))

(defn load-session [source]
  (bounded #(fixture/session source)))

(defn observed [session name]
  (data/payload (fixture/binding-value session name)))

(defn publish-phase! [session transport name revision candidate]
  (case transport
    :publication (fixture/publish! session name name revision candidate)
    :membership
    (let [state @session
          id (env/resolve-binding-id (:program/net state) (:program/env state) name)]
      (input/commit-runtime-input!
       session {:runtime/input :cell-message :cell-id id
                :update (membership/content
                         {:base candidate :membership #{}
                          :support #{{:source id :timestamp revision :premises-status :active}}})}))))

(defn publish! [session transport name revision candidate]
  (bounded #(publish-phase! session transport name revision candidate)))

(defn selected-expression [kind relays]
  (nth (iterate #(str "(relay " % ")")
                (case kind
                  :if "(if flag left-object right-object)"
                  :choose "(choose flag left-object right-object)")) relays))

(defn nested-program [kind relays accessor]
  (str "(def-cells flag left-value right-value unrelated)
        (define relay (network [x] x))
        (define choose (network [condition left right] (if condition left right)))
        (define left-object (struct :nested (struct :value left-value :unrelated unrelated)))
        (define right-object (struct :nested (struct :value right-value :unrelated unrelated)))
        (define selected " (selected-expression kind relays) ")
        (define missing ((slot :missing) selected))
        (define answer "
       (case accessor
         :direct "((slot :value) ((slot :nested) selected))"
         :piped "(|> selected (slot :nested) (slot :value))") ")"))

(deftest nested-objects-follow-current-selection-through-accessors-and-relays
  (doseq [kind [:if :choose] relays [0 1 2]
          accessor [:direct :piped] transport [:membership :publication]]
    (testing (pr-str [kind relays accessor transport])
      (let [loaded (load-session (nested-program kind relays accessor))]
        (is (value/nothing? (observed loaded 'answer)))
        (doseq [[name revision candidate expected]
                [['flag 1 true value/nothing] ['left-value 1 10 10]
                 ['right-value 1 20 10] ['left-value 2 11 11]
                 ['flag 2 false 20] ['left-value 3 12 20]
                 ['right-value 2 21 21] ['flag 3 value/nothing value/nothing]
                 ['flag 4 true 12]]]
          (publish! loaded transport name revision candidate)
          (is (= expected (observed loaded 'answer)) (pr-str [name revision candidate])))
        (is (value/nothing? (observed loaded 'missing)))
        (publish! loaded transport 'left-value 1 999)
        (is (= 12 (observed loaded 'answer)) "Stale source revisions cannot overwrite current fields")
        (let [before (:program/net @loaded) repeated (bounded #(fixture/reevaluate! loaded))]
          (is (= (net/net-graph before) (net/net-graph repeated)))
          (is (= 12 (observed loaded 'answer))))))))

(defn legacy-publication-network [actual-source close-actual?]
  (let [loaded (load-session
                (str "(define legacy (list 1)) (define actual " actual-source ")"
                     "(define callable (network [] 1))"))
        state @loaded
        network (:program/net state)
        resolve-id #(env/resolve-binding-id network (:program/env state) %)
        target (resolve-id 'legacy)
        source (resolve-id 'actual)
        operator (resolve-id 'callable)
        target-end (obj/existing-slot-cell-id network :cdr target)
        prepared (nb/seed-cell network target-end :compiler-2/list-empty)
        prepared (if close-actual?
                   (let [tail (obj/existing-slot-cell-id prepared :cdr source)
                         end (obj/existing-slot-cell-id prepared :cdr tail)]
                     (nb/seed-cell prepared end :compiler-2/list-empty))
                   prepared)
        config {:operator operator
                :key (ports/candidate-key (net/network-cell-strongest prepared operator))}
        [publisher installed] ((publication/publisher config source target) prepared)]
    {:net installed :props [publisher]}))

(deftest genuinely-closed-legacy-interfaces-still-reject-incompatible-results
  (doseq [[source closed?] [["(list 1 2)" true] ["3" false]]]
    (testing source
      (let [{:keys [net props]} (legacy-publication-network source closed?)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible return interfaces"
                             (nb/run-propagators net props)))))))

(deftest contradictory-fields-withdraw-and-recover-through-the-selected-owner
  (doseq [transport [:membership :publication]]
    (let [loaded (load-session (nested-program :choose 2 :piped))]
      (publish! loaded transport 'flag 1 true)
      (publish! loaded transport 'left-value 1 10)
      (is (= 10 (observed loaded 'answer)))
      (publish! loaded transport 'left-value 2 value/contradiction)
      (is (value/contradiction? (observed loaded 'answer)))
      (publish! loaded transport 'left-value 3 value/nothing)
      (is (value/nothing? (observed loaded 'answer)))
      (publish! loaded transport 'left-value 4 12)
      (is (= 12 (observed loaded 'answer))))))

(deftest predicates-distinguish-pending-empty-and-nonempty-lists
  (let [loaded (load-session
                "(define unavailable)
                 (define pending-network (network? unavailable))
                 (define pending-value (value? unavailable))
                 (define pending-empty (empty-list? unavailable))
                 (define empty-list (empty-list? (list)))
                 (define prefix-empty (empty-list? (list 1)))
                 (define empty-value (value? (list)))
                 (define empty-network (network? (list)))
                 (define prefix-network (network? (list 1)))
                 (define prefix-value (value? (list 1)))
                 (define scalar-network (network? 3))
                 (define scalar-value (value? 3))")]
    (doseq [name '[pending-network pending-value pending-empty]]
      (is (value/nothing? (observed loaded name))))
    (is (= true (observed loaded 'empty-list)))
    (is (= false (observed loaded 'prefix-empty)))
    (is (= true (observed loaded 'empty-value)))
    (is (= false (observed loaded 'empty-network)))
    (is (= true (observed loaded 'prefix-network)))
    (is (= true (observed loaded 'prefix-value)))
    (is (= false (observed loaded 'scalar-network)))
    (is (= true (observed loaded 'scalar-value)))))

(deftest selected-open-prefix-grows-when-a-live-source-tail-arrives
  (doseq [transport [:membership :publication]]
    (let [loaded (load-session
                  "(def-cells flag later)
                   (define selected (if flag (cons 1 later) (list 10)))
                   (define first (car selected))
                   (define tail (cdr selected))
                   (define second (car tail))
                   (define third (car (cdr tail)))
                   (define tail-network (network? tail))")]
      (publish! loaded transport 'flag 1 true)
      (is (= 1 (observed loaded 'first)))
      (is (value/nothing? (observed loaded 'second)))
      (is (value/nothing? (observed loaded 'tail-network)))
      (bounded #(fixture/checked-load! loaded "(-> (list 2 3) later)"))
      (is (= 2 (observed loaded 'second)))
      (is (= 3 (observed loaded 'third)))
      (is (= true (observed loaded 'tail-network)))
      (publish! loaded transport 'flag 2 false)
      (is (= :compiler-2/list-empty (observed loaded 'tail)))
      (is (= false (observed loaded 'tail-network)))
      (publish! loaded transport 'flag 3 true)
      (is (= 3 (observed loaded 'third))))))

(deftest selected-explicit-open-tail-stays-pending-until-it-is-closed
  (doseq [transport [:membership :publication]]
    (let [loaded (load-session
                  "(def-cells flag short-tail)
                   (define selected (if flag (list 1 2) (cons 10 short-tail)))
                   (define head (car selected))
                   (define tail (cdr selected))
                   (define second (car tail))
                   (define tail-network (network? tail))
                   (define tail-empty (empty-list? tail))")]
      (publish! loaded transport 'flag 1 true)
      (is (= 2 (observed loaded 'second)))
      (is (= false (observed loaded 'tail-empty)))
      (publish! loaded transport 'flag 2 false)
      (is (= 10 (observed loaded 'head)))
      (is (value/nothing? (observed loaded 'tail)))
      (is (value/nothing? (observed loaded 'second)))
      (is (value/nothing? (observed loaded 'tail-network)))
      (is (value/nothing? (observed loaded 'tail-empty)))
      (bounded #(fixture/checked-load! loaded "(-> (list) short-tail)"))
      (is (= :compiler-2/list-empty (observed loaded 'tail)))
      (is (= false (observed loaded 'tail-network)))
      (is (= true (observed loaded 'tail-empty)))
      (publish! loaded transport 'flag 3 true)
      (is (= 2 (observed loaded 'second)))
      (is (= false (observed loaded 'tail-empty))))))

(deftest selected-list-shortening-replaces-the-old-tail-with-known-empty
  (doseq [transport [:membership :publication]]
    (testing transport
      (let [loaded (load-session
                    "(define flag)
                     (define selected (if flag (list 1 2 3) (list 10)))
                     (define head (car selected))
                     (define tail (cdr selected))
                     (define second (car tail))
                     (define tail-network (network? tail))
                     (define tail-value (value? tail))")]
        (is (value/nothing? (observed loaded 'head)))
        (publish! loaded transport 'flag 1 true)
        (is (= 1 (observed loaded 'head)))
        (is (= 2 (observed loaded 'second)))
        (is (= true (observed loaded 'tail-network)))
        (is (= true (observed loaded 'tail-value)))
        (publish! loaded transport 'flag 2 false)
        (is (= 10 (observed loaded 'head)))
        (is (= :compiler-2/list-empty (observed loaded 'tail))
            "The short list exposes its known empty tail instead of the old owner")
        (is (value/nothing? (observed loaded 'second)))
        (is (= false (observed loaded 'tail-network)))
        (is (= true (observed loaded 'tail-value)))
        (publish! loaded transport 'flag 3 true)
        (is (= 2 (observed loaded 'second)))
        (is (= true (observed loaded 'tail-network)))
        (is (= true (observed loaded 'tail-value)))))))
