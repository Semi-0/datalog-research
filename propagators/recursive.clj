(ns propagators.recursive
  "Recursive compound propagation over activation-local network values."
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.datastructures.named-network :as named]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def self-key :recursive/self)
(def depth-key :recursive/depth)
(def accumulator-key :recursive/accumulator)
(def frame-index-key :recursive/frames)

(def runtime-closure-dict-keys
  #{self-key depth-key accumulator-key})

(defn frame-key
  [op args]
  [:recursive/frame op args])

(defn frame-dict-key
  [frame slot]
  [:recursive/frame frame slot])

(defn- stable-frame-cell-id
  [dict-key]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str [:recursive/frame-fragment dict-key])
               StandardCharsets/UTF_8))))

(defn- add-named-cell
  [n dict-key v]
  (let [id (stable-frame-cell-id dict-key)]
    (-> n
        (nb/install-cell id v v)
        (net/assoc-net-dict-entry dict-key id))))

(defn frame-fragment
  "Build a named-network fragment for semantic facts about one recursive frame."
  [frame slot-values]
  (let [fragment (reduce-kv (fn [n slot v]
                              (add-named-cell n (frame-dict-key frame slot) v))
                            net/empty-net
                            slot-values)]
    (net/update-net-dict-entry fragment
                               frame-index-key
                               #(conj (or % #{}) frame))))

(defn frame-index
  [n]
  (get (net/net-dict-or-empty n) frame-index-key #{}))

(defn- write-outputs
  [network output-ids v]
  (reduce #(nb/seed-cell %1 %2 v) network output-ids))

(defn- normalize-step-result
  [result]
  (if (and (map? result) (contains? result :network))
    {:network (:network result)
     :frame-fragment (or (:frame-fragment result)
                         (:closure-net-update result))}
    {:network result
     :frame-fragment nil}))

(defn- semantic-closure-net
  [closure-net]
  (-> closure-net
      net/clear-io
      (net/net-with-dict
       (apply dissoc
              (net/net-dict-or-empty closure-net)
              runtime-closure-dict-keys))))

(defn- join-fragments
  [& fragments]
  (reduce (fn [acc fragment]
            (if (or (nil? fragment) (value/contradiction? acc))
              acc
              (named/join acc fragment)))
          net/empty-net
          fragments))

(defn- fragment-empty?
  [fragment]
  (empty? (net/net-dict-or-empty fragment)))

(defn- closure-with-net-fragment
  [closure-value fragment]
  (if (or (nil? fragment) (fragment-empty? fragment))
    nil
    (let [merged-net (named/join (closure/closure-net closure-value) fragment)]
      (if (value/contradiction? merged-net)
        value/contradiction
        (assoc closure-value :net merged-net)))))

(defn recursive-closure
  "Build a closure whose body may install recursive calls to `:recursive/self`.

  `step-f` receives `{:closure-net :self-id :acc-id :input-ids :output-ids
  :network :depth}` and returns either an updated activation-local network value
  or `{:network n :frame-fragment fragment}`."
  ([step-f]
   (recursive-closure step-f {}))
  ([step-f {:keys [max-depth]
            :or {max-depth 1024}}]
   (closure/closure
    (fn [closure-net input-ids output-ids network]
      (let [depth (or (net/network-dict-entry closure-net depth-key) 0)
            self-id (net/network-dict-entry closure-net self-key)]
        (if (>= depth max-depth)
          (write-outputs network output-ids value/contradiction)
          (step-f {:closure-net closure-net
                   :self-id self-id
                   :acc-id (net/network-dict-entry closure-net accumulator-key)
                   :input-ids (vec input-ids)
                   :output-ids (vec output-ids)
                   :network network
                   :depth depth}))))
    net/empty-net)))

(defn- closure-net-with-frame
  [closure-net self-id depth]
  (-> closure-net
      (net/assoc-net-dict-entry self-key self-id)
      (net/assoc-net-dict-entry depth-key depth)))

(defn- with-closure-net
  [closure-value closure-net]
  (assoc closure-value :net closure-net))

(defn- prepare-recursive-frame
  ([network closure-value]
   (prepare-recursive-frame network closure-value (closure/closure-net closure-value)))
  ([network closure-value base-closure-net]
   (let [current-depth (or (net/network-dict-entry
                            base-closure-net
                            depth-key)
                           0)
        self-id (ids/new-node-id)
        current-closure-net (closure-net-with-frame
                             base-closure-net
                             self-id
                             current-depth)
        child-closure-net (closure-net-with-frame
                           base-closure-net
                           self-id
                           (inc current-depth))
        child-closure (with-closure-net closure-value child-closure-net)
        network' (-> network
                     (nb/install-cell self-id child-closure child-closure)
                     (net/assoc-net-dict-entry self-key self-id)
                     (net/assoc-net-dict-entry depth-key current-depth))]
    {:network network'
     :closure-net current-closure-net
     :self-id self-id})))

(defn- run-recursive-frame
  [outer-network closure-value arg-ids out-id
   {:keys [extra-output-ids closure-net-fn]}]
  (let [output-ids (vec (distinct (concat [out-id] extra-output-ids)))
        with-boundary (-> outer-network
                          (boundary/create-boundary-outputs output-ids)
                          (boundary/create-boundary-inputs arg-ids))
        input-avatars (mapv #(net/lookup-inner-in with-boundary %) arg-ids)
        output-avatars [(net/lookup-inner-out with-boundary out-id)]
        base-closure-net (if closure-net-fn
                           (closure-net-fn (closure/closure-net closure-value)
                                           with-boundary)
                           (closure/closure-net closure-value))
        {:keys [network closure-net self-id]} (prepare-recursive-frame
                                               with-boundary
                                               closure-value
                                               base-closure-net)
        step-result (normalize-step-result
                     ((closure/closure-f closure-value)
                      closure-net
                      input-avatars
                      output-avatars
                      network))
        after (boundary/run-internal-network input-avatars (:network step-result))]
    {:after after
     :self-id self-id
     :frame-fragment (:frame-fragment step-result)}))

(defn- recursive-activate
  [closure-id arg-ids out-id]
  (fn [_inputs _outputs outer-network]
    (let [closure-cv (net/network-cell-strongest outer-network closure-id)
          closure-value (value/value-payload closure-cv)
          arg-values (mapv #(net/network-cell-strongest outer-network %) arg-ids)]
      (if (or (value/unusable? closure-cv)
              (apply value/any-unusable-values? arg-values)
              (nil? closure-value))
        []
        (let [with-boundary (-> outer-network
                                (boundary/create-boundary-outputs [out-id])
                                (boundary/create-boundary-inputs arg-ids))
              input-avatars (mapv #(net/lookup-inner-in with-boundary %) arg-ids)
              output-avatars [(net/lookup-inner-out with-boundary out-id)]
              {:keys [network closure-net]} (prepare-recursive-frame
                                             with-boundary
                                             closure-value)
              step-result (normalize-step-result
                           ((closure/closure-f closure-value)
                            closure-net
                            input-avatars
                            output-avatars
                            network))
              after (boundary/run-internal-network input-avatars (:network step-result))]
          (diff/diff-internal-output-cells after outer-network [out-id]))))))

(defn- self-refining-recursive-activate
  [closure-id arg-ids out-id]
  (fn [_inputs _outputs outer-network]
    (let [closure-cv (net/network-cell-strongest outer-network closure-id)
          closure-value (value/value-payload closure-cv)
          arg-values (mapv #(net/network-cell-strongest outer-network %) arg-ids)]
      (if (or (value/unusable? closure-cv)
              (apply value/any-unusable-values? arg-values)
              (nil? closure-value))
        []
        (let [{:keys [after self-id frame-fragment]}
              (run-recursive-frame outer-network closure-value arg-ids out-id {})
              output-messages (vec (diff/diff-internal-output-cells
                                    after
                                    outer-network
                                    [out-id]))
              self-closure (value/value-payload
                            (net/network-cell-strongest after self-id))
              child-fragment (when (closure/closure? self-closure)
                               (semantic-closure-net
                                (closure/closure-net self-closure)))
              fragment (join-fragments child-fragment frame-fragment)
              closure-update (closure-with-net-fragment closure-value fragment)]
          (cond-> output-messages
            closure-update
            (conj (message closure-id closure-update))))))))

(defn- accumulating-recursive-activate
  [closure-id arg-ids acc-id out-id]
  (fn [_inputs _outputs outer-network]
    (let [closure-cv (net/network-cell-strongest outer-network closure-id)
          closure-value (value/value-payload closure-cv)
          arg-values (mapv #(net/network-cell-strongest outer-network %) arg-ids)]
      (if (or (value/unusable? closure-cv)
              (apply value/any-unusable-values? arg-values)
              (nil? closure-value))
        []
        (let [{:keys [after frame-fragment]}
              (run-recursive-frame
               outer-network
               closure-value
               arg-ids
               out-id
               {:extra-output-ids [acc-id]
                :closure-net-fn
                (fn [closure-net boundary-net]
                  (net/assoc-net-dict-entry
                   closure-net
                   accumulator-key
                   (net/lookup-inner-out boundary-net acc-id)))})
              output-messages (vec (diff/diff-internal-output-cells
                                    after
                                    outer-network
                                    [out-id acc-id]))]
          (cond-> output-messages
            frame-fragment
            (conj (message acc-id frame-fragment))))))))

(defn- normalize-arg-ids
  [arg-ids]
  (if (ids/node-id? arg-ids)
    [arg-ids]
    (vec arg-ids)))

(defn p:recursive-compound
  "Apply a recursive closure-valued cell to argument cells and one output cell."
  [closure-id arg-ids out-id]
  (let [args (normalize-arg-ids arg-ids)]
    (prop/construct-propagator
     (recursive-activate closure-id args out-id)
     (into [closure-id] args)
     [out-id])))

(defn p:self-refining-recursive-compound
  "Experimental recursive compound that writes discovered frame facts back into the closure cell."
  [closure-id arg-ids out-id]
  (let [args (normalize-arg-ids arg-ids)]
    (prop/construct-propagator
     (self-refining-recursive-activate closure-id args out-id)
     (into [closure-id] args)
     [closure-id out-id])))

(defn p:accumulating-recursive-compound
  "Experimental recursive compound that writes discovered frame facts into an explicit accumulator cell."
  [closure-id arg-ids acc-id out-id]
  (let [args (normalize-arg-ids arg-ids)]
    (prop/construct-propagator
     (accumulating-recursive-activate closure-id args acc-id out-id)
     (into [closure-id acc-id] args)
     [acc-id out-id])))
