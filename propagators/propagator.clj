(ns propagators.propagator)

(defn- tagged? [x tag] (and (vector? x) (= tag (first x))))

(defn prop? [x] (tagged? x :prop))
(defn prop [f] [:prop f])
(defn prop-f [p] (nth p 1))
(def make-propagator prop)
(defn propagator? [x] (prop? x))
(def ->Propagator prop)
