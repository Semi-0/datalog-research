(ns propagators.experimental.ttms-application.runtime
  "Supported callable handler. Candidate frames and all gates live in Net."
  (:require [propagators.application-dispatch :as dispatch]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.ttms-application.ports :as ports]
            [propagators.experimental.ttms-application.publication :as publication]
            [propagators.gur.flat :as gur]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]))

(defn- copy-input [network config source target]
  (let [id (gur/stable-node-id [::input config source target])
        activate (fn [inputs outputs current]
                   (if (publication/own-feedback? current config source)
                     []
                     (observation/activation inputs outputs current)))
        installer (prop/construct-propagator id ::input activate [source] [target])
        [id installed] (installer network)]
    (effects/register-props installed [id])))

(defn- validate-signature [network context signature]
  (let [id (gur/stable-node-id [::signature (:app-key context)])
        prior (when (contains? (net/net-env network) id)
                (net/network-cell-strongest network id))]
    (if (or (nil? prior) (value/nothing? prior) (= prior signature))
      (effects/seed-cell (effects/ensure-cell network id) id signature)
      (throw (ex-info "Supported candidates have incompatible call interfaces"
                      {:expected prior :actual signature})))))

(defn- candidate-effects [callable context invocation result]
  (let [plan (ports/plan context callable invocation result)
        {:keys [private key callable-id inputs outputs caller-targets]} plan
        config {:operator (:closure-id context) :key key}
        boundary (partial publication/boundary config caller-targets)
        body (:gur.flat/body callable)
        adapted (assoc callable :gur.flat/body
                       (fn [context arguments output]
                         (body (assoc context :application/output-boundary boundary) arguments output)))
        prepared (-> (effects/begin (:network context))
                     (validate-signature context (:signature plan)))
        prepared (reduce effects/ensure-cell prepared (vals private))
        prepared (if (contains? (net/net-env prepared) callable-id)
                   prepared
                   (effects/seed-cell (effects/ensure-cell prepared callable-id) callable-id adapted))
        prepared (reduce (fn [n source] (copy-input n config source (private source)))
                         prepared (distinct inputs))
        prepared (reduce (fn [n target]
                           (let [[id installed] ((publication/publisher config (private target) target) n)]
                             (effects/register-props installed [id])))
                         prepared (distinct (conj (vec outputs) result)))]
    [(effects/result prepared)
     (gur/apply-closure-effect callable-id (:invocation plan) (:output plan))]))

(defn application-effects [callable context invocation result]
  (if (datum/layer-present? callable :base)
    (let [base (publication/base callable)]
      (if (or (value/unusable? callable) (value/unusable? base))
        []
        (candidate-effects base context invocation result)))
    ((get-method dispatch/application-effects :default) callable context invocation result)))

(defmethod dispatch/application-effects propagators.network.Net
  [callable context invocation result]
  (application-effects callable context invocation result))
