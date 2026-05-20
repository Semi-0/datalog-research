(ns propagators.cells.value
  "Cell contents: plain payloads, with `nothing` and `contradiction` sentinels.")

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(def nothing [:nothing])
(def contradiction [:contradiction])

(defn nothing? [x] (= nothing x))
(defn contradiction? [x] (= contradiction x))
(defn unusable? [x] (or (nothing? x) (contradiction? x)))
(defn any-unusable-values? [& values] (boolean (some unusable? values)))
(defn value-payload [x] (when-not (unusable? x) x))
(defn cell-value-equal? [a b] (= a b))
