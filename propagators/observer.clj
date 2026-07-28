(ns propagators.observer
  "Edge-free, explicitly sampled network observers."
  (:require [propagators.ids :as ids]
            [propagators.network :as net]))

(def observers-key ::observers)

(defrecord Observer [id name sample])

(defn observer?
  [x]
  (instance? Observer x))

(defn observers
  "Observers installed in `network`, keyed by observer id."
  [network]
  (or (net/network-dict-entry network observers-key) {}))

(defn network-observer
  [network observer-id]
  (get (observers network) observer-id))

(defn construct-observer
  "Return a standard network installer for an explicitly sampled observer.

  Unlike a propagator constructor, this records no graph node or cell edges.
  `sample` receives the current immutable network when requested."
  ([sample]
   (construct-observer (ids/new-node-id) :observer/anonymous sample))
  ([id-or-name sample]
   (if (ids/node-id? id-or-name)
     (construct-observer id-or-name :observer/anonymous sample)
     (construct-observer (ids/new-node-id) id-or-name sample)))
  ([id name sample]
   (when-not (fn? sample)
     (throw (ex-info "observer sample must be a function"
                     {:observer/id id :observer/name name})))
   (fn [arg]
     (let [network (net/as-net arg)
           observer (->Observer id name sample)
           network' (net/update-net-dict-entry
                     network observers-key (fnil assoc {}) id observer)]
       [id network']))))

(defn sample-observer
  "Run one observer against the supplied immutable network."
  [network observer-id]
  (if-let [observer (network-observer network observer-id)]
    ((:sample observer) network)
    (throw (ex-info "observer is not installed"
                    {:observer/id observer-id}))))
