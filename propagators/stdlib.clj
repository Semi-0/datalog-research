(ns ^{:deprecated "Import propagators.stdlib.prop, .boundary, .effect, .layered, or .arithmetic.* directly."}
  propagators.stdlib
  "Deprecated barrel. Use leaf namespaces under `propagators.stdlib.*`."
  (:require [propagators.stdlib.arithmetic.provenance :as provenance]
            [propagators.stdlib.boundary :as boundary]
            [propagators.stdlib.effect :as effect]
            [propagators.stdlib.prop :as prop]))

;; Legacy names only — no prop/+ or layered/+ re-exports.

(def ^{:deprecated true} p:id prop/id)
(def ^{:deprecated true} p:switch prop/switch)
(def ^{:deprecated true} p:provenance-union provenance/p:union)

(def ^{:deprecated true} effect:tap effect/effect:tap)
(def ^{:deprecated true} mark-updated-tap effect/mark-updated-tap)
(def ^{:deprecated true} hook-output-taps effect/hook-output-taps)

(def ^{:deprecated true} p:nothing prop/nothing)
(def ^{:deprecated true} nothing-out-link boundary/nothing-out-link)
(def ^{:deprecated true} nothing-in-link boundary/nothing-in-link)
(def ^{:deprecated true} bi-sync boundary/bi-sync)
(def ^{:deprecated true} bi-sync-closure boundary/bi-sync-closure)
(def ^{:deprecated true} fast-bi-sync boundary/fast-bi-sync)
