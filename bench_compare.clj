;; Benchmarks: leapfrog vs differential-leapfrog (manual compare with leapfrog-rs via cargo bench).
;; Recorded results and crossover notes: docs/bench-incremental-path-expansion.md
(ns bench-compare
  (:require [differential-leapfrog.core :as d]
            [leapfrog :refer [R S T lftj path-rules semi-naive semi-naive-step]]))

;; --- recorded sweep (2026-05-19, single JVM); see docs/bench-incremental-path-expansion.md ---

(def ^:private dleap-drain-timeout-ms 200)

(def recorded-delta-sweep
  "One-edge txn on pre-built chain (EDB build excluded). 2026-05-19, iters=3."
  [{:base-n 5   :path-tuples 15     :full-ms 7.25    :incr-ms 4.73}
   {:base-n 10  :path-tuples 55     :full-ms 27.11   :incr-ms 18.14}
   {:base-n 20  :path-tuples 210    :full-ms 33.36   :incr-ms 3.90}
   {:base-n 100 :path-tuples 5050   :full-ms 605.31  :incr-ms 89.16}
   {:base-n 300 :path-tuples 45150  :full-ms 15879.08 :incr-ms 1808.91}
   {:base-n 500 :path-tuples 125250 :full-ms 62640.16 :incr-ms 6590.95}])

(def recorded-growth-sweep
  "Frozen ms timings for chain path expansion (one insert per edge)."
  [{:n 20  :full-ms 14.0   :incr-step-ms 17.3   :dleap-ms 4112.6  :dleap-one-ms 203.7}
   {:n 50  :full-ms 18.7   :incr-step-ms 41.3   :dleap-ms 11047.7 :dleap-one-ms 207.1}
   {:n 100 :full-ms 126.8  :incr-step-ms 826.8  :dleap-ms 24142.2 :dleap-one-ms 257.8}
   {:n 200 :full-ms 305.5  :incr-step-ms 2877.0 :dleap-ms 54917.2 :dleap-one-ms 423.8}
   {:n 300 :full-ms 1364.8 :incr-step-ms 12772.4 :dleap-ms 91077.6 :dleap-one-ms 238.9}
   {:n 400 :full-ms 2497.5 :incr-step-ms 23074.6 :dleap-ms 113325.3 :dleap-one-ms 265.6}])

(defn chain-fact-counts
  "Fact counts after n edges on a directed chain (nodes 1..n+1).
  :path-tuples counts pairs i<j reachable along forward edges (no reflexive tuples)."
  [n]
  (let [nodes (inc n)]
    {:edges n
     :nodes nodes
     :path-tuples (/ (* n nodes) 2)}))

