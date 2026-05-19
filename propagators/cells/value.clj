(ns propagators.cells.value
  "CellValue lattice — `[:cell-value kind payload]`."
  (:refer-clojure :exclude [partial]))

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn cell-value? [x] (tagged? x :cell-value))
(defn cell-value [kind payload] [:cell-value kind payload])
(def ->CellValue cell-value)

(def nothing (cell-value :nothing nil))
(def contradiction (cell-value :contradiction nil))
(defn partial [v] (cell-value :partial v))
(defn complete [v] (cell-value :complete v))

(defn cv-kind [cv] (nth cv 1))
(defn cv-payload [cv] (nth cv 2))
(defn nothing? [x] (and (cell-value? x) (= :nothing (cv-kind x))))
(defn contradiction? [x] (and (cell-value? x) (= :contradiction (cv-kind x))))
(defn partial? [x] (and (cell-value? x) (= :partial (cv-kind x))))
(defn complete? [x] (and (cell-value? x) (= :complete (cv-kind x))))
(defn unusable? [x] (or (nothing? x) (contradiction? x)))
(defn any-unusable-values? [& values] (boolean (some unusable? values)))
(defn value-payload [x] (when (or (partial? x) (complete? x)) (cv-payload x)))
(defn cell-value-equal? [a b] (= a b))

(defn cell-merge
  [content update]
  (cond
    (nothing? content) update
    (nothing? update) content
    (contradiction? content) contradiction
    (contradiction? update) contradiction
    (cell-value-equal? content update) content
    :else contradiction))

(defn cell-updated? [new old] (not (cell-value-equal? new old)))
