(ns gx10-benchmark.journal
  "Atomic EDN checkpoints and a host-wide controller lock. No result overwrites."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io FileOutputStream RandomAccessFile]
           [java.nio.file Files StandardCopyOption CopyOption]
           [java.nio.channels OverlappingFileLockException]))

(defn read-record [path]
  (edn/read-string (slurp path)))

(defn write-record! [path record]
  (let [file (io/file path)
        parent (.toPath (.getCanonicalFile (.getParentFile file)))
        temporary (Files/createTempFile parent ".checkpoint-" ".edn"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        bytes (.getBytes (str (pr-str record) "\n") "UTF-8")]
    (try
      (with-open [stream (FileOutputStream. (.toFile temporary))]
        (.write stream bytes)
        (.sync (.getFD stream)))
      (Files/move temporary (.toPath file)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      record
      (finally (Files/deleteIfExists temporary)))))

(defn new-directory! [path]
  (let [file (.getCanonicalFile (io/file path))]
    (Files/createDirectory (.toPath file)
                           (make-array java.nio.file.attribute.FileAttribute 0))
    file))

(defn with-host-lock! [lock-path f]
  (with-open [file (RandomAccessFile. (io/file lock-path) "rw")
              channel (.getChannel file)]
    (let [lock (try (.tryLock channel)
                    (catch OverlappingFileLockException _ nil))]
      (when-not lock
        (throw (ex-info "Another experiment controller owns the host lock" {:lock lock-path})))
      (try (f) (finally (.release lock))))))
