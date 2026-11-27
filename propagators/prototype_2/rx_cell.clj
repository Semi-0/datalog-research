(ns propagators.prototype-2.rx-cell
  "Cell as reactive operator (Art of the Propagator + L-2 spines).

  Content is not overwritten: each `add-content` extends an observation spine
  and merges via lattice `front-add` / `tms-step` into an undominated frontier
  (incremental dataflow aggregate — C/A/I). Full `tms-front-reduce` on
  `reproject`. Neighbors alert only when the frontier changes.

  Shape:
    spine      — Church list cell of observations (newest at car)
    projected  — frontier vector of `[base support]` (undominated set)
    neighbors  — callbacks alerted on frontier change

  See `propagators/doc/prototype-2-ddb-rx.md`."
  (:require [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.support-tag :as stag]
            [propagators.prototype-2.types :as t]))

(defrecord RxCell [spine-id projected neighbors])

(defn rx-cell? [x]
  (instance? RxCell x))

(defn- frontier
  [projected]
  (cond
    (nil? projected) []
    (and (vector? projected)
         (or (empty? projected)
             (and (vector? (first projected))
                  (= 2 (count (first projected))))))
    projected
    :else [projected]))

(defn empty-rx
  "Fresh rx-cell whose spine is `'()` and frontier is empty."
  [network]
  (let [[spine network] (church/empty-list-cell network)]
    {:network network
     :cell (->RxCell spine [] #{})}))

(defn content-frontier
  "Cached undominated frontier (vector of `[base support]`)."
  [rx]
  (frontier (:projected rx)))

(defn content
  "Thesis tip view: first frontier member, or nil when empty."
  [rx]
  (first (content-frontier rx)))

(defn content-base
  [rx]
  (when-let [[b _] (content rx)] b))

(defn content-support
  [rx]
  (when-let [[_ s] (content rx)] s))

(defn spine-id
  [rx]
  (:spine-id rx))

(defn add-neighbor
  [rx neighbor]
  (update rx :neighbors (fnil conj #{}) neighbor))

(defn- alert
  "Invoke neighbor callbacks with the updated cell; return cell."
  [rx]
  (doseq [n (:neighbors rx)]
    (when (fn? n) (n rx)))
  rx)

(defn- frontier-changed?
  [prior next]
  (not= (set (frontier prior)) (set (frontier next))))

(defn add-content
  "Thesis `add-content`: extend spine; lattice-join tip into frontier.

  Returns `{:network :cell :changed?}`. Alerts only when the frontier moves."
  [network rx obs-cell]
  (let [pair (let [v (net/cell-value network obs-cell)]
               (when (plist/church-cons? v)
                 [(net/cell-value network (:a v))
                  (net/cell-value network (:b v))]))]
    (if-not pair
      {:network network :cell rx :changed? false}
      (let [{:keys [network list]} (stag/extend-tip network (:spine-id rx) obs-cell)
            prior (content-frontier rx)
            next (stag/tms-step-incremental prior pair)
            changed? (frontier-changed? prior next)
            rx' (assoc rx :spine-id list :projected next)]
        {:network network
         :cell (if changed? (alert rx') rx')
         :changed? changed?}))))

(defn add-content-values
  "Host sugar: base + support-set → observation cell → `add-content`."
  [network rx base support-set]
  (let [{network :network obs :cell} (stag/observation-from-values network base support-set)]
    (add-content network rx obs)))

(defn reproject
  "Full lattice front-reduce over the spine (worldview-style refresh)."
  [network rx]
  (let [next (stag/project-frontier network (:spine-id rx))
        prior (content-frontier rx)
        changed? (frontier-changed? prior next)
        rx' (assoc rx :projected next)]
    {:network network
     :cell (if changed? (alert rx') rx')
     :changed? changed?}))

(defn install-add-content-prop
  "Propagator: rx-handle + observation → updated rx-handle.

  Merge is incremental lattice `tms-step` (no content overwrite)."
  [network rx-in-cell obs-cell out]
  (let [expand
        (fn [n]
          (let [rx (net/cell-value n rx-in-cell)
                obs-v (net/cell-value n obs-cell)]
            (if (or (not (rx-cell? rx))
                    (t/nothing? obs-v)
                    (not (plist/church-cons? obs-v)))
              n
              (let [{:keys [network cell]} (add-content n rx obs-cell)]
                (ev/write-cell network out cell)))))]
    (ctor/pending-compound-constructor
     network expand [rx-in-cell obs-cell] [out])))
