(ns propagators.compiler-2.runtime.session.file-loader-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.compiler-2.runtime.bridge.web :as bridge]
            [graph.compiler-2-runtime-server :as server]
            [propagators.compiler-2.model.env :as cenv]
            [propagators.network :as net]))

(def demo-file "examples/lain/demo.lain")

(deftest demo-lain-info-form-loads
  (let [session (runtime/new-session)
        info-source (-> demo-file
                        loader/read-file-source
                        loader/source-forms
                        first
                        pr-str)
        client-id "printer"]
    (loader/load-source! session info-source {:client-id client-id})
    (is (some? (cenv/resolve-binding-id
                (:program/net @session)
                (:program/env @session)
                'info)))))

(deftest runtime-server-loads-lain-file-command
  (let [{:keys [port close]} (server/start-server 0)
        client-id "printer"]
    (try
      (let [response (server/request server/default-host
                                     port
                                     {:op :compile/load-file
                                      :file demo-file
                                      :client-id client-id})]
        (is (:ok response))
        (is (= client-id (get-in response [:result :client-id])))
        (is (re-find #"demo\.lain$"
                     (get-in response [:result :file]))))
      (finally
        (close)))))

(deftest runtime-server-startup-load-option-loads-lain-file
  (let [temporary (java.io.File/createTempFile "compiler2-startup-" ".lain")
        _ (spit temporary "(def info 1)")
        startup-file (.getAbsolutePath temporary)
        client-id "printer"
        opts (#'server/parse-server-args
              ["--load" startup-file
               "--load-client" client-id
               "--load-blocks" "1"])
        {:keys [close] :as server-state} (server/start-server 0)]
    (try
      (is (= startup-file (:load-file opts)))
      (is (= client-id (:load-client-id opts)))
      (let [loaded (#'server/load-startup-file! server-state opts)]
        (is (= client-id (:client-id loaded)))
        (is (= startup-file (:file loaded))))
      (finally
        (close)
        (.delete temporary)))))

(deftest lain-source-normalizer-accepts-consecutive-top-level-forms
  (is (= ['(def-cell x) '(<-> 1 x)]
         (loader/source-forms "(def-cell x)\n(<-> 1 x)"))))

(deftest lain-loader-preserves-block-by-block-def-net-application
  (let [session (runtime/new-session)
        source "(def-cell clients)
                (runtime:clients clients)
                (def-net first-client [clients] [out]
                  (p:car clients))
                (def-cell f)
                (first-client clients f)"]
    (loader/load-source! session source {:client-id "file-test"})
    (runtime/commit-runtime-input! session
                                   {:runtime/input :cell-message
                                    :cell-id (bridge/client-list-source-id)
                                    :update (bridge/linked-list-value ["A" "B"])})
    (is (= (bridge/client-handle "A")
           (net/network-cell-strongest
            (:program/net @session)
            (cenv/resolve-binding-id
             (:program/net @session)
             (:program/env @session)
             'f))))))
