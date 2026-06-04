(ns propagators-eager-install-test
  "Stage-1 experiments for eager installer / compile activation.

  See propagators/doc/eager-install-and-arithmetic-procedure.md."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compile :as compile :refer [*eager-install?*
                                                       *eager-install-batch?*
                                                       *eager-seed?*]]
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

(defn- layered-ctx [n sym->value expr]
  (compile/eval-layered n layered-installers sym->value expr))

(defn- procedure-object [n proc]
  (net/network-cell-value n proc))

(defn- install-extension-expr
  [proc extension extension-value]
  (list 'do
        (list 'layered/p:layered-procedure proc extension)
        (list 'seed extension extension-value)))

(defn- install-extension
  "Install one procedure extension via compile; optional manual run."
  [n proc extension extension-value & {:keys [run-props?] :or {run-props? true}}]
  (let [ctx (layered-ctx
             n
             {'proc proc
              'extension extension
              'extension-value extension-value}
             (install-extension-expr 'proc 'extension 'extension-value))
        prop (first (:props ctx))]
    (if run-props?
      {:net (nb/run-propagators (:net ctx) [prop])
       :prop prop}
      {:net (:net ctx) :prop prop})))

(defn- install-plus-procedure-manual
  [n proc base-extension prov-extension]
  (let [base (install-extension
              n proc base-extension
              (arithmetic/base-extension base/plus-closure))
        prov (install-extension
              (:net base) proc prov-extension
              (arithmetic/provenance-extension provenance/+))]
    {:net (:net prov)}))

(defn- install-plus-procedure-eager-batch
  [n proc base-extension prov-extension]
  (binding [*eager-install-batch?* true]
    (let [base-ctx (layered-ctx
                    n
                    {'proc proc
                     'extension base-extension
                     'extension-value (arithmetic/base-extension base/plus-closure)}
                    (install-extension-expr 'proc 'extension 'extension-value))
          prov-ctx (layered-ctx
                    (:net base-ctx)
                    {'proc proc
                     'extension prov-extension
                     'extension-value (arithmetic/provenance-extension provenance/+)}
                    (install-extension-expr 'proc 'extension 'extension-value))]
      {:net (:net prov-ctx)})))

(deftest h2-eager-per-install-before-seed-does-not-merge-base
  (testing "H2: eager install in do before seed leaves proc without base layer"
    (let [cells (layered-ctx net/empty-net {} '(let-cell [proc ext] proc))
          proc (compile/cell-ref cells 'proc)
          ext (compile/cell-ref cells 'ext)
          ctx (binding [*eager-install?* true
                        *eager-install-batch?* false]
                (layered-ctx
                 (:net cells)
                 {'proc proc
                  'extension ext
                  'extension-value (arithmetic/base-extension base/plus-closure)}
                 (install-extension-expr proc ext
                                         (arithmetic/base-extension base/plus-closure))))
          n (:net ctx)
          proc-value (procedure-object n proc)]
      (is (or (value/nothing? proc-value)
              (and (named/named-network? proc-value)
                   (nil? (net/network-dict-entry proc-value :base))))))))

(deftest h1-eager-batch-after-do-merges-without-manual-run
  (testing "H1/E2: batch eager at end of do merges base+provenance without run-propagators"
    (let [cells (layered-ctx net/empty-net {}
                               '(let-cell [proc base-extension prov-extension] proc))
          proc (compile/cell-ref cells 'proc)
          base-extension (compile/cell-ref cells 'base-extension)
          prov-extension (compile/cell-ref cells 'prov-extension)
          {:keys [net]} (install-plus-procedure-eager-batch
                          (:net cells) proc base-extension prov-extension)
          obj (procedure-object net proc)]
      (is (obj/slot-strongest obj :base))
      (is (obj/slot-strongest obj :provenance)))))

(deftest eager-seed-wakes-layered-procedure-neighbor
  (testing "seed eager runs p:layered-procedure after extension is seeded"
    (let [cells (layered-ctx net/empty-net {}
                               '(let-cell [proc ext] proc))
          proc (compile/cell-ref cells 'proc)
          ext (compile/cell-ref cells 'ext)
          wired (layered-ctx
                 (:net cells)
                 {'proc proc 'ext ext}
                 '(layered/p:layered-procedure proc ext))
          n (:net wired)
          prop (first (:props wired))
          seeded (binding [*eager-seed?* true]
                   (layered-ctx
                    n
                    {'ext ext
                     'fragment (arithmetic/base-extension base/plus-closure)}
                    '(seed ext fragment)))
          n' (:net seeded)]
      (is (obj/slot-strongest (procedure-object n' proc) :base))
      (is (prop/prop? (net/network-lookup-propagator n' prop))))))

(deftest eager-batch-equivalent-to-manual-run-for-plus-procedure
  (testing "E2 batch eager matches manual run-propagators on extension props"
    (let [cells (layered-ctx net/empty-net {}
                               '(let-cell [proc base-extension prov-extension] proc))
          proc (compile/cell-ref cells 'proc)
          base-extension (compile/cell-ref cells 'base-extension)
          prov-extension (compile/cell-ref cells 'prov-extension)
          manual (install-plus-procedure-manual
                  (:net cells) proc base-extension prov-extension)
          eager (install-plus-procedure-eager-batch
                 (:net cells) proc base-extension prov-extension)]
      (is (= (obj/slot-strongest (procedure-object (:net manual) proc) :base)
             (obj/slot-strongest (procedure-object (:net eager) proc) :base)))
      (is (= (obj/slot-strongest (procedure-object (:net manual) proc) :provenance)
             (obj/slot-strongest (procedure-object (:net eager) proc) :provenance))))))
