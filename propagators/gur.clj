(ns propagators.gur
  "Main public GUR surface.

  This namespace intentionally points at accumulating GUR. Older namespaces
  such as `propagators.gur.subenv` remain available as compatibility experiments,
  but new compiler/macro work should require this namespace or
  `propagators.gur.accumulating` directly."
  (:require [propagators.gur.accumulating :as acc]))

(def recursive-closure-tag acc/recursive-closure-tag)
(def frame-index-key acc/frame-index-key)
(def frame-prop-index-key acc/frame-prop-index-key)
(def task-index-key acc/task-index-key)
(def application-request-index-key acc/application-request-index-key)

(def add-task-facts acc/add-task-facts)
(def application-key acc/application-key)
(def application-request-fragment acc/application-request-fragment)
(def application-requests acc/application-requests)
(def recursive-closure acc/recursive-closure)
(def recursive-closure? acc/recursive-closure?)
(def strongest-or-nothing acc/strongest-or-nothing)
(def p:accumulate-apply-closure acc/p:accumulate-apply-closure)
(def p:when-topology acc/p:when-topology)
(def p:run-accumulated-network acc/p:run-accumulated-network)
(def p:apply-closure acc/p:apply-closure)

(def contextual-installers acc/contextual-installers)
(def default-installers acc/default-installers)
(def recursive-definition acc/recursive-definition)
(def source-recursive-closure acc/source-recursive-closure)

(defmacro def-recursive
  [& args]
  `(acc/def-recursive ~@args))
