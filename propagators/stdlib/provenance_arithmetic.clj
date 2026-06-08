(ns propagators.stdlib.provenance-arithmetic
  "Layered arithmetic with base + provenance pre-installed on a procedure cell.

  Each of `+`, `-`, `*`, `/` takes a network and returns `{:net :proc :operator}`
  where `:operator` is the usual `propagators.stdlib.layered` installer (e.g. for
  `layered/-` in compile). Reactive / base-only bootstrap:
  `(+ n {:provenance? false})`."
  (:refer-clojure :exclude [+ - * /])
  (:require [propagators.ids :refer [new-node-id]]
            [propagators.layered :as layered]
            [propagators.network-builder :as nb]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.intensity :as intensity]
            [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.layered :as layered-ops]))

(defn- closures
  [op]
  (case op
    :+ {:base base/plus-closure
        :provenance provenance/+
        :intensity intensity/+}
    :- {:base base/minus-closure
        :provenance provenance/-
        :intensity intensity/-}
    :* {:base base/times-closure
        :provenance provenance/*
        :intensity intensity/*}
    :/ {:base base/divide-closure
        :provenance provenance//
        :intensity intensity//}
    (throw (ex-info "unknown layered arithmetic op" {:op op}))))

(defn- layered-operator
  [op proc-id]
  (case op
    :+ (layered-ops/+ proc-id)
    :- (layered-ops/- proc-id)
    :* (layered-ops/* proc-id)
    :/ (layered-ops// proc-id)))

(defn- install-closure-cell
  [n closure-value]
  (let [closure-id (new-node-id)]
    [closure-id (nb/install-cell n closure-id closure-value closure-value)]))

(defn- install-layer!
  [n proc layer-name closure-value]
  (let [[closure-id n0] (install-closure-cell n closure-value)]
    (layered/install-layered-procedure! n0 proc layer-name closure-id)))

(defn install!
  "Install base (+ optional provenance/intensity) layers on a fresh `proc` in `n`.

  Returns `{:net :proc :operator}`."
  [n op & {:keys [provenance? intensity?]
           :or {provenance? true
                intensity? false}}]
  (let [proc (new-node-id)
        {:keys [base provenance intensity]} (closures op)
        n0 (nb/install-cell n proc)
        base-layer (install-layer! n0 proc :base base)
        provenance-layer (when provenance?
                           (install-layer! (:net base-layer)
                                           proc
                                           :provenance
                                           provenance))
        intensity-layer (when intensity?
                          (install-layer! (:net (or provenance-layer base-layer))
                                          proc
                                          :intensity
                                          intensity))
        net (:net (or intensity-layer provenance-layer base-layer))]
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
