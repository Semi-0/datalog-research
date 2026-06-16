(ns propagators.gur.subenv.env
  "Lexical sub-env dictionary keys and dispatch registration."
  (:require [propagators.ids :as ids]
            [propagators.network :as net]))

(def scope-key [:env/scope])
(def parent-scope-key [:env/parent-scope])
(def dispatch-tags #{:dispatch/local :dispatch/subenv :dispatch/subenv-ref})
(def env-dispatch-tags #{:env/scope :env/ref :env/cell-ref})

(defn scope-ref [scope] [:env/scope scope])
(defn name-ref [scope name] [:env/ref scope name])
(defn cell-ref [scope local-id] [:env/cell-ref scope local-id])
(defn bind-key [name] [:env/bind name])

(defn extend-env
  ([n scope] (extend-env n scope nil))
  ([n scope parent-scope]
   (cond-> (net/assoc-net-dict-entry n scope-key scope)
     parent-scope (net/assoc-net-dict-entry parent-scope-key parent-scope))))

(defn bind
  [n name local-id]
  (net/assoc-net-dict-entry n (bind-key name) local-id))

(defn current-scope
  [n]
  (net/network-dict-entry n scope-key))

(defn- bind-entry?
  [k]
  (and (vector? k)
       (= 2 (count k))
       (= :env/bind (first k))))

(defn bindings
  [child-net]
  (->> (net/net-dict-or-empty child-net)
       (keep (fn [[k local-id]]
               (when (and (bind-entry? k) (ids/node-id? local-id))
                 [(second k) local-id])))))

(defn- subenv-scope
  [child-net]
  (when (net/net? child-net)
    (net/network-dict-entry child-net scope-key)))

(defn- env-dispatch-key?
  [k]
  (and (vector? k)
       (contains? env-dispatch-tags (first k))
       (> (count k) 1)))

(defn- env-dispatch-scope
  [k]
  (when (env-dispatch-key? k)
    (second k)))

(defn- register-direct-binding
  [parent-net owner-id scope [name local-id]]
  (-> parent-net
      (net/assoc-net-dict-entry (name-ref scope name)
                                [:dispatch/subenv owner-id local-id])
      (net/assoc-net-dict-entry (cell-ref scope local-id)
                                [:dispatch/subenv owner-id local-id])))

(defn- register-direct-bindings
  [parent-net owner-id scope child-net]
  (reduce #(register-direct-binding %1 owner-id scope %2)
          parent-net
          (bindings child-net)))

(defn- lifted-nested-dispatch-key?
  [scope k]
  (and (env-dispatch-key? k)
       (not= scope (env-dispatch-scope k))))

(defn- register-lifted-nested-ref
  [parent-net owner-id target]
  (net/assoc-net-dict-entry parent-net
                            target
                            [:dispatch/subenv-ref owner-id target]))

(defn- register-lifted-nested-refs
  [parent-net owner-id scope child-net]
  (reduce-kv
   (fn [n k _v]
     (if (lifted-nested-dispatch-key? scope k)
       (register-lifted-nested-ref n owner-id k)
       n))
   parent-net
   (net/net-dict-or-empty child-net)))

(defn register-subenv-from-owner
  [parent-net owner-id child-net]
  (if-let [scope (subenv-scope child-net)]
    (-> parent-net
        (net/assoc-net-dict-entry (scope-ref scope) owner-id)
        (register-direct-bindings owner-id scope child-net)
        (register-lifted-nested-refs owner-id scope child-net))
    parent-net))

(defn maybe-register-subenv
  [parent-net owner-id strongest]
  (if (subenv-scope strongest)
    (register-subenv-from-owner parent-net owner-id strongest)
    parent-net))

(defn- directory-map
  [directory]
  (if (net/net? directory)
    (net/net-dict-or-empty directory)
    (or directory {})))

(defn- dispatch-entry?
  [entry]
  (and (vector? entry)
       (contains? dispatch-tags (first entry))))

(defn resolve-dispatch
  [directory target _parent-net]
  (let [entry (get (directory-map directory) target)]
    (cond
      (dispatch-entry? entry)
      entry

      (ids/node-id? entry)
      [:dispatch/local entry]

      (ids/node-id? target)
      [:dispatch/local target]

      :else
      (throw (ex-info "unresolvable cell dispatch target"
                      {:target target :entry entry})))))
