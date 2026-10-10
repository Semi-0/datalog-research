(ns propagators.compiler-2.language.parser
  "Reader-backed source parser for compile-2 expressions.

  Functional networks use the ordinary reader and named CPS handlers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.rest-parameters :as rest-parameters])
  (:import [java.io PushbackReader StringReader]))

(def network-marker :compiler/network)
(def ^:private eof (Object.))

(declare parse-form)

(defn- parse-error [message data]
  (throw (ex-info message data)))

(defn- preprocess-source [source]
  (str/replace source #"\(\s*::(?=\s)" (str "(" network-marker)))

(defn read-form
  "Read exactly one source form."
  [source]
  (let [reader (PushbackReader. (StringReader. (preprocess-source source)))
        form (edn/read {:eof eof} reader)
        trailing (edn/read {:eof eof} reader)]
    (when (identical? eof form)
      (parse-error "empty compiler-2 source" {:source source}))
    (when-not (identical? eof trailing)
      (parse-error "compiler-2 source must contain exactly one form"
                   {:source source
                    :form form
                    :trailing trailing}))
    form))

(defn- symbol-vector [v role]
  (when-not (vector? v)
    (parse-error (str role " must be a vector") {:value v}))
  (when-not (every? symbol? v)
    (parse-error (str role " must contain only symbols") {:value v}))
  v)

(defn- body-form [forms role]
  (when-not (seq forms)
    (parse-error (str role " requires at least one body expression")
                 {:body forms}))
  (if (= 1 (count forms))
    (parse-form (first forms))
    (apply ast/sequence* (map parse-form forms))))

(defn- parse-let-cell [[names & body]]
  (ast/let-cell (symbol-vector names "let-cell bindings")
                (body-form body "let-cell")))

(defn- parse-let [[bindings & body]]
  (when-not (vector? bindings)
    (parse-error "let bindings must be a vector" {:bindings bindings}))
  (when (odd? (count bindings))
    (parse-error "let bindings must contain name/expression pairs"
                 {:bindings bindings}))
  (let [pairs (partition 2 bindings)]
    (doseq [[name _expr] pairs]
      (when-not (symbol? name)
        (parse-error "let binding names must be symbols"
                     {:binding name})))
    (ast/let* (mapv (fn [[name expr]]
                      [name (parse-form expr)])
                    pairs)
              (body-form body "let"))))

(defn- parse-functional-network [[parameters & body]]
  (when-not (sequential? parameters)
    (parse-error "network parameters must be a symbol list or vector"
                 {:parameters parameters :capability :computed-input-description}))
  (let [parameters (vec parameters)]
    (rest-parameters/parameter-spec parameters)
    (when (vector? (first body))
      (parse-error "Explicit network output vectors were removed; connect output cells and return (list ...)"
                   {:parameters parameters :body body}))
    (ast/network parameters (body-form body "network"))))

(defn- parse-define [operands]
  (let [[name expression] operands]
    (when-not (and (symbol? name) (contains? #{1 2} (count operands)))
      (parse-error "define expects a name and optional expression" {:operands operands}))
    (ast/def* name (if (= 2 (count operands)) (parse-form expression) nil))))

(defn- removed-form [form]
  (parse-error (str (first form) " was removed; use define, network, connections, and a body return")
               {:form form :syntax/error :removed-form}))

(defn- parse-if [operands]
  (when-not (= 3 (count operands))
    (parse-error "if expects condition, then expression, and else expression"
                 {:operands operands}))
  (apply ast/app (ast/sym 'if) (map parse-form operands)))

(defn- parse-when [[condition & body]]
  (when (or (nil? condition)
            (not (seq body)))
    (parse-error "when expects a condition and at least one body expression"
                 {:condition condition
                  :body body}))
  (ast/when-topology (parse-form condition)
                     (body-form body "when")))

(defn- parse-cond-form [[clauses]]
  (when-not (vector? clauses)
    (parse-error "cond expects one vector of condition/expression clauses"
                 {:clauses clauses}))
  (when (odd? (count clauses))
    (parse-error "cond clauses must contain condition/expression pairs"
                 {:clauses clauses}))
  (letfn [(build [pairs]
            (let [[[condition expr] & more] pairs]
              (cond
                (nil? condition)
                (parse-error "cond expects at least one clause"
                             {:clauses clauses})

                (= 'else condition)
                (if (seq more)
                  (parse-error "cond else clause must be last"
                               {:clauses clauses})
                  (parse-form expr))

                (seq more)
                (ast/app (ast/sym 'if)
                         (parse-form condition)
                         (parse-form expr)
                         (build more))

                :else
                (ast/app (ast/sym 'switch)
                         (parse-form expr)
                         (parse-form condition)))))]
    (build (partition 2 clauses))))

(defn- parse-application [forms]
  (apply ast/app (map parse-form forms)))

(defn parse-form
  "Normalize one reader form into compiler-2 source IR."
  [form]
  (cond
    (ast/ast-node? form)
    (ast/ast form)

    (seq? form)
    (case (first form)
      let (parse-let (rest form))
      let-cell (parse-let-cell (rest form))
      (:compiler/network cell-expr def-net def-constraint def def-cell def-cells compound)
      (removed-form form)
      network (parse-functional-network (rest form))
      define (parse-define (rest form))
      if (parse-if (rest form))
      when (parse-when (rest form))
      cond (parse-cond-form (rest form))
      do (removed-form form)
      (parse-application form))

    (vector? form)
    (mapv parse-form form)

    (symbol? form)
    (ast/sym form)

    :else
    (ast/lit form)))

(defn parse-string
  "Parse one source string into compiler-2 source IR."
  [source]
  (parse-form (read-form source)))

(def parse parse-string)
