(ns graph.lain-editor.runtime.live-call
  "Choose a callable revision once per identified request arriving at a live site."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.session.connected :as connected]
            [propagators.compiler-2.session.host :as host]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.layered-value :as datum]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.gur :as gur]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn- latest-binding [network initial]
  (let [revisions (net/network-dict-entry network host/revisions-key)]
    (loop [id initial]
      (if-let [next-id (get revisions id)] (recur next-id) id))))

(defn- deliver-current [id packet result out]
  (fn [_ _ network]
    (let [current (connected/freeze network (net/network-cell-strongest network packet))
          reply (connected/freeze network (net/network-cell-strongest network result))]
      (when (and (= id (:id current)) (not= connected/pending reply))
        (let [prior (net/network-cell-strongest network out)]
          (when (or (value/nothing? prior) (not= reply (connected/payload prior)))
            [(message out (collection/content (publication/next-source-datum out prior reply :active)))]))))))

(def live-call
  (operator/propagator-operator
    {:name 'editor:live-call
     :activate
     (fn [network inputs [out] context]
       (when-not (= 2 (count inputs))
         (throw (ex-info "editor:live-call requires a callable and a request packet" {})))
       (let [[callable packet] inputs
             raw (net/network-cell-strongest network packet)
             request (connected/freeze network raw)]
         (when-not (= connected/pending request)
           (let [id (:id request)]
             (when-not (and (string? id) (seq id))
               (throw (ex-info "editor:live-call requires a request with a nonempty string :id" {})))
             (let [arg (gur/stable-node-id [::request out id])
                   result (gur/stable-node-id [::result out id])]
               (if (contains? (net/net-env network) arg)
                 (when-not (= request (connected/freeze network (net/network-cell-strongest network arg)))
                   (throw (ex-info "Conflicting reuse of live request identity" {:request id})))
                 {:effects [(gur/declare-cell arg) (gur/declare-cell result)
                            (gur/apply-closure-effect (latest-binding network callable) [context arg] result)
                            (gur/declare-prop (gur/stable-node-id [::deliver out id]) ::deliver
                                              [packet result] [out] (deliver-current id packet result out))]
                  :messages [(message arg (collection/content {:base request :support (datum/support-of raw)}))]}))))))}))
