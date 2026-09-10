(ns propagators.gur.accumulating.core
  "Accumulating GUR closure values and declaration-time frame accumulation."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.gur.accumulating.ids :as acc-ids]
            [propagators.gur.subenv.env :as env]
            [propagators.gur.subenv.scoped-slot :as scoped-slot]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def recursive-closure-tag :gur/accumulating-recursive-closure?)
(def declaration-id-key :gur/declaration-id)
(def declaration-key :gur/declaration)
(def captured-cell-ids-key :gur/captured-cell-ids)
(def declare-frame?-key :gur/declare-frame?)
(def boundary-cell-ids-key :gur/boundary-cell-ids)
(def application-boundary-cell-ids-key :gur/application-boundary-cell-ids)
(def boundary-output-cell-ids-key :gur/boundary-output-cell-ids)
(def boundary-dict-keys-key :gur/boundary-dict-keys)

(defn declare-topology-when-operator-usable
  [_arg-values _out-value]
  true)

(defn recursive-closure
  ([name body-fn]
   (recursive-closure name body-fn {}))
  ([name body-fn {:keys [declaration-id declaration captured-cell-ids
                         declare-frame? boundary-cell-ids
                         application-boundary-cell-ids
                         boundary-output-cell-ids boundary-dict-keys]}]
   (cond
     (not (ifn? body-fn))
     (throw (ex-info "GUR closure body must be callable"
                     {:name name :body body-fn}))

     :else
     (let [base {recursive-closure-tag true
                 :gur/name name
                 :gur/body body-fn
                 captured-cell-ids-key (vec captured-cell-ids)}
           with-declaration-id
           (cond
             (some? declaration-id)
             (assoc base declaration-id-key declaration-id)

             :else
             base)
           with-declaration
           (cond
             (some? declaration)
             (assoc with-declaration-id declaration-key declaration)

             :else
             with-declaration-id)
           with-readiness
           (cond
             (fn? declare-frame?)
             (assoc with-declaration declare-frame?-key declare-frame?)

             (nil? declare-frame?)
             with-declaration

             :else
             (throw (ex-info "GUR frame readiness must be callable"
                             {:name name
                              :declare-frame? declare-frame?})))]
       (let [with-boundary-inputs
             (cond
               (fn? boundary-cell-ids)
               (assoc with-readiness boundary-cell-ids-key boundary-cell-ids)

               (nil? boundary-cell-ids)
               with-readiness

               :else
               (throw (ex-info "GUR boundary projection must be callable"
                               {:name name
                                :boundary-cell-ids boundary-cell-ids})))]
         (let [with-application-boundary
               (cond
                 (fn? application-boundary-cell-ids)
                 (assoc with-boundary-inputs
                        application-boundary-cell-ids-key
                        application-boundary-cell-ids)

                 (nil? application-boundary-cell-ids)
                 with-boundary-inputs

                 :else
                 (throw (ex-info
                         "GUR application boundary projection must be callable"
                         {:name name
                          :application-boundary-cell-ids
                          application-boundary-cell-ids})))
               with-boundary-outputs
               (cond
           (fn? boundary-output-cell-ids)
           (assoc with-application-boundary
                  boundary-output-cell-ids-key
                  boundary-output-cell-ids)

           (nil? boundary-output-cell-ids)
           with-application-boundary

           :else
           (throw (ex-info "GUR boundary output projection must be callable"
                           {:name name
                            :boundary-output-cell-ids
                            boundary-output-cell-ids})))]
           (cond
             (sequential? boundary-dict-keys)
             (assoc with-boundary-outputs
                    boundary-dict-keys-key
                    (vec boundary-dict-keys))

             (nil? boundary-dict-keys)
             with-boundary-outputs

             :else
             (throw (ex-info "GUR boundary dictionary keys must be sequential"
                             {:name name
                              :boundary-dict-keys boundary-dict-keys})))))))))

(defn recursive-closure?
  [x]
  (and (map? x)
       (true? (get x recursive-closure-tag))
       (ifn? (get x :gur/body))))

(defn captured-cell-ids
  [closure]
  (cond
    (recursive-closure? closure)
    (vec (get closure captured-cell-ids-key []))

    :else
    []))

