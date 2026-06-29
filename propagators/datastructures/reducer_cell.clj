(ns propagators.datastructures.reducer-cell
  "Slotful reducer-cell values.

  A reducer cell keeps monotone slot evidence in cell content. Its strongest
  value is a pure one-shot projection. A reducer has one network for cell-merge
  retention and one network for strongest projection."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def required-strongest-keys #{:slots :out})
(def required-merge-keys #{:content :update :out})

(defn reducer-cell
  ([id merge-net strongest-net]
   (reducer-cell id merge-net strongest-net {}))
  ([id merge-net strongest-net slots]
   {:reducer/id id
    :reducer/merge-net merge-net
    :reducer/strongest-net strongest-net
    :reducer/slots (or slots {})}))

(defn reducer-cell?
  [x]
  (and (map? x)
       (contains? x :reducer/id)
       (contains? x :reducer/merge-net)
       (contains? x :reducer/strongest-net)
       (contains? x :reducer/slots)))

(defn reducer-id [x] (:reducer/id x))
(defn merge-net [x] (:reducer/merge-net x))
(defn strongest-net [x] (:reducer/strongest-net x))
(defn reducer-slots [x] (:reducer/slots x))

(defn reducer-slot-update
  [id merge-net strongest-net slot-key value]
  (reducer-cell id merge-net strongest-net {slot-key value}))

(defn reduced-value
  [dependence epoch result]
  {:reduced/dependence dependence
   :reduced/epoch epoch
   :reduced/result result})

(defn reduced-value?
  [x]
  (and (map? x)
       (contains? x :reduced/dependence)
       (contains? x :reduced/epoch)
       (contains? x :reduced/result)))

(defn reduced-dependence [x] (:reduced/dependence x))
(defn reduced-epoch [x] (:reduced/epoch x))
(defn reduced-result [x] (:reduced/result x))

(defn- compatible-reducer?
  [a b]
  (and (= (reducer-id a) (reducer-id b))
       (= (merge-net a) (merge-net b))
       (= (strongest-net a) (strongest-net b))))

(defn- dict-cell-id
  [n k]
  (or (net/network-dict-entry n k)
      (throw (ex-info "reducer-cell net missing required dict key"
                      {:key k :dict (net/net-dict-or-empty n)}))))

(defn- maybe-dict-cell-id
  [n k]
  (net/network-dict-entry n k))

(defn- reducer-net-valid?
  [n required-keys]
  (and (net/network? n)
       (every? #(contains? (net/net-dict-or-empty n) %) required-keys)))

(defn- seed-cell
  [n id v]
  (net/assoc-net-cell n id (cell/cell v v)))

(defn- run-projection-net
  [template seed-values wake-ids]
  (let [n0 (reduce-kv seed-cell template seed-values)
        tasks (pop-inputs wake-ids (net/net-graph n0))
        run-tasks (requiring-resolve 'propagators.core/run-tasks)]
    (run-tasks tasks n0)))

(defn- run-merge-net
  [template content-slots update-slots]
  (if-not (reducer-net-valid? template required-merge-keys)
    value/contradiction
    (try
      (let [content-id (dict-cell-id template :content)
            update-id (dict-cell-id template :update)
            out-id (dict-cell-id template :out)
            after (run-projection-net template
                                      {content-id (or content-slots {})
                                       update-id (or update-slots {})
                                       out-id value/nothing}
                                      [content-id update-id])
            out (net/network-cell-strongest after out-id)]
        (if (map? out)
          out
          value/contradiction))
      (catch Exception _
        value/contradiction))))

(defn- merge-reducer-cells
  [content update]
  (cond
    (not (compatible-reducer? content update))
    value/contradiction

    :else
    (let [slots (run-merge-net (merge-net content)
                               (reducer-slots content)
                               (reducer-slots update))]
      (if (value/contradiction? slots)
        value/contradiction
        (reducer-cell (reducer-id content)
                      (merge-net content)
                      (strongest-net content)
                      slots)))))

(defn merge-content
  [content update _cell-merge-f _network]
  (cond
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (value/nothing? update) content
    (= content update) content

    (and (value/nothing? content)
         (reducer-cell? update))
    (merge-reducer-cells (reducer-cell (reducer-id update)
                                       (merge-net update)
                                       (strongest-net update)
                                       {})
                         update)

    (value/nothing? content) update

    (not (and (reducer-cell? content)
              (reducer-cell? update)))
    value/contradiction

    :else
    (merge-reducer-cells content update)))

(defn- deterministic-epoch
  [id merge-net strongest-net slots]
  [:reducer/epoch
   id
   (hash (pr-str [(some-> merge-net net/net-dict-or-empty)
                  (some->> merge-net net/net-env keys (sort-by pr-str))
                  (net/net-dict-or-empty strongest-net)
                  (sort-by pr-str (keys (net/net-env strongest-net)))
                  (sort-by (comp pr-str key) slots)]))])

(defn strongest
  [reducer-value]
  (let [template (strongest-net reducer-value)]
    (if-not (reducer-net-valid? template required-strongest-keys)
      value/contradiction
      (try
        (let [slots-id (dict-cell-id template :slots)
              out-id (dict-cell-id template :out)
              dependence-id (maybe-dict-cell-id template :dependence)
              epoch-id (maybe-dict-cell-id template :epoch)
              after (run-projection-net
                     template
                     (cond-> {slots-id (reducer-slots reducer-value)
                              out-id value/nothing}
                       dependence-id (assoc dependence-id value/nothing)
                       epoch-id (assoc epoch-id value/nothing))
                     [slots-id])
              result (net/network-cell-strongest after out-id)
              dependence (if dependence-id
                           (let [v (net/network-cell-strongest after dependence-id)]
                             (if (value/unusable? v)
                               #{[:reducer/id (reducer-id reducer-value)]}
                               v))
                           #{[:reducer/id (reducer-id reducer-value)]})
              epoch (if epoch-id
                      (let [v (net/network-cell-strongest after epoch-id)]
                        (if (value/unusable? v)
                          (deterministic-epoch (reducer-id reducer-value)
                                               (merge-net reducer-value)
                                               template
                                               (reducer-slots reducer-value))
                          v))
                      (deterministic-epoch (reducer-id reducer-value)
                                           (merge-net reducer-value)
                                           template
                                           (reducer-slots reducer-value)))]
          (if (value/contradiction? result)
            value/contradiction
            (reduced-value dependence epoch result)))
        (catch Exception _
          value/contradiction)))))

(defn p:reducer-slot
  [reducer-id merge-net strongest-net slot-key value-id reducer-cell-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network value-id)]
       (cond
         (value/contradiction? v)
         [(message reducer-cell-id value/contradiction)]

         (value/nothing? v)
         []

         :else
         [(message reducer-cell-id
                   (reducer-slot-update reducer-id
                                        merge-net
                                        strongest-net
                                        slot-key
                                        v))])))
   [value-id]
   [reducer-cell-id]))

(defn p:reduced-result
  [reduced-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network reduced-id)]
       (cond
         (value/contradiction? v)
         [(message out-id value/contradiction)]

         (value/nothing? v)
         []

         (reduced-value? v)
         [(message out-id (reduced-result v))]

         :else
         [(message out-id value/contradiction)])))
   [reduced-id]
   [out-id]))
