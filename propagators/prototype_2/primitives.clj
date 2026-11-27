(ns propagators.prototype-2.primitives
  "Identity, bi-sync, and compound id-bridge installers."
  (:require [propagators.prototype-2.construct :as ctor]))

(defn id-f
  "Identity propagator procedure — one input, same output."
  [x]
  x)

(defn install-id
  "Pending identity propagator from `in` to `out`.

  Default dict name is `out` (output cell = name of this first dependent prop)."
  ([network in out]
   (install-id network in out out))
  ([network in out name]
   (ctor/pending-propagator-constructor network id-f [[in] out] name)))

(defn install-id-chain
  "Wire `(count cell-ids) - 1` identity propagators across adjacent cells."
  [network cell-ids]
  (reduce (fn [n [in out]]
            (install-id n in out))
          network
          (partition 2 1 cell-ids)))

(defn install-bi-sync
  "Two pending identity props: `a ──▶ b` and `b ──▶ a`."
  [network a b]
  (-> network
      (install-id a b)
      (install-id b a)))

(defn install-bi-sync-chain
  "Bi-sync between each adjacent pair of `cell-ids` (lazy both directions)."
  [network cell-ids]
  (reduce (fn [n [a b]]
            (install-bi-sync n a b))
          network
          (partition 2 1 cell-ids)))

(defn install-compound-id-bridge
  "Compound whose expand installs an identity chain from `in` to `out`
  through `n-hops` intermediate cells (default 3 hops ⇒ 2 middles).

  Smart skip: if `in` and `out` are already satisfied, expand never runs."
  ([network in out]
   (install-compound-id-bridge network in out 3))
  ([network in out n-hops]
   (let [expand
         (fn [n]
           (let [middles (mapv (fn [i] (keyword (str "bridge-" i)))
                               (range (dec n-hops)))
                 cells (into [in] (conj middles out))]
             (install-id-chain n cells)))]
     (ctor/pending-compound-constructor network expand [in] [out]))))
