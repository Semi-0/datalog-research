(ns propagators.semantic-trace.capture
  "Partial, identity-preserving semantic observations.

  Capture is an information-domain value. Acquisition is kept behind
  `SemanticCaptureSource`; joining fragments never consults a live network."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]))

(defprotocol SemanticCaptureSource
  (capture-fragment [source request]))

(def ^:private capture-tag ::fragment)
(def ^:private observation-key ::observation)
(def ^:private scopes-key ::scopes)
(def ^:private support-key ::support)
(def ^:private provenance-key ::provenance)

(defn fragment? [candidate]
  (and (object/accessor-network? candidate)
       (= capture-tag (object/accessor-source-slot-value candidate ::type))))

(defn fragment
  "Construct an immutable capture using the public compound interface."
  [{:keys [observation scopes support provenance]}]
  (when (nil? observation)
    (throw (ex-info "Semantic capture requires an observation identity" {})))
  (object/as-accessor-network
   {::type capture-tag
    observation-key observation
    scopes-key (or scopes {})
    support-key (or support #{})
    provenance-key (vec (or provenance []))}))

(defn observation-id [capture] (object/accessor-source-slot-value capture observation-key))
(defn scopes [capture] (object/accessor-source-slot-value capture scopes-key))
(defn scope [capture scope-id] (get (scopes capture) scope-id))
(defn support [capture] (object/accessor-source-slot-value capture support-key))
(defn provenance [capture] (object/accessor-source-slot-value capture provenance-key))

(defn equivalent?
  "Compare capture facts independently of compound accessor topology."
  [left right]
  (if (and (fragment? left) (fragment? right))
    (and (= (observation-id left) (observation-id right))
         (= (scopes left) (scopes right))
         (= (support left) (support right))
         (= (provenance left) (provenance right)))
    (= left right)))

(defn semantic-graph?
  "Consumer predicate for captures and legacy partial graph recordings."
  [candidate]
  (or (fragment? candidate)
      (and (map? candidate) (true? (:semantic-trace/graph candidate)))))

(defn root-graph [candidate]
  (if (fragment? candidate) (scope candidate :root) candidate))

(defn update-scope
  "Apply a graph operation to one captured scope without losing capture facts."
  [captured scope-id update-graph]
  (when-not (fragment? captured)
    (throw (ex-info "Scope updates require a capture fragment"
                    {:captured captured})))
  (fragment
   {:observation (observation-id captured)
    :scopes (update (scopes captured) scope-id update-graph)
    :support (support captured)
    :provenance (provenance captured)}))

(defn with-diagnostic
  ([capture diagnostic]
   (with-diagnostic capture :root diagnostic))
  ([capture scope-id diagnostic]
   (fragment
    {:observation (observation-id capture)
     :scopes (update (scopes capture) scope-id
                     (fn [scope]
                       (update (or scope {:semantic-trace/graph true
                                          :nodes {} :node-kinds {}
                                          :values {} :edges []})
                               :semantic-capture/diagnostics
                               (fnil conj [])
                               diagnostic)))
     :support (support capture)
     :provenance (provenance capture)})))

(defn- conflict
  [path left right]
  (value/contradiction-with-provenance
   #{{:semantic-capture/error :incompatible-evidence
      :semantic-capture/path path
      :semantic-capture/evidence #{left right}}}))

(defn- conflict-values
  [candidate]
  (if (value/contradiction? candidate)
    (into #{}
          (mapcat (fn [reason]
                    (or (:semantic-capture/evidence reason) #{})))
          (value/contradiction-provenance candidate))
    #{candidate}))

(defn- join-conflict
  [path left right]
  (value/contradiction-with-provenance
   #{{:semantic-capture/error :incompatible-evidence
      :semantic-capture/path path
      :semantic-capture/evidence (set/union (conflict-values left)
                                            (conflict-values right))}}))

(declare join-information)

(defn- join-maps
  [path left right]
  (reduce
   (fn [result key]
     (assoc result key
            (join-information (conj path key)
                              (get left key value/nothing)
                              (get right key value/nothing))))
   {}
   (set/union (set (keys left)) (set (keys right)))))

(defn join-information
  "ACI join for capture-owned facts. Conflicts remain at the smallest fact."
  [path left right]
  (cond
    (= left right) left
    (value/nothing? left) right
    (value/nothing? right) left
    (or (value/contradiction? left) (value/contradiction? right))
    (join-conflict path left right)
    (and (= :edges (peek path))
         (sequential? left)
         (sequential? right))
    (vec (sort-by pr-str (set (concat left right))))
    (and (= :semantic-capture/diagnostics (peek path))
         (sequential? left)
         (sequential? right))
    (vec (sort-by pr-str (set (concat left right))))
    (and (some #{:values :contents} path)
         (not (contains? #{:values :contents} (peek path))))
    (conflict path left right)
    (and (map? left) (map? right)) (join-maps path left right)
    (and (set? left) (set? right)) (set/union left right)
    :else (conflict path left right)))

(defn join
  "Join contributions from one original frozen observation."
  [left right _network]
  (cond
    (= left right) left
    (value/nothing? left) right
    (value/nothing? right) left
    (value/contradiction? left) left
    (value/contradiction? right) right
    (not (and (fragment? left) (fragment? right)))
    (conflict [] left right)
    (not= (observation-id left) (observation-id right))
    (conflict [::observation] (observation-id left) (observation-id right))
    :else
    (fragment
     {:observation (observation-id left)
      :scopes (join-information [::scopes] (scopes left) (scopes right))
      :support (set/union (support left) (support right))
      :provenance (vec (sort-by pr-str
                                (set (concat (provenance left)
                                             (provenance right)))))})))
