(ns examples.lain.subagents.boundary
  "Outside interpreter ownership, receipts and explicit EDN recording. No FSM rules."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [examples.lain.subagents.model :as model]))

(defrecord Boundary [identity mode invoke recording ledger])
(defn open!
  [identity {:keys [mode invoke recording] :or {mode :observe}}]
  (model/empty-events identity)
  (when-not (#{:observe :synthetic :live :replay} mode)
    (throw (ex-info "Unsupported effect mode" {:mode mode})))
  (when (and (#{:synthetic :live} mode) (not (fn? invoke)))
    (throw (ex-info "Execution needs an outside interpreter" {:mode mode})))
  (->Boundary identity mode invoke recording (atom {:closed? false :receipts {}})))
(defn receipts [boundary] (:receipts @(:ledger boundary)))
(defn- record! [boundary entry]
  (when-let [path (:recording boundary)]
    (spit path (str (pr-str (assoc entry :session (:identity boundary))) "\n") :append true)))
(defn normalize-response
  "Translate a bounded interpreter observation to one stable event fact."
  [request raw]
  (let [kind (:status raw)
        fields (case kind
                 (:launched :reconciled) (select-keys raw [:child])
                 :completed (select-keys raw [:child :result])
                 :stopped (select-keys raw [:child :cleanup-verified])
                 :uncertain (select-keys raw [:failure])
                 :failed (select-keys raw [:failure])
                 (throw (ex-info "Unsupported interpreter response" {:response raw})))]
    (model/event (:session request) (pr-str [(:id request) kind]) (:order request) kind fields)))
(defn dispatch!
  "Claim before invocation. Repeated or concurrent evaluation returns the same receipt.
  An uncertain launch is retained and must be reconciled through a separate request."
  [boundary request]
  (when-not (= (:identity boundary) (:session request))
    (throw (ex-info "Foreign session effect" {:session (:session request)})))
  (locking (:ledger boundary)
    (when (:closed? @(:ledger boundary)) (throw (ex-info "Session boundary is disposed" {})))
    (if (or (= :none (:operation request)) (#{:observe :replay} (:mode boundary)))
      []
      (if-let [prior (get (receipts boundary) (:id request))]
        (do
          (when-not (= request (:request prior))
            (throw (ex-info "Conflicting reused effect identity" {:id (:id request)})))
          (:observations prior))
        (do
          (when-not (= (:id request) (model/effect-id (:identity boundary) (:operation request)))
            (throw (ex-info "Invalid effect identity" {:id (:id request)})))
          (model/plugin-request request)
          (record! boundary {:kind :intent :request request})
          (swap! (:ledger boundary) assoc-in [:receipts (:id request)] {:status :pending :observations []})
          (let [raw (try ((:invoke boundary) request)
                         (catch Exception error
                           {:status (if (= :launch (:operation request)) :uncertain :failed)
                            :failure {:message (.getMessage error)}}))
                observations [(normalize-response request raw)]
                receipt {:status (:status raw) :request request :observations observations}]
            (swap! (:ledger boundary) assoc-in [:receipts (:id request)] receipt)
            (record! boundary (assoc receipt :kind :receipt))
            observations))))))
(defn close!
  "The interpreter owns all children, including launches whose acknowledgement was lost."
  [boundary]
  (locking (:ledger boundary)
    (if (:closed? @(:ledger boundary))
      {:cleanup-verified true :already-closed true}
      (let [receipt (if (#{:synthetic :live} (:mode boundary))
                      ((:invoke boundary) {:session (:identity boundary) :operation :dispose})
                      {:cleanup-verified true})]
        (when-not (true? (:cleanup-verified receipt))
          (throw (ex-info "Session cleanup was not verified" {:receipt receipt})))
        (record! boundary {:kind :disposed :receipt receipt})
        (swap! (:ledger boundary) assoc :closed? true)
        receipt))))
(defn read-recording
  "Explicit file I/O. Replay consumes observations; intents are never dispatched."
  [path identity]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (loop [observations []]
      (let [entry (edn/read {:eof ::eof} reader)]
        (if (= ::eof entry)
          observations
          (do
            (when-not (= identity (:session entry)) (throw (ex-info "Foreign recording" {})))
            (recur (into observations (:observations entry)))))))))
