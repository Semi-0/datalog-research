(ns propagators.network-patch
  "Generic immutable-Net declaration patches.

  Patch construction is separate from scheduling. Applying a declaration
  returns tasks plus the updated Net."
  (:require [propagators.cell-evaluator :as cell-evaluator]
            [propagators.combinator :as combinator]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def name-bindings-key [:network :name-bindings])

(defn declare-cell [id]
  {:op :network/declare-cell
   :id id})

(defn declare-propagator
  ([id inputs outputs activate]
   (declare-propagator id :propagator/anonymous inputs outputs activate))
  ([id name inputs outputs activate]
   {:op :network/declare-propagator
    :id id
    :name name
    :inputs (vec inputs)
    :outputs (vec outputs)
    :activate activate}))

(defn bind-name [scope name id]
  {:op :network/bind-name
   :scope scope
   :name name
   :id id})

(defn network-declaration?
  [patch]
  (contains? #{:network/declare-cell
               :network/declare-propagator
               :network/bind-name}
             (:op patch)))

(defn- apply-network-declaration
  [declaration network]
  (case (:op declaration)
    :network/declare-cell
    [tq/empty-queue (nb/ensure-cell network (:id declaration))]

    :network/declare-propagator
    (let [{:keys [id name inputs outputs activate]} declaration
          already? (contains? (net/net-env network) id)
          prepared (reduce nb/ensure-cell
                           network
                           (distinct (concat inputs outputs)))
          [_ installed]
          (if already?
            [id prepared]
            (nb/install-propagator
             prepared
             (prop/construct-propagator
              id name activate inputs outputs)))]
      [(if already? tq/empty-queue (tq/enqueue tq/empty-queue id))
       installed])

    :network/bind-name
    [tq/empty-queue
     (net/update-net-dict-entry
      network
      name-bindings-key
      #(assoc-in (or % {})
                 [(:scope declaration) (:name declaration)]
                 (:id declaration)))]

    (throw (ex-info "unknown network declaration patch"
                    {:patch declaration}))))

(defn- apply-cell-message
  [cell-message network]
  (cell-evaluator/evaluate cell-message network))

(defn- reject-unknown-patch
  [unknown-patch _network]
  (throw (ex-info "unknown network patch" {:patch unknown-patch})))

(def apply-patch
  (combinator/branch
   network-declaration? apply-network-declaration
   message/message? apply-cell-message
   reject-unknown-patch))

(defn normalize-activation-return
  [ret]
  (cond
    (nil? ret)
    {:messages [] :effects [] :semantic-relationships nil}

    (and (map? ret)
         (or (contains? ret :messages)
             (contains? ret :effects)
             (contains? ret :semantic-relationships)))
    {:messages (vec (:messages ret))
     :effects (vec (:effects ret))
     :semantic-relationships (:semantic-relationships ret)}

    (network-declaration? ret)
    {:messages [] :effects [ret] :semantic-relationships nil}

    (message/message? ret)
    {:messages [ret] :effects [] :semantic-relationships nil}

    (sequential? ret)
    (reduce
     (fn [acc value]
       (let [{:keys [messages effects semantic-relationships]}
             (normalize-activation-return value)]
         (cond-> (-> acc
                     (update :messages into messages)
                     (update :effects into effects))
           semantic-relationships
           (assoc :semantic-relationships semantic-relationships))))
     {:messages [] :effects [] :semantic-relationships nil}
     ret)

    :else
    (throw (ex-info "unknown activation return" {:return ret}))))
