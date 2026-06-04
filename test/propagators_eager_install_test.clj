(ns propagators-eager-install-test
  "Stage-1 experiments for global builder queue policy.

  See propagators/doc/eager-install-and-arithmetic-procedure.md."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.builder-policy :as policy :refer [*builder-policy*]]
            [propagators.compile :as compile]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.named-network :as named]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic :as arithmetic]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]))

(def ^:private layered-installers
  {'layered/p:layered-procedure layered/p:layered-procedure})

(defmacro with-queue-policy [& body]
  `(binding [*builder-policy* :queue]
     ~@body))

(defn- layered-ctx [n sym->value expr]
  (compile/eval-layered n layered-installers sym->value expr))

(defn- procedure-object [n proc]
  (net/network-cell-value n proc))

(defn- install-extension-expr [proc extension extension-value]
  (list 'do
        (list 'layered/p:layered-procedure proc extension)
        (list 'seed extension extension-value)))

(defn- install-extension
  [n proc extension extension-value & {:keys [run-props?] :or {run-props? true}}]
  (let [ctx (layered-ctx
             n
             {'proc proc
              'extension extension
              'extension-value extension-value}
             (install-extension-expr proc extension extension-value))
        prop (first (:props ctx))]
    (if run-props?
      {:net (nb/run-propagators (:net ctx) [prop]) :prop prop}
      {:net (:net ctx) :prop prop})))

(defn- install-plus-procedure-manual [n proc base-extension prov-extension]
  (let [base (install-extension
              n proc base-extension (arithmetic/base-extension base/plus-closure))
        prov (install-extension
              (:net base) proc prov-extension
              (arithmetic/provenance-extension provenance/+))]
    {:net (:net prov)}))

(defn- install-plus-procedure-queued [n proc base-extension prov-extension]
  (with-queue-policy
    (let [base-ctx (layered-ctx
                    n
                    {'proc proc
                     'extension base-extension
                     'extension-value (arithmetic/base-extension base/plus-closure)}
                    (install-extension-expr
                     proc base-extension
                     (arithmetic/base-extension base/plus-closure)))
          prov-ctx (layered-ctx
                    (:net base-ctx)
                    {'proc proc
                     'extension prov-extension
                     'extension-value (arithmetic/provenance-extension provenance/+)}
                    (install-extension-expr
                     proc prov-extension
                     (arithmetic/provenance-extension provenance/+)))]
      {:net (:net prov-ctx)})))

(defn- proc-missing-base? [n proc]
  (let [v (procedure-object n proc)]
    (or (value/nothing? v)
        (and (named/named-network? v)
             (nil? (net/network-dict-entry v :base))))))

(deftest queue-policy-install-only-does-not-merge-before-seed
  (testing "flush after install-only runs prop against empty extension"
    (with-queue-policy
      (let [cells (layered-ctx net/empty-net {} '(let-cell [proc ext] proc))
            proc (compile/cell-ref cells 'proc)
            ext (compile/cell-ref cells 'ext)
            ctx (layered-ctx (:net cells) {'proc proc 'ext ext}
                             '(layered/p:layered-procedure proc ext))]
        (is (proc-missing-base? (:net ctx) proc))))))

(deftest queue-policy-do-flush-merges-after-seed-without-manual-run
  (testing ":queue flushes at end of do after install and seed"
    (with-queue-policy
      (let [cells (layered-ctx net/empty-net {}
                                 '(let-cell [proc base-extension prov-extension] proc))
            proc (compile/cell-ref cells 'proc)
            base-extension (compile/cell-ref cells 'base-extension)
            prov-extension (compile/cell-ref cells 'prov-extension)
            {:keys [net]} (install-plus-procedure-queued
                            (:net cells) proc base-extension prov-extension)
            obj (procedure-object net proc)]
        (is (obj/slot-strongest obj :base))
        (is (obj/slot-strongest obj :provenance))))))

(deftest queue-policy-seed-enqueues-layered-procedure-neighbor
  (testing ":queue on seed enqueues neighbors; expr flush runs merge"
    (with-queue-policy
      (let [cells (layered-ctx net/empty-net {} '(let-cell [proc ext] proc))
            proc (compile/cell-ref cells 'proc)
            ext (compile/cell-ref cells 'ext)
            wired (layered-ctx (:net cells) {'proc proc 'ext ext}
                             '(layered/p:layered-procedure proc ext))
            prop (first (:props wired))
            seeded (layered-ctx (:net wired)
                                {'ext ext
                                 'fragment (arithmetic/base-extension base/plus-closure)}
                                '(seed ext fragment))
            n' (:net seeded)]
        (is (obj/slot-strongest (procedure-object n' proc) :base))
        (is (prop/prop? (net/network-lookup-propagator n' prop)))))))

(deftest queue-policy-equivalent-to-manual-run-for-plus-procedure
  (testing ":queue flush matches manual run-propagators"
    (let [cells (layered-ctx net/empty-net {}
                               '(let-cell [proc base-extension prov-extension] proc))
          proc (compile/cell-ref cells 'proc)
          base-extension (compile/cell-ref cells 'base-extension)
          prov-extension (compile/cell-ref cells 'prov-extension)
          manual (install-plus-procedure-manual
                  (:net cells) proc base-extension prov-extension)
          queued (install-plus-procedure-queued
                  (:net cells) proc base-extension prov-extension)]
      (is (= (obj/slot-strongest (procedure-object (:net manual) proc) :base)
             (obj/slot-strongest (procedure-object (:net queued) proc) :base)))
      (is (= (obj/slot-strongest (procedure-object (:net manual) proc) :provenance)
             (obj/slot-strongest (procedure-object (:net queued) proc) :provenance))))))

(deftest lazy-policy-default-unchanged
  (testing "default :lazy does not flush installs"
    (is (policy/policy-lazy?))
    (let [cells (layered-ctx net/empty-net {} '(let-cell [proc ext] proc))
          proc (compile/cell-ref cells 'proc)
          ext (compile/cell-ref cells 'ext)
          ctx (layered-ctx (:net cells) {'proc proc 'ext ext}
                           (install-extension-expr proc ext
                                                   (arithmetic/base-extension base/plus-closure)))]
      (is (proc-missing-base? (:net ctx) proc)))))
