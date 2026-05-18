(ns propagators.propagator
  "Propagator: `id` plus `activate` function.")

(defrecord Propagator [f])

(defn propagator?
  [x]
  (instance? Propagator x))

(defn make-propagator
  [f]
  (->Propagator  f))
