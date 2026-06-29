(ns propagators.datastructures.reducer-cell
  "Slotful reducer-cell values.

  A reducer cell keeps monotone slot evidence in cell content. Its strongest
  value is a pure one-shot projection produced by a reducer network."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def required-reducer-keys #{:slots :out})

(defn reducer-cell
  ([id reducer-net]
   (reducer-cell id reducer-net {}))
  ([id reducer-net slots]
   {:reducer/id id
    :reducer/net reducer-net
    :reducer/slots (or slots {})}))

(defn reducer-cell?
  [x]
  (and (map? x)
       (contains? x :reducer/id)
       (contains? x :reducer/net)
       (contains? x :reducer/slots)))

(defn reducer-id [x] (:reducer/id x))
(defn reducer-net [x] (:reducer/net x))
(defn reducer-slots [x] (:reducer/slots x))

(defn reducer-slot-update
  [id reducer-net slot-key value]
  (reducer-cell id reducer-net {slot-key value}))

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
       (= (reducer-net a) (reducer-net b))))

(defn- merge-slot-maps
  [content update cell-merge-f network]
  (reduce-kv
   (fn [slots slot-key update-value]
     (if (contains? slots slot-key)
       (let [merged (cell-merge-f (get slots slot-key) update-value network)]
         (if (value/contradiction? merged)
           (reduced (assoc slots slot-key value/contradiction))
           (assoc slots slot-key merged)))
       (assoc slots slot-key update-value)))
   (or content {})
   (or update {})))

(defn merge-content
  [content update cell-merge-f network]
  (cond
    (value/nothing? content) update
    (value/nothing? update) content
    (value/contradiction? content) value/contradiction
    (value/contradiction? update) value/contradiction
    (= content update) content

    (not (and (reducer-cell? content)
              (reducer-cell? update)))
    value/contradiction

    (not (compatible-reducer? content update))
    value/contradiction

    :else
    (let [slots (merge-slot-maps (reducer-slots content)
                                 (reducer-slots update)
                                 cell-merge-f
                                 network)]
      (if (some value/contradiction? (vals slots))
        value/contradiction
        (reducer-cell (reducer-id content)
                      (reducer-net content)
                      slots)))))

(defn- dict-cell-id
  [n k]
  (or (net/network-dict-entry n k)
      (throw (ex-info "reducer-cell net missing required dict key"
                      {:key k :dict (net/net-dict-or-empty n)}))))

(defn- maybe-dict-cell-id
  [n k]
  (net/network-dict-entry n k))

(defn- reducer-net-valid?
  [n]
  (and (net/network? n)
       (every? #(contains? (net/net-dict-or-empty n) %) required-reducer-keys)))

(defn- seed-cell
  [n id v]
  (net/assoc-net-cell n id (cell/cell v v)))

(defn- deterministic-epoch
  [id reducer-net slots]
  [:reducer/epoch
   id
   (hash (pr-str [(net/net-dict-or-empty reducer-net)
                  (sort-by pr-str (keys (net/net-env reducer-net)))
                  (sort-by (comp pr-str key) slots)]))])

(defn strongest
  [reducer-value]
  (let [template (reducer-net reducer-value)]
    (if-not (reducer-net-valid? template)
      value/contradiction
      (try
        (let [slots-id (dict-cell-id template :slots)
              out-id (dict-cell-id template :out)
              dependence-id (maybe-dict-cell-id template :dependence)
              epoch-id (maybe-dict-cell-id template :epoch)
              n0 (cond-> template
                   true (seed-cell slots-id (reducer-slots reducer-value))
                   true (seed-cell out-id value/nothing)
                   dependence-id (seed-cell dependence-id value/nothing)
                   epoch-id (seed-cell epoch-id value/nothing))
              tasks (pop-inputs [slots-id] (net/net-graph n0))
              run-tasks (requiring-resolve 'propagators.core/run-tasks)
              after (run-tasks tasks n0)
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
                                               template
                                               (reducer-slots reducer-value))
                          v))
                      (deterministic-epoch (reducer-id reducer-value)
                                           template
                                           (reducer-slots reducer-value)))]
          (if (value/contradiction? result)
            value/contradiction
            (reduced-value dependence epoch result)))
        (catch Exception _
          value/contradiction)))))

(defn p:reducer-slot
  [reducer-id reducer-net slot-key value-id reducer-cell-id]
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
                   (reducer-slot-update reducer-id reducer-net slot-key v))])))
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
