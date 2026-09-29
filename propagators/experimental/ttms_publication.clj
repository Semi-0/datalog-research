(ns propagators.experimental.ttms-publication
  "Source publication transforms. Sampling and derived support remain separate."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship-observer :as observer]))

(defn next-source-datum
  "Stamp one dedicated injection cell. Epochs are local positive integers.
  The previous projection is explicit; no counter or cache exists outside Net."
  [source previous base status]
  (when-not (and (ids/node-id? source) (contains? #{:active :retracted} status))
    (throw (ex-info "Invalid source publication" {:source source :status status})))
  (let [support (datum/support-of previous)
        old (first support)
        empty? (value/nothing? previous)]
    (when-not (or empty?
                  (and (= 1 (count support)) (= source (:source old))
                       (pos-int? (:timestamp old))
                       (contains? #{:active :retracted} (:premises-status old))
                       (datum/layer-present? previous :base)))
      (throw (ex-info "Publication requires a dedicated integer-epoch source cell"
                      {:source source :previous previous})))
    (let [unchanged? (and (not empty?) (= base (datum/layer-value previous :base))
                          (= status (:premises-status old)))
          epoch (cond empty? 1
                      unchanged? (:timestamp old)
                      :else (inc (:timestamp old)))]
      {:base base :support #{{:source source :timestamp epoch :premises-status status}}})))

(defn stamp-source
  "Activation transform for at most one message to a dedicated source cell.
  Other patches are unchanged. Message lift separately packages the datum."
  [source status]
  (fn [patches _inputs _outputs network]
    (let [target? #(and (message/message? %) (= source (message/message-id %)))]
      (when (> (count (filter target? patches)) 1)
        (throw (ex-info "Source publication must emit at most one observation per activation"
                        {:source source})))
      (mapv (fn [patch]
              (if (target? patch)
                (update patch :value
                        #(next-source-datum source (net/network-cell-strongest network source)
                                            % status))
                patch)) patches))))

(defn p:observe
  "Compose a pure complete sampler with source stamping and message lift.
  Source is an explicit injection cell, not any of the cells being observed."
  [id trigger source sample]
  (fn [network]
    (let [[id installed] ((observer/p:observe-network id trigger source sample) network)
          sampler (net/network-env-lookup installed id)
          activate (prop/compose-activation
                    (prop/prop-f sampler)
                    (stamp-source source :active)
                    (message/lift-message collection/content))]
      [id (net/assoc-net-prop installed id (assoc sampler :activate activate))])))
