(ns examples.lain.subagents.effect-boundary
  "Session-owned outside effects, durable claims, and observation-only replay."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [examples.lain.subagents.facts :as facts])
  (:import [java.io BufferedReader PushbackReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileLock]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def max-payload-bytes (* 1024 1024))
(def max-string-length (* 64 1024))
(def max-depth 12)
(def max-nodes 4096)
(def max-recording-bytes (* 64 max-payload-bytes))
(def max-recording-entries 8192)
(def close-wait-ms 5000)
(def success-statuses #{:completed :sent :written :acknowledged :closed :disposed})
(def failure-statuses #{:failed :uncertain :partial :partial-write})

(defn- bounded-number? [number]
  (and (Double/isFinite (double number))
       (cond
         (integer? number) (<= Long/MIN_VALUE number Long/MAX_VALUE)
         (ratio? number) (and (<= Long/MIN_VALUE (numerator number) Long/MAX_VALUE)
                              (<= 1 (denominator number) Long/MAX_VALUE))
         (decimal? number) (and (<= (.precision ^java.math.BigDecimal number) 64)
                                (<= -64 (.scale ^java.math.BigDecimal number) 64))
         :else true)))

(defn- scalar? [value]
  (or (nil? value) (boolean? value) (string? value)
      (keyword? value) (and (number? value) (bounded-number? value))))

(defn- scalar-bytes [value]
  (when (and (string? value) (> (count value) max-string-length))
    (throw (ex-info "Effect string exceeds its bound" {:limit max-string-length})))
  (when (and (keyword? value)
             (or (> (+ (count (name value)) (count (namespace value))) max-string-length)
                 (re-find #"[\s\[\]{}()\";,\\@]" (str value))))
    (throw (ex-info "Effect keyword is not a bounded EDN token" {})))
  (alength (.getBytes ^String (pr-str value) StandardCharsets/UTF_8)))

(defn bounded-data!
  "Validate bounded plain data before serialization. Records, Net graphs, lazy
  sequences, mutable objects and non-finite numbers are rejected without walking
  their internals. Returns the original value."
  [value]
  (loop [pending [[value 0]] nodes 0 bytes 0]
    (if-let [[current depth] (peek pending)]
      (let [remaining (pop pending)
            nodes (inc nodes)]
        (when (or (> nodes max-nodes) (> depth max-depth) (record? current)
                  (= :bool4/nothing current) (= :bool4/contradiction current))
          (throw (ex-info "Effect data exceeds its structural bound or contains an internal record"
                          {:nodes nodes :depth depth})))
        (cond
          (scalar? current)
          (let [bytes (+ bytes 1 (scalar-bytes current))]
            (when (> bytes max-payload-bytes)
              (throw (ex-info "Effect data exceeds its encoded bound" {:limit max-payload-bytes})))
            (recur remaining nodes bytes))

          (map? current)
          (do
            (when (or (> (count current) max-nodes)
                      (not-every? #(or (keyword? %) (string? %)) (keys current)))
              (throw (ex-info "Effect maps need bounded keyword/string keys" {})))
            (recur (reduce-kv (fn [stack key item]
                                (conj stack [key (inc depth)] [item (inc depth)]))
                              remaining current)
                   nodes (+ bytes 2)))

          (vector? current)
          (do
            (when (> (count current) max-nodes)
              (throw (ex-info "Effect vector exceeds its bound" {})))
            (recur (into remaining (map #(vector % (inc depth)) current)) nodes (+ bytes 2)))

          :else
          (throw (ex-info "Effect data must contain only plain scalar/map/vector values"
                          {:type (.getName (class current))}))))
      (do
        (when (> (alength (.getBytes ^String (pr-str value) StandardCharsets/UTF_8)) max-payload-bytes)
          (throw (ex-info "Effect data exceeds its encoded bound" {:limit max-payload-bytes})))
        value))))

(defrecord Boundary [session mode invoke! path channel file-lock ledger])

(defn- parse-entry [line session]
  (when (> (alength (.getBytes ^String line StandardCharsets/UTF_8)) max-payload-bytes)
    (throw (ex-info "Recording entry exceeds its encoded bound" {})))
  (with-open [reader (PushbackReader. (StringReader. line))]
    (let [entry (edn/read {:eof ::eof} reader)
          trailing (edn/read {:eof ::eof} reader)]
      (when (or (= ::eof entry) (not= ::eof trailing))
        (throw (ex-info "Recording needs exactly one EDN entry per line" {})))
      (bounded-data! entry)
      (when-not (and (map? entry) (= 1 (:record/schema entry)) (= session (:session entry)))
        (throw (ex-info "Invalid or foreign-session effect recording" {})))
      entry)))

(defn- read-line! [^BufferedReader reader]
  (let [builder (StringBuilder.)]
    (loop []
      (let [character (.read reader)]
        (cond
          (= -1 character) (when (pos? (.length builder)) (.toString builder))
          (= 10 character) (.toString builder)
          (>= (.length builder) max-payload-bytes)
          (throw (ex-info "Recording line exceeds its bound" {}))
          :else (do (.append builder (char character)) (recur)))))))

(defn- recording-entries [path session]
  (let [file (io/file path)]
    (when (> (.length file) max-recording-bytes)
      (throw (ex-info "Effect recording exceeds its retained bound" {})))
    (with-open [reader (io/reader file :encoding "UTF-8")]
      (loop [entries []]
        (if-let [line (read-line! reader)]
          (do
            (when (>= (count entries) max-recording-entries)
              (throw (ex-info "Effect recording has too many entries" {})))
            (recur (conj entries (parse-entry line session))))
          entries)))))

(defn- release-recording! [boundary]
  (when-let [^FileLock lock (:file-lock boundary)]
    (when (.isValid lock) (.release lock)))
  (when-let [^FileChannel channel (:channel boundary)]
    (.close channel)))

(defn- open-recording! [path]
  (let [target (.toPath (.getAbsoluteFile (io/file path)))
        attributes (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                               (PosixFilePermissions/fromString "rw-------"))])]
    (when-let [parent (.getParent target)]
      (Files/createDirectories parent
                               (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                                           (PosixFilePermissions/fromString "rwx------"))])))
    (when (Files/isSymbolicLink target)
      (throw (ex-info "Effect recording cannot be a symbolic link" {})))
    (try (Files/createFile target attributes)
         (catch java.nio.file.FileAlreadyExistsException _ nil))
    (let [permissions (Files/getPosixFilePermissions target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
      (when-not (= permissions (PosixFilePermissions/fromString "rw-------"))
        (throw (ex-info "Effect recording must have mode 0600" {}))))
    (let [channel (FileChannel/open target
                                    (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/APPEND
                                                            LinkOption/NOFOLLOW_LINKS]))]
      (try
        (let [lock (.tryLock channel)]
          (when-not lock (throw (ex-info "Effect recording already has an owner" {})))
          {:path (str target) :channel channel :file-lock lock})
        (catch Throwable error (.close channel) (throw error))))))

(defn- record! [boundary entry]
  (when-let [^FileChannel channel (:channel boundary)]
    (let [entry (assoc entry :record/schema 1 :session (:session boundary))
          _ (bounded-data! entry)
          bytes (.getBytes ^String (str (pr-str entry) "\n") StandardCharsets/UTF_8)
          buffer (ByteBuffer/wrap bytes)]
      (when (> (+ (.size channel) (alength bytes)) max-recording-bytes)
        (throw (ex-info "Effect recording exceeds its retained bound" {})))
      (while (.hasRemaining buffer) (.write channel buffer))
      (.force channel true))))

(defn- checked-request [session request]
  (bounded-data! request)
  (facts/from-facts session [request])
  request)

(defn- observation-id [request-id index kind]
  (str "effect/observation/" (pr-str [request-id index kind])))

(defn- restored-ledger [session entries]
  (reduce
   (fn [ledger entry]
     (when (:recorded-disposed? ledger)
       (throw (ex-info "Recording contains entries after disposal" {})))
     (case (:record/type entry)
       :intent
       (let [request (checked-request session (:request entry))
             id (:id request)
             prior (get-in ledger [:receipts id])]
         (when-not (and (integer? (:next-order entry)) (<= 0 (:next-order entry) Long/MAX_VALUE))
           (throw (ex-info "Recorded intent has invalid observation order" {:id id})))
         (when (and prior (not= request (:request prior)))
           (throw (ex-info "Conflicting recorded effect identity" {:id id})))
         (if prior ledger
             (assoc-in ledger [:receipts id]
                       {:request request :status :uncertain :observations [] :unfinished? true
                        :error {:reason :interrupted-intent}
                        :next-order (:next-order entry)
                        :result (facts/empty-log session)})))
       :receipt
       (let [request (checked-request session (:request entry))
             id (:id request)
             prior (get-in ledger [:receipts id])
             observations (:observations entry)]
         (when-not (and prior (= request (:request prior)) (vector? observations))
           (throw (ex-info "Receipt does not belong to a recorded intent" {:id id})))
         (when-not (and (integer? (:next-order prior))
                         (<= 0 (:next-order prior) Long/MAX_VALUE)
                         (every? true?
                                 (map-indexed
                                  (fn [index observation]
                                    (and (= (observation-id id index (:kind observation)) (:id observation))
                                         (= (+ (:next-order prior) index) (:order observation))))
                                  observations)))
           (throw (ex-info "Recorded observations do not match their effect identity/order" {:id id})))
         (let [result (facts/from-facts session observations)
               receipt (assoc (select-keys entry [:request :status :observations :receipt :error])
                              :next-order (:next-order prior) :result result)]
           (when-not (or (success-statuses (:status receipt)) (failure-statuses (:status receipt)))
             (throw (ex-info "Unsupported recorded receipt status" {:id id})))
           (when (and (not (:unfinished? prior))
                      (not= (dissoc prior :result) (dissoc receipt :result)))
             (throw (ex-info "Conflicting recorded receipt" {:id id})))
           (assoc-in ledger [:receipts id] receipt)))
       :disposed (assoc ledger :recorded-disposed? true)
       (throw (ex-info "Unsupported effect recording entry" {:type (:record/type entry)}))))
   {:closed? false :closing? false :receipts {}} entries))

(defn open!
  [session {:keys [mode invoke! recording] :or {mode :observe}}]
  (facts/empty-log session)
  (when-not (#{:observe :synthetic :live :replay} mode)
    (throw (ex-info "Unsupported effect boundary mode" {:mode mode})))
  (when (and (#{:synthetic :live} mode) (not (fn? invoke!)))
    (throw (ex-info "Execution mode needs an outside interpreter" {:mode mode})))
  (let [resource (when (and recording (#{:synthetic :live} mode)) (open-recording! recording))]
    (try
      (let [entries (if (and recording (or (not= :observe mode) (.exists (io/file recording))))
                      (recording-entries (or (:path resource) recording) session) [])
            ledger (restored-ledger session entries)]
        (when (and (#{:synthetic :live} mode)
                   (or (:recorded-disposed? ledger)
                       (some #(= :uncertain (:status %)) (vals (:receipts ledger)))))
          (throw (ex-info "Recorded boundary needs explicit reconciliation or a new session"
                          {:reason :unresolved-recording})))
        (map->Boundary (merge resource {:session session :mode mode :invoke! invoke!
                                        :ledger (atom ledger)})))
      (catch Throwable error (release-recording! resource) (throw error)))))

(defn receipts [boundary]
  (into {} (map (fn [[id receipt]] [id (dissoc receipt :signal :result :unfinished? :next-order)]))
        (:receipts @(:ledger boundary))))

(defn- normalize-response [raw]
  (bounded-data! raw)
  (let [response (cond
                   (vector? raw) {:observations raw :receipt {} :status :completed}
                   (map? raw) (merge {:observations [] :receipt {} :status :completed} raw)
                   :else (throw (ex-info "Outside interpreter must return observations or an explicit receipt" {})))]
    (when-not (and (vector? (:observations response))
                   (or (success-statuses (:status response)) (failure-statuses (:status response))))
      (throw (ex-info "Unsupported outside interpreter outcome" {})))
    response))

(defn- failure-response [error]
  (let [details (try
                  (bounded-data! (select-keys (ex-data error) [:status :written :reason]))
                  (catch Throwable _ {:reason :unbounded-failure-metadata}))
        status (if (failure-statuses (:status details)) (:status details) :uncertain)
        message (or (ex-message error) "Outside interpreter failed")]
    {:status status :receipt details
     :error {:message (subs message 0 (min 4096 (count message)))
             :type (.getName (class error))}
     :observations []}))

(defn- stamp-observations [boundary request next-order response]
  (let [templates (cond-> (:observations response)
                    (failure-statuses (:status response))
                    (conj {:kind :effect/failed :request-id (:id request)
                           :status (:status response)
                           :failure (or (:error response) (:receipt response))}))]
    (mapv
     (fn [index template]
       (when-not (and (map? template) (keyword? (:kind template))
                      (not-any? #(contains? template %) [:session :id :order]))
         (throw (ex-info "Observation template cannot override its stamped identity" {})))
       (facts/fact (:session boundary)
                   (observation-id (:id request) index (:kind template))
                   (+ next-order index) (:kind template) (dissoc template :kind)))
     (range) templates)))

(defn- finish! [boundary request next-order response signal]
  (let [observations (stamp-observations boundary request next-order response)
        result (facts/from-facts (:session boundary) observations)
        receipt (merge (select-keys response [:status :receipt :error])
                       {:request request :observations observations :result result :next-order next-order})]
    (locking (:ledger boundary)
      (record! boundary (assoc (dissoc receipt :result) :record/type :receipt))
      (swap! (:ledger boundary) assoc-in [:receipts (:id request)] receipt))
    (deliver signal result)
    result))

(defn dispatch!
  "Persist one claim before invocation. Concurrent duplicates await the same saved
  observations; uncertain outcomes never invoke again under the same identity."
  [boundary request next-global-order]
  (checked-request (:session boundary) request)
  (when-not (and (integer? next-global-order) (<= 0 next-global-order Long/MAX_VALUE))
    (throw (ex-info "Observation order must be a nonnegative bounded integer" {})))
  (let [{:keys [prior signal execute?]}
        (locking (:ledger boundary)
          (when (or (:closed? @(:ledger boundary)) (:closing? @(:ledger boundary)))
            (throw (ex-info "Session effect boundary is disposed" {})))
          (if-let [prior (get-in @(:ledger boundary) [:receipts (:id request)])]
            (do
              (when-not (= request (:request prior))
                (throw (ex-info "Conflicting reused effect identity" {:id (:id request)})))
              {:prior prior})
            (if (#{:observe :replay} (:mode boundary))
              {:prior {:result (facts/empty-log (:session boundary))}}
              (let [signal (promise)]
                (swap! (:ledger boundary) assoc-in [:receipts (:id request)]
                       {:status :pending :request request :observations [] :signal signal})
                (try
                  (record! boundary {:record/type :intent :request request :next-order next-global-order})
                  {:signal signal :execute? true}
                  (catch Throwable error {:signal signal :execute? false :prior nil :error error}))))))]
    (if prior
      (if-let [waiting (:signal prior)] @waiting (:result prior))
      (let [response (if execute?
                       (try (normalize-response ((:invoke! boundary) request))
                            (catch Throwable error (failure-response error)))
                       {:status :failed :receipt {} :error {:message "Effect intent could not be persisted"}
                        :observations []})]
        (try
          (finish! boundary request next-global-order response signal)
          (catch Throwable error
            (let [failed (failure-response error)
                  observations (stamp-observations boundary request next-global-order failed)
                  result (facts/from-facts (:session boundary) observations)]
              (locking (:ledger boundary)
                (swap! (:ledger boundary) assoc-in [:receipts (:id request)]
                       (merge failed {:request request :observations observations :result result})))
              (deliver signal result)
              result)))))))

(defn- wait-for-claims! [signals]
  (let [deadline (+ (System/nanoTime) (* close-wait-ms 1000000))]
    (doseq [signal signals]
      (let [remaining (max 0 (quot (- deadline (System/nanoTime)) 1000000))]
        (when (= ::pending (deref signal remaining ::pending))
          (throw (ex-info "Effects remain in flight after disposal" {:cleanup-verified false})))))))

(defn- close-result [result]
  (when (= :failed (:status result))
    (throw (ex-info "Effect boundary cleanup was not verified" result)))
  result)

(defn- claim-close! [boundary]
  (locking (:ledger boundary)
    (cond
      (:closed? @(:ledger boundary)) {:prior (:close-result @(:ledger boundary))}
      (:closing? @(:ledger boundary)) {:signal (:close-signal @(:ledger boundary))}
      :else
      (let [signal (promise)]
        (swap! (:ledger boundary) assoc :closing? true :close-signal signal)
        {:signal signal :signals (vec (keep :signal (vals (:receipts @(:ledger boundary)))))}))))

(defn- dispose-outside! [boundary signals]
  (let [response (if (#{:synthetic :live} (:mode boundary))
                   (normalize-response ((:invoke! boundary)
                                        {:operation :dispose :session (:session boundary)}))
                   {:status :closed :receipt {:cleanup-verified true} :observations []})
        receipt (:receipt response)]
    (when (or (failure-statuses (:status response))
              (and (map? receipt) (contains? receipt :cleanup-verified)
                   (not (true? (:cleanup-verified receipt)))))
      (throw (ex-info "Outside cleanup was not verified" {:receipt receipt})))
    (wait-for-claims! signals)
    (cond-> {:status :closed :closed true :receipt receipt}
      (and (map? receipt) (contains? receipt :cleanup-verified))
      (assoc :cleanup-verified (:cleanup-verified receipt)))))

(defn- complete-close! [boundary signal result]
  (let [result (try
                 (locking (:ledger boundary)
                   (record! boundary {:record/type :disposed :receipt result}))
                 result
                 (catch Throwable error
                   (assoc result :status :failed :recording-failure (:error (failure-response error)))))
        result (try
                 (release-recording! boundary)
                 result
                 (catch Throwable error
                   (assoc result :status :failed :recording-failure (:error (failure-response error)))))]
    (swap! (:ledger boundary) assoc :closed? true :closing? false :close-result result)
    (deliver signal result)
    (close-result result)))

(defn close! [boundary]
  (let [{:keys [prior signal signals]} (claim-close! boundary)]
    (cond
      prior (close-result prior)
      (nil? signals) (close-result @signal)
      :else
      (complete-close! boundary signal
                       (try
                         (dispose-outside! boundary signals)
                         (catch Throwable error
                           {:status :failed :closed true :cleanup-verified false
                            :failure (:error (failure-response error))}))))))

(defn read-recording
  "Read only saved observations. Intent records never cause external execution."
  [path session]
  (facts/empty-log session)
  (let [entries (recording-entries path session)
        ledger (restored-ledger session entries)]
    (facts/from-facts session (mapcat :observations (vals (:receipts ledger))))))
