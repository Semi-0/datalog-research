(ns graph.lain-editor.protected-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [graph.json :as json]
            [graph.lain-editor.protected-server :as protected]
            [graph.lain-editor.server :as server])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def credential (apply str (repeat 40 "a")))

(defn request [port authorizations body]
  (let [builder (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/api")))]
    (doseq [authorization authorizations]
      (.header builder "Authorization" authorization))
    (.POST builder (HttpRequest$BodyPublishers/ofString body))
    (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))))

(defn temporary-root []
  (.toFile (Files/createTempDirectory "lain-protected-test" (make-array FileAttribute 0))))

(deftest invalid-configuration-fails-before-startup
  (let [root (temporary-root)
        file (io/file root "credential")
        environment {"LAIN_RUNTIME_HOST" "127.0.0.1" "LAIN_RUNTIME_PORT" "0"
                     "LAIN_RUNTIME_PROJECT_ROOT" (.getPath root)
                     "LAIN_RUNTIME_BEARER_CREDENTIAL_FILE" (.getPath file)}]
    (try
      (spit file credential)
      (is (= credential (:credential (protected/configuration environment))))
      (doseq [key (keys environment)]
        (is (thrown? Exception (protected/configuration (dissoc environment key)))))
      (doseq [host ["0.0.0.0" "10.0.0.10"]]
        (is (thrown? Exception (protected/configuration (assoc environment "LAIN_RUNTIME_HOST" host)))))
      (doseq [value ["" "short" "contains whitespace and invalid ! characters"]]
        (spit file value)
        (is (thrown? Exception (protected/configuration environment))))
      (finally (.delete file) (.delete root)))))

(deftest rejected-requests-never-parse-or-dispatch-commands
  (let [root (temporary-root)
        runtime (protected/start! {:port 0 :credential credential :project-root (.getPath root)})
        reads (atom 0)]
    (try
      (with-redefs [json/read-json (fn [_] (swap! reads inc) (throw (Exception. "must not parse")))]
        (doseq [headers [[] ["Bearer wrong"] ["Basic invalid"]
                         [(str "Bearer " credential) (str "Bearer " credential)]
                         [(str "Bearer " credential ", Bearer wrong")]]
                operation ["run" "save-block" "save-workspace" "inspect" "workspace-poll"
                           "development" "list-sessions"]]
          (is (= 401 (.statusCode (request (:port runtime) headers
                                          (str "{\"op\":\"" operation "\"}")))))))
      (is (zero? @reads))
      (is (empty? @(:editors runtime)))
      (is (empty? (.listFiles root)))
      (finally ((:close runtime)) (.delete root)))))

(deftest authenticated-execution-project-root-and-credential-rotation
  (let [root (temporary-root)
        runtime (protected/start! {:port 0 :credential credential :project-root (.getPath root)})
        headers [(str "Bearer " credential)]
        call (fn [command] (request (:port runtime) headers (json/write-json command)))]
    (try
      (let [response (call {:op "run" :pane "authenticated" :source "(define result (+ 2 3)) (display result)"})
            result (:result (json/read-json (.body response)))]
        (is (= 200 (.statusCode response)))
        (is (:generation result))
        (is (= 200 (.statusCode (call {:op "inspect" :pane "authenticated" :generation (:generation result)})))))
      (let [response (call {:op "save-block" :path "controlled.lain" :source "(display 5)"})]
        (is (= 200 (.statusCode response))))
      (is (.exists (io/file root "controlled.lain")))
      (is (= 400 (.statusCode (call {:op "save-block" :path "../escape.lain" :source "(display 5)"}))))
      (finally ((:close runtime)) (.delete (io/file root "controlled.lain")) (.delete root)))
    (let [next-root (temporary-root)
          next-runtime (protected/start! {:port 0 :credential (apply str (repeat 40 "b"))
                                          :project-root (.getPath next-root)})]
      (try
        (is (= 401 (.statusCode (request (:port next-runtime) headers "{}"))))
        (finally ((:close next-runtime)) (.delete next-root))))))
