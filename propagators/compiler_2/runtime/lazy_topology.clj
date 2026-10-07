(ns propagators.compiler-2.runtime.lazy-topology
  "Compiler 2 body compilation adapted to flat GUR availability effects."
  (:require [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.topology-effects :as topology-effects]
            [propagators.compiler-2.runtime.declaration-effects :as core]
            [propagators.gur :as gur]))

(defn- compile-body-state
  [compile* base-network captured-state body]
  (let [body-state
        (-> captured-state
            (assoc :net (core/begin base-network)
                   :path (conj (:path captured-state) :body)
                   :props []
                   :applications []))
        [compiled-state _binding]
        (compile* body-state body)]
    compiled-state))

(defn- body-effects
  [compile* base-network captured-state body]
  (let [compiled-state
        (compile-body-state compile*
                            base-network
                            captured-state
                            body)]
    (core/result (:net compiled-state))))

(defn install-when-topology-with
  [compile* state condition-id body]
  (let [[prepared result-binding]
        (h/new-cell state :when-result)
        result-id
        (env/binding-id result-binding)
        when-key
        [:compiler-2 :when (:seed prepared) (:path prepared) result-id]
        base-network
        (:net prepared)
        effect
        (gur/when-effect
         when-key
         condition-id
         (fn []
           (body-effects compile*
                         base-network
                         prepared
                         body)))
        [_ installed]
        (core/eval-activation-result
         {:effects [effect (gur/bind-name topology-effects/topology-result-scope when-key result-id)]}
         base-network)]
    [(-> prepared
         (assoc :net installed)
         (h/add-props [(:id effect)]))
     result-binding]))

(defn install-when-topology
  [state condition-id body]
  (install-when-topology-with compiler-dispatch/default-compiler
                              state
                              condition-id
                              body))
