(ns propagators.prototype-2.amb
  "Dependence-directed amb for prototype_2 (TTMS supports + optional subworlds).

  Mirrors experimental amb algebra at a smaller scale:
    choice → require certificate → learn nogood → backtrack repair → apply

  Nogoods come from failure-certificate supports (`learn` /
  `certificate->nogood`), not from `tms-reduce` over attempt spines.
  Repair is dependence-directed: only decisions whose literals appear in an
  applicable nogood are withdrawn; the next admissible alternative is chosen.

  Subworlds: `solve-worlds` forks lazy children (replace-definition) per domain
  value, probes via observe, and backtracks without copying parent env.

  See `propagators/doc/prototype-2-ddb-rx.md`."
  (:require [clojure.set :as set]
            [propagators.datastructures.support :as support]
            [propagators.ids :as ids]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.types :as t]))

;; ---------------------------------------------------------------------------
;; Choice / knowledge data
;; ---------------------------------------------------------------------------

(defn premise
  "TTMS premise for an active/retracted choice."
  [record]
  {:source (:source record)
   :timestamp (:timestamp record)
   :premises-status (:status record)})

(defn stamp
  "Singleton support for `record`'s current premise."
  [record]
  #{(premise record)})

(defn initial-choice
  "Fresh choice over `domain` (vector of alternatives), starting at index 0."
  ([domain] (initial-choice (ids/new-node-id) domain))
  ([source domain]
   (let [domain (vec domain)]
     (when (empty? domain)
       (throw (ex-info "amb domain empty" {})))
     {:source source
      :domain domain
      :index 0
      :timestamp 1
      :status :active
      :handled-request nil})))

(defn choice-at
  "Like `initial-choice`, but start at `index` (for L-2 DDB near-solution tests)."
  [domain index]
  (let [domain (vec domain)]
    (when-not (and (nat-int? index) (< index (count domain)))
      (throw (ex-info "choice-at: index out of domain" {:index index :domain domain})))
    (assoc (initial-choice domain) :index index)))

(defn chosen
  "Current domain value (nil if retracted)."
  [record]
  (when (= :active (:status record))
    (get (:domain record) (:index record))))

(defn assignment
  [records]
  (into #{} (map (juxt :source :index)) records))

(defn- applicable?
  [nogood context]
  (set/subset? (:context nogood) context))

(defn blocked?
  [knowledge assign context]
  (boolean (some #(and (applicable? % context)
                       (set/subset? (:literals %) assign))
                 knowledge)))

(defn- certificate->nogood
  "Split failure support into choice literals + external context."
  [certificate records]
  (let [by-source (into {} (map (juxt :source identity)) records)
        premises (:support certificate)]
    (when (and (= :failed (:status certificate))
               (support/support? premises))
      (let [choice-ps (filter #(contains? by-source (:source %)) premises)
            context (set (remove #(contains? by-source (:source %)) premises))]
        (when (every? (fn [p]
                        (let [r (by-source (:source p))]
                          (= p (premise r))))
                      choice-ps)
          {:literals (into #{} (map (fn [p]
                                      [(:source p) (:index (by-source (:source p)))])
                                    choice-ps))
           :context context})))))

(defn learn
  "Add nogood from failure certificate; returns updated knowledge set."
  [knowledge certificate records]
  (if-let [ng (certificate->nogood certificate (vec records))]
    (conj (set knowledge) ng)
    (set knowledge)))

(defn- conflicts
  [knowledge assign context]
  (filter #(and (applicable? % context)
                (set/subset? (:literals %) assign))
          knowledge))

(defn- admissible-index
  [record records knowledge context]
  (let [source (:source record)
        others (disj (assignment records) [source (:index record)])
        relevant (filter #(some (fn [[id _]] (= source id)) (:literals %))
                         knowledge)]
    (first (filter (fn [i]
                     (and (not= i (:index record))
                          (not (blocked? relevant (conj others [source i]) context))))
                   (range (count (:domain record)))))))

(defn decide
  "Dependence-directed repair request from knowledge + active assignment."
  [records knowledge]
  (let [records (vec records)
        context (into #{} (mapcat :context) knowledge)
        active-conflicts (conflicts knowledge (assignment records) context)
        participating (into #{} (map first) (mapcat :literals active-conflicts))]
    (cond
      (some #(empty? (:literals %)) active-conflicts)
      {:action :unsatisfiable}

      (empty? active-conflicts)
      {:action :idle}

      :else
      (or (first
           (keep (fn [record]
                   (when (contains? participating (:source record))
                     (when-let [idx (admissible-index record records knowledge context)]
                       {:action :withdraw
                        :target (:source record)
                        :expected-timestamp (:timestamp record)
                        :next-index idx
                        :reason (set active-conflicts)})))
                 (reverse records)))
          {:action :unsatisfiable}))))

(defn apply-repair
  "Apply withdraw/choose to a choice record. Two-phase: withdraw then choose."
  [record request]
  (cond
    (or (nil? request) (= :idle (:action request)))
    record

    (= :unsatisfiable (:action request))
    record

    (and (= :withdraw (:action request))
         (= (:source record) (:target request))
         (= (:timestamp record) (:expected-timestamp request))
         (= :active (:status record)))
    (assoc record
           :status :retracted
           :timestamp (inc (:timestamp record))
           :handled-request request)

    (and (= :choose (:action request))
         (= (:source record) (:target request))
         (= :retracted (:status record)))
    (assoc record
           :status :active
           :index (:next-index request)
           :timestamp (inc (:timestamp record))
           :handled-request request)

    ;; Convenience: one-shot advance = withdraw + choose next index
    (and (= :advance (:action request))
         (= (:source record) (:target request))
         (= :active (:status record)))
    (assoc record
           :status :active
           :index (:next-index request)
           :timestamp (inc (:timestamp record))
           :handled-request request)

    :else record))

(defn advance-request
  "Turn a withdraw decision into a single :advance (prototype convenience)."
  [request]
  (if (= :withdraw (:action request))
    (assoc request :action :advance)
    request))

;; ---------------------------------------------------------------------------
;; Host solve loops
;; ---------------------------------------------------------------------------

(defn require-bool
  "Boolean probe → certificate. False → :failed with `support`."
  [ok? support]
  (cond
    (true? ok?) {:status :satisfied :support (or support #{})}
    (false? ok?) {:status :failed :support (or support #{})}
    :else {:status :pending}))

(defn- reset-choice
  "Fresh active tip at index 0 (new epoch) — used when an earlier amb advances."
  [record]
  (assoc record
         :index 0
         :status :active
         :timestamp (inc (:timestamp record))
         :handled-request nil))

(defn- apply-repair-among
  "Apply repair to the targeted choice; reset later choices in vector order."
  [choices request]
  (let [choices (vec choices)
        target (:target request)
        idx (first (keep-indexed (fn [i r] (when (= (:source r) target) i)) choices))]
    (if (nil? idx)
      choices
      (vec (map-indexed
            (fn [i r]
              (cond
                (= i idx) (apply-repair r request)
                (> i idx) (reset-choice r)
                :else r))
            choices)))))

(defn solve
  "Run `probe` under successive amb alternatives until satisfied or exhausted.

  First arg is a domain vector or an existing choice record.
  `probe` — `(fn [chosen support] {:status :failed|:satisfied|:pending :support? :value?})`
  Returns `{:status :satisfied|:unsatisfiable :choice :value :knowledge}`."
  [domain-or-choice probe]
  (loop [choice (if (and (map? domain-or-choice) (contains? domain-or-choice :domain))
                  domain-or-choice
                  (initial-choice domain-or-choice))
         knowledge #{}
         guard 0]
    (when (> guard 64)
      (throw (ex-info "amb solve exceeded iteration budget" {:choice choice})))
    (if-not (= :active (:status choice))
      {:status :unsatisfiable :choice choice :knowledge knowledge}
      (let [ch (chosen choice)
            sup (stamp choice)
            result (probe ch sup)]
        (case (:status result)
          :satisfied
          {:status :satisfied
           :choice choice
           :value (:value result)
           :support (or (:support result) sup)
           :knowledge knowledge}

          :pending
          {:status :pending :choice choice :knowledge knowledge}

          :failed
          (let [cert (assoc result :support (or (:support result) sup))
                knowledge (learn knowledge cert [choice])
                req (advance-request (decide [choice] knowledge))]
            (case (:action req)
              (:unsatisfiable nil)
              {:status :unsatisfiable :choice choice :knowledge knowledge :request req}

              :idle
              {:status :unsatisfiable :choice choice :knowledge knowledge}

              (recur (apply-repair choice req) knowledge (inc guard))))

          (throw (ex-info "probe returned unknown status" {:result result})))))))

(defn solve-choices
  "Multi-decision dependence-directed search.

  `choices` — seq of choice records (or domain vectors → fresh choices).
  `probe` — `(fn [chosens support] certificate)` where `chosens` is a vector of
  current domain values and `support` combines all active choice stamps.
  On repair of choice i, choices i+1… are reset (chronological nest)."
  [choices probe]
  (let [choices (mapv (fn [c]
                        (if (and (map? c) (contains? c :domain))
                          c
                          (initial-choice (vec c))))
                      choices)
        budget (* 4 (apply * (map #(count (:domain %)) choices)))]
    (loop [choices choices
           knowledge #{}
           steps 0]
      (when (> steps budget)
        (throw (ex-info "amb solve-choices exceeded budget"
                        {:steps steps :budget budget})))
      (if-not (every? #(= :active (:status %)) choices)
        {:status :unsatisfiable :choices choices :knowledge knowledge :steps steps}
        (let [chosens (mapv chosen choices)
              sup (apply support/combine (map stamp choices))
              result (probe chosens sup)]
          (case (:status result)
            :satisfied
            {:status :satisfied
             :choices choices
             :value (or (:value result) chosens)
             :support (or (:support result) sup)
             :knowledge knowledge
             :steps steps}

            :pending
            {:status :pending :choices choices :knowledge knowledge :steps steps}

            :failed
            (let [cert (assoc result :support (or (:support result) sup))
                  knowledge (learn knowledge cert choices)
                  req (advance-request (decide choices knowledge))]
              (case (:action req)
                (:unsatisfiable nil :idle)
                {:status :unsatisfiable
                 :choices choices
                 :knowledge knowledge
                 :request req
                 :steps steps}

                (recur (apply-repair-among choices req)
                       knowledge
                       (inc steps))))

            (throw (ex-info "probe returned unknown status" {:result result}))))))))

(defn pythagorean?
  [a b c]
  (and (number? a) (number? b) (number? c)
       (<= a b) (<= b c)
       (= (+ (* a a) (* b b)) (* c c))))

(defn pythagorean
  "First Pythagorean triple in 1..n via *host* multi-amb loop (Clojure recur).

  Prefer `pythagorean-l2` for a search defined in L-2 and run by the p2 compiler."
  [n]
  (let [domain (vec (range 1 (inc n)))]
    (solve-choices
     [domain domain domain]
     (fn [[a b c] support]
       (if (pythagorean? a b c)
         {:status :satisfied :support support :value [a b c]}
         {:status :failed :support support})))))

(def l2-pythagorean-forms
  "L-2 DDB Pythagorean: recursive `ddb-pyth` (prelude) over three amb choices.
  Expects `ca`/`cb`/`cc` (choice records) and `kn` (knowledge set) seeded in env."
  '[(ddb-pyth ca cb cc kn ())])

(defn- church-triple
  "Read `(a b c)` Church list → `[a b c]` or nil."
  [network list-or-cell]
  (let [v0 (if (keyword? list-or-cell)
             (net/cell-value network list-or-cell)
             list-or-cell)]
    (when (plist/church-cons? v0)
      (let [a (net/cell-value network (:a v0))
            v1 (net/cell-value network (:b v0))]
        (when (plist/church-cons? v1)
          (let [b (net/cell-value network (:a v1))
                v2 (net/cell-value network (:b v1))]
            (when (plist/church-cons? v2)
              (let [c (net/cell-value network (:a v2))]
                (when (every? number? [a b c])
                  [a b c])))))))))

(defn pythagorean-l2
  "First Pythagorean triple via L-2 propagator-sense DDB.

  Host only seeds three choice records + empty knowledge. Search is L-2
  `ddb-pyth`: require → learn → backtrack → repair-choices → recur, while each
  attempt is published as an observation tip on `attempts` (reactive lift).
  No host `solve-choices` loop; scheduler is `run-tasks`.

  Opts: `:ca`/`:cb`/`:cc`/`:kn` override seeds (near-solution tests)."
  ([n] (pythagorean-l2 n nil))
  ([n {:keys [ca cb cc kn]}]
   (let [fresh-state (requiring-resolve 'propagators.prototype-2.compiler/fresh-state)
         compile-program (requiring-resolve 'propagators.prototype-2.compiler/compile-program)
         reactive-prelude (requiring-resolve 'propagators.prototype-2.compiler/l2-reactive-prelude)
         lenv-extend (requiring-resolve 'propagators.prototype-2.env/env-extend)
         tell-cell (requiring-resolve 'propagators.prototype-2.church/tell-cell)
         run-tasks (requiring-resolve 'propagators.prototype-2.evaluate/run-tasks)
         domain (vec (range 1 (inc n)))
         ca (or ca (initial-choice domain))
         cb (or cb (initial-choice domain))
         cc (or cc (initial-choice domain))
         kn (or kn #{})
         state (fresh-state)
         c-ca (net/new-cell-id "ca")
         c-cb (net/new-cell-id "cb")
         c-cc (net/new-cell-id "cc")
         c-kn (net/new-cell-id "kn")
         network (-> (:network state)
                     (tell-cell c-ca ca)
                     (tell-cell c-cb cb)
                     (tell-cell c-cc cc)
                     (tell-cell c-kn kn))
         [env network] (lenv-extend network (:env state) 'ca c-ca)
         [env network] (lenv-extend network env 'cb c-cb)
         [env network] (lenv-extend network env 'cc c-cc)
         [env network] (lenv-extend network env 'kn c-kn)
         state (assoc state :network network :env env)
         t0 (System/nanoTime)
         state (compile-program state (concat (vec @reactive-prelude) l2-pythagorean-forms))
         t1 (System/nanoTime)
         network (run-tasks (:network state))
         t2 (System/nanoTime)
         result (:result state)
         triple (church-triple network result)]
     {:status (if triple :satisfied :unsatisfiable)
      :value triple
      :compile-ms (/ (double (- t1 t0)) 1e6)
      :run-ms (/ (double (- t2 t1)) 1e6)
      :engine :prototype-2-l2-ddb
      :network network
      :result-cell result})))

(defn- world-registry-ops
  "Late-bound world/registry fns (avoid compiler↔world↔amb cycle)."
  []
  {:empty-registry (requiring-resolve 'propagators.prototype-2.world-registry/empty-registry)
   :register (requiring-resolve 'propagators.prototype-2.world-registry/register)
   :spawn (requiring-resolve 'propagators.prototype-2.world-registry/spawn)
   :touch (requiring-resolve 'propagators.prototype-2.world-registry/touch)
   :get-world (requiring-resolve 'propagators.prototype-2.world-registry/get-world)
   :hibernate (requiring-resolve 'propagators.prototype-2.world-registry/hibernate)
   :observe (requiring-resolve 'propagators.prototype-2.world/observe)
   :world-from-forms (requiring-resolve 'propagators.prototype-2.world/world-from-forms)})

(defn- spawn-child
  "Spawn under `parent-id`; return `[reg child-id]`."
  [spawn reg parent-id transform]
  (let [before (:active reg)
        reg (spawn reg parent-id transform)
        child-id (first (set/difference (:active reg) before))]
    (when-not child-id
      (throw (ex-info "spawn-child: no new world" {:parent-id parent-id})))
    [reg child-id]))

(defn pythagorean-subworlds
  "Pythagorean search where nested amb reset = abandon inner subworlds.

  For each `a`, spawn Wa; for each `b`, spawn Wb under Wa; for each `c`,
  spawn Wc under Wb and observe. Exhausting `c` does not mutate Wa — next `b`
  is a fresh sibling under Wa (old Wb branch dropped via hibernate). Same for
  advancing `a`. Mono env spines; no `reset-choice` on inner records."
  [n]
  (let [{:keys [empty-registry register spawn touch get-world hibernate observe world-from-forms]}
        (world-registry-ops)
        w0 (world-from-forms '(define a 0) '(define b 0) '(define c 0))
        reg (register (empty-registry (:network w0)) w0)
        root (:world-id w0)
        domain (vec (range 1 (inc n)))]
    (loop [as domain
           reg reg
           steps 0
           spawned 0
           abandoned 0]
      (if-not (seq as)
        {:status :unsatisfiable
         :steps steps
         :spawned spawned
         :abandoned abandoned}
        (let [a (first as)
              [reg wa] (spawn-child spawn reg root
                                    (list 'replace-definition 'a a))
              ;; Materialize before forking children so they share extended env
              reg (touch reg wa)
              spawned (inc spawned)]
          (let [result
                (loop [bs domain
                       reg reg
                       steps steps
                       spawned spawned
                       abandoned abandoned]
                  (if-not (seq bs)
                    {:status :continue :reg reg :steps steps
                     :spawned spawned :abandoned abandoned}
                    (let [b (first bs)
                          [reg wb] (spawn-child spawn reg wa
                                                (list 'replace-definition 'b b))
                          reg (touch reg wb)
                          spawned (inc spawned)
                          inner
                          (loop [cs domain
                                 reg reg
                                 steps steps
                                 spawned spawned
                                 abandoned abandoned]
                            (if-not (seq cs)
                              {:status :exhausted :reg reg :steps steps
                               :spawned spawned :abandoned abandoned}
                              (let [c (first cs)
                                    [reg wc] (spawn-child spawn reg wb
                                                          (list 'replace-definition 'c c))
                                    spawned (inc spawned)
                                    child (get-world reg wc)
                                    [va child] (observe child 'a)
                                    [vb child] (observe child 'b)
                                    [vc child] (observe child 'c)
                                    reg (assoc reg :network (:network child))
                                    steps (inc steps)]
                                (if (pythagorean? va vb vc)
                                  {:status :satisfied
                                   :value [va vb vc]
                                   :reg reg
                                   :steps steps
                                   :spawned spawned
                                   :abandoned abandoned}
                                  (recur (next cs)
                                         (hibernate reg wc)
                                         steps spawned (inc abandoned))))))]
                      (if (= :satisfied (:status inner))
                        inner
                        (recur (next bs)
                               (hibernate (:reg inner) wb)
                               (:steps inner)
                               (:spawned inner)
                               (inc (:abandoned inner)))))))]
            (if (= :satisfied (:status result))
              (select-keys result [:status :value :steps :spawned :abandoned])
              (recur (next as)
                     (hibernate (:reg result) wa)
                     (:steps result)
                     (:spawned result)
                     (inc (:abandoned result))))))))))

(defn solve-worlds
  "Amb over subworlds: each domain value → lazy revive `replace-definition`
  of `name` under `parent`. Probe observes under the child; backtrack does not
  deep-copy parent — only child frames materialize on observe.

  `probe` — `(fn [child-world support] certificate-map)`

  Uses requiring-resolve so this ns stays free of the compiler↔world cycle."
  [parent name domain probe]
  (let [{:keys [empty-registry register spawn get-world]} (world-registry-ops)
        reg0 (register (empty-registry (:network parent)) parent)
        parent-id (:world-id parent)]
    (solve (vec domain)
           (fn [alt support]
             (let [transform (list 'replace-definition name alt)
                   reg (spawn reg0 parent-id transform)
                   child-id (first (remove #{parent-id} (:active reg)))
                   child (get-world reg child-id)]
               (probe child support))))))

;; ---------------------------------------------------------------------------
;; L-2 / network primitives
;; ---------------------------------------------------------------------------

(defn install-amb-chosen-prop
  "Write current domain value of choice-cell → out."
  [network choice-cell out]
  (ctor/pending-propagator-constructor
   network
   (fn [record]
     (if (and (map? record) (contains? record :domain))
       (chosen record)
       t/nothing))
   [[choice-cell] out]))

(defn install-amb-support-prop
  "Write TTMS stamp of choice-cell → out."
  [network choice-cell out]
  (ctor/pending-propagator-constructor
   network
   (fn [record]
     (if (and (map? record) (contains? record :domain))
       (stamp record)
       t/nothing))
   [[choice-cell] out]))

(defn install-require-bool-prop
  "`(require-bool ok support)` → certificate map."
  [network ok-cell support-cell out]
  (ctor/pending-propagator-constructor
   network
   (fn [ok support]
     (require-bool ok (if (support/support? support) support #{})))
   [[ok-cell support-cell] out]))

(defn- church-list->records
  [network list-cell]
  (loop [id list-cell
         acc []]
    (let [v (net/cell-value network id)]
      (cond
        (or (t/nothing? v) (plist/empty-list? v)) acc
        (plist/church-cons? v)
        (let [item (net/cell-value network (:a v))]
          (recur (:b v) (cond-> acc (map? item) (conj item))))
        :else acc))))

(defn install-learn-prop
  "learn(knowledge, certificate, decisions-list) → new knowledge."
  [network knowledge-cell cert-cell decisions-cell out]
  (let [expand
        (fn [n]
          (let [kn (net/cell-value n knowledge-cell)
                cert (net/cell-value n cert-cell)
                records (church-list->records n decisions-cell)]
            (if (or (t/nothing? kn) (t/nothing? cert)
                    (not (map? cert)) (empty? records))
              n
              (let [knowledge (if (set? kn) kn #{})
                    next (learn knowledge cert records)]
                (ev/write-cell n out next)))))]
    (ctor/pending-compound-constructor
     network expand [knowledge-cell cert-cell decisions-cell] [out])))

(defn install-backtrack-prop
  "backtrack(decisions-list, knowledge) → repair request (advanced)."
  [network decisions-cell knowledge-cell out]
  (let [expand
        (fn [n]
          (let [kn (net/cell-value n knowledge-cell)
                records (church-list->records n decisions-cell)]
            (if (or (t/nothing? kn) (empty? records))
              n
              (let [req (advance-request (decide records (if (set? kn) kn #{})))]
                (ev/write-cell n out req)))))]
    (ctor/pending-compound-constructor
     network expand [decisions-cell knowledge-cell] [out])))

(defn install-apply-repair-prop
  "apply-repair(choice, request) → updated choice."
  [network choice-cell request-cell out]
  (ctor/pending-propagator-constructor
   network
   (fn [record request]
     (if (and (map? record) (map? request))
       (apply-repair record request)
       t/nothing))
   [[choice-cell request-cell] out]))

(defn install-repair-unsat?-prop
  "True when a backtrack request cannot advance (idle/unsatisfiable)."
  [network request-cell out]
  (ctor/pending-propagator-constructor
   network
   (fn [request]
     (boolean (and (map? request)
                   (contains? #{:unsatisfiable :idle} (:action request)))))
   [[request-cell] out]))

(defn install-repair-choices-prop
  "DDB multi-choice repair: apply request to the targeted decision, reset later
  ones (mono nest). Input is a Church list of choice records; output is a new
  Church list of repaired records (fresh cells — tip publish, no overwrite)."
  [network decisions-cell request-cell out]
  (let [list-from-values (requiring-resolve 'propagators.prototype-2.church/list-from-values)
        expand
        (fn [n]
          (let [req (net/cell-value n request-cell)
                records (church-list->records n decisions-cell)]
            (if (or (t/nothing? req) (not (map? req)) (empty? records))
              n
              (let [repaired (apply-repair-among records req)
                    {:keys [network list]} (list-from-values n repaired)
                    spine (net/cell-value network list)]
                (ev/write-cell network out spine)))))]
    (ctor/pending-compound-constructor
     network expand [decisions-cell request-cell] [out])))
