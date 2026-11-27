(ns propagators.prototype-2.world
  "Worlds as inspectable Church lists + world-relative observe/revive.

  World node shape (L-2-walkable):
    (cons id (cons env-ref lineage))

  id       — cell holding world-id keyword
  env-ref  — cell holding env-root cell id (keyword), like env binding refs
  lineage  — Church p-list of rewrite frames (host maps in cells), newest at car

  Parent→child is mono-directional: revive only env-extends; lookup walks parent.
  See world-specs for the algebra. retire/activate use an atom SSD stand-in.

  L-2 surface (compiler specials → these installers):
    (current-world)           → world list handle for the compile-time env
    (revive w transform)      → child world handle
    (observe w expr)          → value of expr under w's env (expr not outer-compiled)"
  (:require [propagators.prototype-2.church :as church]
            [propagators.prototype-2.compiler :as comp]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.types :as t]))

(def ^:private hibernation-store
  "WorldSpec store — experiment stand-in for SSD."
  (atom {}))

(defn- new-world-id []
  (keyword (str "world-" (java.util.UUID/randomUUID))))

(defn- as-rules
  [transformation]
  (cond
    (nil? transformation) []
    (and (sequential? transformation)
         (symbol? (first transformation))
         (contains? #{'replace-definition 'forget-definition} (first transformation)))
    [transformation]
    (and (sequential? transformation)
         (keyword? (first transformation))
         (contains? #{:replace-definition :forget-definition} (first transformation)))
    [(cons (symbol (name (first transformation))) (rest transformation))]
    (sequential? transformation)
    (mapcat as-rules transformation)
    :else
    (throw (ex-info "unsupported transformation" {:transformation transformation}))))

(defn- tell-value
  [network cell value]
  (church/tell-cell network cell value))

(defn install-world-node
  "Build Church world node (cons id (cons env-ref lineage)).

  Returns `{:network … :handle world-list-cell}`."
  [network world-id env-cell lineage-frames]
  (let [[id-c network] (church/fresh-cell network world-id "wid")
        [env-ref network] (church/fresh-cell network env-cell "wenv")
        {:keys [network list] :as lin}
        (church/list-from-values network (vec lineage-frames))
        {:keys [network list]} (church/list-from-cells network [id-c env-ref] list)]
    {:network network :handle list}))

(defn- node-cons
  [network handle]
  (let [v (net/cell-value network handle)]
    (when (plist/church-cons? v) v)))

(defn world-id-of
  "Read world-id from a world list handle."
  [network handle]
  (when-let [outer (node-cons network handle)]
    (net/cell-value network (:a outer))))

(defn world-env-of
  "Read env-root cell id from a world list handle."
  [network handle]
  (when-let [outer (node-cons network handle)]
    (when-let [inner (node-cons network (:b outer))]
      (net/cell-value network (:a inner)))))

(defn world-lineage-cell-of
  "Lineage list cell (Church p-list of frame cells)."
  [network handle]
  (when-let [outer (node-cons network handle)]
    (when-let [inner (node-cons network (:b outer))]
      (:b inner))))

(defn lineage-frames-of
  "Host vector of rewrite frames from the world lineage spine (newest first)."
  [network handle]
  (loop [id (world-lineage-cell-of network handle)
         acc []]
    (let [v (net/cell-value network id)]
      (cond
        (plist/empty-list? v) acc
        (plist/church-cons? v)
        (recur (:b v) (conj acc (net/cell-value network (:a v))))
        :else acc))))

(defn make-world
  "Install a world node; return {:handle :network :world-id :env-cell :parent-id}."
  ([network env-cell]
   (make-world network env-cell (new-world-id) nil []))
  ([network env-cell world-id parent-id lineage-frames]
   (let [{:keys [network handle]}
         (install-world-node network world-id env-cell lineage-frames)]
     {:handle handle
      :network network
      :world-id world-id
      :env-cell env-cell
      :parent-id parent-id
      :lineage (vec lineage-frames)})))

(defn current-world
  "World from a compiler state (`:env` + `:network`)."
  [state]
  (make-world (:network state) (:env state)
              (or (:world-id state) (new-world-id))
              (:parent-id state)
              (or (:lineage state) [])))

(defn reify-world
  "World → WorldSpec. Lineage from the Church spine when handle present."
  [world]
  (let [network (:network world)
        handle (:handle world)
        frames (if (and network handle)
                 (lineage-frames-of network handle)
                 (vec (:lineage world)))]
    {:world-id (or (when (and network handle) (world-id-of network handle))
                   (:world-id world))
     :parent-id (:parent-id world)
     :lineage (if (seq frames) (seq frames) '())
     :hibernated? false}))

(defn rewrite-spec
  "Pure: append rules onto the WorldSpec lineage head frame."
  [transformation spec]
  (let [rules (vec (as-rules transformation))
        frame {:world-id (new-world-id)
               :rewrites rules}
        lineage (into [frame] (vec (:lineage spec)))]
    (assoc spec
           :world-id (:world-id frame)
           :parent-id (:world-id spec)
           :lineage lineage)))

(defn- apply-rule
  "Apply one rewrite to live env — only extends child (mono-directional)."
  [network env rule]
  (let [[op & args] rule]
    (case op
      replace-definition
      (let [[name value] args
            cell (net/new-cell-id (str "w-" name))
            network (tell-value network cell value)
            [env network] (lenv/env-extend network env name cell)]
        [env network])

      forget-definition
      (let [[name] args
            cell (net/new-cell-id (str "w-forget-" name))
            network (tell-value network cell ::forgotten)
            [env network] (lenv/env-extend network env name cell)]
        [env network])

      (throw (ex-info "unknown rule" {:rule rule})))))

(defn instantiate
  "WorldSpec → World on `network`, applying lineage rewrites oldest-first."
  [spec network]
  (let [[env-cell network] (lenv/empty-env network)
        frames (reverse (vec (:lineage spec)))
        [env-cell network]
        (reduce (fn [[env n] frame]
                  (reduce (fn [[e n2] rule]
                            (apply-rule n2 e rule))
                          [env n]
                          (:rewrites frame)))
                [env-cell network]
                frames)]
    (make-world network env-cell
                (:world-id spec)
                (:parent-id spec)
                (vec (:lineage spec)))))

(defn revive
  "Fork `world` with `transformation` — new child env + new world list node."
  [world transformation]
  (let [rules (vec (as-rules transformation))
        network (:network world)
        env (or (when (:handle world)
                  (world-env-of network (:handle world)))
                (:env-cell world))
        parent-id (or (when (:handle world)
                        (world-id-of network (:handle world)))
                      (:world-id world))
        old-frames (or (when (:handle world)
                         (lineage-frames-of network (:handle world)))
                       (vec (:lineage world)))
        [env network]
        (reduce (fn [[e n] rule] (apply-rule n e rule))
                [env network]
                rules)
        child-id (new-world-id)
        frame {:world-id child-id :rewrites rules}
        lineage (into [frame] old-frames)]
    (make-world network env child-id parent-id lineage)))

(defn observe
  "Evaluate `expression` under the world's env (call-site env = world env)."
  [world expression]
  (let [network (:network world)
        env (or (when (:handle world)
                  (world-env-of network (:handle world)))
                (:env-cell world))
        state {:network network
               :env env
               :out (net/new-cell-id "observe")
               :tail? true
               :result nil}
        state (comp/compile-form state expression)
        network (ev/run-tasks (:network state))
        value (net/cell-value network (:result state))]
    [value (assoc world :network network) (:result state)]))

(defn retire
  "Hibernate: store WorldSpec, return effect map."
  [world]
  (let [spec (assoc (reify-world world) :hibernated? true)
        wid (:world-id spec)]
    (swap! hibernation-store assoc wid spec)
    {:effect :retire :world-id wid :spec spec}))

(defn activate
  "Wake a hibernated WorldSpec (or pass-through a live world)."
  ([world-or-spec]
   (activate world-or-spec net/empty-network))
  ([world-or-spec network]
   (cond
     (and (map? world-or-spec) (:handle world-or-spec) (:network world-or-spec)
          (not (:hibernated? world-or-spec)))
     world-or-spec

     (and (map? world-or-spec) (:env-cell world-or-spec) (:handle world-or-spec)
          (not (:hibernated? world-or-spec)))
     world-or-spec

     (and (map? world-or-spec) (contains? world-or-spec :lineage)
          (not (:handle world-or-spec)))
     (instantiate (assoc world-or-spec :hibernated? false) network)

     (contains? @hibernation-store world-or-spec)
     (instantiate (assoc (get @hibernation-store world-or-spec)
                         :hibernated? false)
                  network)

     :else
     (throw (ex-info "cannot activate" {:value world-or-spec})))))

(defn world-from-forms
  "Compile `forms` into a fresh world (defines land in its env)."
  [& forms]
  (let [state (comp/compile-program (vec forms))
        network (ev/run-tasks (:network state))
        state (assoc state :network network)]
    (current-world state)))

;; ---------------------------------------------------------------------------
;; L-2 installers — world handle is the Church list cell (car/cdr-able)
;; ---------------------------------------------------------------------------

(defn world-from-handle
  "Rebuild host world map from an L-2 world list cell."
  [network handle]
  {:handle handle
   :network network
   :world-id (world-id-of network handle)
   :env-cell (world-env-of network handle)
   :parent-id nil
   :lineage (lineage-frames-of network handle)})

(defn- world-handle?
  [network cell]
  (let [v (net/cell-value network cell)]
    (boolean
     (and (plist/church-cons? v)
          (some? (world-id-of network cell))
          (some? (world-env-of network cell))))))

(defn install-current-world-prop
  "Emit a world list node for `env-cell` into `out`."
  [network env-cell out]
  (let [expand
        (fn [n]
          (let [wid (new-world-id)
                {:keys [network handle]} (install-world-node n wid env-cell [])]
            (prim/install-id network handle out)))]
    (ctor/pending-compound-constructor network expand [env-cell] [out])))

(defn install-revive-prop
  "When world + transform cells are concrete, fork and write child handle to `out`."
  [network world-cell transform-cell out]
  (let [expand
        (fn [n]
          (let [wv (net/cell-value n world-cell)
                tv (net/cell-value n transform-cell)]
            (cond
              (or (t/nothing? wv) (t/nothing? tv))
              n

              (not (world-handle? n world-cell))
              (do (println "revive: expected world list, got" (pr-str wv))
                  n)

              :else
              (let [child (revive (world-from-handle n world-cell) tv)]
                (prim/install-id (:network child) (:handle child) out)))))]
    (ctor/pending-compound-constructor
     network expand [world-cell transform-cell] [out])))

(defn install-observe-prop
  "Compile/run `expression` under world-cell's env; id-sync result → `out`.

  `expression` is closed-over host form (not compiled in the outer world)."
  [network world-cell expression out]
  (let [expand
        (fn [n]
          (let [wv (net/cell-value n world-cell)]
            (cond
              (t/nothing? wv)
              n

              (not (world-handle? n world-cell))
              (do (println "observe: expected world list, got" (pr-str wv))
                  n)

              :else
              (let [[_ world' result-cell]
                    (observe (world-from-handle n world-cell) expression)]
                (prim/install-id (:network world') result-cell out)))))]
    (ctor/pending-compound-constructor network expand [world-cell] [out])))
