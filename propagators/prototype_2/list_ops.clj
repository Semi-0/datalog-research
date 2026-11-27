(ns propagators.prototype-2.list-ops
  "Lazy cons/car/cdr/empty-list? compounds over Church p-lists."
  (:require [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]))

(defn install-cons-prop
  "Compound: when `a` and `b` are concrete, install Church cons and id list→`out`."
  [network a b out]
  (let [expand
        (fn [n]
          (let [{:keys [network list]} (church/install-cons n a b)]
            (prim/install-id network list out)))]
    (ctor/pending-compound-constructor network expand [a b] [out])))

(defn install-car-prop
  "Compound: when `xs` holds a Church cons, bi-sync its `a` into `out`."
  [network xs out]
  (let [expand
        (fn [n]
          (let [v (net/cell-value n xs)]
            (if-not (plist/church-cons? v)
              (do (println "car: expected Church cons, got" (pr-str v))
                  n)
              (let [sink (net/new-cell-id "cdr-sink")
                    [_ n] (church/apply-visitor n (:a v) (:b v)
                                                (church/visitor out sink))]
                n))))]
    (ctor/pending-compound-constructor network expand [xs] [out])))

(defn install-cdr-prop
  "Compound: when `xs` holds a Church cons, bi-sync its `b` into `out`."
  [network xs out]
  (let [expand
        (fn [n]
          (let [v (net/cell-value n xs)]
            (if-not (plist/church-cons? v)
              (do (println "cdr: expected Church cons, got" (pr-str v))
                  n)
              (let [sink (net/new-cell-id "car-sink")
                    [_ n] (church/apply-visitor n (:a v) (:b v)
                                                (church/visitor sink out))]
                n))))]
    (ctor/pending-compound-constructor network expand [xs] [out])))

(defn install-empty-list?-prop
  "Compound: when `xs` is a known list shape, write true/false to `out`.

  empty-list → true; church-cons → false; otherwise skip (leave unbuilt)."
  [network xs out]
  (let [expand
        (fn [n]
          (let [v (net/cell-value n xs)]
            (cond
              (plist/empty-list? v)
              (ev/write-cell n out true)

              (plist/church-cons? v)
              (ev/write-cell n out false)

              :else
              (do (println "empty-list?: expected list shape, got" (pr-str v))
                  n))))]
    (ctor/pending-compound-constructor network expand [xs] [out])))
