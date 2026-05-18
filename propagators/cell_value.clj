(ns propagators.cell-value
  "Four-value cell lattice: nothing, contradiction, partial, complete."
  (:refer-clojure :exclude [partial]))

(defrecord CellValue [kind value])

(defn cell-value?
  [x]
  (instance? CellValue x))

(def nothing (->CellValue :nothing nil))
(def contradiction (->CellValue :contradiction nil))

(defn partial [v]
  (->CellValue :partial v))

(defn complete [v]
  (->CellValue :complete v))

(defn- kind [x]
  (when (cell-value? x) (:kind x)))

(defn nothing?
  [x]
  (= :nothing (kind x)))

(defn contradiction?
  [x]
  (= :contradiction (kind x)))

(defn partial?
  [x]
  (= :partial (kind x)))

(defn complete?
  [x]
  (= :complete (kind x)))

(defn unusable?
  "True for `nothing` or `contradiction`."
  [x]
  (or (nothing? x) (contradiction? x)))

(defn any-unusable-values?
  "True if any `CellValue` in `values` is `nothing` or `contradiction`."
  [& values]
  (boolean (some unusable? values)))

(defn value-payload
  "For partial / complete values, returns `v`; otherwise `nil`."
  [x]
  (when (or (partial? x) (complete? x))
    (:value x)))

(defn cell-value-equal?
  [a b]
  (= a b))
