(require '[propagators.compiler-2-supported-compound-if-test :as acceptance]
         '[propagators.compiler-2-functional-network-gur-test :as g]
         '[propagators.compiler-2-combinator-test :as t]
         '[graph.lain-editor.runtime.session :as editor]
         '[propagators.compiler-2.runtime.session.input :as input]
         '[propagators.compiler-2.model.env :as env]
         '[propagators.datastructures.membership-collection :as membership]
         '[propagators.experimental.visualization.data :as data]
         '[propagators.network :as net])
(def checks (atom []))
(defn report! [result]
  (let [checked (assoc result :pass (and (not (:error result)) (= (:expected result) (:actual result))))]
    (swap! checks conj checked)
    (prn checked)))
(acceptance/bounded
 (fn []
;; Keep this `if` variant as a selector regression control with explicit completion.
(def map-if "(define map-if (network [f xs] (if (empty-list? xs) (list) (cons (f (car xs)) (map-if f (cdr xs))))))")
(defn scalar-summary [v]
  (if (or (keyword? v) (number? v) (boolean? v)) v (str (class v))))
(doseq [[stage expression expected]
        [[1 "(map-if increment (list 1 2 3))" [2 3 4]]
         [2 "(map-if double (map-if increment (list 1 2 3)))" [4 6 8]]
         [3 "(map-if decrement (map-if double (map-if increment (list 1 2 3))))" [3 5 7]]]
        index (range 3)]
  (try
    (let [get-value (str "(car " (nth (iterate #(str "(cdr " % ")") expression) index) ")")
          actual (data/payload (g/scalar (str map-if g/mappers) get-value))]
      (report! {:kind :if-map-chain :stages stage :index index
            :expected (nth expected index) :actual (scalar-summary actual)}))
    (catch Exception e (report! {:kind :if-map-chain :stages stage :index index :error (.getMessage e)}))))
(def static-choice
  "(define choose (network [flag yes no] (if flag yes no)))
   (define relay (network [x] x))
   (define yes-object (struct :nested (struct :value 10)))
   (define no-object (struct :nested (struct :value 20)))
   (define answer (|> (relay (relay (choose true yes-object no-object))) (slot :nested) (slot :value)))")
(try
  (report! {:kind :preconstructed-compounds-through-if-and-two-calls
        :actual (scalar-summary (t/binding-value (t/session static-choice) 'answer)) :expected 10})
  (catch Exception e (report! {:kind :static-choice :error (.getMessage e)})))
(doseq [conditional? [false true] transport [:membership :publication]]
  (let [source (str "(load-lain \"propagators/compiler_2/runtime/lain/combinator.lain\")
                    (define leaf)
                    (define yes-object (struct :nested (struct :value leaf)))
                    (define no-object (struct :nested (struct :value 20)))
                    (define answer (|> " (if conditional? "(if true yes-object no-object)" "yes-object")
                    " (slot :nested) (slot :value)))")
        pane (editor/prepare source [])]
    (try
      (let [session (:session pane)
            state @session
            id (env/resolve-binding-id (:program/net state) (:program/env state) 'leaf)]
        (case transport
          :membership (input/commit-runtime-input!
                       session {:runtime/input :cell-message :cell-id id
                                :update (membership/content {:base 10 :membership #{}
                                                             :support #{{:source id :timestamp 1 :premises-status :active}}})})
          :publication (t/publish! session 'leaf :leaf 1 10))
        (report! {:kind :late-struct-field :if conditional? :transport transport
              :expected 10 :actual (scalar-summary (t/binding-value session 'answer))}))
      (catch Exception e
        (report! {:kind :late-struct-field :if conditional? :transport transport :error (.getMessage e)}))
      (finally (editor/close-session! (:session pane))))))
))
(prn {:passed (count (filter :pass @checks)) :failed (count (remove :pass @checks))})
(shutdown-agents)
(System/exit (if (and (seq @checks) (every? :pass @checks)) 0 1))
