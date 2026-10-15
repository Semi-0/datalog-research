(ns examples.lain.rule-fixedpoint.query
  "Compiled questions for AMB and genuine guards; unconditional joins need none."
  (:require [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.amb.solver-question :as question]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.model :as model]
            [propagators.cells.value :as value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.publication :as publication]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.supported-procedure :as supported]))

(defn primitive [name f]
  (operator/operator-closure
   {:name name
    :install (fn [network arguments output]
               (let [base (prop/primitive-propagator
                           name (fn [& values]
                                  (if (apply value/any-unusable-values? values)
                                    value/nothing (apply f values))))]
                 (supported/install (supported/from-primitive base) network arguments output)))}))

(def ^:dynamic *candidate-checks* nil)

(def membership
  (primitive ::rule-match (fn [request x y z]
                           (if *candidate-checks* (swap! *candidate-checks* inc) nil)
                           (boolean (model/accepts? request [x y z])))))

(def node-at
  (primitive ::node-at (fn [request ordinal] (nth (:domain request) (dec ordinal)))))

(defn encode [request tuple]
  (let [ordinals (zipmap (:domain request) (range 1 (inc (count (:domain request)))))]
    (mapv ordinals tuple)))

(defn decode [request tuple]
  (mapv #(nth (:domain request) (dec %)) tuple))

(defn compile-template
  "Context may contain the catalog Net, needed for a Lain guard's lexical capture."
  [rule context]
  (let [request (ids/new-node-id)
        initial (if (:network context)
                  (assoc (session/empty-state) :program/net (:network context))
                  (session/empty-state))
        guard (:guard rule)
        _ (if (or (nil? guard) (string? guard) (application/compiler-callable? guard))
            nil (throw (ex-info "A rule guard must be a Lain network, never a host callback" {})))
        bindings [['rule-match? membership] ['rule-node node-at] ['rule-request (env/cell-binding request)]]
        bindings (if (and guard (not (string? guard))) (conj bindings ['rule-guard guard]) bindings)
        prepared (-> initial
                     (update :program/net nb/ensure-cell request)
                     (extension/install-session-extension ttms/session-extension {})
                     (extension/install-session-extension
                      (extension/extension-bundle {:id ::binding :bindings bindings :effects []}) {}))
        source (str "(let-cell [] "
                    (if (string? guard) (str "(define rule-guard " guard ") ") "")
                    "(network (ix iy iz) (let-cell [] "
                    "(define x (rule-node rule-request ix)) "
                    "(define y (rule-node rule-request iy)) "
                    "(define z (rule-node rule-request iz)) "
                    (if (nil? guard)
                      "(rule-match? rule-request x y z)"
                      "(and (rule-match? rule-request x y z) (rule-guard x y z))")
                    ")))")
        compiled (compiler/compile-source source (:program/env prepared)
                                          {:net (:program/net prepared) :environment-props (:program/props prepared)})
        settled (checkpoint/settle {:network (:net compiled) :tasks (:props compiled)})
        sources (vec (repeatedly 3 ids/new-node-id))]
    {:network (reduce nb/ensure-cell (:network settled) sources)
     :callable (:cell compiled) :request request :sources sources :source source}))

(defn instantiate [template request]
  (let [small (select-keys request [:rule :variables :domain :demand :tables :deltas :require-delta?])
        ;; The query input is a frozen snapshot, not a new fact authority.
        small (update small :rule dissoc :guard)
        state (channels/tell {:network (:network template) :tasks []} (:request template)
                             (collection/content
                              (:value (publication/snapshot-message (:network template) (:request template) small))))
        settled (checkpoint/settle state)]
    (question/build (:network settled) (:callable template) (:sources template)
                    (vec (range 1 (inc (count (:domain request))))))))

(defn- decision [question state tuple]
  (case (:status (channels/base (:network state) (get-in question [:cells :failure])))
    :satisfied true
    :failed false
    :pending (throw (ex-info "A fully bound rule guard remained unresolved" {:tuple tuple}))
    (throw (ex-info "Unknown rule-guard certificate" {:tuple tuple}))))

(defn accepted?
  "Original sequential validator, retained as the independent benchmark oracle."
  [question tuple]
  (let [state (reduce (fn [state [source v]] (checkpoint/tell state source v :active))
                      (checkpoint/settle (select-keys question [:network :tasks]))
                      (map vector (:sources question) tuple))]
    (decision question state tuple)))

(defn ready-question [template request]
  ;; Reuse this immutable initialized checkpoint for every candidate in a request.
  (checkpoint/settle (instantiate template request)))

(defn accepted-batch?
  "For pure, fully bound guards: queue all inputs, then propagate once.
  Every candidate starts from the supplied immutable question, never its sibling."
  [question tuple]
  (if (= (count (:sources question)) (count tuple))
    (let [queued (reduce (fn [state [source v]]
                           (let [emitted (publication/source-message (:network state) source v :active)]
                             (channels/tell state source (collection/content (:value emitted)))))
                         (select-keys question [:network :tasks])
                         (map vector (:sources question) tuple))
          settled (checkpoint/settle queued)]
      (decision question settled tuple))
    (throw (ex-info "A guard candidate must bind every source exactly once" {:tuple tuple}))))
