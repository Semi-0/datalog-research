(ns propagators.compiler-2-pi-extension-test
  (:require [clojure.test :refer [deftest is testing]]
            [examples.lain.subagents.facts :as facts]
            [examples.lain.subagents.model :as model]
            [examples.lain.subagents.pi-extension :as pi]
            [examples.lain.subagents.pi-transport :as transport]
            [propagators.compiler-2.runtime.session.extension :as extension]))

(def config {:plugin :pi-subagents :version "0.81.0" :provider "ollama"
             :model "explicit-local-model" :cwd "/tmp/lain-pi-fixture"
             :tools ["read" "write"]})
(def offer {:session "s" :assignment-id "a" :task "Solve fixture" :context {}})
(defn log-of [& events] (facts/from-facts "s" events))
(defn runtime [id order kind fields]
  (facts/fact "s" id order kind (assoc fields :assignment-id "a")))
(defn request-of [requests]
  (:request (first (facts/ordered-facts requests))))
(defn launched [] (runtime "launched" 2 :runtime/started {:child "child-a"}))
(defn claimed []
  (runtime "requested" 1 :runtime/requested {:task "Solve fixture" :backend :pi-subagents}))

(deftest pure-leaves-reuse-the-per-assignment-fsm
  (let [empty (facts/empty-log "s")
        launch (request-of (pi/derive-execution-requests "s" config offer empty))
        active (log-of (claimed) (launched))
        cancellation (log-of (facts/fact "s" "cancel" 3 :assignment/cancel-requested
                                         {:assignment-id "a"}))
        cancel-claim (runtime "cancel-claim" 3 :runtime/cancel-requested {})
        stopped (runtime "stopped" 4 :runtime/cancelled
                         {:child "child-a" :cleanup-verified true})]
    (is (= :launch (:operation launch)))
    (is (= {:trial "fixture"}
           (:context (request-of (pi/derive-execution-requests "s" config
                                  (assoc offer :context {:trial "fixture"}) empty)))))
    (is (= [(pi/trial-id "s" "a") :launch] (:id launch)))
    (is (= :collect (:operation (request-of (pi/derive-execution-requests "s" config nil active)))))
    (is (= :stop (:operation (request-of (pi/derive-control-requests "s" config cancellation active)))))
    (is (empty? (facts/ordered-facts (pi/derive-completions "s" active))))
    (is (empty? (facts/ordered-facts
                (pi/derive-completions "s" (log-of (claimed) (launched) stopped)))))
    (is (= :runtime/cancel-requested
           (:kind (pi/cancel-requested-fact "s" "a" 3
                    (request-of (pi/derive-control-requests "s" config cancellation active))))))
    (is (= [stopped] (facts/ordered-facts (pi/derive-completions "s" (log-of (claimed) (launched) cancel-claim stopped)))))
    (let [ignored-stop (assoc stopped :id "ignored-stop" :order 3)
          later-cancel (assoc cancel-claim :order 4)
          accepted-stop (assoc stopped :order 5)
          ignored-completion (runtime "late-completion" 6 :runtime/completed {:child "child-a" :result "ignored"})]
      (is (= [accepted-stop]
             (facts/ordered-facts (pi/derive-completions "s"
               (log-of (claimed) (launched) ignored-stop later-cancel accepted-stop ignored-completion))))))
    (is (empty? (facts/ordered-facts
                (pi/derive-execution-requests "s" config nil (log-of (claimed) (launched) cancel-claim stopped)))))
    (is (empty? (facts/ordered-facts
                (pi/derive-control-requests "s" config cancellation (log-of (claimed) (launched) cancel-claim stopped)))))
    (is (= (pi/derive-execution-requests "s" config nil active)
           (pi/derive-execution-requests "s" config nil (log-of (launched) (claimed) (launched)))))
    (is (empty? (facts/ordered-facts (pi/derive-execution-requests "s" config nil empty))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pi/normalize-observations "other" active)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pi/normalize-observations "s"
                   (log-of (runtime "bad-stop" 4 :runtime/cancelled {:child "child-a"})))))))

(deftest terminal-results-are-not-progress-and-late-progress-is-stable
  (let [progress (runtime "progress" 3 :runtime/progress {:child "child-a"})
        complete (runtime "complete" 4 :runtime/completed {:child "child-a" :result {:text "done"}})
        observations (log-of complete progress (launched) (claimed))]
    (is (= [complete] (facts/ordered-facts (pi/derive-completions "s" observations))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pi/derive-completions "s" (log-of complete))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pi/derive-completions "s" (log-of (claimed) (launched)
                   (assoc complete :child "foreign-child")))))
    (is (= observations (pi/derive-status "s" observations)))
    (is (empty? (facts/ordered-facts (pi/derive-execution-requests "s" config nil observations))))
    (is (= #{'pi:normalize-observations 'pi:derive-execution-requests 'pi:derive-control-requests
             'pi:derive-completions 'pi:derive-status}
           (set (map first (extension/extension-bindings (pi/session-extension "s" config))))))))

(deftest pinned-plugin-translation-is-explicit
  (let [request {:backend :pi-subagents :operation :launch :task "fixture"}
        ordinary (transport/plugin-request config request)
        better (transport/plugin-request (assoc config :plugin :pi-better-subagents :version "0.19.1") request)]
    (is (= "subagent" (:tool ordinary)))
    (is (= (select-keys config [:provider :model :cwd :tools])
           (select-keys (:arguments ordinary) [:provider :model :cwd :tools])))
    (is (= "subagent_spawn_batch" (:tool better)))
    (is (= "explicit-local-model" (get-in better [:arguments :tasks 0 :model])))
    (is (thrown? clojure.lang.ExceptionInfo (transport/validate-config (dissoc config :provider))))
    (is (thrown? clojure.lang.ExceptionInfo (transport/validate-config (assoc config :version "latest"))))))

