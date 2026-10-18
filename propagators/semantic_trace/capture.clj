(ns propagators.semantic-trace.capture
  "Partial, identity-preserving semantic observations.

  Capture is an information-domain value. Acquisition is kept behind
  `SemanticCaptureSource`; joining fragments never consults a live network."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.layered-value :as datum]
            [propagators.network :as net]
            [propagators.datastructures.support-collection :as collection]))

(defprotocol SemanticCaptureSource
  (capture-fragment [source request]))

(def ^:private capture-tag ::fragment)
(def ^:private observation-key ::observation)
(def ^:private scopes-key ::scopes)
(def ^:private support-key ::support)
(def ^:private provenance-key ::provenance)
(def ^:private retain-source-op ::retain-source)

(defn fragment?
  [candidate]
  (and (map? candidate) (= capture-tag (::type candidate))))

(defn fragment
  "Construct one immutable partial capture.

  Scope maps are keyed by stable semantic scope identities and contain public
  scene facts such as `:nodes`, `:edges`, `:values`, and diagnostics."
  [{:keys [observation scopes support provenance]}]
  (when (nil? observation)
    (throw (ex-info "Semantic capture requires an observation identity" {})))
  (let [scopes (or scopes {})
        root (or (get scopes :root) {})]
    (merge root
           {:semantic-trace/graph true
            ::type capture-tag
            observation-key observation
            scopes-key scopes
            support-key (or support #{})
            provenance-key (vec (or provenance []))})))

(defn observation-id [capture] (get capture observation-key))
(defn scopes [capture] (get capture scopes-key {}))
(defn scope [capture scope-id] (get (scopes capture) scope-id))
(defn support [capture] (get capture support-key #{}))
(defn provenance [capture] (get capture provenance-key []))

(defn retain-source-patch
  "Transient runner effect retaining the immutable source of one publication.

  The patch is consumed only by a capture-enabled session runner and is never
  installed in an immutable propagator network."
  [source captured]
  (when-not (fragment? captured)
    (throw (ex-info "Source retention requires a capture fragment"
                    {:captured captured})))
  {:op retain-source-op
   :observation (observation-id captured)
   :source source
   :capture captured})

(defn retain-source-patch?
  [candidate]
  (and (map? candidate)
       (= retain-source-op (:op candidate))))

(defn retained-source
  [patch]
  (when-not (retain-source-patch? patch)
    (throw (ex-info "Not a semantic capture source-retention patch"
                    {:patch patch})))
  {:source (:source patch)
   :capture (:capture patch)})

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

(defn capture-pair?
  "Recognize only capture-domain joins, in either operand order."
  [left right _network]
  (or (and (fragment? left)
           (or (fragment? right)
               (value/nothing? right)
               (value/contradiction? right)))
      (and (fragment? right)
           (or (fragment? left)
               (value/nothing? left)
               (value/contradiction? left)))))

(def ^:private publication-kind (object/internal-metadata-key ::kind))
(def ^:private publication-evidence-key (object/internal-metadata-key ::evidence))

(defn publication? [candidate]
  (and (net/net? candidate)
       (= ::publication (object/slot-value candidate publication-kind))))

(defn retained-evidence [publication]
  (object/slot-value publication publication-evidence-key))

(defn- join-bases [left right]
  (join (dependency/unwrap left) (dependency/unwrap right) nil))

(defn publication
  "Retain opaque supported evidence and explicitly derive its capture projection."
  [evidence]
  (let [projected (collection/strongest-value evidence join-bases)
        layers {publication-kind ::publication publication-evidence-key evidence
                :base (datum/layer-value projected :base)
                :support (datum/support-of projected)}]
    (object/compound-object
     (if (datum/layer-present? projected :premise-state)
       (assoc layers :premise-state (datum/layer-value projected :premise-state))
       layers))))

(defn- publication-evidence [candidate]
  (cond
    (publication? candidate) (retained-evidence candidate)
    (value/nothing? candidate) value/nothing
    (collection/content? candidate) candidate
    (and (datum/layer-present? candidate :base)
         (datum/layer-present? candidate :support)) (collection/content candidate)
    :else (throw (ex-info "Capture publication requires supported evidence"
                          {:candidate candidate}))))

(defn- supported-capture? [candidate]
  (and (collection/content? candidate)
       (fragment? (datum/layer-value
                   (collection/strongest-value candidate join-bases) :base))))

(defn publication-pair? [left right _network]
  (or (publication? left) (publication? right)
      (supported-capture? left) (supported-capture? right)))

(defn join-publications [left right _network]
  (cond
    (value/contradiction? left) left
    (value/contradiction? right) right
    :else (let [evidence (collection/merge-content
                         (publication-evidence left) (publication-evidence right))]
            (cond
              (and (publication? left) (= evidence (retained-evidence left))) left
              (and (publication? right) (= evidence (retained-evidence right))) right
              :else (publication evidence)))))
