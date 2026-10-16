(ns propagators.application-dispatch-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.application-dispatch :as dispatch]
            [propagators.cells.value :as value]
            [propagators.gur.flat :as flat]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(deftype TestCallable [result])

(deftest default-method-retains-flat-application-contract
  (let [context {:network net/empty-net :closure-id :procedure :app-key :call}
        closure (flat/recursive-closure :test (fn [ctx args out] [ctx args out]))
        applied (dispatch/application-effects closure context [:arg] :out)
        existing (net/assoc-net-dict-entry net/empty-net flat/name-bindings-key
                                           {flat/frame-scope {:call :out}})]
    (doseq [pending [value/nothing value/contradiction]]
      (is (= [] (dispatch/application-effects pending context [] :out))))
    (is (= [(message :out value/contradiction)]
           (dispatch/application-effects 42 context [] :out)))
    (is (= [context [:arg] :out] (second applied)))
    (is (= [] (dispatch/application-effects closure (assoc context :network existing) [] :out)))))

(deftest handler-registration-receives-whole-value-and-is-removable
  (let [context {:network net/empty-net}
        callable (TestCallable. 17)
        prior (get (methods dispatch/application-effects) TestCallable)]
    (try
      (.addMethod dispatch/application-effects TestCallable
                  (fn [whole ctx args out]
                    [{:whole whole :context ctx :arguments args :output out}]))
      (is (= [{:whole callable :context context :arguments [:a] :output :out}]
             (dispatch/application-effects callable context [:a] :out)))
      (finally
        (remove-method dispatch/application-effects TestCallable)
        (when prior (.addMethod dispatch/application-effects TestCallable prior))))
    (is (= prior (get (methods dispatch/application-effects) TestCallable)))))