(defn projected-boundary-cell-ids
  [closure runtime-net arg-ids]
  (let [project-boundary (get closure boundary-cell-ids-key)]
    (cond
      (fn? project-boundary)
      (let [projected (project-boundary runtime-net (vec arg-ids))]
        (cond
          (sequential? projected)
          (vec (filter ids/node-id? projected))

          (nil? projected)
          []

          :else
          (throw (ex-info "GUR boundary projection must return cell IDs"
                          {:closure-name (:gur/name closure)
                           :projected projected}))))

      (nil? project-boundary)
      []

      :else
      (throw (ex-info "invalid GUR boundary projection"
                      {:closure-name (:gur/name closure)
                       :boundary-cell-ids project-boundary})))))

(defn projected-boundary-output-cell-ids
  [closure runtime-net arg-ids]
  (let [project-boundary (get closure boundary-output-cell-ids-key)]
    (cond
      (fn? project-boundary)
      (let [projected (project-boundary runtime-net (vec arg-ids))]
        (cond
          (sequential? projected)
          (vec (filter ids/node-id? projected))

          (nil? projected)
          []

          :else
          (throw (ex-info "GUR boundary output projection must return cell IDs"
                          {:closure-name (:gur/name closure)
                           :projected projected}))))

      (nil? project-boundary)
      []

      :else
      (throw (ex-info "invalid GUR boundary output projection"
                      {:closure-name (:gur/name closure)
                       :boundary-output-cell-ids project-boundary})))))

(defn projected-application-boundary-cell-ids
  [closure runtime-net arg-ids out-id]
  (let [project-boundary (get closure application-boundary-cell-ids-key)]
    (cond
      (fn? project-boundary)
      (let [projected (project-boundary runtime-net (vec arg-ids) out-id)]
        (cond
          (sequential? projected)
          (vec (filter ids/node-id? projected))

          (nil? projected)
          []

          :else
          (throw (ex-info
                  "GUR application boundary projection must return cell IDs"
                  {:closure-name (:gur/name closure)
                   :projected projected}))))

      (nil? project-boundary)
      []

      :else
      (throw (ex-info "invalid GUR application boundary projection"
                      {:closure-name (:gur/name closure)
                       :application-boundary-cell-ids project-boundary})))))

(defn boundary-dict-keys
  [closure]
  (cond
    (recursive-closure? closure)
    (vec (get closure boundary-dict-keys-key []))

    :else
    []))

(defn strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn strip-compiler-symbols
  [n]
  (net/net-with-dict
   n
   (into {}
         (remove (fn [[k _v]] (symbol? k)))
         (net/net-dict-or-empty n))))

(defn bind-local-alias
  [n scope name id]
  (-> n
      (env/bind-in-scope scope name id)
      (env/bind-in-scope scope (keyword (clojure.core/name name)) id)))

(defn boundary-cell-ids
  [source-net ids values]
  (distinct
   (concat ids
           (mapcat #(scoped-slot/accessor-parent-cell-ids source-net %)
                   values))))

(defn- frame-scope
  [closure app-key]
  [:gur/accumulating-frame (:gur/name closure) app-key])

(defn- ensure-cell-value
  [n id v]
  (-> n
      (nb/ensure-cell id)
      (nb/seed-cell id v)))

(defn- copy-runtime-cell
  [n runtime-net id]
  (if-let [entry (and (ids/node-id? id)
                      (contains? (net/net-env runtime-net) id)
                      (net/network-env-lookup runtime-net id))]
    (if (cell/cell? entry)
      (cond-> (-> n
                  (nb/ensure-cell id)
                  (net/assoc-net-cell id (cell/cell (cell/cell-content entry)
                                                    (cell/cell-strongest entry))))
        (contains? (net/net-graph runtime-net) id)
        (net/assoc-net-node id (get (net/net-graph runtime-net) id)))
      n)
    (nb/ensure-cell n id)))

(defn- copy-boundary-cells
  [frame-net runtime-net ids values]
  (reduce #(copy-runtime-cell %1 runtime-net %2)
          frame-net
          (boundary-cell-ids runtime-net ids values)))

