(ns differential-dataflow.graph.declaration
  "Pure data: operator connectivity and initial frontiers. No queues, atoms, or run logic.")

(defn unary-op-decl
  "Declare a unary operator by logical stream keywords (`in-kw`, `out-kw`)."
  [id in-kw out-kw initial-frontier]
  {:op/id id
   :op/kind :unary
   :op/in in-kw
   :op/out out-kw
   :op/initial-frontier initial-frontier})

(defn binary-op-decl
  "Declare a binary operator by logical stream keywords."
  [id in-a-kw in-b-kw out-kw initial-frontier]
  {:op/id id
   :op/kind :binary
   :op/in-a in-a-kw
   :op/in-b in-b-kw
   :op/out out-kw
   :op/initial-frontier initial-frontier})

(defn graph-decl
  "`operator-decls` is a vector of maps from `unary-op-decl` / `binary-op-decl`."
  [operator-decls]
  {:graph/operator-decls (vec operator-decls)})

(defn- op-stream-kws [m]
  (case (:op/kind m)
    :unary [(:op/in m) (:op/out m)]
    :binary [(:op/in-a m) (:op/in-b m) (:op/out m)]
    (throw (ex-info "unknown :op/kind" {:op m}))))

(defn declared-stream-ids
  "All logical stream keywords referenced by the declaration."
  [graph-decl]
  (into #{}
        (mapcat op-stream-kws)
        (:graph/operator-decls graph-decl)))

(defn validate-graph-decl!
  "Throws if decls are inconsistent (duplicate :op/id, unknown kind)."
  [graph-decl]
  (let [decls (:graph/operator-decls graph-decl)
        ids (map :op/id decls)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "duplicate :op/id in graph declaration" {:ids ids})))
    (doseq [d decls]
      (when-not (#{:unary :binary} (:op/kind d))
        (throw (ex-info "invalid :op/kind" {:op d})))
      (case (:op/kind d)
        :unary (when-not (and (:op/in d) (:op/out d))
                  (throw (ex-info "unary decl needs :op/in and :op/out" {:op d})))
        :binary (when-not (and (:op/in-a d) (:op/in-b d) (:op/out d))
                  (throw (ex-info "binary decl needs :op/in-a :op/in-b :op/out" {:op d})))))
    graph-decl))
