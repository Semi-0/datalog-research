(ns propagators.gur
  "Main public GUR surface.

  Flat GUR is the default: recursive bodies emit bounded declaration effects
  into the active immutable Net."
  (:require [propagators.gur.flat :as flat]))

(def recursive-closure-tag flat/recursive-closure-tag)
(def root-key flat/root-key)
(def cell-index-key flat/cell-index-key)
(def prop-index-key flat/prop-index-key)
(def name-bindings-key flat/name-bindings-key)

(def stable-node-id flat/stable-node-id)
(def application-key flat/application-key)
(def vm-net flat/vm-net)
(def declare-cell flat/declare-cell)
(def declare-prop flat/declare-prop)
(def bind-name flat/bind-name)
(def recursive-closure flat/recursive-closure)
(def recursive-closure? flat/recursive-closure?)
(def recursive-declaration flat/recursive-declaration)
(def apply-closure-effect flat/apply-closure-effect)
(def when-effect flat/when-effect)
