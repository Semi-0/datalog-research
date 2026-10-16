(ns propagators.experimental.ttms-application.ports
  "Candidate identities and private invocation ports; no evidence conversion."
  (:require [propagators.compiler-2.model.closure-value :as closure]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.gur.flat :as gur]))

(defn candidate-key [callable]
  (if (application/compiler-callable? callable)
    [(get callable application/declaration-id-key)
     (get callable application/captured-environment-key)]
    (throw (ex-info "Supported application requires an inspectable compiler callable"
                    {:callable callable}))))

(defn- closure-ports [declaration arguments output]
  (let [n (count (closure/closure-inputs declaration))
        out (closure/closure-output declaration)
        outputs (cond (symbol? out) [out]
                      (vector? out) out
                      (nil? out) []
                      :else (throw (ex-info "Unsupported output interface" {:output out})))
        implicit? (closure/implicit-return-output? out)
        expected (+ n (if implicit? 0 (count outputs)))]
    (if (= expected (count arguments))
      {:inputs (subvec arguments 0 n)
       :outputs (if implicit? [output] (subvec arguments n))
       :signature [n (if implicit? :return :explicit) (count outputs)]}
      (throw (ex-info "Supported closure application has invalid arity"
                      {:expected expected :actual (count arguments)})))))

(defn plan [context callable invocation output]
  (let [arguments (subvec (vec invocation) 1)
        declaration (application/callable-declaration callable)
        ports (if (closure/closure-info? declaration)
                (closure-ports declaration arguments output)
                (let [call (operator/operator-call callable arguments output (first invocation))]
                  (assoc call :signature [(count (:inputs call)) :primitive (count (:outputs call))])))
        key (candidate-key callable)
        private (into {} (map (fn [id]
                               [id (gur/stable-node-id [(:app-key context) key :port id])]))
                      (distinct (conj arguments output)))]
    (assoc ports :key key :private private
           :caller-targets (into {} (map (fn [[public local]] [local public])) private)
           :callable-id (gur/stable-node-id [(:app-key context) key :callable])
           :invocation (into [(first invocation)] (map private arguments))
           :output (private output))))
