(ns gx10-benchmark.provenance
  (:require [clojure.java.io :as io]
            [gx10-benchmark.session :as session])
  (:import [java.security MessageDigest]))

(defn sha256 [text]
  (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                                   (.getBytes ^String text "UTF-8")))))

(defn capture [cell]
  {:protocol :gx10-clojure-v1
   :cell cell
   :runtime {:clojure (clojure-version) :java (System/getProperty "java.version")
             :os (System/getProperty "os.name") :architecture (System/getProperty "os.arch")}
   :sources (into {} (for [file ["workflow.lain" "refinement.lain"]
                          :let [text (session/source file)]]
                      [file {:sha256 (sha256 text) :source text}]))
   :recorded-epoch-ms (System/currentTimeMillis)})
