(ns propagators.experimental.file-premise.files
  "Immutable experimental file worlds; no watcher, replacement, or retirement."
  (:refer-clojure :exclude [load-file])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.compiler-2.runtime.session.program.source :as source]
            [propagators.core :as core]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.operators :as amb]
            [propagators.experimental.file-premise.compiler :refer [file-compiler]]
            [propagators.experimental.ttms-application :as native]
            [propagators.experimental.ttms-primitives :as primitives]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(defn initial-world []
  (let [s (reduce #(extension/install-session-extension %1 %2 {})
                  (session/empty-state)
                  [primitives/session-extension amb/session-extension native/session-extension])]
    {:net (:program/net s) :env (:program/env s) :props (:program/props s) :files {}}))

(defn- file-forms [path]
  (let [forms (source/read-source-forms (slurp path))
        parsed (mapv compiler/parse-form forms)]
    (if (and (seq parsed)
             (every? #(and (= :def (ast/type %))
                           (= :network (ast/type (ast/body %)))) parsed))
      nil
      (throw (ex-info "Files must contain top-level network definitions" {:path path})))
    (let [names (mapv ast/name parsed)]
      (if (and (every? simple-symbol? names)
               (= (count names) (count (set names))))
        nil
        (throw (ex-info "File exports must have unique unqualified names" {:path path})))
      {:forms forms :names names})))

(defn- available-path [world path alias]
  (if (and (string? path) (simple-symbol? alias))
    nil
    (throw (ex-info "Expected a file path and unqualified alias" {:path path :alias alias})))
  (let [canonical (.getCanonicalPath (io/file path))]
    (cond
      (contains? (:files world) alias)
      (throw (ex-info "File alias already loaded" {:alias alias}))

      (some #(= canonical (:path %)) (vals (:files world)))
      (throw (ex-info "File path already loaded" {:path canonical}))

      :else nil)
    canonical))

(defn- completed-network [result]
  (if (= :completed (:status result))
    (:network result)
    (throw (ex-info "File world evaluation failed" {:status (:status result)} (:error result)))))

(defn load-file
  "Read network definitions into a private lexical frame, supported by one real
  file source cell. Reject duplicate aliases/paths; failure leaves world intact."
  [world path alias]
  (let [path (available-path world path alias)
        {:keys [forms names]} (file-forms path)
        source-id (ids/new-node-id)
        token (collection/content {:base path :support #{{:source source-id :timestamp 1 :premises-status :active}}})
        network (nb/install-cell (:net world) source-id token (collection/strongest-value token (fn [_ b] b)))
        seed [::file path]
        [state bindings] (declarations/declare-local-cells
                          {:net network :env (:env world) :seed seed :path [] :props (:props world)}
                          ::file-frame names)
        compiled (compiler/compile-program forms (:env state)
                                          {:net (:net state) :seed seed :compiler (file-compiler source-id)
                                           :environment-props (:props state)})
        evaluated (completed-network (runner/run-network (:props compiled) (:net compiled)))]
    (-> world
        (assoc :net evaluated)
        (assoc-in [:files alias] {:path path :premise-id source-id :exports bindings}))))

(defn query
  "Compile a single query expression using qualified alias/export bindings and
  the default compiler. Loaded procedures retain their own file compilers."
  [world text]
  (let [bindings (vec (for [[alias file] (:files world) [name binding] (:exports file)]
                        [(symbol (str alias) (str name)) binding]))
        imported (extension/install-extension-bindings (:net world) (:env world) (ids/new-node-id) bindings)
        compiled (compiler/compile-source text (:env imported)
                                          {:net (:net imported)
                                           :environment-props (into (:props world) (:props imported))})
        network (completed-network (runner/run-network (:props compiled) (:net compiled)))]
    (assoc world :net network :compiled compiled)))

(defn publish
  "Publish availability at an explicit epoch. Older epochs are ignored by TTMS;
  withdrawal retains declarations and does not replace or retire callables."
  [world alias epoch status]
  (if (contains? (:files world) alias)
    nil
    (throw (ex-info "Unknown file alias" {:alias alias})))
  (if (and (pos-int? epoch) (#{:active :retracted} status))
    nil
    (throw (ex-info "Expected a positive epoch and active/retracted status"
                    {:epoch epoch :status status})))
  (let [{:keys [premise-id path]} (get-in world [:files alias])
        update (collection/content {:base path :support #{{:source premise-id :timestamp epoch :premises-status status}}})
        [tasks network] (core/eval-cell premise-id (message premise-id update) (:net world))]
    (assoc world :net (nb/run-propagators network tasks))))

(s/def ::net net/network?)
(s/def ::env ids/node-id?)
(s/def ::props (s/coll-of ids/node-id? :kind vector?))
(s/def ::path string?)
(s/def ::premise-id ids/node-id?)
(s/def ::exports (s/map-of symbol? #(ids/node-id? (env/binding-id %))))
(s/def ::file (s/keys :req-un [::path ::premise-id ::exports]))
(s/def ::files (s/map-of simple-symbol? ::file))
(s/def ::world (s/keys :req-un [::net ::env ::props ::files]))
(s/fdef initial-world :args (s/cat) :ret ::world)
(s/fdef load-file :args (s/cat :world ::world :path string? :alias simple-symbol?) :ret ::world)
(s/fdef query :args (s/cat :world ::world :source string?) :ret ::world)
(s/fdef publish :args (s/cat :world ::world :alias symbol? :epoch pos-int?
                            :status #{:active :retracted}) :ret ::world)
