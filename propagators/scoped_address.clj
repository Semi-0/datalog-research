(ns propagators.scoped-address
  "Shared scoped cell address shape for dispatchable environment refs.")

(def dispatch-tags #{:env/scope :env/ref :env/cell-ref})

(defn scope-ref [scope] [:env/scope scope])
(defn name-ref [scope name] [:env/ref scope name])
(defn cell-ref [scope local-id] [:env/cell-ref scope local-id])

(defn address?
  [x]
  (and (vector? x)
       (contains? dispatch-tags (first x))
       (> (count x) 1)))

(defn address-scope
  [x]
  (when (address? x)
    (second x)))
