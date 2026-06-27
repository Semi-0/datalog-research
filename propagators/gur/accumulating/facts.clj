(ns propagators.gur.accumulating.facts
  "Monotone declaration facts stored in accumulated GUR network values."
  (:require [propagators.gur.subenv.queue :as queue]
            [propagators.network :as net]))

(def frame-index-key [:gur/accumulating :frames])
(def frame-prop-index-key [:gur/accumulating :props])
(def task-index-key [:gur/accumulating :tasks])
(def application-request-index-key [:gur/accumulating :application-requests])

(def ^:private frame-scope-prefix [:gur/accumulating :scope])
(def ^:private frame-declared-prefix [:gur/accumulating :frame-declared])
(def ^:private when-applied-prefix [:gur/accumulating :when-applied])

(defn add-task-facts
  ([n cause tasks]
   (add-task-facts n cause tasks 0))
  ([n cause tasks index]
   (let [prop-ids (queue/task-ids tasks)]
     (if (empty? prop-ids)
       n
       (net/update-net-dict-entry
        n
        task-index-key
        (fn [task-map]
          (reduce (fn [m prop-id]
                    (update m cause
                            (fn [entry]
                              {:prop-ids (conj (set (:prop-ids entry)) prop-id)
                               :indexes (conj (set (:indexes entry)) index)})))
                  (or task-map {})
                  prop-ids)))))))

(defn application-key
  [closure-id arg-ids out-id]
  [:gur/application closure-id (vec arg-ids) out-id])

(defn application-request-fragment
  [closure-id arg-ids out-id]
  (let [app-key (application-key closure-id arg-ids out-id)]
    (net/assoc-net-dict-entry
     net/empty-net
     application-request-index-key
     {app-key {:closure-id closure-id
               :arg-ids (vec arg-ids)
               :out-id out-id}})))

(defn application-requests
  [n]
  (or (net/network-dict-entry n application-request-index-key) {}))

(defn application-requested?
  [n app-key]
  (contains? (application-requests n) app-key))

(defn frame-scope-key
  [app-key]
  (conj frame-scope-prefix app-key))

(defn frame-declared-key
  [app-key]
  (conj frame-declared-prefix app-key))

(defn frame-declared?
  [runtime-net applied-net app-key]
  (or (true? (net/network-dict-entry runtime-net (frame-declared-key app-key)))
      (and (net/net? applied-net)
           (true? (net/network-dict-entry applied-net
                                          (frame-declared-key app-key))))))

(defn record-frame-fragment
  [n app-key scope prop-ids]
  (-> n
      (net/assoc-net-dict-entry (frame-declared-key app-key) true)
      (net/update-net-dict-entry frame-index-key #(conj (or % #{}) app-key))
      (net/update-net-dict-entry frame-prop-index-key
                                 #(into (or % #{}) (queue/task-ids prop-ids)))
      (add-task-facts [:frame app-key] prop-ids)
      (net/assoc-net-dict-entry (frame-scope-key app-key) scope)))

(defn when-applied-key
  [when-key]
  (conj when-applied-prefix when-key))

(defn when-applied?
  [runtime-net when-key]
  (true? (net/network-dict-entry runtime-net (when-applied-key when-key))))

(defn record-when-fragment
  [n when-key prop-ids]
  (-> n
      (net/update-net-dict-entry frame-prop-index-key
                                 #(into (or % #{}) (queue/task-ids prop-ids)))
      (add-task-facts [:when when-key] prop-ids)
      (net/assoc-net-dict-entry (when-applied-key when-key) true)))
