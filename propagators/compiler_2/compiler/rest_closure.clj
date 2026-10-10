(ns propagators.compiler-2.compiler.rest-closure
  "Compose rest declaration adaptation around the ordinary closure constructor."
  (:require [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.rest-parameters :as parameters]
            [propagators.compiler-2.runtime.rest-application :as rest-application]
            [propagators.network :as net]
            [propagators.compiler-2.runtime.declaration-effects :as nb]))

(defn declaration
  [declare-fixed]
  (fn declare-closure
    ([state inputs output body]
     (declare-closure state nil inputs output body))
    ([state name inputs output body]
     (let [{:keys [required rest] :as signature} (parameters/parameter-spec inputs)]
       (if (nil? rest)
         (declare-fixed state name inputs output body)
         (do
           (when (some? output)
             (throw (ex-info "Rest parameters require an implicit-return closure"
                             {:inputs inputs :output output})))
           (let [[declared binding]
                 (declare-fixed state name (conj required rest) output body)
                 id (env/binding-id binding)
                 callable (net/network-cell-strongest (:net declared) id)]
             [(update declared :net nb/seed-cell id
                      (rest-application/rest-callable callable signature))
              binding])))))))
