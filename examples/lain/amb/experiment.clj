(ns examples.lain.amb.experiment
  "Headless finite search example; instrumentation observes the normal runner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [propagators.cells.cell :as cell]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.runtime.inspection.semantic-graph :as semantic]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as state]
            [propagators.debug :as debug]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.operators :as operators]
            [propagators.experimental.amb.primitives :as primitives]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

(def source (slurp (io/resource "examples/lain/amb/pythagorean.lain")))

(defn source-for [n]
  (str/replace source "(list 1 2 3 4 5)"
               (str "(list " (str/join " " (range 1 (inc n))) ")")))

(defn compile-source [source]
  (let [session (-> (state/empty-state)
                    (extension/install-session-extension ttms/session-extension {})
                    (extension/install-session-extension operators/session-extension {}))]
    (compiler/compile-source source (:program/env session)
                             {:net (:program/net session)
                              :environment-props (:program/props session)})))

(defn cell-bases [network]
  (into [] (keep (fn [[_ entry]]
                  (if (cell/cell? entry)
                    (observation/base (cell/cell-strongest entry))
                    nil))) (net/net-env network)))

(defn topology [network]
  {:cells (count (filter cell/cell? (vals (net/net-env network))))
   :propagators (count (filter prop/prop? (vals (net/net-env network))))})

(defn choice-records [network]
  (into []
        (mapcat (fn [[id entry]]
                  (if (and (prop/prop? entry) (= ::primitives/amb (prop/prop-name entry)))
                    (keep (fn [output]
                            (let [v (observation/cell-base network output)]
                              (if (and (map? v) (contains? v :handled-request)) v nil)))
                          (:outputs (get (net/net-graph network) id)))
                    []))) (net/net-env network)))

(defn run [source]
  (let [started (System/nanoTime)
        compiled (compile-source source)
        compiled-at (System/nanoTime)
        profile (debug/activation-profile)
        observer (fn [advance]
                   (fn [state continuations]
                     (when (> (get-in @profile [:totals :calls] 0) 100000)
                       (throw (ex-info "Diagnostic activation limit" {})))
                     (advance state continuations)))
        result (binding [runner/*advance-transform* observer]
                 (debug/call-with-activation-profile
                  profile #(runner/run-network (:props compiled) (:net compiled))))
        final (:network result)
        finished (System/nanoTime)
        snapshot (observation/list-snapshot final (:cell compiled))]
    {:compiled compiled :result result
     :profile (debug/activation-profile-report profile)
     :trace (semantic/compiled-semantic-graph compiled final)
     :solution (if (:ready? snapshot) (:items snapshot) nil)
     :metrics {:compile-ms (/ (- compiled-at started) 1e6)
               :run-ms (/ (- finished compiled-at) 1e6)
               :activations (get-in @profile [:totals :calls])
               :initial (topology (:net compiled)) :final (topology final)}}))

(defn -main [& arguments]
  (let [n (if (seq arguments) (Long/parseLong (first arguments)) 5)
        {:keys [result solution metrics]} (run (source-for n))]
    (prn {:runtime-status (:status result) :solution solution :metrics metrics
          :choices (mapv #(select-keys % [:index :timestamp :status])
                         (choice-records (:network result)))})
    (if (= :failed (:status result))
      (throw (:error result))
      nil)))
