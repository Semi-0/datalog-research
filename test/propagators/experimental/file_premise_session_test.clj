(ns propagators.experimental.file-premise-session-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [examples.lain.amb.experiment :as amb]
            [examples.lain.amb.file-premise-session :as example]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.operators.environment :as operators]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.file-premise.composition :as composition]
            [propagators.experimental.file-premise.files :as files]
            [propagators.experimental.file-premise.session :as session]
            [propagators.network :as net]))

(def declared (delay (example/declare-composition example/source)))
(def completed (delay (effects/drain-environment-effects @declared)))

(deftest compositions-are-validated-immutable-declarations
  (let [empty composition/empty-composition
        one (composition/load-declaration empty "one.lain" "one")]
    (is (composition/composition? empty))
    (is (= [] (:modules empty)))
    (is (= [{:path "one.lain" :alias 'one}] (:modules one)))
    (is (thrown? Exception (composition/load-declaration one "other.lain" "one")))
    (doseq [[plan path alias] [[nil "one.lain" "one"] [empty 42 "one"]
                             [empty "" "one"] [empty "one.lain" 42]
                             [empty "one.lain" ""] [empty "one.lain" "a/b"]]]
      (is (thrown? Exception (composition/load-declaration plan path alias))))))

(deftest declaration-emits-without-loading-files
  (with-redefs [files/load-file (fn [& _] (throw (ex-info "Unexpected load" {})))]
    (let [declared (example/declare-composition example/source)
          requests (effects/outbox-effects (:program/net declared))]
      (is (= 1 (count requests)))
      (is (empty? (::session/worlds declared)))
      (is (empty? (:environment/effects declared)))
      (is (= ['one 'ten 'goal]
             (mapv :alias (get-in (first requests) [:boundary/payload :modules])))))))

(deftest native-networks-compose-and-defer-declarations
  (let [deferred (example/declare-composition
                  "(define deferred (network () (file-run file-composition \"query.lain\")))")
        composed (example/declare-composition
                  "(let []
                     (define add-module
                       (network (plan) (file-load plan \"one.lain\" \"one\")))
                     (file-run (add-module file-composition) \"query.lain\"))")
        requests (effects/outbox-effects (:program/net composed))]
    (is (empty? (effects/outbox-effects (:program/net deferred))))
    (is (= 1 (count requests)))
    (is (= [{:path "one.lain" :alias 'one}]
           (get-in (first requests) [:boundary/payload :modules]))))
  (is (thrown? Exception
               (example/declare-composition "(file-load file-composition \"one.lain\")"))))

(deftest live-session-accepts-native-composition
  (let [live (runtime/new-session)]
    (runtime/register-tui! live {:client-id "file-session-test"})
    (runtime/install-session-extension! live session/session-extension
                                        {:outbox-id (state/boundary-outbox-id)})
    (runtime/append-tui-block! live {:client-id "file-session-test" :text example/source})
    (let [worlds (::session/worlds @live)
          receipt (:receipt (first (vals (:environment/effects @live))))]
      (is (= 1 (count worlds)))
      (is (= 2 (:answer receipt)))
      (is (not-any? :error (vals (:program/results @live))))
      (is (empty? (:runtime/errors @live)))
      (runtime/append-tui-block! live {:client-id "file-session-test" :text "(+ 1 2)"})
      (is (= worlds (::session/worlds @live)))
      (is (empty? (:runtime/errors @live))))))

(deftest boundary-executes-and-replays-without-reloading
  (let [done @completed
        request (first (effects/outbox-effects (:program/net @declared)))
        id (:boundary/id request)
        world (get-in done [::session/worlds id])
        receipt (get-in done [:environment/effects id :receipt])
        answer (net/network-cell-strongest (:net world) (get-in world [:compiled :cell]))
        replay (with-redefs [files/load-file
                            (fn [& _] (throw (ex-info "Unexpected reload" {})))]
                 (effects/drain-environment-effects done))
        delivered (obj/slot-value
                   (net/network-cell-strongest (:program/net done) (:boundary/receipt-id request))
                   (state/receipt-slot-key id))]
    (is (= :completed (:status receipt)))
    (is (true? (:ready? receipt)))
    (is (= 2 (:answer receipt)))
    (is (= 3 (count (:support receipt))))
    (is (= (datum/support-of answer) (:support receipt)))
    (is (= :completed (:boundary/status delivered)))
    (is (= 2 (:answer delivered)))
    (is (= (:support receipt) (:support delivered)))
    (is (= 1 (count (::session/worlds done))))
    (is (identical? world (get-in replay [::session/worlds id])))
    (is (= (:environment/effects done) (:environment/effects replay)))))

(deftest failed-execution-preserves-prior-worlds
  (let [done @completed
        original (first (effects/outbox-effects (:program/net @declared)))
        request (-> original
                    (assoc :boundary/id [::missing-query])
                    (assoc-in [:boundary/payload :query-file]
                              "/no-such-file-premise-query.lain"))
        failed (effects/perform-registered-environment-request done request)]
    (is (= :failed (get-in failed [:environment/effects [::missing-query] :status])))
    (is (= (::session/worlds done) (::session/worlds failed)))
    (is (= 1 (count (::session/worlds failed))))))

(deftest execution-identity-includes-explicit-revision
  (let [request (fn [revision]
                  (first
                   (effects/outbox-effects
                    (:program/net
                     (example/declare-composition
                      (str "(file-run file-composition \"query.lain\"" revision ")"))))))
        implicit (request "")
        same (request " 0")
        next (request " 1")]
    (is (= (:boundary/id implicit) (:boundary/id same)))
    (is (not= (:boundary/id same) (:boundary/id next))))
  (let [installed (session/install (state/empty-state))]
    (is (= (:program/env installed) (:program/env (session/install installed))))))

(deftest run-normalization-is-exhaustive
  (let [effect (first (extension/extension-effects session/session-extension))
        normalize #(operators/normalize-effect-invocation effect {} %)]
    (is (= :ready (:status (normalize [composition/empty-composition "query.lain"]))))
    (is (= :wait (:status (normalize [value/nothing "query.lain"]))))
    (doseq [args [[42 "query.lain"] [composition/empty-composition 42]
                 [composition/empty-composition ""]
                 [composition/empty-composition "query.lain" -1]
                 [composition/empty-composition "query.lain" false] []]]
      (is (= :invalid (:status (normalize args)))))))

(deftest impossible-query-completes-without-an-answer
  (let [file (java.io.File/createTempFile "file-premise-no-fit-" ".lain")]
    (try
      (spit file (str/replace (slurp "examples/lain/amb/file_premise_selection/target.lain")
                             "(goal/target procedure)" "(= (procedure 5) 999)"))
      (let [source (str/replace example/source
                                "\"examples/lain/amb/file_premise_selection/target.lain\""
                                (pr-str (.getCanonicalPath file)))
            done (effects/drain-environment-effects (example/declare-composition source))
            world (first (vals (::session/worlds done)))
            receipt (:receipt (first (vals (:environment/effects done))))]
        (is (= :completed (:status receipt)))
        (is (false? (:ready? receipt)))
        (is (nil? (:answer receipt)))
        (is (some #(= {:action :unsatisfiable} %) (amb/cell-bases (:net world)))))
      (finally (.delete file)))))
