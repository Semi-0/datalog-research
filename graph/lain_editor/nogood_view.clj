(ns graph.lain-editor.nogood-view
  "Read-only rows for the explicit AMB learning-view presentation contract."
  (:require [propagators.experimental.amb.learning-view :as view]))

(def learning-view? view/learning-view?)

(defn- source-row [source index depth reference]
  {:key "reject alternative" :value (str index) :depth depth
   :sourceToken (pr-str source) :sourceReference (reference source)})

(defn- context-rows [context depth reference]
  (if (empty? context)
    [{:key "context" :value "unconditional" :depth depth}]
    (mapv (fn [{:keys [source timestamp premises-status]}]
            {:key "context premise" :value (str timestamp " · " (name premises-status))
             :depth depth :sourceToken (pr-str source)
             :sourceReference (reference source)})
          (sort-by (juxt (comp pr-str :source) (comp pr-str :timestamp)
                         :premises-status) context))))

(defn rows [value depth reference]
  (let [knowledge (view/knowledge value)]
    (into [{:key "learned no-goods" :value (str (count knowledge)) :depth depth}]
          (mapcat
           (fn [[index {:keys [literals context]}]]
             (concat
              [{:key (str "no-good " (inc index))
                :value (if (empty? literals) "unsatisfiable under context" "forbidden combination")
                :depth (inc depth)}]
              (map (fn [[source alternative]]
                     (source-row source alternative (+ depth 2) reference))
                   (sort-by (juxt (comp pr-str first) second) literals))
              (context-rows context (+ depth 2) reference)))
           (map-indexed vector (sort-by pr-str knowledge))))))