(defn- bind-frame-args
  [frame-net scope arg-ids]
  (reduce (fn [n [i id]]
            (env/bind-in-scope n scope [:arg i] id))
          frame-net
          (map-indexed vector arg-ids)))

(defn- normalize-body-result
  [result]
  (if (and (map? result) (contains? result :net))
    {:net (:net result)
     :prop-ids (vec (:prop-ids result))}
    {:net result
     :prop-ids []}))

(defn- frame-context
  [closure closure-id self-id applied-net-id scope arg-values app-key declaration]
  {:closure closure
   :closure-id closure-id
   :self-id self-id
   :applied-net-id applied-net-id
   :scope scope
   :arg-values (vec arg-values)
   :application-key app-key
   :application-declaration declaration})

(defn build-frame-fragment
  [runtime-net closure-id arg-ids applied-net-id out-id closure arg-values out-value
   declaration]
  (let [app-key (facts/application-key closure-id arg-ids out-id)
        scope (frame-scope closure app-key)
        self-id (acc-ids/stable-node-id [app-key :self])
        captured-ids (captured-cell-ids closure)
        projected-ids (projected-boundary-cell-ids closure runtime-net arg-ids)
        application-boundary-ids
        (projected-application-boundary-cell-ids closure runtime-net arg-ids out-id)
        declared-boundary-ids (facts/declaration-cell-ids declaration)
        captured-values (mapv #(strongest-or-nothing runtime-net %)
                              captured-ids)
        projected-values (mapv #(strongest-or-nothing runtime-net %)
                               projected-ids)
        application-boundary-values
        (mapv #(strongest-or-nothing runtime-net %)
              application-boundary-ids)
        declared-boundary-values (mapv #(strongest-or-nothing runtime-net %)
                                       declared-boundary-ids)]
    ;; ponytail: body declaration uses global id generation; pin it per frame or
    ;; repeated accumulation grows topology.
    (with-redefs [ids/new-node-id (acc-ids/stable-id-generator app-key)]
      (let [base (-> net/empty-net
                     (copy-runtime-cell runtime-net closure-id)
                     (copy-boundary-cells runtime-net
                                          captured-ids
                                          captured-values)
                     (copy-boundary-cells runtime-net
                                          projected-ids
                                          projected-values)
                     (copy-boundary-cells runtime-net
                                          application-boundary-ids
                                          application-boundary-values)
                     (copy-boundary-cells runtime-net
                                          declared-boundary-ids
                                          declared-boundary-values)
                     (copy-boundary-cells runtime-net
                                          (conj (vec arg-ids) out-id)
                                          (conj (vec arg-values) out-value))
                     (nb/ensure-cell applied-net-id)
                     (ensure-cell-value self-id closure)
                     (env/bind-in-scope scope :self self-id)
                     (env/bind-in-scope scope :out out-id)
                     (bind-frame-args scope arg-ids))
            ctx (frame-context closure closure-id self-id applied-net-id scope
                               arg-values app-key declaration)
            body-result (normalize-body-result
                         ((:gur/body closure)
                          ctx
                          base
                          arg-ids
                          out-id))
            prop-ids (:prop-ids body-result)]
        (facts/record-frame-fragment (:net body-result)
                                     app-key
                                     scope
                                     prop-ids)))))

(defn- enough-information-to-apply?
  [closure arg-values out-value]
  (let [declare-frame? (get closure declare-frame?-key)]
    (cond
      (value/unusable? closure)
      false

      (fn? declare-frame?)
      (true? (declare-frame? arg-values out-value))

      (nil? declare-frame?)
      (or (not (apply value/any-unusable-values? arg-values))
          (not (value/unusable? out-value)))

      :else
      (throw (ex-info "invalid GUR frame readiness predicate"
                      {:closure-name (:gur/name closure)
                       :declare-frame? declare-frame?})))))

