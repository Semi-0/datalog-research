(ns propagators.gur.subenv.frame
  "Frame construction and contextual apply/recur for lexical sub-env GUR."
  (:require [propagators.boundary :as boundary]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.output :as output]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def recursive-closure-tag :gur/recursive-closure?)

(defn application-key
  [closure-id arg-ids out-id]
  [:gur/application closure-id (vec arg-ids) out-id])

(defn- copy-boundary-cell
  [child-net parent-net id]
  (cond
    (contains? (net/net-env child-net) id)
    child-net

    (contains? (net/net-env parent-net) id)
    (nb/install-cell child-net
                     id
                     (net/network-cell-content parent-net id)
                     (net/network-cell-strongest parent-net id))

    :else
    (nb/ensure-cell child-net id)))

(defn- boundary-accessor-parent-ids
  [parent-net outer-ids]
  (->> outer-ids
       (filter #(contains? (net/net-env parent-net) %))
       (mapcat #(scoped-slot/accessor-parent-cell-ids
                 parent-net
                 (net/network-cell-strongest parent-net %)))
       (filter #(contains? (net/net-env parent-net) %))
       distinct
       vec))

(defn install-frame-boundary
  [parent-net child-net input-ids output-ids]
  (let [outer-ids (vec (distinct (concat input-ids output-ids)))
        accessor-parent-ids (boundary-accessor-parent-ids parent-net outer-ids)]
    (-> (reduce #(copy-boundary-cell %1 parent-net %2) child-net outer-ids)
        (as-> n (reduce #(copy-boundary-cell %1 parent-net %2)
                        n
                        accessor-parent-ids))
        (boundary/create-boundary-outputs output-ids)
        (boundary/create-boundary-inputs input-ids))))

(defn recursive-closure
  [name body-fn]
  {recursive-closure-tag true
   :gur/name name
   :gur/body body-fn})

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (get x :gur/body))))

(declare frame-key-value)

(defn- frame-key
  [closure arg-values frame-id]
  [:gur/frame (:gur/name closure) (mapv frame-key-value arg-values) frame-id])

(defn- frame-key-value
  [v]
  (if (and (net/net? v) (obj/accessor-network? v))
    [:accessor-source
     (mapv (fn [[slot-key slot-value]]
             [slot-key (frame-key-value slot-value)])
           (sort-by (comp pr-str key)
                    (obj/accessor-source-slots v)))]
    v))

(defn applied?
  [frame-net frame-key]
  (true? (net/network-dict-entry frame-net [:gur/applied frame-key])))

(defn mark-applied
  [frame-net frame-key]
  (net/assoc-net-dict-entry frame-net [:gur/applied frame-key] true))

(defn- applied-closure-key
  [frame-key]
  [:gur/applied-closure frame-key])

(defn accumulate-applied-closure
  [frame-net frame-key closure]
  (if (net/network-dict-entry frame-net (applied-closure-key frame-key))
    frame-net
    (let [id (ids/new-node-id)]
      (-> frame-net
          (nb/install-cell id closure closure)
          (net/assoc-net-dict-entry (applied-closure-key frame-key) id)))))

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(defn strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- frame-base-net
  [current-frame scope]
  (env/extend-env (if (net/net? current-frame)
                    current-frame
                    net/empty-net)
                  scope))

(defn- bind-frame-args
  [frame-net inner-args]
  (reduce (fn [n [i id]]
            (env/bind n [:arg i] id))
          frame-net
          (map-indexed vector inner-args)))

(defn- frame-context
  [closure self-id frame-id scope arg-values]
  {:closure closure
   :self-id self-id
   :frame-id frame-id
   :scope scope
   :arg-values (vec arg-values)})

(defn- build-frame-net
  [parent-net frame-id closure arg-ids out-id arg-values current-frame]
  (let [scope (frame-key closure arg-values frame-id)
        base (frame-base-net current-frame scope)
        with-boundary (install-frame-boundary parent-net base arg-ids [out-id])
        inner-args (mapv (partial net/lookup-inner-in with-boundary) arg-ids)
        out-inner (net/lookup-inner-out with-boundary out-id)
        self-id (ids/new-node-id)
        frame-net (-> with-boundary
                      (nb/install-cell self-id closure closure)
                      (env/bind :self self-id)
                      (env/bind :out out-inner)
                      (bind-frame-args inner-args))
        body-result (normalize-body-result
                     ((:gur/body closure)
                      (frame-context closure
                                     self-id
                                     frame-id
                                     scope
                                     arg-values)
                      frame-net
                      inner-args
                      out-inner))]
    (-> (:net body-result)
        (accumulate-applied-closure scope closure)
        (mark-applied scope)
        (queue/queue-child-props (:prop-ids body-result)))))

(defn- missing-closure-input?
  [closure arg-values]
  (or (value/unusable? closure)
      (apply value/any-unusable-values? arg-values)))

(defn- already-applied?
  [current-frame closure arg-values frame-id]
  (and (net/net? current-frame)
       (applied? current-frame (frame-key closure arg-values frame-id))))

(defn- apply-closure-messages
  [parent-net frame-id closure-id arg-ids out-id]
  (let [closure (strongest-or-nothing parent-net closure-id)
        arg-values (mapv #(strongest-or-nothing parent-net %) arg-ids)
        current-frame (strongest-or-nothing parent-net frame-id)]
    (cond
      (missing-closure-input? closure arg-values)
      []

      (not (recursive-closure? closure))
      [(message out-id value/contradiction)]

      (already-applied? current-frame closure arg-values frame-id)
      []

      :else
      [(message frame-id
                (build-frame-net parent-net
                                 frame-id
                                 closure
                                 arg-ids
                                 out-id
                                 arg-values
                                 current-frame))])))

(defn p:apply-closure
  [closure-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        frame-id (ids/new-node-id)
        inputs (into [closure-id] arg-ids)]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network (conj inputs out-id frame-id))
            activate
            (fn [_inputs _outputs parent-net]
              (apply-closure-messages parent-net
                                      frame-id
                                      closure-id
                                      arg-ids
                                      out-id))
            [apply-prop n1] ((prop/construct-propagator activate
                                                        inputs
                                                        [frame-id])
                             n0)
            [publish-prop n2] ((scoped-slot/p:publish-child-accessors frame-id)
                               n1)
            [runner-prop n3] ((output/p:run-subenv-frame frame-id [out-id]) n2)]
        [[apply-prop publish-prop runner-prop]
         (net/assoc-net-dict-entry n3
                                   (application-key closure-id arg-ids out-id)
                                   frame-id)]))))

(defn p:contextual-apply
  [closure-id arg-ids out-id]
  (p:apply-closure closure-id arg-ids out-id))

(defn p:contextual-recur
  [self-id arg-ids out-id]
  (p:apply-closure self-id arg-ids out-id))

(defn contextual-api
  [ctx]
  {:apply (fn [network closure-id arg-ids out-id]
            ((p:contextual-apply closure-id arg-ids out-id) network))
   :recur (fn [network arg-ids out-id]
            ((p:contextual-recur (:self-id ctx) arg-ids out-id) network))})

(defn def-recursive
  "Build a recursive closure with contextual apply/recur bound to each frame.

  `closure` receives a map containing `:apply`, `:recur`, `:network`, `:args`,
  `:out`, and `:ctx`, and returns either a network or
  `{:net frame-net :prop-ids [...]}`.
  "
  [name closure]
  (recursive-closure
   name
   (fn [ctx frame-net arg-ids out-id]
     (closure (assoc (contextual-api ctx)
                     :ctx ctx
                     :network frame-net
                     :args arg-ids
                     :out out-id)))))
