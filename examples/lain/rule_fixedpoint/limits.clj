(ns examples.lain.rule-fixedpoint.limits
  "Shared cooperative budget, including nested solver activations. Not inference state."
  (:require [examples.lain.amb.search-budget :as budget]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.runner :as runner]))

(def defaults {:milliseconds 30000 :activations 1000000 :proofs 10000 :env-entries 20000})

(defn transform [scope]
  (fn [advance]
    ((budget/transform scope)
     (fn [state continuations]
       (if (> (count (net/net-env (:network state))) (get-in scope [:limits :env-entries]))
         (throw (ex-info "Rule experiment Net size budget reached" {:type ::exhausted}))
         (advance state continuations))))))

(defn evaluator [scope relations]
  (let [guard ((transform scope) (fn [_ _] nil))]
    (fn [id network continuations]
      (guard {:network network :tasks (tq/into-queue [id])} {})
      (let [entries (count (net/net-env network))
            proofs (reduce + 0 (map #(count (:experiment/proof-evidence (net/network-cell-content network %))) relations))]
        (if (or (> entries (get-in scope [:limits :env-entries]))
                (> proofs (get-in scope [:limits :proofs])))
          ((:fail continuations) (ex-info "Rule experiment size budget reached"
                                        {:type ::exhausted :entries entries :proofs proofs}))
          (runner/evaluate-propagator id network continuations))))))

(defn exhausted? [error]
  (if (budget/exhausted? error)
    true
    (loop [current error]
      (if (nil? current) false
          (if (= ::exhausted (:type (ex-data current))) true
              (recur (.getCause ^Throwable current)))))))
