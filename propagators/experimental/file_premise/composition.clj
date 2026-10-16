(ns propagators.experimental.file-premise.composition
  "Pure file composition declarations; no file reads or runtime worlds."
  (:require [clojure.string :as str]))

(defrecord Composition [modules])

(def empty-composition (->Composition []))

(defn composition? [candidate]
  (and (instance? Composition candidate)
       (vector? (:modules candidate))
       (every? (fn [{:keys [path alias]}]
                 (and (string? path) (not (str/blank? path)) (simple-symbol? alias)))
               (:modules candidate))
       (= (count (:modules candidate))
          (count (set (map :alias (:modules candidate)))))))

(defn load-declaration
  "Append a module declaration. Aliases arrive from Lain as unqualified strings;
  path canonicalization and all file reads belong to boundary execution."
  [composition path alias]
  (cond
    (not (composition? composition))
    (throw (ex-info "file-load expects a file composition" {}))

    (or (not (string? path)) (str/blank? path))
    (throw (ex-info "file-load expects a nonempty path" {:path path}))

    (or (not (string? alias)) (str/blank? alias)
        (not (simple-symbol? (symbol alias))))
    (throw (ex-info "file-load expects an unqualified alias string" {:alias alias}))

    (some #(= (symbol alias) (:alias %)) (:modules composition))
    (throw (ex-info "File alias already declared" {:alias alias}))

    :else
    (update composition :modules conj {:path path :alias (symbol alias)})))
