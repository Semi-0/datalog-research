(ns differential-dataflow.multiset
  "Multiset = vector of rows `[datum mult]` (Python `Collection`). One row `[[0 1] 1]`
  must be wrapped as `[[[0 1] 1]]` so `seq` sees one row, not `[0 1]` and `1` separately.")

(defn multiset-map
  [f pairs]
  (mapv (fn [[datum multiplicity]] [(f datum) multiplicity]) pairs))

(defn multiset-filter
  [pred pairs]
  (filterv (fn [[datum _]] (boolean (pred datum))) pairs))

(defn multiset-negate
  [pairs]
  (mapv (fn [[datum multiplicity]] [datum (- multiplicity)]) pairs))

(def multiset-append into)

(defn multiset-consolidate
  [pairs]
  (letfn [(gather-counts-by-datum [counts-by-datum remaining-pairs]
            (if (empty? remaining-pairs)
              counts-by-datum
              (let [first-pair (first remaining-pairs)
                    datum (first first-pair)
                    multiplicity (second first-pair)
                    prev (get counts-by-datum datum 0)]
                (recur (assoc counts-by-datum datum (+ prev multiplicity))
                       (rest remaining-pairs)))))]
    (let [by-datum (gather-counts-by-datum {} pairs)
          nonzero-pairs (filterv (fn [[_ multiplicity]] (not= multiplicity 0))
                                 by-datum)]
      (vec (sort nonzero-pairs)))))

(defn multiset-subtract-ms
  "Multiset difference: treat each vector as [[v m] ...], merge with sign."
  [a b]
  (multiset-consolidate (multiset-append a (multiset-negate b))))
