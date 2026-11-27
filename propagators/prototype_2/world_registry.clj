(ns propagators.prototype-2.world-registry
  "Runner-side WorldRegistry: live handles + hibernated specs, shared network.

  Spawned children share the parent's env spine until touched — revive is
  lazy (`:pending-rewrites`); `observe` / `touch` env-extends only child frames.
  No deep-copy of parent bindings; lookup falls through on miss.

  Shape:
    {:network …
     :worlds {id entry}       ; live
     :hibernated {id WorldSpec}
     :active #{id}}

  entry: {:world-id :handle :env-cell :parent-id :lineage :pending-rewrites?}"
  (:require [propagators.prototype-2.env :as lenv]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.world :as world]))

(defn empty-registry
  "Fresh registry over an optional shared network."
  ([] (empty-registry net/empty-network))
  ([network]
   {:network network
    :worlds {}
    :hibernated {}
    :active #{}}))

(defn- entry-of
  [world]
  (cond-> {:world-id (:world-id world)
           :handle (:handle world)
           :env-cell (:env-cell world)
           :parent-id (:parent-id world)
           :lineage (vec (:lineage world))}
    (seq (:pending-rewrites world))
    (assoc :pending-rewrites (vec (:pending-rewrites world)))))

(defn- world-from-entry
  [reg entry]
  (cond-> {:world-id (:world-id entry)
           :handle (:handle entry)
           :env-cell (:env-cell entry)
           :parent-id (:parent-id entry)
           :lineage (vec (:lineage entry))
           :network (:network reg)}
    (seq (:pending-rewrites entry))
    (assoc :pending-rewrites (vec (:pending-rewrites entry)))))

(defn put-world
  "Register / refresh a live world; network advances to `world`'s network."
  [reg world]
  (let [id (:world-id world)]
    (-> reg
        (assoc :network (:network world))
        (assoc-in [:worlds id] (entry-of world))
        (update :active (fnil conj #{}) id)
        (update :hibernated dissoc id))))

(defn register
  "Index an existing live world (e.g. from `world-from-forms`)."
  [reg world]
  (put-world reg world))

(defn get-world
  "Live world map for `id`, or nil."
  [reg id]
  (when-let [entry (get-in reg [:worlds id])]
    (world-from-entry reg entry)))

(defn live?
  [reg id]
  (contains? (:active reg) id))

(defn hibernated?
  [reg id]
  (contains? (:hibernated reg) id))

(defn spawn
  "Lazy revive under registry: child shares parent env; frames pending until touch."
  [reg parent-id transformation]
  (let [parent (or (get-world reg parent-id)
                   (throw (ex-info "spawn: parent not live" {:parent-id parent-id})))
        child (world/revive parent transformation {:lazy? true})]
    (put-world reg child)))

(defn touch
  "Materialize pending child frames (env-extend onto parent spine)."
  [reg world-id]
  (let [w (or (get-world reg world-id)
              (throw (ex-info "touch: world not live" {:world-id world-id})))
        w' (world/materialize-pending w)]
    (put-world reg w')))

(defn observe
  "Touch then observe `expression` under world `id`. Returns `[value reg result-cell]`."
  [reg world-id expression]
  (let [reg (touch reg world-id)
        w (get-world reg world-id)
        [value w' result] (world/observe w expression)]
    [value (put-world reg w') result]))

(defn- head-rewrites
  "Child-local rewrites from spec lineage head (or stored pending)."
  [spec]
  (or (not-empty (vec (:pending-rewrites spec)))
      (vec (:rewrites (first (:lineage spec))))
      []))

(defn hibernate
  "Move live world to `:hibernated` WorldSpec; drop live handle from RAM index.
  Parent bindings are not snapshotted — activate re-links to a live parent."
  [reg world-id]
  (let [w (or (get-world reg world-id)
              (throw (ex-info "hibernate: world not live" {:world-id world-id})))
        spec (-> (world/reify-world w)
                 (assoc :hibernated? true)
                 (cond-> (seq (:pending-rewrites w))
                   (assoc :pending-rewrites (vec (:pending-rewrites w)))))]
    (-> reg
        (update :worlds dissoc world-id)
        (update :active disj world-id)
        (assoc-in [:hibernated world-id] spec))))

(defn activate
  "Wake hibernated world: share parent env again; child frames stay pending
  until touch/observe. Activates hibernated ancestors first."
  [reg world-id]
  (cond
    (live? reg world-id) reg

    (not (hibernated? reg world-id))
    (throw (ex-info "activate: not hibernated" {:world-id world-id}))

    :else
    (let [spec (get-in reg [:hibernated world-id])
          parent-id (:parent-id spec)
          reg (cond
                (nil? parent-id) reg
                (live? reg parent-id) reg
                (hibernated? reg parent-id) (activate reg parent-id)
                :else
                (throw (ex-info "activate: parent missing"
                                {:world-id world-id :parent-id parent-id})))
          [parent-env network]
          (if parent-id
            [(:env-cell (get-in reg [:worlds parent-id])) (:network reg)]
            (lenv/empty-env (:network reg)))
          pending (head-rewrites spec)
          lineage (vec (:lineage spec))
          child (world/make-world network parent-env world-id parent-id lineage)
          child (cond-> child
                  (seq pending) (assoc :pending-rewrites pending))]
      (-> (put-world reg child)
          (update :hibernated dissoc world-id)))))
