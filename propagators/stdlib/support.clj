(ns propagators.stdlib.support
  "Support is a procedure layer, not a gate around layered application."
  (:require [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def p:combine
  (prop/primitive-propagator
   :stdlib/support-combine
   (fn [_current & arguments]
     ;; Previous output is not an additional input premise. Carrying it here
     ;; would permanently mix an old computation's versions with fresh inputs.
     (apply support/combine (map datum/support-of arguments)))))

(def procedure
  "Standard nonbase contract: [current-layer & full-arguments] -> support.
  Runs for unusable bases too; only downstream readers decide usability."
  {:f (fn [_closure-net input-ids output-ids network]
        (second ((apply p:combine (concat input-ids output-ids)) network)))
   :net net/empty-net})
