(ns propagators.compiler-2.experimental.constructor-receipts
  "Development instrumentation; observes real declarations without activating them."
  (:require [propagators.propagator :as prop]))

(defn capture [build]
  (let [constructor prop/construct-propagator
        receipts (atom [])
        thread (Thread/currentThread)]
    (letfn [(observe [& args]
              (let [installer (apply constructor args)]
                (if (and (= thread (Thread/currentThread)) (= 5 (count args)))
                  (fn [network]
                    (let [[id installed :as result] (installer network)
                          [_ name activation inputs outputs] args]
                      (swap! receipts conj
                             {:id id :name name :source-class (class activation)
                              :installed-activation (get-in installed [:env id :activate])
                              :inputs (set inputs) :outputs (set outputs)})
                      result))
                  installer)))]
      (let [result (with-redefs [prop/construct-propagator observe] (build))]
        {:result result :receipts (vec @receipts)}))))

(defn facts [receipts]
  (into #{} (mapcat (fn [{:keys [id source-class installed-activation inputs outputs]}]
                      (concat [[[:env id] :constructor/source-class source-class]
                               [[:env id] :constructor/activation installed-activation]]
                              (map #(vector [:env id] :constructor/input %) inputs)
                              (map #(vector [:env id] :constructor/output %) outputs)))) receipts))
