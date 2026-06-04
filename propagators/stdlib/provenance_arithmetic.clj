(ns propagators.stdlib.provenance-arithmetic
  "Layered arithmetic with base + provenance pre-installed on a procedure cell.

  Each of `+`, `-`, `*`, `/` takes a network and returns `{:net :proc :operator}`
  where `:operator` is the usual `propagators.stdlib.layered` installer (e.g. for
  `layered/-` in compile). Reactive / base-only bootstrap:
  `(+ n {:provenance? false})`."
  (:refer-clojure :exclude [+ - * /])
  (:require [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.arithmetic :as arithmetic]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.intensity :as intensity]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]))

(defn- fragments
  [op]
  (case op
    :+ {:base (arithmetic/base-extension base/plus-closure)
        :prov (arithmetic/provenance-extension provenance/+)
        :intensity (arithmetic/intensity-extension intensity/+)}
    :- {:base (arithmetic/minus-base-extension)
        :prov (arithmetic/minus-provenance-extension)
        :intensity (arithmetic/minus-intensity-extension)}
    :* {:base (arithmetic/times-base-extension)
        :prov (arithmetic/times-provenance-extension)
        :intensity (arithmetic/times-intensity-extension)}
    :/ {:base (arithmetic/divide-base-extension)
        :prov (arithmetic/divide-provenance-extension)
        :intensity (arithmetic/divide-intensity-extension)}
    (throw (ex-info "unknown layered arithmetic op" {:op op}))))

(defn- layered-operator
  [op proc-id]
  (case op
    :+ (layered-ops/+ proc-id)
    :- (layered-ops/- proc-id)
    :* (layered-ops/* proc-id)
    :/ (layered-ops// proc-id)))

(defn install!
  "Install base (+ optional provenance/intensity) layers on a fresh `proc` in `n`.

  Returns `{:net :proc :operator}`."
  [n op & {:keys [provenance? intensity?]
           :or {provenance? true
                intensity? false}}]
  (let [proc (new-node-id)
        base-extension (new-node-id)
        prov-extension (new-node-id)
        intensity-extension (new-node-id)
        {:keys [base prov intensity]} (fragments op)
        n0 (reduce nb/install-cell n [proc
                                       base-extension
                                       prov-extension
                                       intensity-extension])
        {:keys [net]} (layered/install-layered-procedure!
                       n0
                       proc
                       base-extension
                       base)
        {:keys [net]} (if provenance?
                        (layered/install-layered-procedure!
                         net
                         proc
                         prov-extension
                         prov)
                        {:net net})
        {:keys [net]} (if intensity?
                        (layered/install-layered-procedure!
                         net
                         proc
                         intensity-extension
                         intensity)
                        {:net net})]
    {:net net
     :proc proc
     :operator (layered-operator op proc)}))

(defn +
  ([n] (+ n {}))
  ([n opts] (install! n :+ opts)))

(defn -
  ([n] (- n {}))
  ([n opts] (install! n :- opts)))

(defn *
  ([n] (* n {}))
  ([n opts] (install! n :* opts)))

(defn /
  ([n] (/ n {}))
  ([n opts] (install! n :/ opts)))
