(ns graph.lain-editor.stack-scene
  "Pure interpretation of materialized stacks into spatial semantic planes."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.network :as net]))

(defn- slot-value [candidate slot]
  (let [source (object/accessor-source-slot-value candidate slot)]
    (if-not (value/nothing? source)
      source
      (if-let [id (net/network-dict-entry
                   candidate
                   (object/internal-metadata-key :accessor slot :canonical-cell))]
        (net/network-cell-strongest candidate id)
        value/nothing))))

(defn stack-value? [candidate]
  (and (object/accessor-network? candidate)
       (= :stack (slot-value candidate :type))))

(defn- stack-ref? [candidate]
  (and (object/accessor-network? candidate)
       (= :stack-ref (slot-value candidate :type))))

(defn- linked-items [candidate]
  (loop [current candidate items [] seen #{}]
    (cond
      (= :compiler-2/list-empty current) items
      (value/unusable? current) current
      (or (not (object/accessor-network? current)) (contains? seen current))
      value/contradiction
      :else
      (recur (slot-value current :cdr)
             (conj items (slot-value current :car))
             (conj seen current)))))

(defn compile-scene
  "Return one semantic plane per expanded stack occurrence. References remain
  explicit edges and never create duplicate planes."
  [root]
  (letfn [(walk [node depth order parent]
            (let [identity (slot-value node :identity)
                  content (slot-value node :content)
                  children (linked-items (slot-value node :children))
                  plane {:identity identity :content content :depth depth :order order}
                  hierarchy (when parent [{:from parent :to identity :kind :child}])]
              (if (value/unusable? children)
                {:planes [plane]
                 :edges (vec hierarchy)
                 :diagnostics [{:kind :missing
                                :identity identity
                                :message "Child membership is unavailable"}]
                 :next-order (inc order)}
                (reduce
                 (fn [{:keys [planes edges diagnostics next-order]} child]
                   (cond
                     (stack-ref? child)
                     {:planes planes
                      :edges (conj edges {:from identity
                                          :to (slot-value child :identity)
                                          :kind :reference})
                      :diagnostics diagnostics
                      :next-order next-order}

                     (stack-value? child)
                     (let [compiled (walk child (inc depth) next-order identity)]
                       {:planes (into planes (:planes compiled))
                        :edges (into edges (:edges compiled))
                        :diagnostics (into diagnostics (:diagnostics compiled))
                        :next-order (:next-order compiled)})

                     :else
                     {:planes planes :edges edges
                      :diagnostics (conj diagnostics
                                         {:kind :conflict :identity identity
                                          :message "Child is neither a stack nor a reference"})
                      :next-order next-order}))
                 {:planes [plane] :edges (vec hierarchy)
                  :diagnostics [] :next-order (inc order)}
                 children))))]
    (if-not (stack-value? root)
      {:stackScene true :planes [] :edges []
       :diagnostics [{:kind :conflict :message "Expected a materialized stack"}]}
      (let [compiled (walk root 0 0 nil)]
        {:stackScene true
         :root (slot-value root :identity)
         :planes (:planes compiled)
         :edges (:edges compiled)
         :diagnostics (:diagnostics compiled)}))))
