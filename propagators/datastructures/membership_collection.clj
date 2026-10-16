(ns propagators.datastructures.membership-collection
  "Versioned evidence with value requirements and independently usable membership guards.
  Retain observations; projection never gives an old payload a new source version."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as obj]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.support-collection :as legacy]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net])
  (:import [java.nio.charset StandardCharsets] [java.util UUID]))

(defn content? [x]
  (and (map? x) (contains? x :membership/observations)))

(defn- checked-support [x]
  (if (support/support? x)
    x
    (throw (ex-info "Membership evidence requires valid source premises" {:premises x}))))

(defn- field [x k]
  (if (net/net? x) (obj/slot-value x k) (get x k)))

(defn- fields [x]
  (cond
    (net/net? x) (set (obj/public-slot-keys x))
    (map? x) (set (keys x))
    :else (throw (ex-info "Membership datum must have named layers" {:datum x}))))

(defn content
  "Package one observation or a frontier-only update. Membership may require absence."
  [datum]
  (let [layers (fields datum)
        states (checked-support (or (field datum :premise-state) #{}))]
    (cond
      (= #{:premise-state} layers)
      {:membership/observations #{} :membership/states (support/join states)}
      (contains? #{#{:base :support :membership}
                   #{:base :support :membership :premise-state}} layers)
      {:membership/observations #{{:base (field datum :base)
                                   :support (checked-support (field datum :support))
                                   :membership (checked-support (field datum :membership))}}
       :membership/states (support/join states (field datum :support) (field datum :membership))}
      :else (throw (ex-info "Unsupported membership datum layers" {:layers layers})))))

(defn- normalized [x]
  (cond
    (value/nothing? x) {:membership/observations #{} :membership/states #{}}
    (content? x)
    (if (and (= #{:membership/observations :membership/states} (set (keys x)))
             (set? (:membership/observations x)))
      (let [observations (map #(content %) (:membership/observations x))]
        {:membership/observations (set (mapcat :membership/observations observations))
         :membership/states (apply support/join (checked-support (:membership/states x))
                                   (map :membership/states observations))})
      (throw (ex-info "Invalid membership evidence container" {:content x})))
    (legacy/content? x)
    (let [old (legacy/merge-content value/nothing x)]
      (normalized {:membership/observations (set (map #(assoc % :membership #{})
                                                     (:support/observations old)))
                   :membership/states (get old :support/states #{})}))
    :else (throw (ex-info "Cannot merge membership evidence with this value" {:content x}))))

(defn merge-content [a b]
  (let [a (normalized a) b (normalized b)]
    {:membership/observations (set/union (:membership/observations a) (:membership/observations b))
     :membership/states (support/join (:membership/states a) (:membership/states b))}))

(defn frontier [x] (:membership/states (normalized x)))

(defn current-observations [x]
  (let [{:membership/keys [observations states]} (normalized x)
        by-source (group-by :source states)
        matches? (fn [premise] (= [premise] (get by-source (:source premise))))]
    (into #{}
          (filter (fn [{:keys [support membership]}]
                    (and (every? #(= :active (:premises-status %)) support)
                         (support/compatible? support membership)
                         (every? matches? (concat support membership)))))
          observations)))

(defn current-content [x]
  {:membership/observations (current-observations x)
   :membership/states (frontier x)})

(defn- project-layers [layers]
  (reduce-kv
   (fn [network layer v]
     (let [id (ids/->NodeId (UUID/nameUUIDFromBytes
                            (.getBytes (str "membership/projection/" layer) StandardCharsets/UTF_8)))]
       (-> network
           (net/assoc-net-cell id (cell/cell v v))
           (net/assoc-net-node id (graph/blank-node))
           (net/assoc-net-dict-entry layer id))))
   (net/net-with-dict net/empty-net {:slot-index {}}) layers))

(defn strongest-value [x merge-base strongest-base]
  (let [current (sort-by pr-str (current-observations x))]
    (project-layers {:base (strongest-base (reduce merge-base value/nothing (map :base current)))
     :support (apply support/combine (map :support current))
     :membership (apply support/combine (map :membership current))
     :premise-state (frontier x)})))
