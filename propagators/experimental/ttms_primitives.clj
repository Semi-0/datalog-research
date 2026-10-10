(ns propagators.experimental.ttms-primitives
  "Opt-in scalar procedures and branching. Compiler defaults remain unchanged."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.declaration-effects :as nb]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.experimental.ttms-branching :as branching]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as standard]
            [propagators.stdlib.supported-procedure :as supported]))

(defn- scalar-primitive [name f]
  (prop/primitive-propagator
   name
   (fn [& values]
     (cond
       (some value/contradiction? values)
       (let [reasons (into #{} (mapcat value/contradiction-provenance) values)]
         (value/contradiction-with-provenance reasons))
       (some value/nothing? values) value/nothing
       :else (try (apply f values)
                  (catch Exception error
                    (value/contradiction-with-provenance
                     #{{:operation name :reason (.getMessage error)}})))))))

(defn procedure-operator
  "Compose an existing base procedure with the support and premise-state layers."
  [name base]
  (operator/operator-closure
   {:name [::scalar name]
    :install (fn [network arguments output]
               (let [[installed props selected] (supported/install base network arguments output)]
                 [(nb/register-props installed props) props selected]))}))

(defn scalar-operator [name primitive]
  (procedure-operator name (supported/from-primitive primitive)))

(defn slot-operator
  "Read a slot from the base compound through an ordinary layered application.
  The base's slots are separate from the datum's base/support layers. This
  publishes a derived result; it does not grant writes into the TTMS owner.
  Experimental: nested supported slots and domain :support fields still fail
  the compound-boundary tests in shared transport/readiness. Not a replacement
  for the ordinary bidirectional slot operator."
  [slot]
  (scalar-operator
   [::slot slot]
   (scalar-primitive
    [::slot slot]
    (fn [base]
      (let [compound (if (evidence/evidence-set? base)
                       (evidence/strongest base)
                       base)]
        (cond
          (value/contradiction? compound) compound
          (contains? (obj/public-slot-keys compound) slot)
          (obj/slot-value compound slot)
          :else value/nothing))))))

(def session-extension
  (extension/extension-bundle
   {:id ::scalars
    :bindings
    (into (vec (extension/extension-bindings branching/session-extension))
          (map (fn [[name primitive]] [name (scalar-operator name primitive)]))
          [['+ standard/+] ['- standard/-] ['* standard/*] ['/ standard//]
           ['<= (scalar-primitive '<= <=)] ['not (scalar-primitive 'not not)]
           ['and standard/and] ['or standard/or]
           ['< (scalar-primitive '< <)] ['> (scalar-primitive '> >)]
           ['>= (scalar-primitive '>= >=)] ['= (scalar-primitive '= =)]
           ['str (scalar-primitive 'str str)]])
    :effects []}))
