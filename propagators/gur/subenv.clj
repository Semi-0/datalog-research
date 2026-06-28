(ns propagators.gur.subenv
  "Compatibility facade for the deprecated lexical sub-env GUR experiment.

  New compiler/macro work should use `propagators.gur`, which points at
  accumulating GUR. This namespace remains for regression tests, comparison, and
  the scoped routing helpers that accumulating GUR still reuses internally.

  The implementation is split by responsibility under `propagators.gur.subenv.*`:
  env/dispatch-directory, child queues, output projection, frame application,
  and concrete example probes.
  "
  (:require [propagators.gur.subenv.dispatch :as dispatch]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.examples :as examples]
            [propagators.gur.subenv.frame :as frame]
            [propagators.gur.subenv.output :as output]
            [propagators.gur.subenv.queue :as queue]
            [propagators.gur.subenv.source :as source]))

(def scope-key env/scope-key)
(def parent-scope-key env/parent-scope-key)
(def scopes-key env/scopes-key)
(def child-queue-key queue/child-queue-key)
(def recursive-closure-tag frame/recursive-closure-tag)

(def scope-ref env/scope-ref)
(def name-ref env/name-ref)
(def cell-ref env/cell-ref)
(def bind-key env/bind-key)
(def application-key frame/application-key)

(def extend-env env/extend-env)
(def bind env/bind)
(def bind-in-scope env/bind-in-scope)
(def current-scope env/current-scope)
(def bindings env/bindings)
(def scoped-bindings env/scoped-bindings)
(def scopes env/scopes)
(def register-subenv-from-owner env/register-subenv-from-owner)
(def maybe-register-subenv env/maybe-register-subenv)
(def resolve-dispatch env/resolve-dispatch)

(def queue-child-props queue/queue-child-props)
(def pending-run-tokens queue/pending-run-tokens)
(def pending-prop-ids queue/pending-prop-ids)
(def mark-child-props-ran queue/mark-child-props-ran)
(def clear-child-queue queue/clear-child-queue)
(def run-child-queue queue/run-child-queue)

(def externalize-output-value output/externalize-output-value)
(def externalize-output-cells output/externalize-output-cells)
(def p:run-subenv-frame output/p:run-subenv-frame)

(def eval-cell* dispatch/eval-cell*)

(def install-frame-boundary frame/install-frame-boundary)
(def recursive-closure frame/recursive-closure)
(def recursive-closure? frame/recursive-closure?)
(def applied? frame/applied?)
(def mark-applied frame/mark-applied)
(def accumulate-applied-closure frame/accumulate-applied-closure)
(def p:apply-closure frame/p:apply-closure)
(def p:contextual-apply frame/p:contextual-apply)
(def p:contextual-recur frame/p:contextual-recur)
(def contextual-api frame/contextual-api)
(def def-recursive frame/def-recursive)

(def source-recursive-definition source/recursive-definition)
(def source-recursive-closure source/recursive-closure)
(def source-contextual-installers source/contextual-installers)

(def fib examples/fib)
(def fib-closure examples/fib-closure)
(def factorial examples/factorial)
(def factorial-closure examples/factorial-closure)
(def int-sqrt-search examples/int-sqrt-search)
(def int-sqrt-search-closure examples/int-sqrt-search-closure)
(def empty-list examples/empty-list)
(def empty-list? examples/empty-list?)
(def cons-cell-value examples/cons-cell-value)
(def cons-list-value examples/cons-list-value)
(def list-node-value? examples/list-node-value?)
(def map-list examples/map-list)
(def map-list-closure examples/map-list-closure)
(def map-list-fib examples/map-list-fib)
(def map-list-fib-closure examples/map-list-fib-closure)
(def reduce-list examples/reduce-list)
(def reduce-list-closure examples/reduce-list-closure)
(def prefix-reduce-list examples/prefix-reduce-list)
(def prefix-reduce-list-closure examples/prefix-reduce-list-closure)
(def filter-list examples/filter-list)
(def filter-list-closure examples/filter-list-closure)
(def sum-step examples/sum-step)
(def sum-step-closure examples/sum-step-closure)
(def sum-present-step examples/sum-present-step)
(def sum-present-step-closure examples/sum-present-step-closure)
(def even-predicate examples/even-predicate)
(def even-predicate-closure examples/even-predicate-closure)
(def run-fib examples/run-fib)
(def run-factorial examples/run-factorial)
(def run-int-sqrt examples/run-int-sqrt)
(def run-map-list-fib examples/run-map-list-fib)
(def run-nested-map-list-fib examples/run-nested-map-list-fib)
(def run-reduce-list-sum examples/run-reduce-list-sum)
(def run-filter-list-even examples/run-filter-list-even)