(defn- apply-state
  [runtime-net closure-id arg-ids out-id]
  {:closure (strongest-or-nothing runtime-net closure-id)
   :arg-values (mapv #(strongest-or-nothing runtime-net %) arg-ids)
   :out-value (strongest-or-nothing runtime-net out-id)})

(defn- accumulate-apply-messages
  [runtime-net closure-id arg-ids applied-net-id out-id]
  (let [app-key (facts/application-key closure-id arg-ids out-id)
        applied-net (strongest-or-nothing runtime-net applied-net-id)
        {:keys [closure arg-values out-value]}
        (apply-state runtime-net closure-id arg-ids out-id)]
    (cond
      (facts/frame-declared? runtime-net applied-net app-key)
      []

      (or (facts/application-requested? runtime-net app-key)
          (and (net/net? applied-net)
               (facts/application-requested? applied-net app-key)))
      []

      (not (enough-information-to-apply? closure arg-values out-value))
      []

      (not (recursive-closure? closure))
      []

      :else
      [(message applied-net-id
                (facts/application-request-fragment
                 closure-id
                 arg-ids
                 out-id
                 (facts/application-declaration runtime-net app-key)))])))

;; Accumulating GUR has one owner frame network. Expanding a child request can
;; wake parent-frame props again, so the runner owns duplicate-work prevention.

(defn p:accumulate-apply-closure
  [closure-id arg-ids applied-net-id out-id]
  (let [arg-ids (vec arg-ids)
        inputs (vec (distinct (conj (into [closure-id] arg-ids) out-id)))]
    (fn [network]
      (let [n0 (reduce nb/ensure-cell network
                       (conj (into inputs [applied-net-id]) out-id))
            [prop-id n1] ((prop/construct-propagator
                           (fn [_inputs _outputs runtime-net]
                             (accumulate-apply-messages runtime-net
                                                        closure-id
                                                        arg-ids
                                                        applied-net-id
                                                        out-id))
                           inputs
                           [])
                          n0)
            entry (net/network-lookup-propagator n1 prop-id)]
        [prop-id (net/assoc-net-prop n1 prop-id (assoc entry :observe :inputs))]))))

(defn- delayed-body-fragment
  [runtime-net when-key declare-body]
  ;; ponytail: deterministic body ids keep repeated non-nothing checks idempotent.
  (with-redefs [ids/new-node-id (acc-ids/stable-id-generator [when-key :body])]
    (let [{:keys [net prop-ids]} (declare-body runtime-net)]
      (-> net
          strip-compiler-symbols
          (facts/record-when-fragment when-key prop-ids)))))

(defn p:when-declaration
  "Presence-gated topology declaration shared by GUR front ends."
  [condition-id applied-net-id declare-body]
  (let [prop-id (ids/new-node-id)
        when-key [:gur/accumulating :when prop-id]]
    (cond
      (not (ifn? declare-body))
      (throw (ex-info "GUR when declaration must be callable"
                      {:condition-id condition-id
                       :applied-net-id applied-net-id}))

      :else
      (prop/construct-propagator
       prop-id
       (fn [_inputs _outputs runtime-net]
         (let [condition (strongest-or-nothing runtime-net condition-id)]
           (cond
             (value/nothing? condition)
             []

             (value/contradiction? condition)
             [(message applied-net-id value/contradiction)]

             (facts/when-applied? runtime-net when-key)
             []

             :else
             [(message applied-net-id
                       (delayed-body-fragment runtime-net
                                              when-key
                                              declare-body))])))
       [condition-id]
       []))))

(defn p:when-topology
  "Presence-gated topology builder. `nothing` waits; any other value builds."
  [condition-id bindings body-expr installers applied-net-id]
  (p:when-declaration
   condition-id
   applied-net-id
   (fn [runtime-net]
     (let [{:keys [net props]} (compile/eval-net-with-bindings
                                runtime-net
                                installers
                                bindings
                                body-expr)]
       {:net net
        :prop-ids props}))))

(defn contextual-api
  [{:keys [self-id applied-net-id]}]
  {:apply (fn [network closure-id arg-ids out-id]
            ((p:accumulate-apply-closure closure-id
                                         arg-ids
                                         applied-net-id
                                         out-id)
             network))
   :recur (fn [network arg-ids out-id]
            ((p:accumulate-apply-closure self-id
                                         arg-ids
                                         applied-net-id
                                         out-id)
             network))
   :when (fn [network condition-id bindings body-expr installers]
           ((p:when-topology condition-id
                             bindings
                             body-expr
                             installers
                             applied-net-id)
            network))})
