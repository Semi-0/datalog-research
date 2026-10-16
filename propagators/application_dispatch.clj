(ns propagators.application-dispatch
  "Runtime application extension point. Handlers receive the whole callable and
  return ordinary activation effects; each handler owns its representation policy.")

(defmulti application-effects
  "Apply a callable observation in its declared GUR context. Register extension
  methods before evaluating networks; importing this namespace registers none."
  (fn [callable _context _invocation-ids _result-id] (class callable)))
