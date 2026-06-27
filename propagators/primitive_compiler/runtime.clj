(ns propagators.primitive-compiler.runtime
  (:require [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.primitive-compiler.core :as pc]
            [propagators.primitive-compiler.installers :as installers]))

(def install-net net/install-net)
(def seed-net-cell net/seed-net-cell)

(defn run-net-let
  "Runtime network builder.

  `cell-binds`: `[[sym id] | [sym id content strongest] ...]`
  `prop-forms`: `((installer arg-sym ...) ...)`
  Returns updated net."
  [n installers cell-binds prop-forms]
  (let [[n' cells]
        (reduce
         (fn [[n acc] entry]
           (let [[sym id & seed] entry
                 n' (if (empty? seed)
                      (net/seed-net-cell n id)
                      (net/seed-net-cell n id (first seed) (second seed)))]
             [n' (assoc acc sym id)]))
         [n {}]
         cell-binds)]
    (reduce
     (fn [n form]
       (let [[inst & arg-syms] form
             ids (mapv #(get cells %) arg-syms)
             inst-fn (or (get installers inst)
                         (throw (ex-info "unknown installer" {:inst inst})))]
         (net/install-net n (apply inst-fn ids))))
     n'
     prop-forms)))

(defn install-and-run
  "Install one propagator installer on `n` and run the installed propagator ids."
  [n installer]
  (let [[installed-id n'] (installer n)]
    (nb/run-propagators n' (pc/prop-ids installed-id))))

(defn cell-bind-entry [entry]
  (let [[sym id & seed] entry]
    `(list '~sym ~id ~@seed)))

(defmacro net-let
  "Thread net through cell bindings and propagator installs.

  Example:
  ```clojure
  (net-let n
    [[a input-id content strongest]
     [b output-id content' strongest']]
    (p:id a b)
    (p:id b a))
  ```

  Custom installers: call `run-net-let` directly."
  [n cell-binds & prop-forms]
  `(run-net-let ~n (installers/default-installers)
                (vector ~@(map cell-bind-entry cell-binds))
                '~prop-forms))
