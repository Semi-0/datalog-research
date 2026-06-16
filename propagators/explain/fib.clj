(ns propagators.explain.fib
  "Evidence-backed explanation harness for recursive Fibonacci propagation."
  (:require [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.datastructures.named-network :as named]
            [propagators.explain.facts :as facts]
            [propagators.explain.llm :as llm]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.recursive :as recursive]))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- seed-output
  [n out-id v]
  (nb/seed-cell n out-id v))

(defn- eval-dsl
  [n sym->value expr]
  (compile/eval-layered n (compile/default-installers) sym->value expr))

(defn- eval-and-run
  [n sym->value expr]
  (let [{:keys [net props] :as ctx} (eval-dsl n sym->value expr)]
    (assoc ctx :net (nb/run-propagators net props))))

(defn- fib-frame-step
  [branch-builds]
  (fn [{:keys [self-id acc-id input-ids output-ids network]}]
    (let [[n-id] input-ids
          [out-id] output-ids
          n-value (strongest network n-id)
          frame [:fib n-value]]
      (cond
        (value/unusable? n-value)
        {:network network}

        (not (and (integer? n-value) (not (neg? n-value))))
        {:network (seed-output network out-id value/contradiction)
         :frame-fragment
         (recursive/frame-fragment frame {:input n-value
                                          [:status :contradiction] true})}

        (<= n-value 1)
        (let [ctx (eval-dsl
                   network
                   {'n n-id
                    'out out-id}
                   '(do
                      (let-cell [base?]
                        (seed base? true)
                        (prop/switch n base? out))))
              n2 (nb/run-propagators (:net ctx) (:props ctx))]
          {:network n2
           :frame-fragment
           (recursive/frame-fragment frame {:input n-value
                                            :output n-value
                                            [:status :done] true})})

        :else
        (do
          (swap! branch-builds inc)
          (let [ctx (eval-and-run
                     network
                     {'self self-id
                      'acc acc-id
                      'out out-id
                      'n-minus-1 (dec n-value)
                      'n-minus-2 (- n-value 2)}
                     '(do
                        (let-cell [n-1 n-2 fib-1 fib-2]
                          (seed n-1 n-minus-1)
                          (seed n-2 n-minus-2)
                          (recursive/p:accumulating-recursive-compound self n-1 acc fib-1)
                          (recursive/p:accumulating-recursive-compound self n-2 acc fib-2))))
                n6 (:net ctx)
                fib-1-id (compile/cell-ref ctx 'fib-1)
                fib-2-id (compile/cell-ref ctx 'fib-2)
                fib-1 (strongest n6 fib-1-id)
                fib-2 (strongest n6 fib-2-id)
                fragment (recursive/frame-fragment
                          frame
                          {:input n-value
                           [:child 0] [:fib (dec n-value)]
                           [:child 1] [:fib (- n-value 2)]
                           :combine :+
                           [:status :expanded] true})]
            (cond
              (or (value/contradiction? fib-1)
                  (value/contradiction? fib-2))
              {:network (seed-output n6 out-id value/contradiction)
               :frame-fragment fragment}

              (or (value/unusable? fib-1)
                  (value/unusable? fib-2))
              {:network n6
               :frame-fragment fragment}

              :else
              {:network (:net (eval-and-run
                               n6
                               {'fib-1 fib-1-id
                                'fib-2 fib-2-id
                                'out out-id}
                               '(prop/+ fib-1 fib-2 out)))
               :frame-fragment
               (named/join
                fragment
                (recursive/frame-fragment frame {:output (+ fib-1 fib-2)
                                                  [:status :done] true}))})))))))

(defn fib-closure
  ([] (fib-closure {}))
  ([opts]
   (let [branch-builds (or (:branch-builds opts) (atom 0))]
     (recursive/recursive-closure (fib-frame-step branch-builds)
                                  (select-keys opts [:max-depth])))))

(defn run-fib
  "Run accumulating recursive Fibonacci and return the final value plus frame net."
  ([n]
   (run-fib n {}))
  ([n opts]
   (let [closure-value (fib-closure opts)
         ctx (eval-and-run
              net/empty-net
              {'closure-value closure-value
               'n-value n}
              '(do
                 (let-cell [fib acc n out]
                   (seed fib closure-value)
                   (seed n n-value)
                   (recursive/p:accumulating-recursive-compound fib n acc out))))
         acc-id (compile/cell-ref ctx 'acc)
         out-id (compile/cell-ref ctx 'out)
         n' (:net ctx)]
     {:net n'
      :acc-id acc-id
      :out-id out-id
      :frame-net (strongest n' acc-id)
      :value (strongest n' out-id)})))

(def default-question
  "What happened during Fibonacci recursion?")

(defn explain-fib
  [{:keys [n model question include-topology? call-llm?]
    :or {n 5
         question default-question
         call-llm? true}}]
  (let [{:keys [net frame-net value] :as run} (run-fib n)
        evidence (facts/fib-evidence {:n n
                                      :value value
                                      :frame-net frame-net
                                      :topology-net net
                                      :include-topology? include-topology?})
        prompt (facts/prompt {:question question
                              :evidence evidence})
        llm-result (when call-llm?
                     (llm/explain-with-ollama prompt {:model (or model llm/default-model)}))]
    (cond-> {:value value
             :facts evidence
             :evidence evidence
             :prompt prompt
             :run run
             :explanation (:explanation llm-result)}
      (and llm-result (not (:ok? llm-result)))
      (assoc :error (:error llm-result)
             :llm llm-result)

      (and llm-result (:ok? llm-result))
      (assoc :llm llm-result))))
