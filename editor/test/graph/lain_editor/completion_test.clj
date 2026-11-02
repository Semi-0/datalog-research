(ns graph.lain-editor.completion-test
  (:require [clojure.test :refer [deftest is testing]]
            [graph.lain-editor.completion :as completion]
            [graph.lain-editor.runtime.session :as editor]
            [graph.lain-editor.server :as server]
            [graph.json :as json]
            [propagators.network :as net])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defn- labels [response]
  (set (map :label (:candidates response))))

(deftest visible-names-are-current-and-observation-only
  (let [editors (atom {})]
    (try
      (let [entry (editor/run! editors {:pane "p"
                                       :source "(define public-name 7) (define helper (network [private-argument] (+ private-argument 1))) (define helper-result (helper 2))"})
            session (:session (get @editors "p"))
            before @session
            publications (atom 0)
            request (assoc entry :pane "p")]
        (add-watch session ::completion (fn [& _] (swap! publications inc)))
        (let [result (with-redefs [net/network-cell-strongest
                                  (fn [& _] (throw (ex-info "Completion read a binding value" {})))]
                       (completion/candidates editors request))
              names (labels result)]
          (is (contains? names "public-name"))
          (is (contains? names "helper"))
          (is (contains? names "+"))
          (is (not (contains? names "private-argument")))
          (is (= (:generation entry) (:generation result)))
          (is (= (:sessionId entry) (:sessionId result)))
          (is (false? (:truncated result)))
          (is (every? #(= "environment" (:origin %)) (:candidates result))))
        (is (identical? before @session))
        (is (zero? @publications))
        (remove-watch session ::completion)
        (let [extended (editor/extend! editors (assoc request :source "(define later-name 9)"))]
          (is (contains? (labels (completion/candidates editors (assoc extended :pane "p"))) "later-name")))
        (is (thrown-with-msg? Exception #"Pane changed" (completion/candidates editors request))))
      (finally (editor/close! editors)))))

(deftest invalid-targets-fail-explicitly
  (is (thrown-with-msg? Exception #"pane and generation" (completion/candidates (atom {}) {})))
  (is (thrown-with-msg? Exception #"Run this pane first"
        (completion/candidates (atom {}) {:pane "missing" :generation "g"}))))

(defn- request [port command origin authorized?]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api")))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-json command))))]
    (when origin (.header builder "Origin" origin))
    (when authorized? (.header builder "Authorization" "Bearer test-credential"))
    (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))))

(deftest completion-uses-the-existing-authenticated-api
  (let [{:keys [port close editors]}
        (server/start! 0 {:authorize? #(= "Bearer test-credential"
                                         (.getFirst (.getRequestHeaders %) "Authorization"))})]
    (try
      (let [run (request port {:op "run" :pane "http" :source "(define http-name 17)"} nil true)
            entry (:result (json/read-json (.body run)))
            command (assoc entry :op "complete" :pane "http")
            completed (request port command nil true)]
        (is (= 200 (.statusCode run)))
        (is (= 200 (.statusCode completed)))
        (is (contains? (labels (:result (json/read-json (.body completed)))) "http-name"))
        (is (= "no-store" (.orElse (.firstValue (.headers completed) "Cache-Control") "")))
        (is (= 401 (.statusCode (request port command nil false))))
        (is (= 400 (.statusCode (request port command "https://elsewhere.invalid" true))))
        (is (= 400 (.statusCode (request port (assoc command :generation "stale") nil true))))
        (is (= #{"http"} (set (keys @editors)))))
      (finally (close)))))
