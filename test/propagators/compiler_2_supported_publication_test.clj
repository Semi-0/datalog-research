(ns propagators.compiler-2-supported-publication-test
  "Focused production publication contracts; no behavior overrides."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [propagators.compiler-2-combinator-test :as fixture]
            [propagators.compiler-2-supported-compound-if-test :as acceptance]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.declaration-effects :as effects]
            [propagators.gur.flat :as gur]
            [propagators.compiler-2.runtime.supported-application.publication :as publication]
            [propagators.compiler-2.runtime.supported-application.ports :as ports]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.premise-state :as state]))

(use-fixtures :each acceptance/bounded)

(defn publication-fixture [source-program]
  (let [loaded (fixture/session
                (str "(define source " source-program ")"
                     "(define target) (define callable (network [] 1))"))
        program @loaded
        network (:program/net program)
        resolve-id #(env/resolve-binding-id network (:program/env program) %)
        source (gur/stable-node-id [::source])
        target (gur/stable-node-id [::target])
        network (reduce effects/ensure-cell network [source target])
        operator (resolve-id 'callable)
        config {:operator operator
                :key (ports/candidate-key (net/network-cell-strongest network operator))}
        [publisher installed] ((publication/publisher config source target) network)]
    {:network installed :publisher publisher :source source :target target}))

(deftest publication-preserves-supported-bottom-and-contradiction
  (doseq [base [value/nothing value/contradiction]]
    (testing (str base)
      (let [{:keys [network publisher source target]} (publication-fixture "")
            premise {:source source :timestamp 1 :premises-status :active}
            withdrawn {:source target :timestamp 2 :premises-status :retracted}
            prepared (second
                      (core/eval-cell
                       source
                       (message/message source
                                        (collection/content {:base base :support #{premise}
                                                             :premise-state #{withdrawn}}))
                       network))
            settled (nb/run-propagators prepared [publisher])
            result (net/network-cell-strongest settled target)]
        (is (= base (publication/base result)))
        (is (= #{premise} (datum/support-of result)))
        (is (contains? (state/states-of result) withdrawn))))))

(deftest prefilled-legacy-fields-preserve-the-callers-constraint
  (doseq [source-value [1 2]]
    (let [loaded (fixture/session
                  (str "(define legacy (list 1)) (define actual (list " source-value "))"
                       "(define callable (network [] 1))"))
          program @loaded
          network (:program/net program)
          resolve-id #(env/resolve-binding-id network (:program/env program) %)
          target (resolve-id 'legacy)
          source (resolve-id 'actual)
          operator (resolve-id 'callable)
          prepared (reduce (fn [n owner]
                             (nb/seed-cell n (obj/existing-slot-cell-id n :cdr owner)
                                           :compiler-2/list-empty))
                           network [source target])
          config {:operator operator
                  :key (ports/candidate-key (net/network-cell-strongest prepared operator))}
          [publisher installed] ((publication/publisher config source target) prepared)
          settled (nb/run-propagators installed [publisher])
          field (net/network-cell-strongest settled (obj/existing-slot-cell-id settled :car target))
          [inactive withdrawn] ((publication/publisher (assoc config :key ::inactive) source target) settled)
          withdrawn (nb/run-propagators withdrawn [inactive])]
      (is (obj/accessor-network? (net/network-cell-strongest settled target)))
      (is (obj/accessor-network? (net/network-cell-strongest withdrawn target))
          "An inactive candidate cannot replace the caller's plain owner")
      (is (= field (net/network-cell-strongest withdrawn
                                              (obj/existing-slot-cell-id withdrawn :car target))))
      (if (= 1 source-value)
        (is (= 1 field))
        (is (value/contradiction? field))))))

(deftest closed-legacy-contract-survives-owner-publication-and-late-tail-completion
  (let [loaded (fixture/session
                "(define legacy (list 1))
                 (define open-end)
                 (define actual (cons 1 (cons 2 open-end)))
                 (define callable (network [] 1))")
        program @loaded
        network (:program/net program)
        resolve-id #(env/resolve-binding-id network (:program/env program) %)
        target (resolve-id 'legacy)
        source (resolve-id 'actual)
        operator (resolve-id 'callable)
        target-end (obj/existing-slot-cell-id network :cdr target)
        actual-tail (obj/existing-slot-cell-id network :cdr source)
        actual-end (obj/existing-slot-cell-id network :cdr actual-tail)
        prepared (nb/seed-cell network target-end :compiler-2/list-empty)
        config {:operator operator
                :key (ports/candidate-key (net/network-cell-strongest prepared operator))}
        [publisher installed] ((publication/publisher config source target) prepared)
        open-prefix (nb/run-propagators installed [publisher])]
    (is (obj/accessor-network? (publication/base (net/network-cell-strongest open-prefix target)))
        "A currently observed prefix does not impose a final length")
    (is (= 1 (publication/base
              (net/network-cell-strongest open-prefix
                                          (obj/existing-slot-cell-id open-prefix :car target)))))
    (let [completed (second (core/eval-cell
                             actual-end (message/message actual-end :compiler-2/list-empty)
                             open-prefix))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible return interfaces"
                           (nb/run-propagators
                            completed (nb/neighbor-propagator-ids completed actual-end)))))))
