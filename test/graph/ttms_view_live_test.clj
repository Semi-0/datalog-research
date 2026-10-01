(ns graph.ttms-view-live-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.xr-server :as server]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.view-xr-test :as fixture])
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
                   (onError [_ _ error]
                     (.offer messages {:error (str error)})))]
    (-> (HttpClient/newHttpClient) (.newWebSocketBuilder)
        (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/ws")) listener)
        (.join))))

(defn- await-view [messages expected]
  (let [deadline (+ (System/nanoTime) 20000000000)]
    (loop []
      (let [remaining (- deadline (System/nanoTime))]
        (if (pos? remaining)
          (let [message (.poll messages remaining TimeUnit/NANOSECONDS)
                children (get-in message [:result :views 0 :children])]
            (cond
              (:error message) (throw (ex-info "WebSocket error" message))
              (and (= 8 (count children))
                   (= expected (count (:items (nth children 4))))) children
              :else (recur)))
          nil)))))

(deftest complete-chain-selection-clear-and-reselection-reach-websocket
  (let [session (loader/load-session-from-file
                 "examples/lain/visualization_combinators/chain.lain" fixture/ttms-options)
        http (server/start-server 0 session)
        messages (LinkedBlockingQueue.)
        socket (connect (:port http) messages)]
    (try
      (is (some? (await-view messages 0)))
      (.join (.sendText socket (json/write-json (fixture/command session 0)) true))
      (is (some? (await-view messages 1)))
      (let [clear (assoc (dissoc (fixture/command session 0) :item-id) :clear? true)]
        (.join (.sendText socket (json/write-json clear) true)))
      (let [cleared (await-view messages 0)]
        (is (some? cleared))
        (is (and cleared (every? #(empty? (:items %)) (take 3 (drop 4 cleared))))))
      (.join (.sendText socket (json/write-json (fixture/command session 1)) true))
      (is (some? (await-view messages 1)))
      (finally
        (.join (.sendClose socket 1000 "done"))
        ((:close http))))))
