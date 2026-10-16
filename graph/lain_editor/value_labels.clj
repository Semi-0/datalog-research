(ns graph.lain-editor.value-labels
  "Read-only, complete presentations of every public layer; runtime data stays intact."
  (:require [clojure.string :as str]
            [graph.lain-editor.call-inspection :as calls]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.event :as event]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(defn public-keys [v]
  (cond
    (object/accessor-network? v) (object/accessor-slot-keys v)
    (net/net? v) (object/public-slot-keys v)
    (map? v) (keys v)
    :else []))

(defn public-slot [v key]
  (cond
    (object/accessor-source-slot-present? v key) (object/accessor-source-slot-value v key)
    (net/net? v) (object/slot-value v key)
    :else (get v key)))

(defn- event-layers [v]
  (mapv (fn [fact]
          {:base (event/event-value fact)
           :timestamp (event/timestamp fact)
           :source-state (event/source-state fact)})
        (sort-by (comp pr-str event/timestamp) (event/facts v))))

(defn- preview [v]
  (cond
    (value/nothing? v) 'the-nothing
    (value/contradiction? v) 'the-contradiction
    (ids/node-id? v) (symbol (str "cell/" (:uuid v)))
    (event/event-protocol-value? v) (mapv #(preview %) (event-layers v))
    (calls/callable-inputs v) (symbol (str "network " (calls/callable-inputs v)))
    (net/net? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                     (map (fn [key] [(preview key) (preview (public-slot v key))]))
                     (sort-by pr-str (public-keys v)))
    (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (map (fn [[key item]] [(preview key) (preview item)]))
                   (sort-by (comp pr-str key) v))
    (set? v) (into #{} (map #(preview %)) v)
    (sequential? v) (mapv #(preview %) v)
    :else v))

(defn datum-text [v]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (preview v))))

(defn- layer-sections [v]
  (mapv (fn [key] {:title (pr-str key) :value (datum-text (public-slot v key))})
        (sort-by #(vector (not= :base %) (pr-str %)) (public-keys v))))

(defn sections [v]
  (cond
    (or (value/nothing? v) (value/contradiction? v)) [{:title "" :value (datum-text v)}]
    (event/event-protocol-value? v) (vec (mapcat layer-sections (event-layers v)))
    (contains? (set (public-keys v)) :base) (layer-sections v)
    :else [{:title "" :value (datum-text v)}]))

(defn lines [v]
  (let [rows (map (fn [{:keys [title value]}]
                    (str/trim (str title " " value))) (sections v))]
    (vec (mapcat #(re-seq #"[\s\S]{1,52}" %) rows))))

(defn format-value
  "The same public-layer preview for direct display, labels and tooltips."
  [v]
  (str/join "\n" (lines v)))
