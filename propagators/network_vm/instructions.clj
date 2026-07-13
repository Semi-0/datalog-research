(ns propagators.network-vm.instructions
  "Data constructors for the experimental monotone network VM instruction set.")

(def task-index-key [:network-vm :tasks])
(def name-bindings-key [:network-vm :name-bindings])

(defn declare-cell
  [id]
  {:network-vm/op :declare-cell
   :id id})

(defn declare-prop
  ([id inputs outputs activate]
   {:network-vm/op :declare-prop
    :id id
    :inputs (vec inputs)
    :outputs (vec outputs)
    :activate activate})
  ([id name inputs outputs activate]
   (assoc (declare-prop id inputs outputs activate) :name name)))

(defn bind-name
  [scope name id]
  {:network-vm/op :bind-name
   :scope scope
   :name name
   :id id})

(defn tell
  [cell-id partial-info]
  {:network-vm/op :tell
   :id cell-id
   :value partial-info})

(defn schedule
  ([cause prop-ids]
   (schedule cause prop-ids 0))
  ([cause prop-ids index]
   {:network-vm/op :schedule
    :cause cause
    :prop-ids (vec prop-ids)
    :index index}))
