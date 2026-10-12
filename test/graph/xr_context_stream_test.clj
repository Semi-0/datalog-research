(ns graph.xr-context-stream-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as sets]
            [graph.json :as json]
            [graph.xr-server :as server]
            [propagators.compiler-2.runtime.session.file-loader :as loader])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket$Listener]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- connect [port messages]
  (let [fragments (atom "")
        listener (reify WebSocket$Listener
                   (onOpen [_ socket] (.request socket 1))
                   (onText [_ socket text last?]
                     (swap! fragments str text)
                     (when last?
                       (.offer messages (json/read-json @fragments))
                       (reset! fragments ""))
                     (.request socket 1)
                     nil)
                   (onError [_ _ error] (.offer messages {:error (str error)})))]
    (-> (HttpClient/newHttpClient) (.newWebSocketBuilder)
        (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/ws")) listener)
        (.join))))

(defn- await-context [messages generation]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (let [remaining (- deadline (System/nanoTime))]
        (if (pos? remaining)
          (let [message (.poll messages remaining TimeUnit/NANOSECONDS)
                context (get-in message [:result :context])]
            (cond
              (:error message) (throw (ex-info "Context stream failed" message))
              (and (= "xr/context/update" (:type message))
                   (= generation (:generation context))) context
              :else (recur)))
          nil)))))

(deftest context-subscription-survives-full-environment-replacement
  (let [session (loader/load-session-from-source "(define observed 2)")
        http (server/start-server 0 session)
        messages (LinkedBlockingQueue.)
        socket (connect (:port http) messages)
        subscribe #(-> socket (.sendText (json/write-json {:op "xr/context/subscribe"}) true) .join)]
    (try
      (subscribe)
      (let [context (await-context messages (:environment/generation @session))]
        (is (= "ready" (:status context)))
        (is (seq (get-in context [:datum :objects]))))
      ;; Re-subscribing is idempotent; the same channel observes the new Net.
      (subscribe)
      (let [previous (:environment/generation @session)]
        (loader/replace-session-from-source! session "(define observed 3)")
        (is (not= previous (:environment/generation @session)))
        (is (= "ready" (:status (await-context messages (:environment/generation @session))))))
      (finally
        (.join (.sendClose socket 1000 "done"))
        ((:close http))))))

(defn- context-workers []
  (into #{} (filter #(.startsWith (.getName ^Thread %) "xr-context"))
        (keys (Thread/getAllStackTraces))))

(defn- stopped? [workers]
  (let [deadline (+ (System/nanoTime) 2000000000)]
    (loop []
      (cond
        (not-any? #(.isAlive ^Thread %) workers) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest abrupt-disconnect-stops-context-worker
  (let [before (context-workers)
        session (loader/load-session-from-source "(define observed 2)")
        http (server/start-server 0 session)
        messages (LinkedBlockingQueue.)
        socket (connect (:port http) messages)]
    (try
      (.join (.sendText socket (json/write-json {:op "xr/context/subscribe"}) true))
      (is (some? (await-context messages (:environment/generation @session))))
      (let [owned (sets/difference (context-workers) before)]
        (is (= 1 (count owned)))
        (.abort socket)
        (is (stopped? owned)))
      (finally (.abort socket) ((:close http))))))
