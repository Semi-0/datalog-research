(ns benchmark.stable-distinct)
(defn stable-distinct-by [key-fn coll]
  (second (reduce (fn [[seen result] item]
                    (let [key (key-fn item)]
                      (if (contains? seen key) [seen result] [(conj seen key) (conj result item)])))
                  [#{} []] coll)))
