(ns examples.lain.amb.premise-selection
  "Probe procedure selection on the current compiler without changing its runtime.
  Run -M -m examples.lain.amb.premise-selection for the compatibility matrix."
  (:require [examples.lain.amb.experiment :as experiment]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.observation :as observation]
            [propagators.network :as net]))

(defn- candidate [bias premise premised?]
  (let [procedure (list 'network '(x) (list '+ 'x bias))]
    (if premised?
      (list 'premise-closure procedure premise 0)
      procedure)))

(defn source
  "Choose between x+1 and x+10 for input 5, constrained by required-output.
  :result selects computed values; :callable selects a function before calling.
  An exact output constraint is satisfiability, not a global fitness optimizer."
  [premised? selection required-output]
  (let [calls (case selection
                :result '((define result (if (= chosen 1) (plus-one 5) (plus-ten 5))))
                :callable '((define procedure (if (= chosen 1) plus-one plus-ten))
                            (define result (procedure 5)))
                (throw (ex-info "Unknown procedure-selection mode" {:selection selection})))
        declarations [(list 'define 'plus-one (candidate 1 :definition/plus-one premised?))
                      (list 'define 'plus-ten (candidate 10 :definition/plus-ten premised?))
                      '(define chosen (amb (list 1 2) repairs control))]
        search [(list 'define 'valid (list '= 'result required-output))
                '(define choices (list control))
                '(define failure (require valid))
                '(define knowledge (learn failure choices))
                '(-> (backtrack choices knowledge) repairs)
                '(list (switch chosen valid) (switch result valid))]]
    (pr-str (apply list 'let-cell '[repairs control] (concat declarations calls search)))))

(defn report
  "Summarize actual search state; a completed runner alone is not a solution."
  [run]
  (let [network (get-in run [:result :network])
        records (experiment/choice-records network)
        snapshot (observation/list-snapshot network (get-in run [:compiled :cell]))]
    {:runtime-status (get-in run [:result :status])
     :solution (:solution run)
     :ready? (:ready? snapshot)
     :choices (mapv #(select-keys % [:index :timestamp :status]) records)
     :exhausted? (boolean (some #(= {:action :unsatisfiable} %)
                               (experiment/cell-bases network)))
     :call-result-contradiction?
     (boolean (some (fn [{:keys [result-id]}]
                      (value/contradiction? (net/network-cell-strongest network result-id)))
                    (application/application-topologies network)))
     :error (some-> (get-in run [:result :error]) ex-message)}))

(defn -main [& _]
  (try
    (doseq [[label premised? selection] [[:ordinary-results false :result]
                                        [:premised-results true :result]
                                        [:ordinary-callable false :callable]
                                        [:premised-callable true :callable]]]
      (prn label (report (experiment/run (source premised? selection 15)))))
    (finally (shutdown-agents))))
