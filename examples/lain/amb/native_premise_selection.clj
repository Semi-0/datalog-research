(ns examples.lain.amb.native-premise-selection
  "Select a premise-supported procedure through ordinary call syntax.
  Run -M -m examples.lain.amb.native-premise-selection."
  (:require [clojure.java.io :as io]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.compound-object :as obj]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.operators :as amb]
            [propagators.experimental.ttms-application :as application]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.network-builder :as nb]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(def source (slurp (io/resource "examples/lain/amb/native_premise_selection.lain")))

(defn prepare
  ([] (prepare source))
  ([source]
   (let [state (reduce (fn [s ext] (extension/install-session-extension s ext {}))
                       (session/empty-state)
                       [primitives/session-extension amb/session-extension application/session-extension])
         sources (into {} (map (fn [name] [name (ids/new-node-id)]))
                       '[definition-one definition-ten])
         network (reduce (fn [n [name id]]
                           (let [v (collection/content
                                    {:base name :support #{{:source id :timestamp 1
                                                           :premises-status :active}}})]
                             (nb/install-cell n id v (collection/strongest-value v (fn [_ b] b)))))
                         (:program/net state) sources)
         bindings (mapv (fn [[name id]] [name (env/cell-binding id)]) sources)
         installed (extension/install-extension-bindings
                    network (:program/env state) (ids/new-node-id) bindings)
         compiled (compiler/compile-source source (:env installed)
                                           {:net (:net installed)
                                            :environment-props (into (:program/props state)
                                                                     (:props installed))})]
     {:compiled compiled :sources sources})))

(defn run
  ([] (run source))
  ([source]
   (let [{:keys [compiled] :as prepared} (prepare source)
         result (runner/run-network (:props compiled) (:net compiled))
         network (:network result)
         root (:cell compiled)
         snapshot (if (or (obj/existing-slot-cell-id network :car root)
                          (= :compiler-2/list-empty (net/network-cell-strongest network root)))
                    (observation/list-snapshot network root)
                    {:value (observation/base (net/network-cell-strongest network root))})]
     (assoc prepared :result result :snapshot snapshot))))

(defn -main [& _]
  (try
    (let [run (run)]
      (prn {:status (get-in run [:result :status])
            :solution (get-in run [:snapshot :items])
            :ready? (get-in run [:snapshot :ready?])})
      (if-let [error (get-in run [:result :error])] (throw error) nil))
    (finally (shutdown-agents))))