(deftest receipt-dedup-polling-and-verified-disposal
  (let [calls (atom []) disposed (atom 0)
        adapter (transport/open! "s" config
                  {:mode :synthetic
                   :call! (fn [request]
                            (swap! calls conj request)
                            (case (:tool request)
                              "subagents_enable" {:enabled true}
                              "subagent" (if (= "status" (get-in request [:arguments :action]))
                                           (if (= 3 (count @calls))
                                             {:status :running :child "child-a"}
                                             {:status :completed :child "child-a" :result "done"})
                                           {:status :launched :child "child-a"})))
                   :reconcile! (fn [_] {:status :reconciled :child "child-a"})
                   :dispose! (fn [_] (swap! disposed inc) {:cleanup-verified true})})
        request (assoc (model/request (pi/trial-id "s" "a")
                                     (model/transition (model/initial-state)
                                       (model/event (pi/trial-id "s" "a") "start" 0 :start
                                                    {:backend :pi-subagents :task "fixture"}))) :order 1)
        request (assoc request :context {:instructions "Allowed execution text" :trial-id "trial"
                                         :grading-secret "must stay outside child boundary"})
        first-result (transport/dispatch! adapter "a" request)]
    (is (= {:instructions "Allowed execution text" :trial-id "trial"}
           (:assignment-context (last @calls))))
    (is (= first-result (transport/dispatch! adapter "a" request)))
    (is (= 2 (count @calls)))
    (let [collect (assoc request :id [(pi/trial-id "s" "a") :collect]
                                :operation :collect :child "child-a" :order 2)]
      (is (= :progress (:kind (first (transport/dispatch! adapter "a" collect)))))
      (is (= (transport/dispatch! adapter "a" collect)
             (transport/dispatch! adapter "a" collect))))
    (is (= 3 (count @calls)))
    (is (= :completed (:kind (first (transport/poll! adapter "a" "poll-2" 3)))))
    (is (= 4 (count @calls)))
    (is (= 2 (count (:polls (transport/receipts adapter)))))
    (is (= #{"a"} (set (keys (:trials (transport/receipts adapter))))))
    (is (true? (:cleanup-verified (transport/close! adapter))))
    (transport/close! adapter)
    (is (= 1 @disposed))
    (is (thrown? clojure.lang.ExceptionInfo (transport/dispatch! adapter "a" request)))))

(deftest uncertain-launch-reconciles-without-another-launch
  (let [calls (atom 0) reconciles (atom [])
        adapter (transport/open! "s" (assoc config :plugin :pi-better-subagents :version "0.19.1")
                  {:mode :synthetic :call! (fn [_] (swap! calls inc) (throw (ex-info "ack lost" {})))
                   :reconcile! (fn [request] (swap! reconciles conj request)
                                 {:status :reconciled :child "owned-child"})
                   :dispose! (fn [_] {:cleanup-verified true})})
        launch {:session (pi/trial-id "s" "a") :id [(pi/trial-id "s" "a") :launch]
                :operation :launch :backend :pi-better-subagents :task "fixture" :order 1}
        uncertain (first (transport/dispatch! adapter "a" launch))
        normalized (pi/observation-fact "s" "a" uncertain)
        observations (log-of (runtime "request" 0 :runtime/requested {:task "fixture" :backend :pi-better-subagents}) normalized)
        request (request-of (pi/derive-execution-requests "s" (assoc config :plugin :pi-better-subagents :version "0.19.1") nil observations))]
    (is (thrown? clojure.lang.ExceptionInfo (pi/observation-fact "other" "a" uncertain)))
    (is (= :runtime/uncertain (:kind normalized)))
    (is (= :reconcile (:operation request)))
    (is (= :reconciled (:kind (first (transport/dispatch! adapter "a" (assoc request :order 2))))))
    (is (= 1 @calls))
    (is (= 1 (count @reconciles)))
    (is (= (:id request) (:effect-id (first @reconciles))))
    (is (= (:id launch) (:launch-effect-id (first @reconciles))))
    (transport/close! adapter)))

(deftest replay-and-observation-cannot-invoke-and-cleanup-is-checked
  (doseq [mode [:observe :replay]]
    (let [calls (atom 0)
          forbidden (fn [_] (swap! calls inc) (throw (ex-info "must not invoke" {})))
          adapter (transport/open! "s" config {:mode mode :call! forbidden
                                               :reconcile! forbidden :dispose! forbidden})
          request {:session (pi/trial-id "s" "a") :id [(pi/trial-id "s" "a") :launch]
                   :operation :launch :backend :pi-subagents :task "fixture" :order 1}]
      (is (= [] (transport/dispatch! adapter "a" request)))
      (is (= [] (transport/poll! adapter "a" "p" 2)))
      (is (true? (:cleanup-verified (transport/close! adapter))))
      (is (zero? @calls))
      (is (thrown? clojure.lang.ExceptionInfo (transport/dispatch! adapter "foreign" request)))))
  (let [adapter (transport/open! "s" config
                  {:mode :synthetic :call! (fn [_] {}) :reconcile! (fn [_] {})
                   :dispose! (fn [_] {:cleanup-verified false})})]
    (is (thrown? clojure.lang.ExceptionInfo (transport/close! adapter)))))
