(ns tools.lain-example-runner
  "Harness for executing the .lain examples that back the Lain style guides.

  A session is an ordinary compiler-2 source session (the same loader the
  editor uses).  Examples are pushed into a session as source text, then the
  named bindings are projected back into plain Clojure data so a documented
  expectation can be asserted.

  Projection contract:
    * an unresolved binding projects to :unresolved
    * a contradiction projects to :contradiction
    * a connected list projects to a vector; an open tail contributes a final
      :unresolved element
    * a compound owner projects to a map of its public slot keys to the
      strongest value currently held by each slot cell"
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.network :as net]))

(def unresolved :unresolved)
(def contradiction :contradiction)

(defn new-session []
  (loader/load-session-from-source "0"))

(defn load-source!
  "Compile and propagate `source` in `session`, throwing on any load error."
  [session source]
  (let [result (loader/load-source! session source)]
    (when-let [error (:error (:load-result result))]
      (throw (ex-info (or (:message error) "Lain source failed to load")
                      {:form (:form error)})))
    session))

(defn binding-id [session name]
  (let [state @session]
    (env/resolve-binding-id (:program/net state) (:program/env state)
                            (if (symbol? name) name (symbol (str name))))))

(defn- cell-value [network id]
  (let [cell (net/network-cell-strongest network id)]
    (if (datum/layer-present? cell :base)
      (datum/layer-value cell :base)
      cell)))

(defn- slot-pair [network id]
  (let [car (obj/existing-slot-cell-id network :car id)
        cdr (obj/existing-slot-cell-id network :cdr id)]
    (when (and car cdr) [car cdr])))

(defn- canonical-slot [owner slot]
  (when (net/net? owner)
    (try (net/network-dict-entry owner [:compound/internal :accessor slot :canonical-cell])
         (catch Throwable _ nil))))

(declare project-id)

(defn- list-node [head tail]
  ;; A closed tail is already a vector; an open tail is appended as a marker so
  ;; (cons 1 ?) projects to [1 :unresolved] rather than looking complete.
  (if (vector? tail)
    (vec (cons head tail))
    (vec (cons head [tail]))))

(defn- project-struct [v depth]
  (if-let [slots (seq (sort (obj/public-slot-keys v)))]
    (zipmap slots (map #(obj/slot-strongest v %) slots))
    (pr-str v)))

(defn- project-id [network id depth]
  (if (> depth 24)
    :too-deep
    (let [v (cell-value network id)]
      (cond
        (nil? v) unresolved
        (value/contradiction? v) contradiction
        (value/nothing? v) unresolved
        (= basis/list-empty-marker v) []
        (slot-pair network id)
        (let [[car cdr] (slot-pair network id)]
          (list-node (project-id network car (inc depth))
                     (project-id network cdr (inc depth))))
        (net/net? v)
        (if-let [car (canonical-slot v :car)]
          (list-node (project-id v car (inc depth))
                     (project-id v (canonical-slot v :cdr) (inc depth)))
          (project-struct v depth))
        :else v))))

(defn project
  "Project the currently available information held by binding `name`."
  [session name]
  (let [state @session
        network (:program/net state)
        id (binding-id session name)]
    (when-not id
      (throw (ex-info "Unknown binding" {:binding name})))
    (let [v (project-id network id 0)]
      (if (vector? v) (vec v) v))))

(comment
  (def s (doto (new-session)
           (load-source! "(load-lain \"propagators/compiler_2/runtime/lain/list.lain\")
                          (define answer (map-list (network [x] (* x 2)) (list 1 2 3)))")))
  (project s 'answer))
