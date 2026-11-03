(ns hooks.lain
  "Analyze the binding syntax of the specific DSL forms used in this repo."
  (:require [clj-kondo.hooks-api :as api]))

(defn- pattern-variable? [node]
  (let [value (api/sexpr node)]
    (and (symbol? value)
         (or (= '_ value)
             (.startsWith (name value) "?")
             (.startsWith (name value) "!")))))

(defn- simple-pattern? [node]
  (case (api/tag node)
    :token (let [value (api/sexpr node)]
             (or (not (symbol? value)) (pattern-variable? node)))
    (:map :vector :set) (every? simple-pattern? (:children node))
    false))

(defn- pattern-bindings [node]
  (->> (tree-seq #(seq (:children %)) :children node)
       (filter #(and (= :token (api/tag %)) (pattern-variable? %)))
       (reduce (fn [bindings n] (assoc bindings (api/sexpr n) n)) {})
       vals
       (mapcat #(vector % (api/token-node nil)))
       vec))

(defn match [{:keys [node]}]
  (let [[_ expression & clauses] (:children node)
        pairs (partition 2 clauses)]
    ;; Complex Meander operators retain normal analysis. This hook deliberately
    ;; handles only literal/destructuring patterns, never arbitrary DSL calls.
    (when (and expression (seq pairs) (even? (count clauses))
               (every? (comp simple-pattern? first) pairs))
      {:node
       (api/list-node
        (into [(api/token-node 'do) expression]
              (map (fn [[pattern body]]
                     (api/list-node [(api/token-node 'let)
                                     (api/vector-node (pattern-bindings pattern))
                                     body]))
                   pairs)))})))

(defn synth [{:keys [node]}]
  (let [[_ & args] (:children node)
        args (if (string? (some-> args first api/sexpr)) (next args) args)
        [parameters & body] args
        entries (:children parameters)]
    (when (and (= :vector (some-> parameters api/tag))
               (even? (count entries))
               (every? #(symbol? (api/sexpr %)) (take-nth 2 entries)))
      {:node (api/list-node
              (into [(api/token-node 'let) (api/vector-node entries)] body))})))
