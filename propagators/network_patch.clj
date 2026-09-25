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
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]))

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

(defn declaration-patch?
  [_emitter patch _network]
  (network-declaration? patch))

(defn- relate-introduced
  [network emitter introduced]
  (if (nil? emitter)
    network
    (reduce
     (fn [current id]
       (net/update-net-relationship
        current
        relationship/relate
        emitter
        (relationship/node-key [:outer] id)))
     network
     introduced)))

(defn apply-declaration-patch
  [emitter declaration network]
  (case (:op declaration)
    :network/declare-cell
    (let [id (:id declaration)
          new? (not (contains? (net/net-env network) id))
          installed (nb/ensure-cell network id)]
      [tq/empty-queue
       (relate-introduced installed emitter (if new? [id] []))])

    :network/declare-propagator
    (let [{:keys [id name inputs outputs activate]} declaration
          already? (contains? (net/net-env network) id)
          boundary-ids (vec (distinct (concat inputs outputs)))
          missing-boundary-ids
          (filterv #(not (contains? (net/net-env network) %))
                   boundary-ids)
          prepared (reduce nb/ensure-cell
                           network
                           boundary-ids)
          [_ installed]
          (if already?
            [id prepared]
            (nb/install-propagator
             prepared
             (prop/construct-propagator
              id name activate inputs outputs)))]
      [(if already? tq/empty-queue (tq/enqueue tq/empty-queue id))
       (relate-introduced
        installed
        emitter
        (cond-> missing-boundary-ids
          (not already?) (conj id)))])

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

(defn cell-patch?
  [_emitter patch _network]
  (message/message? patch))

(defn apply-cell-patch
  [_emitter cell-message network]
  (cell-evaluator/evaluate cell-message network))

(defn reject-patch
  [_emitter unknown-patch _network]
  (throw (ex-info "unknown network patch" {:patch unknown-patch})))

(defn- root-declaration-patch?
  [patch _network]
  (network-declaration? patch))

(defn- apply-root-declaration-patch
  [patch network]
  (apply-declaration-patch nil patch network))

(defn- root-cell-patch?
  [patch _network]
  (message/message? patch))

(defn- apply-root-cell-patch
  [patch network]
  (apply-cell-patch nil patch network))

(defn- reject-root-patch
  [patch _network]
  (throw (ex-info "unknown root network patch" {:patch patch})))

(def apply-root-patch
  (combinator/branch
   root-declaration-patch? apply-root-declaration-patch
   root-cell-patch? apply-root-cell-patch
   reject-root-patch))

(defn normalize-activation-return
  [ret]
  (cond
    (nil? ret)
    {:messages [] :effects []}

    (and (map? ret)
         (or (contains? ret :messages)
             (contains? ret :effects)))
    {:messages (vec (:messages ret))
     :effects (vec (:effects ret))}

    (network-declaration? ret)
    {:messages [] :effects [ret]}

    (message/message? ret)
    {:messages [ret] :effects []}

    (and (map? ret) (contains? ret :op))
    {:messages [] :effects [ret]}

    (sequential? ret)
    (reduce
     (fn [acc value]
       (let [{:keys [messages effects]}
             (normalize-activation-return value)]
         (-> acc
             (update :messages into messages)
             (update :effects into effects))))
     {:messages [] :effects []}
     ret)

    :else
    (throw (ex-info "unknown activation return" {:return ret}))))
