;; One JVM: time repeated LFTJ + semi-naive (for rough comparison with Rust criterion).
(ns bench-compare
  (:require [clojure.java.io :as io]))

(load-file (.getCanonicalPath (io/file "leapfrog/leapfrog.clj")))

(in-ns 'leapfrog)

(defmacro bench [label expr times]
  `(let [t# (System/nanoTime)]
     (dotimes [_# ~times] ~expr)
     (let [ms# (/ (- (System/nanoTime) t#) 1e6)]
       (println (str ~label ": " (format "%.3f" ms#) " ms total, "
                     (format "%.6f" (/ ms# ~times)) " ms / iter for " ~times " iters")))))

(bench "lftj demo [R S T] [:x :y :z]" (lftj [R S T] [:x :y :z]) 10000)

(def edge120
  {:edge (into #{} (map (fn [i] [i (inc i)])) (range 1 121))})

(bench "semi-naive path line 120 edges"
       (semi-naive edge120 path-rules)
       50)

nil
