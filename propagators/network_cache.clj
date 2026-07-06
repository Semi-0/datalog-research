(ns propagators.network-cache
  "Transaction-local caches for repeated network interpretation.

  The cache is intentionally dynamic and opt-in. It avoids global stale state
  while letting one runtime transaction reuse pure generic dispatch results."
  (:refer-clojure :exclude [get]))

(def ^:dynamic *cache* nil)

(defn empty-cache []
  (atom {:entries {}
         :stats {}}))

(defn cache-bound? []
  (some? *cache*))

(defn- bump
  [stats k]
  (update stats k (fnil inc 0)))

(defn stat!
  [k]
  (when *cache*
    (swap! *cache* update :stats bump k))
  nil)

(defn get
  [k]
  (when *cache*
    (if (contains? (:entries @*cache*) k)
      (do
        (stat! :cache/hit)
        (clojure.core/get (:entries @*cache*) k))
      (do
        (stat! :cache/miss)
        nil))))

(defn put!
  [k v]
  (when *cache*
    (swap! *cache* assoc-in [:entries k] v))
  v)

(defn cached
  [k f]
  (if *cache*
    (if (contains? (:entries @*cache*) k)
      (do
        (stat! :cache/hit)
        (clojure.core/get (:entries @*cache*) k))
      (do
        (stat! :cache/miss)
        (put! k (f))))
    (f)))

(defmacro with-cache
  [& body]
  `(binding [*cache* (or *cache* (empty-cache))]
     ~@body))

(defn stats []
  (when *cache*
    (:stats @*cache*)))
