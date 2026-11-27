(ns propagators.prototype-2.demo-cons-visitor
  "Church-style cons with visitor-network input and bi-sync slots.

  From the repository root:

      clojure -M -m propagators.prototype-2.demo-cons-visitor"
  (:require [propagators.prototype-2.core :as p2])
  (:gen-class))

(defn run-demo
  []
  (println "══ Church cons: visitor-network + bi-sync a/b ══")
  (let [{:keys [network visitor a b]} (p2/install-cons p2/empty-network :a :b :my-pair)
        [t1 network] (p2/tell-debug network a "left")
        [t2 network] (p2/tell-debug network b "right")
        network (p2/run-tasks-debug network (into t1 t2))
        [t-car network] (p2/install-car network visitor :car-out)
        _ (println)
        _ (println "══ apply car visitor (compound expand) ══")
        network (p2/run-tasks-debug network t-car)
        [t-cdr network] (p2/install-cdr network a b :cdr-out)
        _ (println)
        _ (println "══ apply cdr visitor (re-apply) ══")
        network (p2/run-tasks-debug network t-cdr)]
    (println)
    (println "══ summary ══")
    (println " a       =" (p2/cell-value network a))
    (println " b       =" (p2/cell-value network b))
    (println " car-out =" (p2/cell-value network :car-out))
    (println " cdr-out =" (p2/cell-value network :cdr-out))
    network))

(defn -main
  [& _args]
  (run-demo)
  (shutdown-agents))
