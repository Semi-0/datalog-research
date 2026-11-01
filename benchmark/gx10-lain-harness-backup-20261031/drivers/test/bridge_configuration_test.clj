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
