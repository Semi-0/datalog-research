(ns gx10-benchmark.telemetry
  "Never infer uncached prefill or useful completion speed from wall time.")

(defn- rate [tokens milliseconds]
  (when (and (number? tokens) (not (neg? tokens))
             (number? milliseconds) (pos? milliseconds))
    (/ (* 1000.0 tokens) milliseconds)))

(defn request-metrics [request]
  (let [{:keys [finish input-tokens cached-input-tokens output-tokens prefill-ms decode-ms]} request
        eligible? (and (= :stop finish) (not (:error request)))]
    {:request (:id request)
     :completed? (boolean eligible?)
     :decode-tokens-per-second (when eligible? (rate output-tokens decode-ms))
     :prefill-tokens-per-second (when eligible? (rate input-tokens prefill-ms))
     :prefill-basis (cond
                      (nil? cached-input-tokens) :cache-unknown
                      (pos? cached-input-tokens) :includes-cache-hits
                      :else :uncached)
     :raw request}))
