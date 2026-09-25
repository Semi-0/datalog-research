(ns graph.xr-relationship-server-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.xr-server :as server]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.extensions.relationship-xr :as relationship-xr]
            [propagators.compiler-2.runtime.session.file-loader :as loader])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- request
  [method uri body]
  (let [builder (doto (HttpRequest/newBuilder (URI/create uri))
                  (.header "Content-Type" "application/json"))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder
                               (HttpRequest$BodyPublishers/ofString body))
                  (throw (ex-info "unsupported test method" {:method method})))]
    (.send (HttpClient/newHttpClient)
           (.build request)
           (HttpResponse$BodyHandlers/ofString))))

(deftest relationship-page-and-endpoint-share-the-xr-server
  (let [{:keys [port close] :as running}
        (server/start-server 0 (runtime/new-session))
        base (str "http://" server/default-host ":" port)]
    (try
      (let [page (request :get (str base "/relationships") nil)]
        (is (= 200 (.statusCode page)))
        (is (re-find #"Compiler-2 XR Graph" (.body page))))
      (let [graph {:nodes [{:id "a" :label "A" :kind "cell"}]
                   :edges []}
            posted (request :post
                            (str base "/api/relationships")
                            (json/write-json graph))
            fetched (request :get (str base "/api/relationships") nil)]
        (is (= 200 (.statusCode posted)))
        (is (= 200 (.statusCode fetched)))
        (is (= graph (:graph (json/read-json (.body fetched)))))
        (is (= {:graph graph} @(:relationship-state running))))
      (finally
        (close)))))

(defn- next-effect-graph
  [^LinkedBlockingQueue messages timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (when (pos? remaining)
          (when-let [text (.poll messages remaining TimeUnit/MILLISECONDS)]
            (let [payload (json/read-json text)]
              (if (= "xr/effects/update" (:type payload))
                (get-in payload [:result :graph])
                (recur)))))))))

(deftest connected-xr-client-receives-a-fresh-environment-after-file-edit
  (let [temporary (java.io.File/createTempFile "relationship-xr-reload-" ".lain")
        first-source
        "(def-cells source result graph)
         (<-> 1 source)
         (-> (+ source 1) result)
         (relationship:roots source graph)
         (xr:io graph)"
        second-source
        "(def-cells source middle result graph)
         (<-> 2 source)
         (-> (+ source 1) middle)
         (-> (* middle 2) result)
         (relationship:roots source graph)
         (xr:io graph)"
        opts {:extensions [(relationship-xr/extension)]}
        _ (spit temporary first-source)
        session (loader/load-session-from-file temporary opts)
        watcher (loader/watch-file! session temporary
                                    (assoc opts :interval-ms 10))
        xr (server/start-server 0 session)
        messages (LinkedBlockingQueue.)
        fragments (atom "")
        listener
        (reify WebSocket$Listener
          (onOpen [_ socket]
            (.request socket 1))
          (onText [_ socket data last]
            (swap! fragments str data)
            (when last
              (.offer messages @fragments)
              (reset! fragments ""))
            (.request socket 1)
            nil))
        socket (-> (HttpClient/newHttpClient)
                   (.newWebSocketBuilder)
                   (.buildAsync
                    (URI/create
                     (str "ws://" server/default-host ":" (:port xr) "/ws"))
                    listener)
                   (.join))]
    (try
      (let [before (next-effect-graph messages 3000)]
        (is (some? before))
        (spit temporary second-source)
        (let [after (loop [graph (next-effect-graph messages 5000)]
                      (cond
                        (nil? graph) nil
                        (not= before graph) graph
                        :else (recur (next-effect-graph messages 5000))))]
          (is (some? after))
          (is (not= before after))
          (is (> (count (:nodes after)) (count (:nodes before))))))
      (finally
        (.join (.sendClose socket WebSocket/NORMAL_CLOSURE "done"))
        ((:close watcher))
        ((:close xr))
        (.delete temporary)))))

(deftest xr-view-payload-resolves-against-the-replaced-environment
  (let [source (fn [value]
                 (str "(def-cells source window history dashboard)"
                      "(<-> " value " source)"
                      "(cell-window source window)"
                      "(cell-history source history)"
                      "(juxtapose window history dashboard)"
                      "(xr:io dashboard)"))
        opts {:extensions [(relationship-xr/extension)]}
        session (loader/load-session-from-source (source 1) opts)
        first-payload (#'server/latest-effect-payload session)]
    (is (= "juxtapose" (get-in first-payload [:views 0 :type])))
    (is (= "1" (get-in first-payload
                         [:views 0 :children 0 :strongest :value])))
    (is (seq (get-in first-payload [:views 0 :children 1 :samples])))
    (loader/replace-session-from-source! session (source 2) opts)
    (let [second-payload (#'server/latest-effect-payload session)]
      (is (= "2" (get-in second-payload
                           [:views 0 :children 0 :strongest :value])))
      (is (not= first-payload second-payload)))))
