(ns examples.lain.live-environment.source
  "Finite source inspection at the explicit file-loading boundary."
  (:require [clojure.set :as set]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.runtime.session.program.source :as source]))

(declare free-names)

(defn- local-definitions [expression]
  (case (ast/type expression)
    :def #{(ast/name expression)}
    :sequence (into #{} (mapcat local-definitions) (ast/body expression))
    #{}))

(defn- free-body [expression bound]
  (free-names expression (into bound (local-definitions expression))))

(defn free-names [expression bound]
  (cond
    (nil? expression) #{}
    (vector? expression) (apply set/union #{} (map #(free-names % bound) expression))
    :else
    (case (ast/type expression)
      :literal #{}
      :symbol (if (contains? bound (ast/name expression)) #{} #{(ast/name expression)})
      :sequence (apply set/union #{} (map #(free-body % bound) (ast/body expression)))
      :def (free-names (ast/body expression) (conj bound (ast/name expression)))
      :apply (apply set/union (free-names (ast/operator expression) bound)
                    (map #(free-names % bound) (ast/args expression)))
      :network (free-body (ast/body expression)
                          (into bound (remove #{'&} (ast/inputs expression))))
      :let-cell (free-body (ast/body expression) (into bound (ast/names expression)))
      :let (let [local (into bound (map first (ast/bindings expression)))]
             (apply set/union (free-body (ast/body expression) local)
                    (map #(free-names (second %) local) (ast/bindings expression))))
      :when-topology (set/union (free-names (ast/condition expression) bound)
                                (free-body (ast/body expression) bound))
      (throw (ex-info "Unsupported file source expression" {:kind (ast/type expression)})))))

(defn inspect [text]
  (let [;; A discarded reader sentinel permits an empty/comment-only file.
        forms (pop (source/read-source-forms (str text "\nnil")))
        expressions (mapv parser/parse-form forms)
        names (mapv ast/name (filter #(= :def (ast/type %)) expressions))]
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "Duplicate file export" {:exports names})))
    {:forms forms :exports (set names)
     ;; Functional network bodies use the compiler's existing implicit return route.
     :imports (apply set/union #{'->} (map #(free-names % (set names)) expressions))}))
