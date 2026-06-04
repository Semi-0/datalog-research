(ns propagators.effectful-sync
  "Helpers for syncing executable subnet avatars with an outer network."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.boundary :as boundary]))

(defn strongest-equivalent?
  "True when `a` and `b` strongest values are merge-equivalent on `network`."
  [a b network]
  (false? (merge/cell-updated? a b network)))

(defn ensure-indexed-cell
  "Ensure `index-entry` maps to a cell and has an empty index bucket."
  [subnet index-key index-entry]
  (if (net/network-dict-entry subnet index-entry)
    subnet
    (let [cell-id (ids/new-node-id)]
      (-> subnet
          (nb/install-cell cell-id)
          (net/assoc-net-dict-entry index-entry cell-id)
          (net/update-net-dict-entry index-key #(assoc (or % {}) index-entry #{}))))))

(defn ensure-indexed-shell-avatar
  "Ensure `parent-id` has an avatar cell and is indexed, without copying parent values."
  [subnet index-key index-entry parent-id]
  (let [dict (net/net-dict-or-empty subnet)]
    (if (get dict parent-id)
      (net/update-net-dict-entry subnet index-key
                                 #(update (or % {}) index-entry (fnil conj #{}) parent-id))
      (let [avatar-id (ids/new-node-id)]
        (-> subnet
            (nb/install-cell avatar-id)
            (net/assoc-net-dict-entry parent-id avatar-id)
            (net/update-net-dict-entry index-key
                                       #(update (or % {}) index-entry (fnil conj #{}) parent-id)))))))

(defn ensure-parent-avatar
  "Ensure `parent-id` has an avatar cell in `subnet` and is indexed.

  Existing avatar cells receive the parent's current content by normal cell
  merge. New avatars are installed with the parent's content and strongest
  value, recorded under `parent-id`, and added to `index-key` / `index-entry`."
  [subnet index-key index-entry parent-id parent-net]
  (let [dict (net/net-dict-or-empty subnet)
        parent (net/network-env-lookup parent-net parent-id)
        parent-content (cell/cell-content parent)
        parent-strongest (cell/cell-strongest parent)]
    (if-let [avatar-id (get dict parent-id)]
      (let [avatar (get (net/net-env subnet) avatar-id)
            avatar' (merge/merge-cell-entry avatar parent-content parent-net)]
        (net/assoc-net-cell subnet avatar-id avatar'))
      (let [avatar-id (ids/new-node-id)]
        (-> subnet
            (nb/install-cell avatar-id parent-content parent-strongest)
            (net/assoc-net-dict-entry parent-id avatar-id)
            (net/update-net-dict-entry index-key
                                       #(update (or % {}) index-entry (fnil conj #{}) parent-id)))))))

(defn attach-fast-bi-sync
  "Install bidirectional p:id sync between `from-id` and `to-id`, if absent.

  `from->to-key` and `to->from-key` are dict keys used to remember the installed
  sync propagator ids."
  [subnet from-id to-id from->to-key to->from-key]
  (let [dict (net/net-dict-or-empty subnet)]
    (if (and (contains? dict from->to-key)
             (contains? dict to->from-key))
      subnet
      (let [[[from->to to->from] subnet'] (boundary/fast-bi-sync subnet from-id to-id)]
        (-> subnet'
            (net/assoc-net-dict-entry from->to-key from->to)
            (net/assoc-net-dict-entry to->from-key to->from))))))

(defn attach-indexed-bi-sync
  "Install bidirectional p:id sync for one indexed parent/cell relation.

  The installed sync ids are stored under `[sync-key index-entry parent-id direction]`."
  [subnet sync-key index-entry parent-id from-id to-id]
  (attach-fast-bi-sync subnet
                       from-id
                       to-id
                       [sync-key index-entry parent-id :from->to]
                       [sync-key index-entry parent-id :to->from]))

(defn indexed-seed-ids
  "Seed ids for running one indexed cell and its indexed avatars."
  [subnet index-key index-entry cell-id]
  (let [dict (net/net-dict-or-empty subnet)
        parent-ids (vec (net/network-indexed-ids subnet index-key index-entry))
        avatar-ids (keep #(get dict %) parent-ids)]
    (if cell-id (conj (vec avatar-ids) cell-id) (vec avatar-ids))))

(defn indexed-stable-cell-ids
  "Stable cell ids for one indexed cell and its indexed avatars."
  [subnet index-key index-entry cell-id]
  (let [dict (net/net-dict-or-empty subnet)]
    (into [cell-id]
          (keep #(get dict %))
          (net/network-indexed-ids subnet index-key index-entry))))

(defn updated-parent-messages
  "Messages from updated avatar cells back to their outer parent cells."
  [after updated*]
  (let [dict (net/net-dict-or-empty after)]
    (mapv (fn [parent-id]
            (let [avatar-id (get dict parent-id)]
              (message parent-id (net/network-cell-strongest after avatar-id))))
          (sort-by pr-str @updated*))))
