(ns propagators.compiler-2.runtime.tui.annotations
  "Display annotations for projected TUI block values."
  (:require [clojure.string :as str]
            [graph.compiler-2-semantic-repl :as semantic-repl]
            [propagators.cells.value :as value]
            [propagators.cells.merge :as merge]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.tms :as tms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.semantic-trace :as semantic-trace]
            [propagators.stdlib.premise-state :as state]))

(defn project-value [v]
  (cond
    (value/contradiction? v) v
    (or (collection/content? v) (membership/content? v))
    (project-value (merge/strongest-value v net/empty-net))
    (datum/layer-present? v :support) (project-value (datum/layer-value v :base))
    (value/unusable? v) v
    (semantic-trace/semantic-trace-graph? v) v
    (tms/distributed-value? v)
    (let [projected (tms/strongest-distributed-value v)]
      (if (value/unusable? projected)
        projected
        (project-value (tms/distributed-base-value projected))))
    (net/network? v) (or (semantic-repl/display-cell-value v) "network")
    :else v))

(defn- sorted-pr [xs] (->> xs (sort-by pr-str) vec))
(declare value-annotations)

(defn- tms-annotation [v]
  (let [projected (tms/strongest-distributed-value v)]
    (when-not (value/unusable? projected)
      (let [view (tms/tms-view (tms/distributed-slots projected))
            entry (get (tms/propositions view) tms/distributed-proposition)]
        {:kind :tms :status (:tms/status entry)
         :claims (sorted-pr (:tms/claims entry))
         :active-premises (sorted-pr (tms/active-premises view))}))))

(defn- tms-annotations [v]
  (let [projected (tms/strongest-distributed-value v)
        base (when-not (value/unusable? projected) (tms/distributed-base-value projected))
        annotation (tms-annotation v)]
    (cond-> []
      annotation (conj annotation)
      base (into (value-annotations base)))))

(defn- public-support [premises]
  (mapv #(update % :source pr-str) (sorted-pr premises)))

(defn value-annotations [v]
  (cond
    (or (collection/content? v) (membership/content? v))
    (value-annotations (merge/strongest-value v net/empty-net))
    (datum/layer-present? v :support)
    (let [support (datum/support-of v)
          states (state/states-of v)
          nested (value-annotations (datum/layer-value v :base))]
      (cond-> nested
        (or (seq support) (seq states))
        (conj {:kind :ttms :support (public-support support)
               :premise-state (public-support states)})))
    (value/unusable? v) []
    (tms/distributed-value? v) (tms-annotations v)
    :else []))

(def max-annotation-token-length 48)

(defn- abbreviate [s]
  (if (> (count s) max-annotation-token-length)
    (str (subs s 0 (- max-annotation-token-length 3)) "...")
    s))

(defn- annotation-token [x]
  (cond
    (ids/node-id? x) (str "node:" (subs (str (:uuid x)) 0 8))
    (keyword? x) (name x)
    (symbol? x) (name x)
    (string? x) x
    :else (abbreviate (pr-str x))))

(defn- tms-annotation-label [{:keys [status claims active-premises]}]
  (str "[tms:" (or (some-> status name) "unknown")
       (when (seq claims)
         (str " claims=" (str/join "," (map annotation-token claims))))
       (when (seq active-premises)
         (str " premises=" (str/join "," (map annotation-token active-premises))))
       "]"))

(defn format-annotations [annotations]
  (when (seq annotations)
    (str/join " "
              (map (fn [{:keys [kind] :as annotation}]
                     (case kind
                       :tms (tms-annotation-label annotation)
                       :ttms (str "[ttms:" (count (:support annotation)) " sources]")
                       (pr-str annotation))) annotations))))

(defn annotate-block-view [block-view raw-value]
  (let [annotations (value-annotations raw-value)]
    (cond-> block-view
      (seq annotations) (assoc :annotations annotations
                              :annotation (format-annotations annotations)))))
