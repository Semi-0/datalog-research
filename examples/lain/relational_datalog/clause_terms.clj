(ns examples.lain.relational-datalog.clause-terms
  "Bounded Lain clauses over compound terms. No changes to production semantics."
  (:require [examples.lain.relational-datalog.proof-experiment :as proofs]
            [examples.lain.relational-datalog.proof-operators :as operators]
            [examples.lain.relational-datalog.runtime :as runtime]
            [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def directory "examples/lain/relational_datalog/")

(def edge-pair
  (ttms/scalar-operator
   ::edge-pair (prop/concrete-primitive-propagator ::edge-pair vector)))

(defn configuration [mode]
  (case mode
    :compound {:file "compound_clause.lain"
               :readers [['from :from] ['to :to]]}
    :list {:file "linked_clause.lain"
           :readers [['car :car] ['cdr :cdr]]}
    (throw (ex-info "Unknown clause-term mode" {:mode mode}))))

(defn term [mode pair]
  (if (and (vector? pair) (= 2 (count pair)))
    (let [[a b] pair]
      (case mode
        :compound (obj/compound-object {:from a :to b})
        :list (obj/compound-object
               {:car a :cdr (obj/compound-object {:car b :cdr :nil})})
        (throw (ex-info "Unknown clause-term mode" {:mode mode}))))
    (throw (ex-info "Expected a two-element term vector" {:value pair}))))

(defn session-extension [mode]
  (let [{:keys [readers]} (configuration mode)]
    (extension/extension-bundle
     {:id [::terms mode]
      :bindings (into (vec (extension/extension-bindings operators/session-extension))
                      (conj (mapv (fn [[name slot]] [name (ttms/slot-operator slot)]) readers)
                            ['edge-pair edge-pair]))
      :effects []})))

(defn initialize [mode]
  (let [{:keys [file]} (configuration mode)
        sources (zipmap [:ab :bc :ad :dc] (repeatedly 4 ids/new-node-id))
        bottom (collection/content {:premise-state #{}})
        initial (reduce #(nb/install-cell %1 %2 bottom value/nothing)
                        net/empty-net (vals sources))
        bindings (into (vec (basis/default-bindings))
                       (concat (extension/extension-bindings (session-extension mode))
                               (map (fn [[key id]] [(symbol (name key)) (env/cell-binding id)])
                                    sources)))
        compiled (compiler/compile-expr-with-bindings
                  (parser/parse-string (slurp (str directory file))) bindings {:net initial})
        result (runtime/completed
                (runtime/run (:net compiled) (:props compiled) proofs/run-options))]
    (assoc result :sources sources :cell (:cell compiled) :mode mode)))

(defn updates
  "Construct input terms once; reuse these immutable values for exact replay."
  [mode rows]
  (mapv (fn [[key epoch status pair]]
          [key epoch status
           (if (value/nothing? pair) pair (term mode pair))])
        rows))

(defn publish
  "Publish explicit timestamped compound values, then run to quiescence."
  [state rows]
  (assoc (proofs/publish state rows) :mode (:mode state)))

(defn demo [mode]
  (let [initial (initialize mode)
        active (publish initial (updates mode proofs/active-updates))
        withdrawn (publish active [[:bc 2 :retracted value/nothing]
                                   [:dc 2 :retracted value/nothing]])
        restored (publish withdrawn (updates mode [[:bc 3 :active [:b :c]]]))]
    (mapv (fn [[stage state]] {:mode mode :stage stage :result (proofs/summary state)})
          [[:pending initial] [:active active] [:withdrawn withdrawn] [:restored restored]])))

(defn -main [& modes]
  (try
    (doseq [mode (if (seq modes) (map keyword modes) [:compound :list])
            stage (demo mode)]
      (prn stage))
    (shutdown-agents)
    (catch Throwable error
      (.printStackTrace error)
      (shutdown-agents)
      (System/exit 1))))
