(ns examples.lain.amb.incremental-learning
  "Exact existing no-good resolution, indexed and scheduled by changed sources.
  All retained worklist/index state lives in an ordinary immutable cell value."
  (:require [clojure.set :as set]
            [examples.lain.amb.builder :as builder]
            [propagators.cells.value :as value]
            [propagators.experimental.amb.contracts :as contracts]
            [propagators.experimental.amb.learning :as learning]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.amb.publication :as publication]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def empty-state
  {:knowledge #{} :by-source {} :agenda clojure.lang.PersistentQueue/EMPTY
   :queued #{} :resolutions 0})

(defn- schedule [state sources]
  (reduce (fn [state source]
            (if (contains? (:queued state) source)
              state
              (-> state (update :agenda conj source) (update :queued conj source))))
          state sources))

(defn- reindex [index nogood f]
  (reduce (fn [index source] (update index source (fnil f #{}) nogood))
          index (set (map first (:literals nogood)))))

(defn- insert [state nogood]
  (contracts/checked ::contracts/nogood nogood)
  (if (some #(learning/subsumes? % nogood) (:knowledge state))
    state
    (let [removed (set (filter #(learning/subsumes? nogood %) (:knowledge state)))
          index (reduce #(reindex %1 %2 disj) (:by-source state) removed)
          sources (into (set (map first (:literals nogood)))
                        (mapcat #(map first (:literals %))) removed)]
      (-> state
          (assoc :knowledge (conj (set/difference (:knowledge state) removed) nogood)
                 :by-source (reindex index nogood conj))
          (schedule sources)))))

(defn incorporate
  "Add one sound no-good and reach exactly the existing resolution closure.
  Unchanged/subsumed insertions do no resolution work."
  [state nogood records]
  (let [records (contracts/choices records)
        by-id (into {} (map (juxt :source identity)) records)]
    (loop [state (insert state nogood)]
      (if (empty? (:agenda state))
        state
        (let [source (peek (:agenda state))
              next-state (-> state (update :agenda pop) (update :queued disj source))]
          (if-let [record (get by-id source)]
            (let [relevant (get (:by-source state) source #{})
                  derived (learning/resolve-choice relevant record)]
              (recur (reduce insert (update next-state :resolutions inc) derived)))
            (throw (ex-info "No-good references an undeclared choice" {:source source}))))))))

(defn learn [previous certificate records context]
  (let [domains (into {} (map (juxt :source :domain)) (contracts/choices records))
        state (if (value/nothing? previous) (assoc empty-state :domains domains) previous)]
    (if (= domains (:domains state))
      (if-let [nogood (learning/certificate->nogood certificate records context)]
        (incorporate state nogood records)
        state)
      (throw (ex-info "Changing incremental learner domains is unsupported" {})))))

(defn- activation [failure choices knowledge storage]
  (publication/lift
   (fn [_ _ network]
     (let [snapshot (observation/list-snapshot network choices)]
       (if (:ready? snapshot)
         (let [certificate (observation/cell-base network failure)
               previous (observation/cell-base network storage)
               known (if (value/nothing? previous) #{} (:knowledge previous))
               sources (set (map :source (concat (:support certificate) (mapcat :context known))))
               context (observation/context network sources)
               state (learn previous certificate (:items snapshot) context)]
           (if (= previous state)
             []
             [(publication/snapshot-message network storage state)
              (publication/snapshot-message network knowledge (:knowledge state))]))
         [])))))

(defn build
  ([n] (build n builder/pythagorean?))
  ([n predicate]
   (let [built (builder/build n predicate)
         matches (filter (fn [[_ entry]]
                           (and (prop/prop? entry)
                                (= :propagators.experimental.amb.primitives/learn (prop/prop-name entry))))
                         (net/net-env (:network built)))]
     (if (= 1 (count matches))
       (let [[id original] (first matches)
             storage (ids/new-node-id)
             {:keys [failure knowledge]} (:cells built)
             ;; Locate the declared learn input other than failure, not private code.
             choice-cell (first (disj (:inputs (get (net/net-graph (:network built)) id)) failure))
             extended (nb/extend-propagator-outputs (:network built) id [storage])
             updated (net/assoc-net-prop extended id
                                        (assoc original :activate (activation failure choice-cell knowledge storage)))]
         (assoc built :network updated :incremental-state storage))
       (throw (ex-info "Expected one experimental learner" {:matches (count matches)}))))))
