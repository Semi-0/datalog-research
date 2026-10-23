#!/usr/bin/env clojure
(ns migrate-gx10-benchmark
  "Verified, non-destructive transfer of the GX10 harness from KIROSHI."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files Path LinkOption StandardCopyOption]
           [java.security MessageDigest]
           [java.time Instant]))

(def roots ["benchmark/gx10-context" "benchmark/ornith-nvfp4-mtp"])
(def launchers ["scripts/run-background.sh" "scripts/run-background.md"
                "scripts/gx10_protocol_v3.py"])
(def excluded-directories
  #{".git" ".agent-memory" ".venv" "venv" "node_modules" "__pycache__"
    ".pytest_cache" "results" "runs" "outputs" "logs" "tmp" "target"
    "models" "weights" ".cache"})
(def source-extensions
  #{"py" "mjs" "js" "json" "md" "txt" "clj" "cljs" "cljc" "edn"
    "sh" "toml" "yaml" "yml" "lock" "gitignore" "service"})
(def no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def receipt-path "benchmark/gx10-migration-manifest.edn")

(defn fail! [message data]
  (throw (ex-info message data)))

(defn canonical-root [value]
  (.toRealPath (.toPath (io/file value)) (make-array LinkOption 0)))

(defn parse-options [args]
  (loop [remaining args, options {:apply false}]
    (if-let [argument (first remaining)]
      (case argument
        "--apply" (recur (rest remaining) (assoc options :apply true))
        "--source" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc options :source value))
                     (fail! "--source requires a directory" {}))
        "--destination" (if-let [value (second remaining)]
                          (recur (nnext remaining) (assoc options :destination value))
                          (fail! "--destination requires a directory" {}))
        (fail! "Unknown option" {:argument argument}))
      options)))

(defn digest [path]
  (let [hash (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [stream (io/input-stream (.toFile ^Path path))]
      (loop []
        (let [n (.read stream buffer)]
          (when (pos? n)
            (.update hash buffer 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest hash)))))

(defn exclusion [relative]
  (let [parts (str/split relative #"/")
        name (last parts)
        extension (last (str/split name #"\."))]
    (cond
      (some excluded-directories parts) :generated-or-runtime-directory
      (re-find #"(?i)(^\.env|credential|secret|\.pem$|\.key$)" name) :private-file
      (not (source-extensions extension)) :non-source-file
      :else nil)))

(defn discover [source]
  (doseq [relative (concat roots launchers)]
    (when-not (Files/exists (.resolve source relative) no-links)
      (fail! "Required migration input is absent" {:path relative})))
  (let [paths (concat
               (mapcat (fn [relative]
                         (with-open [stream (Files/walk (.resolve source relative)
                                                       (make-array java.nio.file.FileVisitOption 0))]
                           (vec (iterator-seq (.iterator stream))))) roots)
               (map #(.resolve source %) launchers))]
    (reduce
      (fn [inventory path]
        (let [relative (str (.relativize source path))]
          (cond
            (Files/isSymbolicLink path)
            (fail! "Symbolic links are not accepted" {:path relative})
            (Files/isDirectory path no-links) inventory
            (exclusion relative)
            (update inventory :excluded conj {:path relative :reason (exclusion relative)})
            (> (Files/size path) (* 2 1024 1024))
            (fail! "Source file exceeds 2 MiB; inspect before migration" {:path relative})
            :else (update inventory :files conj
                          {:path relative :sha256 (digest path) :bytes (Files/size path)}))))
      {:files [] :excluded []} (sort-by str paths))))

(defn destination-path [destination relative]
  (let [path (.normalize (.resolve destination relative))]
    (when-not (.startsWith path destination)
      (fail! "Destination escapes repository" {:path relative}))
    (loop [parent path]
      (when (and parent (not= parent destination))
        (when (Files/isSymbolicLink parent)
          (fail! "Destination contains a symbolic link" {:path (str parent)}))
        (recur (.getParent parent))))
    path))

(defn verify-destination! [destination files]
  (doseq [{:keys [path sha256]} files]
    (let [target (destination-path destination path)]
      (when (Files/exists target no-links)
        (when-not (and (Files/isRegularFile target no-links) (= sha256 (digest target)))
          (fail! "Refusing to overwrite a different destination file" {:path path}))))))

(defn copy-file! [source destination {:keys [path sha256]}]
  (let [origin (.resolve source path)
        target (destination-path destination path)]
    (when-not (= sha256 (digest origin))
      (fail! "Source changed after inventory" {:path path}))
    (when-not (Files/exists target no-links)
      (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/copy origin target (into-array java.nio.file.CopyOption [StandardCopyOption/COPY_ATTRIBUTES])))
    (when-not (= sha256 (digest target))
      (fail! "Copied file failed SHA-256 verification" {:path path}))))

(defn save-receipt! [destination receipt]
  (let [target (destination-path destination receipt-path)]
    (if (Files/exists target no-links)
      (let [previous (edn/read-string (slurp (.toFile target)))]
        (when-not (= (select-keys previous [:source :destination :files :excluded])
                     (select-keys receipt [:source :destination :files :excluded]))
          (fail! "Existing migration receipt differs" {:path receipt-path})))
      (let [temporary (Files/createTempFile (.getParent target) ".gx10-migration-" ".edn"
                                            (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (.toFile temporary) (str (pr-str receipt) "\n"))
          (Files/move temporary target (make-array java.nio.file.CopyOption 0))
          (finally (Files/deleteIfExists temporary)))))))

(defn -main [& args]
  (let [options (parse-options args)
        destination (canonical-root (get options :destination "."))
        source (canonical-root (get options :source (str (.resolve (.getParent destination) "kiroshi"))))]
    (when (or (.startsWith source destination) (.startsWith destination source))
      (fail! "Source and destination must be separate directory trees" {}))
    (let [inventory (discover source)
          receipt (assoc inventory :protocol 1 :source (str source) :destination (str destination)
                         :created-at (str (Instant/now)) :source-preserved true)]
      (when (empty? (:files inventory))
        (fail! "Migration inventory is empty" {}))
      (verify-destination! destination (:files inventory))
      (when (:apply options)
        (doseq [file (:files inventory)]
          (copy-file! source destination file))
        (save-receipt! destination receipt))
      (prn {:mode (if (:apply options) :applied :dry-run)
            :source (str source) :destination (str destination)
            :file-count (count (:files inventory))
            :bytes (reduce + (map :bytes (:files inventory)))
            :paths (mapv :path (:files inventory))
            :excluded (:excluded inventory)
            :receipt (when (:apply options) receipt-path)}))))

(apply -main *command-line-args*)
