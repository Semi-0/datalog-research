(ns propagators.compiler-2.operators.call-graph-syntax
  "Potential direct call sites. Lexical binders prevent false outer resolution."
  (:require [propagators.compiler-2.language.ast :as ast]))

(defn- declaration-names [expr]
  (case (ast/type expr)
    :def [(ast/name expr)]
    :sequence (mapcat declaration-names (ast/body expr))
    (:literal :symbol :apply :let :let-cell :when-topology :network
     :compound :def-net :def-constraint) []
    (throw (ex-info "Unsupported call-graph syntax" {:type (ast/type expr)}))))

(defn- children [expr bound]
  (let [child (fn [expr scope] [expr scope])]
    (case (ast/type expr)
      :apply (mapv #(child % bound) (into [(ast/operator expr)] (ast/args expr)))
      :sequence (let [scope (into bound (declaration-names expr))]
                  (mapv #(child % scope) (ast/body expr)))
      :let-cell [(child (ast/body expr) (into bound (ast/names expr)))]
      :let (let [bindings (ast/bindings expr)]
             (conj (mapv #(child (second %) bound) bindings)
                   (child (ast/body expr) (into bound (map first bindings)))))
      :when-topology [(child (ast/condition expr) bound) (child (ast/body expr) bound)]
      :def (if-let [body (ast/body expr)] [(child body bound)] [])
      (:network :compound :def-net :def-constraint :literal :symbol) []
      (throw (ex-info "Unsupported call-graph syntax" {:type (ast/type expr)})))))

(defn call-sites [expr bound]
  (letfn [(walk [expr path bound]
            (let [expr (ast/ast expr)
                  operator (ast/operator expr)
                  sites (if (= :apply (ast/type expr))
                          [{:path path :operator operator
                            :bound? (contains? bound (ast/name operator))
                            :operator-label (if (= :symbol (ast/type operator))
                                              (str (ast/name operator))
                                              (str (ast/type operator)))}]
                          [])]
              (into sites (mapcat (fn [[index [child scope]]]
                                    (walk child (conj path index) scope)))
                    (map-indexed vector (children expr bound)))))]
    (walk expr [] bound)))
