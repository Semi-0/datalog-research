(ns graph.lain-editor.stack.scene
  "Pure interpretation of materialized stacks into spatial semantic planes."
  (:require [graph.lain-editor.stack.primitives :as stack]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]))

(defn- slot-value [network candidate slot]
  (if network
    (stack/stack-field network slot candidate)
    (stack/stack-field slot candidate)))

(defn stack-value?
  ([candidate] (stack-value? nil candidate))
  ([network candidate]
   (and (object/accessor-network? candidate)
        (= :stack (slot-value network candidate :type)))))

(defn- stack-ref? [network candidate]
  (and (object/accessor-network? candidate)
       (= :stack-ref (slot-value network candidate :type))))

(defn- linked-items [network candidate]
  (loop [current candidate items [] seen #{}]
    (cond
      (= :compiler-2/list-empty current) items
      (value/unusable? current) current
      (or (not (object/accessor-network? current)) (contains? seen current))
      value/contradiction
      :else
      (recur (slot-value network current :cdr)
             (conj items (slot-value network current :car))
             (conj seen current)))))

(defn compile-scene
  "Return one semantic plane per expanded stack occurrence. Every inter-plane
  edge retains the compound node in the parent graph that owns the expansion.
  References remain explicit and never create duplicate planes."
  ([root] (compile-scene nil root))
  ([network root]
   (letfn [(walk [node depth order]
            (let [identity (slot-value network node :identity)
                  graph (slot-value network node :graph)
                  expansions (linked-items network
                                           (slot-value network node :expansions))
                  plane {:identity identity :graph graph :depth depth :order order}]
              (if (value/unusable? expansions)
                {:planes [plane]
                 :edges []
                 :diagnostics [{:kind :missing
                                :identity identity
                                :message "Expansion membership is unavailable"}]
                 :next-order (inc order)}
                (reduce
                 (fn [{:keys [planes edges diagnostics next-order]} expansion]
                   (let [source (slot-value network expansion :source)
                         target (slot-value network expansion :target)]
                     (cond
                       (not= :stack-expansion
                             (slot-value network expansion :type))
                       {:planes planes :edges edges
                        :diagnostics (conj diagnostics
                                           {:kind :conflict :identity identity
                                            :message "Expected a stack expansion"})
                        :next-order next-order}

                       (stack-ref? network target)
                       {:planes planes
                        :edges (conj edges {:from identity
                                            :source-node source
                                            :to (slot-value network target :identity)
                                            :kind :reference})
                        :diagnostics diagnostics
                        :next-order next-order}

                       (stack-value? network target)
                       (let [compiled (walk target (inc depth) next-order)]
                         {:planes (into planes (:planes compiled))
                          :edges (into (conj edges {:from identity
                                                   :source-node source
                                                   :to (slot-value network target :identity)
                                                   :kind :expansion})
                                       (:edges compiled))
                          :diagnostics (into diagnostics (:diagnostics compiled))
                          :next-order (:next-order compiled)})

                       :else
                       {:planes planes :edges edges
                        :diagnostics (conj diagnostics
                                           {:kind :conflict :identity identity
                                            :source-node source
                                            :message "Expansion target is neither a stack nor a reference"})
                        :next-order next-order})))
                 {:planes [plane] :edges []
                  :diagnostics [] :next-order (inc order)}
                 expansions))))]
    (if-not (stack-value? network root)
      {:stackScene true :planes [] :edges []
       :diagnostics [{:kind :conflict :message "Expected a materialized stack"}]}
      (let [compiled (walk root 0 0)]
        {:stackScene true
         :root (slot-value network root :identity)
         :planes (:planes compiled)
         :edges (:edges compiled)
         :diagnostics (:diagnostics compiled)})))))
