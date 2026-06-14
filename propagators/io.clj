(ns propagators.io
  "Evaluation IO carried by network values.

  Queue, inbox, and outbox are pure data in `Net`; this namespace owns their
  shape so the core evaluator stays domain-agnostic."
  (:require [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]))

(defrecord LexicalCellRef [scope cell])
(defrecord LexicalNameRef [scope name])

(defn cell-ref
  [scope cell]
  (->LexicalCellRef scope cell))

(defn name-ref
  [scope name]
  (->LexicalNameRef scope name))

(defn cell-ref?
  [x]
  (instance? LexicalCellRef x))

(defn name-ref?
  [x]
  (instance? LexicalNameRef x))

(defn lexical-ref?
  [x]
  (or (cell-ref? x)
      (name-ref? x)))

(defn ref-scope
  [ref]
  (:scope ref))

(defn resolve-ref-cell
  [n ref]
  (cond
    (cell-ref? ref)
    (:cell ref)

    (name-ref? ref)
    (net/network-dict-entry n (:name ref))

    :else
    nil))

(defn io
  [n]
  (net/net-io n))

(defn with-io
  [n io']
  (net/net-with-io n io'))

(defn update-io
  [n f & args]
  (with-io n (apply f (io n) args)))

(defn clear-queue
  [n]
  (update-io n assoc :queue [] :queued-props #{}))

(defn clear-activation-state
  "Hide evaluator scheduler/lexical state from normal merge and activation code.

  Inbox/outbox stay visible because reality boundary propagators are declared in
  ordinary networks and read/write those records explicitly."
  [n]
  (update-io n #(dissoc (assoc % :queue [] :queued-props #{}) :lexical-envs)))

(defn lexical-envs
  [n]
  (or (:lexical-envs (io n)) {}))

(defn lexical-env
  [n scope]
  (get (lexical-envs n) scope))

(defn with-lexical-envs
  [n envs]
  (update-io n assoc :lexical-envs (or envs {})))

(defn stored-lexical-env
  "Strip evaluator-local IO before a lexical subenv is stored in the env table."
  [n]
  (net/clear-io n))

(defn assoc-lexical-env
  [n scope child-net]
  (update-io n assoc-in [:lexical-envs scope] (stored-lexical-env child-net)))

(defn merge-lexical-envs
  [n envs]
  (update-io n update :lexical-envs merge (or envs {})))

(defn message-delivery [msg] [:message msg])
(defn prop-delivery [prop-id] [:prop prop-id])
(defn io-delivery [op payload] [:io op payload])

(defn message-delivery? [x]
  (and (vector? x) (= :message (first x)) (message/message? (second x))))

(defn prop-delivery? [x]
  (and (vector? x) (= :prop (first x))))

(defn io-delivery? [x]
  (and (vector? x) (= :io (first x))))

(defn delivery?
  [x]
  (or (message/message? x)
      (message-delivery? x)
      (prop-delivery? x)
      (io-delivery? x)))

(defn normalize-delivery
  [x]
  (cond
    (message/message? x) (message-delivery x)
    (delivery? x) x
    :else (throw (ex-info "unknown evaluator delivery" {:delivery x}))))

(defn enqueue-delivery
  [n delivery]
  (let [d (normalize-delivery delivery)
        io-state (io n)]
    (if (and (prop-delivery? d)
             (contains? (:queued-props io-state) (second d)))
      n
      (with-io n
        (cond-> (update io-state :queue conj d)
          (prop-delivery? d)
          (update :queued-props conj (second d)))))))

(defn enqueue-deliveries
  [n deliveries]
  (reduce enqueue-delivery n deliveries))

(defn- task-ids
  [tasks]
  (loop [q (tq/into-queue tasks)
         ids []]
    (if (tq/queue-empty? q)
      ids
      (let [[id q'] (tq/pop-task q)]
        (recur q' (conj ids id))))))

(defn enqueue-props
  [n tasks]
  (enqueue-deliveries n (map prop-delivery (task-ids tasks))))

(defn enqueue-message
  [n msg]
  (enqueue-delivery n (message-delivery msg)))

(defn pop-delivery
  [n]
  (let [queue (:queue (io n))]
    (when (seq queue)
      (let [delivery (first queue)]
        [delivery
         (with-io n
           (cond-> (assoc (io n) :queue (vec (rest queue)))
             (prop-delivery? delivery)
             (update :queued-props disj (second delivery))))]))))

(defn io-record
  [id cell msg]
  {:id id :cell cell :message msg})

(defn io-record?
  [x]
  (and (map? x)
       (contains? x :id)
       (contains? x :cell)
       (contains? x :message)
       (message/message? (:message x))))

(defn escaped-record
  [msg]
  (io-record :escaped (message/message-id msg) msg))

(defn append-outbox
  [n record]
  (update-io n update :outbox conj record))

(defn append-outbox-records
  [n records]
  (update-io n update :outbox into records))

(defn drain-outbox
  [n]
  [(:outbox (io n))
   (update-io n assoc :outbox [])])

(defn add-inbox-record
  [n record]
  (update-io n update :inbox conj record))

(defn add-inbox-records
  [n records]
  (update-io n update :inbox into records))

(defn matching-inbox-records
  [n io-id cell-id]
  (filterv #(and (= io-id (:id %))
                 (= cell-id (:cell %)))
           (:inbox (io n))))

(defn drain-inbox-records
  [n records]
  (let [records* (set records)]
    (update-io n update :inbox
               #(vec (remove records* %)))))

(defn apply-io-delivery
  [n op payload]
  (case op
    :append-outbox
    (append-outbox n payload)

    :append-outbox-records
    (append-outbox-records n payload)

    :add-inbox
    (add-inbox-record n payload)

    :add-inbox-records
    (add-inbox-records n payload)

    :drain-inbox-records
    (drain-inbox-records n payload)

    :assoc-lexical-env
    (let [[scope child-net] payload]
      (assoc-lexical-env n scope child-net))

    :merge-lexical-envs
    (merge-lexical-envs n payload)

    :replace-lexical-envs
    (with-lexical-envs n payload)

    (throw (ex-info "unknown IO delivery operation"
                    {:op op :payload payload}))))
