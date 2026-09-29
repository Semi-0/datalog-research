(ns propagators.compiler-2.runtime.rest-application
  "Adapt rest calls to ordinary fixed-arity applications using list topology."
  (:require [propagators.combinator :as combinator]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.topology-effects :as topology]
            [propagators.gur :as gur]
            [propagators.network-builder :as nb]))

(def rest-callable-key :compiler-2/rest-callable)

(defn rest-callable? [candidate & _]
  (and (application/compiler-callable? candidate)
       (contains? candidate rest-callable-key)))

(defn- pack-arguments
  "Use inbound proxies so cons access cannot write back to caller cells."
  [network application-id arguments]
  (let [locals (mapv #(gur/stable-node-id [application-id :rest-argument %])
                     (range (count arguments)))
        root (gur/stable-node-id [application-id :rest-list])
        inbound
        (reduce
         (fn [state [i source target]]
           (let [[id network]
                 (((application/concrete-boundary application-id :inbound [:rest i])
                   source target)
                  (reduce nb/ensure-cell (:net state) [source target]))]
             (-> state (assoc :net network) (update :props conj id))))
         {:net network :props [] :seed [:compiler-2/rest application-id] :path []}
         (map vector (range) arguments locals))
        [declared _] (basis/declare-list inbound (mapv env/cell-binding locals) root)]
    (assoc declared :root root)))

(defn- caller-argument-effects
  "Keep the public application trace about caller arguments, not the packed list."
  [effects application-id arguments]
  (let [argument-binding?
        (fn [{:keys [op scope name]}]
          (and (= :network/bind-name op)
               (= application/application-name-scope scope)
               (= application-id (first name))
               (vector? (second name))
               (= :argument (first (second name)))))]
    (into (filterv (complement argument-binding?) effects)
          (map-indexed
           (fn [i id]
             (gur/bind-name application/application-name-scope
                            [application-id [:argument i]] id))
           arguments))))

(declare apply-callable)

(defn apply-rest-callable
  [wrapped context invocation-ids result-id]
  (let [{:keys [callable parameters]} (get wrapped rest-callable-key)
        invocation (vec invocation-ids)
        required-count (count (:required parameters))]
    (when (< (count invocation) (inc required-count))
      (throw (ex-info "Rest closure application has invalid arity"
                      {:parameters parameters :invocation-ids invocation})))
    (let [arguments (subvec invocation 1)
          packed (pack-arguments (:network context) (:app-key context)
                                 (subvec arguments required-count))
          adapted (conj (subvec invocation 0 (inc required-count)) (:root packed))
          result (apply-callable callable (assoc context :network (:net packed))
                                 adapted result-id)
          packing (topology/network-diff (:network context) (:net packed)
                                         (:props packed))]
      {:effects (caller-argument-effects
                 (into (:effects packing) (:effects result))
                 (:app-key context) arguments)
       :messages (into (:messages packing) (:messages result))})))

(defn- ordinary-callable? [candidate & _]
  (application/compiler-callable? candidate))

(defn- apply-ordinary-callable [callable context invocation-ids result-id]
  ((:gur.flat/body callable) context invocation-ids result-id))

(defn- reject-callable [candidate & _]
  (throw (ex-info "Unsupported rest application delegate" {:callable candidate})))

(def apply-callable
  (combinator/branch
   rest-callable? apply-rest-callable
   ordinary-callable? apply-ordinary-callable
   reject-callable))

(defn rest-callable
  "Wrap an ordinary callable without changing its fixed-arity declaration."
  [callable parameters]
  (let [wrapped (assoc callable rest-callable-key
                       {:callable callable :parameters parameters})]
    (assoc wrapped :gur.flat/body (partial apply-callable wrapped))))
