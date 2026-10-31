(ns lain.w23-fake-bridge
  "Deterministic in-process fake of the Node pi-bridge wire protocol (contract.md §1-§4), for
   the drivers-side W23 contract suite. Requests arrive as one JSON object per line on the
   process's stdin; replies go out on stdout with the matching :id. Replies are produced
   SYNCHRONOUSLY inside the write call so the test needs no responder thread and cannot race
   the reader: the driver's own reader task drains whatever this writes.

   The process stdin is a plain (unbuffered) OutputStream wrapper, NOT a BufferedOutputStream:
   a buffered wrapper on a zero-byte stream never forwards bytes, and rpc's flush then raises
   java.io.IOException: Stream closed."
  (:require [cheshire.core :as json])
  (:import [java.io ByteArrayInputStream PipedInputStream PipedOutputStream OutputStream]))

(defn- reply-for [req]
  (cond
    (= "subagent_spawn_batch" (:tool req)) {"status" "launched" :child "w23-child-1"}
    (= "subagent_result"      (:tool req)) {"status" "completed" :child "w23-child-1"
                                            :result {"answer" "7"}}
    (= "subagent_output"      (:tool req)) {"status" "running" :child "w23-child-1"}
    (= "subagent_stop"        (:tool req)) {"status" "stopped" :child "w23-child-1"
                                            "cleanup-verified" true}
    (= "reconcile"            (:operation req)) {"status" "completed" :child "w23-child-1"
                                                 :result {"answer" "7"}}
    (= "stop"                 (:operation req)) {"status" "stopped" "cleanup-verified" true}
    (= "dispose"              (:operation req)) {"status" "stopped" "cleanup-verified" true}
    :else {"status" "failed" :failure {"message" "unhandled fake request"}}))

(defn- sink [resp-out]
  (let [pending (StringBuilder.)]
    (proxy [OutputStream] []
      (write
        ([b] (.write this b 0 (alength b)))
        ([b off len]
         (dotimes [i len]
           (let [c (char (bit-and (int (aget b (+ off i))) 0xff))]
             (if (= \newline c)
               (let [line (str pending)]
                 (.setLength pending 0)
                 (when (seq (seq line))
                   (let [req (try (json/parse-string line true) (catch Exception _ nil))]
                     (when (and (map? req) (:id req))
                       ;; replies are NEWLINE-TERMINATED: the driver reader uses BufferedReader/readLine,
                       ;; which never returns for an unterminated final line
                       (.write ^PipedOutputStream resp-out
                               (.getBytes ^String (str (json/generate-string
                                                        {:id (:id req) :result (reply-for req)})
                                                       "\n")
                                               "utf8"))
                       (.flush ^PipedOutputStream resp-out)))))
               (.append pending c))))))
      (flush [])
      (close []))))

(defn create []
  "Fake bridge Process. Requests arrive as newline-JSON on getOutputStream; replies are produced
   SYNCHRONOUSLY on the paired piped stream so no responder thread or timing is involved."
  (let [resp-in  (PipedInputStream. 65536)
        resp-out (PipedOutputStream. resp-in)]
    (proxy [Process] []
      (getInputStream [] resp-in)
      (getOutputStream [] (sink resp-out))
      (getErrorStream [] (ByteArrayInputStream. (byte-array 0)))
      (waitFor [] 0)
      (exitValue [] 0)
      (destroy [] (doseq [s [resp-out]] (try (.close ^PipedOutputStream s) (catch Exception _))))
      (destroyForcibly [] (.destroy ^Process this))
      (isAlive [] true))))
