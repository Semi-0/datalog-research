(ns gx10-benchmark.process
  "Native bounded tool execution. Only handles created here may be stopped.
   Linux cgroup ownership remains a separate backend admission requirement."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(defn linux-memory []
  (let [values (into {} (keep (fn [line]
                              (when-let [[_ key amount] (re-matches #"([A-Za-z_]+):\s+(\d+).*" line)]
                                [key (* 1024 (Long/parseLong amount))]))
                            (str/split-lines (slurp "/proc/meminfo"))))]
    {:physical-bytes (get values "MemTotal")
     :available-bytes (get values "MemAvailable")
     :swap-used-bytes (- (get values "SwapTotal") (get values "SwapFree"))}))

(defn- remember-descendants! [owned process]
  (with-open [stream (.descendants (.toHandle ^Process process))]
    (doseq [handle (.toList stream)] (swap! owned conj handle))))

(defn- stop-owned! [process owned]
  (remember-descendants! owned process)
  (doseq [handle @owned] (.destroyForcibly ^java.lang.ProcessHandle handle))
  (.destroyForcibly ^Process process)
  (.waitFor ^Process process 5 TimeUnit/SECONDS)
  (doseq [handle @owned]
    (when (.isAlive ^java.lang.ProcessHandle handle)
      (throw (ex-info "Owned tool descendant remains alive" {:pid (.pid handle)})))))

(defn run!
  "ARGV never passes through a shell. Timeout nil is allowed only for agent execution.
   sample! and reserve-bytes are supplied together for guarded model work."
  [argv directory {:keys [timeout-ms sample! reserve-bytes record! environment]
                    :or {timeout-ms 120000 record! (fn [_]) environment {}}}]
  (when-not (and (vector? argv) (seq argv) (every? string? argv)
                 (or (nil? timeout-ms) (and (integer? timeout-ms) (pos? timeout-ms)))
                 (= (some? sample!) (some? reserve-bytes)))
    (throw (ex-info "Invalid command, timeout or RAM guard" {:argv argv})))
  (let [root (io/file directory)
        stdout (io/file root "stdout.log") stderr (io/file root "stderr.log")
        builder (ProcessBuilder. ^java.util.List argv)
        _ (.directory builder root)
        _ (.putAll (.environment builder) environment)
        _ (.redirectOutput builder stdout)
        _ (.redirectError builder stderr)
        started (System/nanoTime)
        owned (atom #{})
        sample-memory! (fn []
                         (when sample!
                           (let [sample (sample!)]
                             (record! sample)
                             (when-not (and (number? (:available-bytes sample))
                                            (>= (:available-bytes sample) reserve-bytes))
                               (throw (ex-info "RAM reserve crossed"
                                               {:outcome :resource-stopped :sample sample}))))))]
    (sample-memory!)
    (let [process (.start builder)]
      (try
        (loop []
          (remember-descendants! owned process)
          (sample-memory!)
          (let [elapsed (/ (- (System/nanoTime) started) 1e6)]
            (cond
              (.waitFor process 200 TimeUnit/MILLISECONDS)
              {:exit-code (.exitValue process) :elapsed-ms (/ (- (System/nanoTime) started) 1e6)
               :stdout (.getPath stdout) :stderr (.getPath stderr)}
              (and timeout-ms (>= elapsed timeout-ms))
              (throw (ex-info "Tool command timed out" {:outcome :tool-timeout :argv argv}))
              :else (recur))))
        (finally (stop-owned! process owned))))))
