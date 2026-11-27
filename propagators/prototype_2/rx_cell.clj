(ns propagators.prototype-2.rx-cell
  "Cell as reactive operator (Art of the Propagator + L-2 spines).

  Content is not overwritten: each `add-content` extends an observation spine
  and projects via `tms-reduce` / incremental `tms-step`. Neighbors alert only
  when the projected tip changes (thesis `eq?` short-circuit).

  Shape:
    spine      — Church list cell of observations (newest at car)
    projected  — `[base support]` or nil (cached strongest consequence)
    neighbors  — propagator ids / callbacks alerted on projection change"
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

(defn empty-rx
  "Fresh rx-cell whose spine is `'()` and projected content is nothing."
  [network]
  (let [[spine network] (church/empty-list-cell network)]
    {:network network
     :cell (->RxCell spine nil #{})}))

(defn content
  "Thesis `content`: projected `[base support]`, or nil when empty."
  [rx]
  (:projected rx))

(defn content-base
  [rx]
  (when-let [[b _] (:projected rx)] b))

(defn content-support
  [rx]
  (when-let [[_ s] (:projected rx)] s))

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

(defn add-content
  "Thesis `add-content`: extend spine with observation, merge via tms-step.

  `obs-cell` — network cell holding `(cons base support)`.
  Returns `{:network :cell :changed?}`. Alerts neighbors only when projected
  tip moves (redundant increments are silent)."
  [network rx obs-cell]
  (let [pair (let [v (net/cell-value network obs-cell)]
               (when (plist/church-cons? v)
                 [(net/cell-value network (:a v))
                  (net/cell-value network (:b v))]))]
    (if-not pair
      {:network network :cell rx :changed? false}
      (let [{:keys [network list]} (stag/extend-tip network (:spine-id rx) obs-cell)
            prior (:projected rx)
            next (stag/tms-step-incremental prior pair)
            changed? (not= prior next)
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
  "Full `tms-reduce` over the spine (worldview / kick-out style refresh).
  Returns `{:network :cell :changed?}`."
  [network rx]
  (let [next (stag/project-tip network (:spine-id rx))
        prior (:projected rx)
        changed? (not= prior next)
        rx' (assoc rx :projected next)]
    {:network network
     :cell (if changed? (alert rx') rx')
     :changed? changed?}))

(defn install-add-content-prop
  "Propagator: rx-handle + observation → updated rx-handle.

  Handles hold host `RxCell` records; merge is incremental `tms-step`
  (spine extend + project; no content overwrite)."
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
