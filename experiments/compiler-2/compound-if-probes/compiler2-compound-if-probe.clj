(require '[clojure.test :as test]
         '[graph.lain-editor.runtime.session :as editor]
         '[propagators.compiler-2.runtime.session.input :as input]
         '[propagators.compiler-2.model.env :as env]
         '[propagators.compiler-2-functional-network-gur-test :as gur-test]
         '[propagators.compiler-2-functional-network-test :as fixture]
         '[propagators.datastructures.membership-collection :as membership]
         '[propagators.cells.value :as value]
         '[propagators.experimental.visualization.data :as data]
         '[propagators.gur :as gur]
         '[propagators.network :as net]
         '[propagators.runner :as runner])
(def checks (atom []))
(defn result-summary [v]
  (cond (value/nothing? v) :unknown
        (value/contradiction? v) :contradiction
        (net/net? v) :compound
        (or (number? v) (keyword? v) (boolean? v) (vector? v)) v
        :else (str (class v))))
(defn check! [label expected actual]
  (let [pass (= expected actual)]
    (swap! checks conj {:case label :pass pass :expected expected :actual (result-summary actual)})
    (prn (peek @checks))))
(defn read-value [pane symbol]
  (let [s @(:session pane)
        id (env/resolve-binding-id (:program/net s) (:program/env s) symbol)]
    (data/payload (net/network-cell-strongest (:program/net s) id))))
(defn publish! [pane symbol version payload status]
  (let [s @(:session pane)
        id (env/resolve-binding-id (:program/net s) (:program/env s) symbol)]
    (input/commit-runtime-input!
     (:session pane)
     {:runtime/input :cell-message :cell-id id
      :update (membership/content
               {:base payload :membership #{}
                :support #{{:source id :timestamp version :premises-status status}}})})))
(defn bounded [f]
  (let [steps (atom 0) deadline (+ (System/nanoTime) 60000000000)]
    (binding [runner/*advance-transform*
              (runner/compose-advance-transforms
               runner/*advance-transform*
               (fn [advance]
                 (fn [state continuations]
                   (when (or (> (swap! steps inc) 100000)
                             (> (System/nanoTime) deadline))
                     (throw (ex-info "Probe exceeded propagation budget" {:steps @steps})))
                   (advance state continuations))))]
      (f))))
(bounded
 (fn []
   (let [pane (editor/prepare
               (str "(load-lain \"propagators/compiler_2/runtime/lain/list.lain\")"
                    gur-test/mappers
                    "(define mapped (map-list decrement (map-list double (map-list increment (list 1 2 3)))))
                     (define first-value (car mapped))
                     (define second-value (car (cdr mapped)))
                     (define third-value (car (cdr (cdr mapped))))") [])]
     (try
       (check! :if-compound-list-first 3 (read-value pane 'first-value))
       (check! :if-compound-list-second 5 (read-value pane 'second-value))
       (check! :if-compound-list-third 7 (read-value pane 'third-value))
       (let [network (:program/net @(:session pane))
             frames (get (net/network-dict-entry network gur/name-bindings-key) [:gur.flat :frames])]
         (check! :gur-frames-present true (pos? (count frames))))
       (finally (editor/close-session! (:session pane)))))))
(bounded
 (fn []
   (let [pane (editor/prepare
               "(load-lain \"propagators/compiler_2/runtime/lain/combinator.lain\")
                (def-cells flag left-value right-value unrelated)
                (define relay (network [x] x))
                (define choose (network [condition left right] (if condition left right)))
                (define left-object
                  (struct :nested (struct :value left-value :unrelated unrelated)))
                (define right-object
                  (struct :nested (struct :value right-value :unrelated unrelated)))
                (define selected (relay (relay (choose flag left-object right-object))))
                (define answer (|> selected (slot :nested) (slot :value)))" [])]
     (try
       (check! :unresolved-condition value/nothing (read-value pane 'answer))
       (publish! pane 'flag 1 true :active)
       (check! :selected-partial-object value/nothing (read-value pane 'answer))
       (publish! pane 'left-value 1 10 :active)
       (check! :late-selected-slot 10 (read-value pane 'answer))
       (publish! pane 'right-value 1 20 :active)
       (check! :inactive-object-is-isolated 10 (read-value pane 'answer))
       (publish! pane 'left-value 2 11 :active)
       (check! :selected-slot-update 11 (read-value pane 'answer))
       (publish! pane 'flag 2 false :active)
       (check! :switch-to-other-compound-object 20 (read-value pane 'answer))
       (publish! pane 'left-value 3 12 :active)
       (check! :old-branch-remains-isolated 20 (read-value pane 'answer))
       (publish! pane 'right-value 2 21 :active)
       (check! :new-selected-slot-update 21 (read-value pane 'answer))
       (publish! pane 'flag 3 value/nothing :retracted)
       (check! :condition-retraction value/nothing (read-value pane 'answer))
       (publish! pane 'flag 4 true :active)
       (check! :return-to-updated-first-object 12 (read-value pane 'answer))
       (finally (editor/close-session! (:session pane)))))))
(prn {:passed (count (filter :pass @checks)) :failed (count (remove :pass @checks))})
(shutdown-agents)
(System/exit (if (and (seq @checks) (every? :pass @checks)) 0 1))
