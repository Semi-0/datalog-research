(ns examples.lain.subagents.session
  "Session-local graph assembly; effects are interpreted outside propagation."
  (:require [examples.lain.subagents.model :as model]
            [examples.lain.subagents.boundary :as boundary]
            [propagators.datastructures.compound-object :as obj]
            [propagators.cells.merge :as merge]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.helpers.task-queue :as queue]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]
            [propagators.propagator :as prop]
            [propagators.stdlib.source-publication :as publication]))

(def fields {:phase model/phase :backend model/backend :operation model/operation
             :task model/task :child model/child :result model/result :failure model/failure})
(defn session-extension [event-cell view-cell]
  (extension/extension-bundle
   {:id ::subagents
    :bindings (into [['subagent-events (env/cell-binding event-cell)]
                     ['subagent-view (env/cell-binding view-cell)]]
                    (map (fn [[field accessor]]
                           [(symbol (str "subagent-" (name field)))
                            (ttms/scalar-operator field
                              (prop/concrete-primitive-propagator field (comp accessor model/current-state)))]) fields))
    :effects []}))
(defn- settle [network props]
  (runner/completed-network
   (runner/run-network (queue/enqueue-all queue/empty-queue props) network)))
(defn- compile-in [session text]
  (let [compiled (compiler/compile-source text (:program/env session) {:net (:program/net session)})]
    [(assoc session :program/net (settle (:net compiled) (:props compiled))) (:cell compiled)]))
(defn prepare
  "Fresh graph, observation mode by default. No bridge is started here."
  [identity]
  (let [event-cell (ids/new-node-id) view-cell (ids/new-node-id)
        events (model/empty-events identity)
        source [::events identity]
        initial (update (state/empty-state) :program/net
                        #(-> % (nb/install-cell event-cell (publication/active source 0 events)
                                                (merge/strongest-value (publication/active source 0 events) %))
                             (nb/ensure-cell view-cell)))
        installed (extension/install-session-extension initial (session-extension event-cell view-cell) {})
        [compiled root] (compile-in installed (slurp "examples/lain/subagents/flow.lain"))
        [session ports] (reduce (fn [[s ports] field]
                                  (let [[s cell] (compile-in s (str "(p:slot " field " subagent-view)"))]
                                    [s (assoc ports field cell)]))
                                [compiled {}] (keys fields))]
    {:identity identity :events events :revision 0 :source source :event-cell event-cell
     :session session :root root :ports ports :mode :observe}))
(defn observe
  "Add facts and publish a new TTMS source revision. Old immutable graphs remain valid."
  [prepared observations]
  (let [events (model/accumulate (:events prepared) observations)]
    (if (= events (:events prepared)) prepared
        (let [revision (inc (:revision prepared)) network (get-in prepared [:session :program/net])
              cell (:event-cell prepared)
              update (publication/active (:source prepared) revision events)
              entry (merge/merge-cell-entry (net/network-lookup-cell network cell) update network)
              updated (net/assoc-net-cell network cell entry)
              settled (settle updated (nb/neighbor-propagator-ids updated cell))]
          (assoc prepared :events events :revision revision
                 :session (assoc (:session prepared) :program/net settled))))))
(defn field
  "Read only an explicitly connected scalar inspection port."
  [prepared key]
  (if-let [cell (get (:ports prepared) key)]
    (obj/slot-strongest (net/network-cell-strongest (get-in prepared [:session :program/net]) cell) :base)
    (throw (ex-info "Unknown inspection field" {:field key}))))
