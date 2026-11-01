(ns lain.pi-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [lain.flash-once :as driver]))

(deftest explicit-pi-profile-is-atomic
  (let [baseline (driver/pi-config-from-environment {})
        selected (driver/pi-config-from-environment
                   {"LAIN_PI_PROVIDER" "gx10-blazux"
                    "LAIN_PI_MODEL" "qwen3.8-flash-next"})]
    (is (= "gx10-flash-next-autoround" (:provider baseline)))
    (is (= "qwen3.8-flash-next-autoround" (:model baseline)))
    (is (= "gx10-blazux" (:provider selected)))
    (is (= "qwen3.8-flash-next" (:model selected)))
    (is (= (dissoc baseline :provider :model)
           (dissoc selected :provider :model)))
    (doseq [invalid [{"LAIN_PI_PROVIDER" "gx10-blazux"}
                     {"LAIN_PI_MODEL" "qwen3.8-flash-next"}
                     {"LAIN_PI_PROVIDER" "" "LAIN_PI_MODEL" "model"}
                     {"LAIN_PI_PROVIDER" "provider" "LAIN_PI_MODEL" "  "}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nonblank pair"
                           (driver/pi-config-from-environment invalid))))))
