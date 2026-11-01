(ns lain.bridge-configuration-test
  (:require [clojure.test :refer [deftest is]]
            [lain.flash-once :as trial]))

(deftest bridge-configuration-matches-the-main-session-and-admitted-profile
  (let [configuration (assoc (select-keys trial/pi-config-map [:provider :model :cwd :tools])
                             :session trial/sid)
        reordered (update configuration :tools #(vec (reverse %)))]
    (is (identical? configuration (trial/validate-bridge-configuration configuration)))
    (is (identical? reordered (trial/validate-bridge-configuration reordered)))
    (doseq [[field wrong] [[:session "lain-bridge-a1"]
                           [:provider "foreign-provider"]
                           [:model "foreign-model"]
                           [:cwd "/tmp"]
                           [:tools ["write"]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Bridge configuration disagrees"
            (trial/validate-bridge-configuration (assoc configuration field wrong)))))))

(deftest fresh-trials-can-select-a-distinct-session-identity
  (is (= "lain-trial/a1" (trial/session-id-from-environment {})))
  (is (= "lain-trial/fresh" (trial/session-id-from-environment
                            {"LAIN_HARNESS_SESSION_ID" "lain-trial/fresh"})))
  (doseq [invalid [nil "" " " 17]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nonblank string"
          (trial/session-id-from-environment {"LAIN_HARNESS_SESSION_ID" invalid})))))

(deftest fresh-trial-rejects-existing-or-escaping-transport-namespaces
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                           "lain-fresh-bridge-"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        existing (java.io.File. directory "fresh")
        configuration {:root "/logs/agent/fresh"}]
    (try
      (is (identical? configuration
                      (trial/require-fresh-bridge-root! directory configuration)))
      (.mkdir existing)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
            (trial/require-fresh-bridge-root! directory configuration)))
      (doseq [root ["/tmp/foreign" "/logs/agent/" "/logs/agent/../outside"]]
        (is (thrown? clojure.lang.ExceptionInfo
              (trial/require-fresh-bridge-root! directory {:root root}))))
      (finally (.delete existing) (.delete directory)))))
