(ns differential-dataflow.graph.construction
  "Turn a `graph-decl` plus per-operator handler factories into runtime writers and `Operator`s."
  (:require [differential-dataflow.graph.declaration :as decl]
            [differential-dataflow.graph.runtime :as rt]
            [differential-dataflow.graph.stream :as stream]))

(defn instantiate-graph!
  "Builds one `DifferenceStreamWriter` per declared stream id and one `Operator` per decl.

  `handlers` — map from `:op/id` to `(fn [ctx] run!)` where:
  - unary `ctx` is `{:reader <DifferenceStreamReader> :writer <DifferenceStreamWriter>}`
  - binary `ctx` is `{:reader-a ... :reader-b ... :writer <DifferenceStreamWriter>}`
  and the function returns a no-arg `run!` (same contract as `unary-operator` / `binary-operator`).

  Returns `{:graph g :writers {stream-id writer}}`."
  [graph-decl handlers]
  (let [graph-decl (decl/validate-graph-decl! graph-decl)
        ids (decl/declared-stream-ids graph-decl)
        writers (into {}
                      (map (fn [k] [k (stream/difference-stream-writer)]))
                      ids)
        operators
        (mapv
         (fn [op]
           (let [id (:op/id op)
                 f (or (get handlers id)
                       (throw (ex-info "missing handler for :op/id" {:op/id id :handlers (keys handlers)})))]
             (case (:op/kind op)
               :unary
               (let [w-in (writers (:op/in op))
                     w-out (writers (:op/out op))
                     r (stream/new-reader! w-in)
                     run! (f {:reader r :writer w-out})]
                 (rt/unary-operator r w-out run! (:op/initial-frontier op)))
               :binary
               (let [wa (writers (:op/in-a op))
                     wb (writers (:op/in-b op))
                     w-out (writers (:op/out op))
                     ra (stream/new-reader! wa)
                     rb (stream/new-reader! wb)
                     run! (f {:reader-a ra :reader-b rb :writer w-out})]
                 (rt/binary-operator ra rb w-out run! (:op/initial-frontier op))))))
         (:graph/operator-decls graph-decl))
        g (rt/graph writers operators)]
    {:graph g :writers writers}))
