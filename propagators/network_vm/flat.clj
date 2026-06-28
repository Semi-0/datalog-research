(ns propagators.network-vm.flat
  "Flat effect constructors for the experimental network VM.

  The main scheduler in `propagators.core` applies these effects. This namespace
  intentionally has no local task cursor, mailbox, or child VM state."
  (:require [propagators.network-vm.flat.effects :as effects]
            [propagators.network-vm.instructions :as instr]))

(def root-key effects/root-key)
(def cell-index-key effects/cell-index-key)
(def prop-index-key effects/prop-index-key)
(def installer-index-key effects/installer-index-key)
(def name-bindings-key effects/name-bindings-key)

(def stable-node-id effects/stable-node-id)
(def vm-net effects/vm-net)

(def declare-cell instr/declare-cell)
(def declare-prop instr/declare-prop)
(def bind-name instr/bind-name)
(def tell instr/tell)
(def schedule instr/schedule)
(def install-topology effects/install-topology)
