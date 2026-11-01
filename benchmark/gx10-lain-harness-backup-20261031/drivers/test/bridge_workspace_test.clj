(ns lain.bridge-workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [lain.flash-once :as trial]))

(deftest trusted-bridge-does-not-protect-the-project-from-writes
  (let [{:keys [argv container-argv]} (trial/bridge-spawn-config)
        script (second argv)
        code-directory (.toPath (.getParentFile (io/file script)))
        project (.toPath (io/file "/app"))]
    (is (.isAbsolute (io/file script)))
    (is (not (.startsWith project code-directory)))
    (is (not (.startsWith code-directory project)))
    (is (= argv (vec (drop 4 container-argv)))))
  (testing "explicit test drivers retain their supplied executable"
    (is (= ["test-node" "/fixture/bridge.mjs" "--probe"]
           (:argv (trial/bridge-spawn-config
                    {:node "test-node" :script "/fixture/bridge.mjs"
                     :serve-args ["--probe"]}))))))
