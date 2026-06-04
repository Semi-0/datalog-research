(ns propagators.builder-policy
  "Global propagator network construction policy.

  Controls whether new installs and seeds only wire topology, enqueue
  scheduler tasks, or run immediately. See doc/eager-install-and-arithmetic-procedure.md.")

(def ^:dynamic *builder-policy* :lazy)

(defn policy-lazy?
  []
  (= *builder-policy* :lazy))

(defn policy-queue?
  []
  (= *builder-policy* :queue))
