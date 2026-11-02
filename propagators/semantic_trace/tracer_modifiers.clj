(ns propagators.semantic-trace.tracer-modifiers
  "Commutative declarations, not an ordered pipeline of destructive transforms."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as ttms]
            [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]))

(defn declaration [kind]
  (when-not (contains? #{:high-level :with-values :caller} kind)
    (throw (ex-info "Unknown tracer modifier" {:kind kind})))
  {:kind kind})

(defn selection [selectors]
  {:kind :select :selection (set selectors)})

(defn declaration? [candidate]
  (and (map? candidate)
       (or (and (contains? #{:high-level :with-values :caller} (:kind candidate))
                (= #{:kind} (set (keys candidate))))
           (and (= :select (:kind candidate)) (set? (:selection candidate))
                (= #{:kind :selection} (set (keys candidate)))))))

(defn normalize [modifiers]
  (doseq [modifier modifiers]
    (when-not (declaration? modifier)
      (throw (ex-info "Expected a tracer modifier declaration" {:modifier modifier}))))
  (let [selected (filter #(= :select (:kind %)) modifiers)
        others (remove #(= :select (:kind %)) modifiers)]
    (if (seq selected)
      (into (conj (set others) (selection #{}))
            (map #(selection #{%})) (mapcat :selection selected))
      (set others))))

(defn- states-of [v]
  (if (datum/layer-present? v :premise-state)
    (datum/layer-value v :premise-state) #{}))

(defn normalize-supported
  "Return a canonical declaration-to-TTMS-content index. Alternative supports
  remain alternatives; declaration order and repeated declarations do not matter."
  [values contents]
  (when-not (= (count values) (count contents))
    (throw (ex-info "Modifier values and evidence must correspond" {})))
  (reduce
   (fn [result [v content]]
     (let [content (if (ttms/content? content) content
                      (ttms/content {:base v :support (datum/support-of content)
                                     :premise-state (states-of content)}))
           projected (ttms/strongest-value content
                       (fn [a b] (cond (= a b) a (value/nothing? a) b
                                       (value/nothing? b) a :else value/contradiction)))
           states (states-of projected)]
       (reduce
        (fn [index row]
          (let [declaration (datum/layer-value row :base)]
            (if (value/unusable? declaration)
              index
              (reduce (fn [index d]
                        (update index d (fn [prior incoming]
                                          (if prior (ttms/merge-content prior incoming) incoming))
                                (ttms/content {:base true :support (datum/support-of row)
                                               :premise-state states})))
                      index (normalize [declaration])))))
        result (ttms/observations content))))
   (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
   (map vector values contents)))

(defn join [left right]
  (merge-with ttms/merge-content left right))

(defn from-facts
  "Read supported declarations without replacing their evidence with plain truth."
  [graph]
  (into (normalize-supported [] [])
        (map (fn [declaration]
               (let [id (facts/assertion-id (facts/observation graph) (facts/scope graph)
                                             :view/declaration [declaration])]
                 [declaration
                  (ttms/merge-content (get (facts/assertions graph) id)
                                      (ttms/content {:premise-state (facts/premise-state graph)}))])))
        (facts/declarations graph)))

(defn apply-modifiers [graph modifiers]
  (let [index (if (map? modifiers) modifiers
                  (normalize-supported modifiers (repeat (count modifiers) value/nothing)))
        modify (fn [scope]
                 (reduce-kv
                  (fn [g declaration content]
                    (let [states (states-of (ttms/strongest-value content (fn [_ b] b)))]
                      (reduce (fn [g row]
                                (facts/declare-view g declaration (datum/support-of row) states))
                              g (ttms/observations content))))
                  scope index))]
    (if (capture/fragment? graph)
      (capture/update-scope graph :root modify)
      (modify (if (facts/fact-graph? graph) graph
                  (facts/from-graph ::legacy :root graph #{}))))))