(defmacro bench [label expr times]
  `(let [t# (System/nanoTime)]
     (dotimes [_# ~times] ~expr)
     (let [ms# (/ (- (System/nanoTime) t#) 1e6)]
       (println (str ~label ": "
                     (format "%.3f" ms#) " ms total, "
                     (format "%.6f" (/ ms# ~times)) " ms / iter for "
                     ~times " iters")))))

;; --- leapfrog-only (Rust comparison baselines) ---

(def edge120
  {:edge (into #{} (map (fn [i] [i (inc i)]) (range 1 121)))})

;; --- shared path rules: d/path-rules and leapfrog/path-rules ---

(defn- d-empty-system []
  {:edb {} :idb {} :time 0 :history [] :rules d/path-rules :idb-preds #{:path}})

(defn- chain-edge [i]
  [i (inc i)])

(defn- edge-delta [i]
  {:edge {(chain-edge i) 1}})

(defn merge-facts [facts delta]
  (reduce-kv (fn [m pred tuples]
               (update m pred (fnil into #{}) tuples))
             facts
             delta))

(defn chain-edges-set [n]
  (into #{} (map chain-edge (range 1 (inc n)))))

(defn build-chain-facts [n]
  (semi-naive {:edge (chain-edges-set n)} path-rules))

(defn- chain-edges-weighted [n]
  (into {} (map (fn [i] [(chain-edge i) 1]) (range 1 (inc n)))))

(defn build-dleap-chain [n]
  (d/transact (d-empty-system) {:edge (chain-edges-weighted n)}))

(defn one-edge-delta-full [base-n]
  "One txn: full `semi-naive` after adding edge (n+1)->(n+2) to an n-edge chain."
  (semi-naive {:edge (conj (chain-edges-set base-n) (chain-edge (inc base-n)))}
              path-rules))

(defn one-edge-delta-incr [base-facts base-n]
  "One txn: `semi-naive-step` fixpoint from a single new edge on existing facts."
  (let [e (chain-edge (inc base-n))
        facts (update base-facts :edge conj e)
        delta {:edge #{e}}]
    (loop [facts facts delta delta]
      (let [new-delta (semi-naive-step facts delta path-rules)]
        (if (every? empty? (vals new-delta))
          facts
          (recur (merge-facts facts new-delta) new-delta))))))

(defn one-edge-delta-dleap [system base-n]
  (d/transact system (edge-delta (inc base-n))))

(defn expand-path-dleap
  "Incremental DB: one `transact` per new edge on a growing line graph."
  [n]
  (reduce (fn [sys i] (d/transact sys (edge-delta i)))
          (d-empty-system)
          (range 1 (inc n))))

(defn expand-path-leapfrog-full
  "Baseline: after each new edge, full `semi-naive` on entire EDB."
  [n]
  (loop [edges #{} i 1]
    (if (> i n)
      (semi-naive {:edge edges} path-rules)
      (recur (conj edges (chain-edge i)) (inc i)))))

(defn expand-path-leapfrog-incremental
  "Leapfrog incremental: one new edge per step, fixpoint via `semi-naive-step`."
  [n]
  (loop [facts {} i 1]
    (if (> i n)
      facts
      (let [e (chain-edge i)
            facts (update facts :edge (fnil conj #{}) e)
            delta {:edge #{e}}]
        (recur (loop [facts facts delta delta]
                 (let [new-delta (semi-naive-step facts delta path-rules)]
                   (if (every? empty? (vals new-delta))
                     facts
                     (recur (merge-facts facts new-delta) new-delta))))
              (inc i))))))

(defn- path-set-leapfrog [facts]
  (:path facts #{}))

(defn- path-set-dleap [system]
  (d/support (d/view system) :path))

(defn verify-incremental-paths-agree
  "Sanity check: all three strategies yield the same :path support."
  [n]
  (let [lf (expand-path-leapfrog-full n)
        dl (expand-path-dleap n)
        li (expand-path-leapfrog-incremental n)
        expected-paths (:path-tuples (chain-fact-counts n))]
    (when (not= (path-set-leapfrog lf) (path-set-dleap dl))
      (throw (ex-info "path mismatch: leapfrog full vs dleap"
                      {:n n
                       :leapfrog (count (path-set-leapfrog lf))
                       :dleap (count (path-set-dleap dl))
                       :expected-paths expected-paths})))
    (when (not= (path-set-leapfrog lf) (path-set-leapfrog li))
      (throw (ex-info "path mismatch: leapfrog full vs incremental step"
                      {:n n
                       :full (count (path-set-leapfrog lf))
                       :incr (count (path-set-leapfrog li))
                       :expected-paths expected-paths})))
    (when (not= expected-paths (count (path-set-leapfrog lf)))
      (throw (ex-info "unexpected path tuple count"
                      {:n n :expected expected-paths :actual (count (path-set-leapfrog lf))})))
    {:n n :path-tuples expected-paths}))

(defn- row-ms [row k] (double (k row)))

(defn verify-recorded-delta-crossover!
  "incr-step should beat full on recorded one-delta-on-large-EDB sweep."
  []
  (doseq [{:keys [base-n full-ms incr-ms path-tuples]} recorded-delta-sweep]
    (assert (< incr-ms full-ms)
            (str "recorded delta sweep: incr should beat full at base-n=" base-n
                 " path-tuples=" path-tuples)))
  :ok)

(defn verify-recorded-crossover-conclusions!
  "Assert documented ordering using frozen timings (no live benchmark)."
  []
  (doseq [{:keys [n full-ms incr-step-ms dleap-ms dleap-one-ms] :as row}
          recorded-growth-sweep
          :let [counts (merge (chain-fact-counts n) row)
                est-dleap (* (double dleap-drain-timeout-ms) n)]]
    (assert (< full-ms incr-step-ms)
            (str "recorded: full should beat incr-step at n=" n
                 " path-tuples=" (:path-tuples counts)))
    (assert (< full-ms dleap-ms)
            (str "recorded: full should beat dleap at n=" n))
    (assert (> (/ dleap-ms est-dleap) 0.85)
            (str "dleap-ms should track ~n*" dleap-drain-timeout-ms " at n=" n))
    (when (>= n 20)
      (assert (< dleap-one-ms 500)
              "single transact dominated by drain timeout, not graph size")))
  :ok)

(defn run-verification!
  "Correctness checks + recorded-table conclusions. No timing benchmark."
  []
  (println "=== path expansion correctness ===")
  (doseq [n [5 10 20 50]]
    (let [{:keys [n path-tuples]} (verify-incremental-paths-agree n)]
      (println "OK n=" n "path-tuples=" path-tuples)))
  (println "=== chain fact counts (formula) ===")
  (assert (= {:edges 100 :nodes 101 :path-tuples 5050} (chain-fact-counts 100)))
  (println "OK chain-fact-counts example n=100 ->" (chain-fact-counts 100))
  (println "=== one-delta on large EDB (recorded) ===")
  (verify-recorded-delta-crossover!)
  (println "=== growing-chain sweep (recorded) ===")
  (verify-recorded-crossover-conclusions!)
  (println "OK recorded tables match docs/")
  :ok)

(defn- ms [f]
  (let [t (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) t) 1e6)))

(defn verify-one-delta-agree [base-n]
  (let [full (one-edge-delta-full base-n)
        incr (one-edge-delta-incr (build-chain-facts base-n) base-n)
        expected (:path-tuples (chain-fact-counts (inc base-n)))]
    (when (not= (path-set-leapfrog full) (path-set-leapfrog incr))
      (throw (ex-info "one-delta path mismatch: full vs incr" {:base-n base-n})))
    (when (not= expected (count (path-set-leapfrog full)))
      (throw (ex-info "one-delta unexpected path count" {:base-n base-n :expected expected})))
    {:base-n base-n :path-tuples expected}))

(defn time-one-delta-on-base [base-n & {:keys [iters include-dleap?] :or {iters 3 include-dleap? false}}]
  (let [base-facts (build-chain-facts base-n)]
    (dotimes [_ 2] (one-edge-delta-full base-n) (one-edge-delta-incr base-facts base-n))
    (merge
     {:base-n base-n
      :counts (chain-fact-counts base-n)
      :path-tuples-after (:path-tuples (chain-fact-counts (inc base-n)))
      :full-ms (ms #(dotimes [_ iters] (one-edge-delta-full base-n)))
      :incr-ms (ms #(dotimes [_ iters] (one-edge-delta-incr base-facts base-n)))}
     (when include-dleap?
       {:dleap-ms
        (let [d-sys (build-dleap-chain base-n)]
          (ms #(dotimes [_ iters] (one-edge-delta-dleap d-sys base-n))))}))))

(defn find-incr-crossover [rows]
  (some (fn [row]
          (when (< (:incr-ms row) (:full-ms row))
            (assoc row :winner :incr)))
        rows))

(defn find-dleap-crossover [rows]
  (some (fn [row]
          (when (< (:dleap-ms row) (:full-ms row))
            (assoc row :winner :dleap)))
        rows))

(defn print-delta-sweep!
  "Large static chain (base-n edges), then one new edge. EDB build is outside timing."
  [& {:keys [base-ns iters include-dleap?]
      :or {base-ns [10 20 50 100 200 300 400 500]
           iters 3
           include-dleap? false}}]
  (println "Scenario: chain with base-n edges; one txn adds edge (base-n+1)->(base-n+2)")
  (println "Timed: full semi-naive vs semi-naive-step (EDB build excluded)")
  (when include-dleap? (println "Including dleap transact (~200ms drain overhead per call)"))
  (println "base-n\tedges\tpath-tuples\tfull-ms\tincr-ms\twinner")
  (let [rows (mapv (fn [base-n]
                     (print "  base-n=" base-n " ... " :flush true)
                     (let [row (time-one-delta-on-base base-n :iters iters :include-dleap? include-dleap?)]
                       (println "done")
                       row))
                   base-ns)]
    (doseq [{:keys [base-n full-ms incr-ms dleap-ms counts path-tuples-after]} rows]
      (let [winner (cond
                     (< incr-ms full-ms) "incr"
                     (and dleap-ms (< dleap-ms full-ms)) "dleap"
                     :else "full")]
        (if dleap-ms
          (println (format "%d\t%d\t%d->%d\t%.2f\t%.2f\t%.2f\t%s"
                           base-n (:edges counts) (:path-tuples counts) path-tuples-after
                           full-ms incr-ms dleap-ms winner))
          (println (format "%d\t%d\t%d->%d\t%.2f\t%.2f\t%s"
                           base-n (:edges counts) (:path-tuples counts) path-tuples-after
                           full-ms incr-ms winner)))))
    (when-let [x (find-incr-crossover rows)]
      (println "\nincr-step crosses full at base-n=" (:base-n x)
               "path-tuples=" (:path-tuples (:counts x))
               "full-ms=" (:full-ms x) "incr-ms=" (:incr-ms x)))
    (when include-dleap?
      (when-let [x (find-dleap-crossover rows)]
        (println "dleap crosses full at base-n=" (:base-n x)
                 "full-ms=" (:full-ms x) "dleap-ms=" (:dleap-ms x))))
    (when (nil? (find-incr-crossover rows))
      (println "\nNo incr-step crossover in sweep range; full wins at all base-n tested."))
    rows))

(defn print-growth-sweep!
  "Time each strategy at sweep-ns and print TSV (optional refresh of recorded table)."
  [& {:keys [sweep-ns] :or {sweep-ns [20 50 100 200 300 400]}}]
  (println "n\tpath-tuples\tfull-ms\tincr-step-ms\tdleap-ms\tdleap-1-ms\test-drain*n")
  (doseq [n sweep-ns]
    (let [{:keys [path-tuples]} (chain-fact-counts n)
          full (ms #(expand-path-leapfrog-full n))
          incr (ms #(expand-path-leapfrog-incremental n))
          d-n (ms #(expand-path-dleap n))
          d-1 (ms #(d/transact (d-empty-system) (edge-delta 1)))
          est (* (double dleap-drain-timeout-ms) n)]
      (println (format "%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.0f"
                       n path-tuples full incr d-n d-1 est)))))

(defn -main [& args]
  (case (first args)
    "verify" (run-verification!)
    "sweep" (print-growth-sweep!)
    "delta-sweep" (print-delta-sweep!)
    (let [expand-n (or (some-> args first Integer/parseInt) 50)
          iters (or (some-> args second Integer/parseInt) 2)]
    (println "=== leapfrog (Rust comparison) ===")
    (bench "lftj demo [R S T] [:x :y :z]" (lftj [R S T] [:x :y :z]) 10000)
    (bench "semi-naive path line 120 edges (one shot)"
           (semi-naive edge120 path-rules)
           50)
    (println "\n=== incremental path expansion (same path-rules) ===")
    (println "Rules: base edge(x,y), recursive path(x,z) <- path(x,y), edge(y,z)")
    (println "Scenario: insert chain edges 1-2, 2-3, ... n-(n+1) one transaction at a time")
    (verify-incremental-paths-agree (min 10 expand-n))
    (bench (str "dleap transact/step (n=" expand-n " edges)")
           (expand-path-dleap expand-n)
           iters)
    (bench (str "leapfrog full semi-naive recompute per edge (n=" expand-n ")")
           (expand-path-leapfrog-full expand-n)
           iters)
    (bench (str "leapfrog semi-naive-step per edge (n=" expand-n ")")
           (expand-path-leapfrog-incremental expand-n)
           iters))))
