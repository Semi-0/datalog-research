(ns differential-dataflow.graph.frontier
  "Frontier partial order for stream monotonicity checks.")

(defprotocol FrontierOrder
  (frontier-lte? [a b] "True iff a ≤ b in the runtime frontier order."))

(extend-protocol FrontierOrder
  java.lang.Long
  (frontier-lte? [a b]
    (<= (long a) (long b))))

;; Example: extend your antichain record when you have it:
;; (extend-protocol FrontierOrder
;;   my.antichain.Antichain
;;   (frontier-lte? [a b] (.lessEqual a b)))
