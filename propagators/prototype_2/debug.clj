(ns propagators.prototype-2.debug
  "Debug runner — log every scheduler / build / evaluate step to the terminal.

  Drains Network.queue (same as `run-tasks`); does not invent wake lists."
  (:require [clojure.string :as string]
            [propagators.prototype-2.edge-chain :as ec]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(defn- dbg-fmt-ids [xs]
  (str "{" (->> xs (map pr-str) sort (string/join " ")) "}"))

(defn- dbg-fmt-chain
  [chain]
  (if (or (nil? chain) (ec/edge-chain-empty? chain))
    "(empty — not yet built)"
    (str (dbg-fmt-ids (ec/edge-chain-origins chain))
         "  ══▶  "
         (dbg-fmt-ids (ec/edge-chain-tail chain))
         "   [origins ══▶ tail]")))

(defn- dbg-fmt-cell
  [network id]
  (let [v (net/cell-value network id)]
    (str (pr-str id) "=" (if (t/nothing? v) "nothing" (pr-str v)))))

(defn- dbg-fmt-cells
  [network ids]
  (->> ids (map #(dbg-fmt-cell network %)) (string/join ", ")))

(defn- dbg-fmt-arrow
  [input-ids output]
  (str (->> input-ids (map pr-str) (string/join " , "))
       "  ──▶  "
       (pr-str output)))

(defn- dbg-log
  [& xs]
  (apply println xs))

(defn- dbg-rule
  ([]
   (dbg-log "──────────────────────────────────────────────────────────"))
  ([label]
   (dbg-log)
   (dbg-log "══" label "══")))

(defn- dbg-indent
  [s]
  (dbg-log (str "    " s)))

(defn- dbg-stream
  [stream]
  (if (empty? stream)
    "(empty)"
    (str "[" (->> stream (map pr-str) (string/join " → ")) "]")))

(defn describe-propagator
  "Human-readable pending/built description of propagator `prop-id`."
  [network prop-id]
  (let [v (net/lookup-value network prop-id)
        n (net/lookup-node network prop-id)
        built? (and n (t/topology-built? n))
        chain (when built? (t/node-inputs n))]
    (cond
      (t/propagator-value? v)
      (let [out (t/propagator-output v)
            kind (if (t/compound-f? v) "compound" "prim")]
        (str (pr-str prop-id) (if built? (str " [BUILT " kind "]") (str " [PENDING " kind "]"))
             "\n      name:   " (pr-str (:name v))
             "\n      ports:  " (dbg-fmt-arrow (:input-ids v) out)
             (when (> (count (:outputs v)) 1)
               (str " (+outs " (dbg-fmt-ids (:outputs v)) ")"))
             "\n      cells:  " (dbg-fmt-cells network (into (:input-ids v) (:outputs v)))
             "\n      chain:  " (dbg-fmt-chain chain)))

      :else
      (str (pr-str prop-id) " (not a propagator value)"))))

(defn evaluate-propagator-debug
  "Like `evaluate-propagator`, but prints build / expand / activate detail.
  Returns updated network (queue may grow via install/write)."
  [network prop-id step]
  (let [v0 (net/lookup-value network prop-id)
        n0 (net/lookup-node network prop-id)
        built-before? (and n0 (t/topology-built? n0))
        q-before (count (net/network-queue network))]
    (dbg-rule (str "step " step " · evaluate " (pr-str prop-id)))
    (cond
      (t/propagator-value? v0)
      (let [ins (:input-ids v0)
            outs (:outputs v0)
            out (t/propagator-output v0)
            skip? (net/boundary-satisfied? network ins outs)
            compound? (t/compound-f? v0)]
        (dbg-indent (str "kind:            " (if compound? "compound" "primitive")))
        (dbg-indent (str "intended ports:  " (dbg-fmt-arrow ins out)))
        (dbg-indent (str "input cells:     " (dbg-fmt-cells network ins)))
        (dbg-indent (str "output cells:    " (dbg-fmt-cells network outs)))
        (dbg-indent (str "boundary done?:  " skip?
                         " (inputs∧outputs satisfied → smart skip)"))
        (if skip?
          (do (dbg-indent "SMART SKIP: no build, no activate")
              (dbg-rule)
              network)
          (let [ready? (net/inputs-ready? network ins)
                _ (dbg-indent (str "inputs ready?:   " ready?))
                before-keys (set (keys (net/network-value network)))
                network (ev/evaluate-propagator network prop-id)
                n1 (net/lookup-node network prop-id)
                built-after? (t/topology-built? n1)
                just-built? (and (not built-before?) built-after?)]
            (when just-built?
              (dbg-indent (if compound? "EXPAND / BUILD topology:" "BUILD topology:"))
              (dbg-indent (str "  " (dbg-fmt-arrow ins out)))
              (dbg-indent (str "  chain: " (dbg-fmt-chain (t/node-inputs n1))))
              (when compound?
                (let [new-ids (into [] (remove before-keys) (keys (net/network-value network)))]
                  (dbg-indent (str "  new values: "
                                   (if (seq new-ids)
                                     (->> new-ids (map pr-str) (string/join ", "))
                                     "(none)"))))))
            (when (and (not built-before?) (not built-after?))
              (dbg-indent "build deferred / declined: waiting or expand skipped"))
            (when (and built-after? (t/propagator-f? v0))
              (dbg-indent (str "activated → " (dbg-fmt-cell network out))))
            (dbg-indent (str "queue size: " (count (net/network-queue network))
                             " (Δ " (- (count (net/network-queue network)) q-before) ")"))
            (dbg-rule)
            network)))

      :else
      (do (dbg-indent "skip: not a propagator value")
          (dbg-rule)
          network))))

(defn run-tasks-debug
  "FIFO drain of Network.queue (optionally seed with `tasks`), logging steps."
  ([network]
   (run-tasks-debug network nil))
  ([network tasks]
   (let [network (if (seq tasks) (net/enqueue-all network tasks) network)]
     (dbg-rule "run-tasks-debug · start")
     (dbg-indent (str "initial queue: " (dbg-stream (net/network-queue network))))
     (doseq [pid (filter #(t/propagator-value? (net/lookup-value network %))
                         (keys (net/network-value network)))]
       (let [lines (string/split-lines (describe-propagator network pid))]
         (dbg-indent (first lines))
         (doseq [line (rest lines)]
           (dbg-log line))))
     (loop [network network
            step 1]
       (let [q (net/network-queue network)]
         (if (empty? q)
           (do (dbg-rule "run-tasks-debug · quiescence")
               (dbg-indent "task queue empty — network settled")
               network)
           (let [prop-id (peek q)
                 network (net/network-with-queue network (pop q))
                 _ (dbg-indent (str "dequeue " (pr-str prop-id)
                                    " · remaining "
                                    (dbg-stream (net/network-queue network))))
                 network (evaluate-propagator-debug network prop-id step)]
             (dbg-indent (str "queue after step " step ": "
                              (dbg-stream (net/network-queue network))))
             (recur network (inc step)))))))))

(defn tell-debug
  "Like `tell`, with terminal logging of the seed and enqueued tasks."
  [network id v]
  (dbg-rule (str "tell " (pr-str id) " ← " (pr-str v)))
  (let [[tasks network] (session/tell network id v)
        dep (session/dependence-of network id)]
    (dbg-indent (str "cell now: " (dbg-fmt-cell network id)))
    (dbg-indent (str "origin stamp: " (dbg-fmt-chain dep)))
    (dbg-indent (str "alerted: "
                     (if (seq tasks)
                       (->> tasks (map pr-str) (string/join ", "))
                       "(none)")))
    (dbg-indent (str "queue size: " (count (net/network-queue network))))
    (dbg-rule)
    [tasks network]))
