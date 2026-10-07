(ns propagators.compiler-2.runtime.topology-effects
  "Idempotent compiler topology declaration, without network reconstruction."
  (:require [propagators.compiler-2.runtime.declaration-effects :as declarations]
            [propagators.gur :as gur]
            [propagators.network :as net]))

(def topology-result-scope [:compiler-2 :topology-results])
(defn topology-result-ids
  "Explicit non-value result declarations."
  [network]
  (set (vals (get (net/network-dict-entry network gur/name-bindings-key)
                  topology-result-scope {}))))

(def declaration-scope :compiler-2/runtime-declarations)

(defn declared? [network declaration-key]
  (boolean (get-in (net/network-dict-entry network gur/name-bindings-key)
                   [declaration-scope declaration-key])))

(defn declare-once [network declaration-key marker-id build]
  (if (declared? network declaration-key)
    {:effects [] :messages []}
    (let [{compiled :net prop-ids :props} (build (declarations/begin network))
          recorded (declarations/register-props compiled prop-ids)]
      (update (declarations/result recorded) :effects conj
              (gur/bind-name declaration-scope declaration-key marker-id)))))
