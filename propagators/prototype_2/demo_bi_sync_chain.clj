(ns propagators.prototype-2.demo-bi-sync-chain
  "Run an 8-cell bi-sync chain with debug logging.

  From the repository root:

      clojure -M -m propagators.prototype-2.demo-bi-sync-chain

  Optional seed argument (default 88):

      clojure -M -m propagators.prototype-2.demo-bi-sync-chain 42"
  (:require [propagators.prototype-2.core :as p2])
  (:gen-class))

(def cells [:c0 :c1 :c2 :c3 :c4 :c5 :c6 :c7])

(defn run-demo
  ([] (run-demo 88))
  ([seed]
   (let [network (p2/install-bi-sync-chain p2/empty-network cells)
         [tasks network] (p2/tell-debug network :c0 seed)
         network (p2/run-tasks-debug network tasks)
         props (into []
                     (keep (fn [[id v]]
                             (when (p2/propagator-f? v) id))
                           (p2/network-value network)))
         built (filterv #(p2/topology-built? (p2/lookup-node network %)) props)]
     (println)
     (println "══ summary ══")
     (doseq [c cells]
       (println " " c "=" (p2/cell-value network c)))
     (println " props" (count props)
              "built" (count built)
              "pending" (- (count props) (count built)))
     (println " chain-complete :c7?" (p2/chain-complete? network :c7))
     network)))

(defn -main
  [& args]
  (let [seed (if (seq args)
               (Long/parseLong (str (first args)))
               88)]
    (run-demo seed)
    (shutdown-agents)))
