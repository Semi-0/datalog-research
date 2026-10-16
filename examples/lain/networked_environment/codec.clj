(ns examples.lain.networked-environment.codec
  "Explicit, non-executing wire projection. No reader-selected code loading."
  (:require [clojure.edn :as edn]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop])
  (:import [java.util IdentityHashMap UUID Date]
           [java.time Instant]))

(def constructors
  {'propagators.network.Net net/map->Net
   'propagators.graph.Node graph/map->Node
   'propagators.ids.NodeId ids/map->NodeId
   'propagators.cells.cell.Cell cell/map->Cell
   'propagators.propagator.Propagator prop/map->Propagator})

(defn registry [bindings]
  (let [identities (IdentityHashMap.)]
    (doseq [[tag item] bindings]
      (.put identities item tag))
    {:by-tag (into {} bindings) :by-identity identities}))

(defn- scalar? [x]
  (or (nil? x) (boolean? x) (number? x) (keyword? x) (symbol? x)
      (string? x) (char? x) (instance? UUID x) (instance? Date x)))

(defn encode [registry datum]
  (let [substitutions (volatile! [])
        identities ^IdentityHashMap (:by-identity registry)]
    (letfn [(visit [x path]
              (cond
                (.containsKey identities x) [::primitive (.get identities x)]
                (scalar? x) [::scalar x]
                (instance? Instant x) [::instant (str x)]
                (record? x)
                (let [tag (symbol (.getName (class x)))]
                  (if (contains? constructors tag)
                    [::record tag (visit (into {} x) path) (visit (meta x) (conj path :meta))]
                    (do (vswap! substitutions conj path) [::scalar value/nothing])))
                (map? x) [::map (mapv (fn [[k v]] [(visit k (conj path :key))
                                                   (visit v (conj path k))]) x)
                          (visit (meta x) (conj path :meta))]
                (vector? x) [::vector (mapv #(visit %1 (conj path %2)) x (range))
                             (visit (meta x) (conj path :meta))]
                (set? x) [::set (mapv #(visit %1 (conj path %2)) x (range))
                          (visit (meta x) (conj path :meta))]
                (list? x) [::list (mapv #(visit %1 (conj path %2)) x (range))
                           (visit (meta x) (conj path :meta))]
                :else (do (vswap! substitutions conj path) [::scalar value/nothing])))]
      (let [wire (visit datum [])]
        {:text (pr-str wire) :substitutions @substitutions}))))

(defn decode [registry text]
  (let [substitutions (volatile! [])]
    (letfn [(visit [wire path]
              (when-not (vector? wire)
                (throw (ex-info "Malformed peer value" {:path path})))
              (let [[tag payload fields metadata] wire]
                (case tag
                  ::scalar payload
                  ::primitive (if (contains? (:by-tag registry) payload)
                                (get (:by-tag registry) payload)
                                (do (vswap! substitutions conj path) value/nothing))
                  ::instant (Instant/parse payload)
                  ::record (if-let [constructor (get constructors payload)]
                             (with-meta (constructor (visit fields path))
                               (visit metadata (conj path :meta)))
                             (do (vswap! substitutions conj path) value/nothing))
                  ::map (with-meta
                          (into {} (map (fn [[k v]]
                                          (let [key (visit k (conj path :key))]
                                            [key (visit v (conj path key))]))) payload)
                          (visit fields (conj path :meta)))
                  ::vector (with-meta (mapv #(visit % path) payload)
                             (visit fields (conj path :meta)))
                  ::set (with-meta (into #{} (map #(visit % path)) payload)
                          (visit fields (conj path :meta)))
                  ::list (with-meta (apply list (map #(visit % path) payload))
                           (visit fields (conj path :meta)))
                  (throw (ex-info "Unknown peer encoding" {:tag tag :path path})))))]
      {:value (visit (edn/read-string {:readers {}} text) [])
       :substitutions @substitutions})))
