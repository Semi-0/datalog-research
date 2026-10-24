(ns graph.lain-editor.workspace.commands
  "Tab-scoped, acknowledged file-open requests. No runtime evaluation."
  (:require [clojure.string :as str]
            [graph.lain-editor.workspace.project-files :as files])
  (:import [java.nio.file Files Path]))

(def operations #{"workspace-poll" "workspaces" "load-file" "load-status"
                  "closed-blocks" "reopen-closed" "reopen-status"})
(def lease-ms 60000)

(defn- require-client [state id]
  (or (get state id) (throw (ex-info "Workspace is not connected; list workspaces first" {}))))

(defn- read-source [root path]
  (let [^Path target (files/project-path root path ".lain")]
    (when (> (Files/size target) (* 1024 1024))
      (throw (ex-info "Lain source exceeds the 1 MiB load limit" {})))
    {:source (Files/readString target) :filename (str (.getFileName target)) :path path}))

(defn- pending-requests [client]
  (filterv #(contains? #{"queued" "delivered"} (:status %)) (:requests client)))

(defn- enqueue! [registry workspace client item]
  (let [pending (pending-requests client)]
    (when (>= (count pending) 16) (throw (ex-info "Workspace command queue is full" {})))
    (let [history (take-last 48 (remove #(contains? #{"queued" "delivered"} (:status %))
                                        (:requests client)))]
      (swap! registry assoc-in [workspace :requests] (conj (into (vec history) pending) item))
      item)))

(defn- request-status [registry workspace request kind]
  (let [client (require-client @registry workspace)
        item (some #(when (= request (:request %)) %) (:requests client))]
    (when-not (and item (= kind (:kind item)))
      (throw (ex-info "Unknown or expired workspace request" {})))
    (select-keys item [:request :pane :path :archive :status :error])))

(defn- acknowledge [client ack]
  (if (nil? ack)
    client
    (let [index (first (keep-indexed #(when (= (:request ack) (:request %2)) %1) (:requests client)))
          request (when (some? index) (get-in client [:requests index]))]
      (when-not (and request (contains? #{"delivered" (:status ack)} (:status request))
                     (contains? #{"applied" "failed"} (:status ack)))
        (throw (ex-info "Invalid workspace acknowledgement" {})))
      (assoc-in client [:requests index]
                (merge request (select-keys ack [:status :error]))))))

(defn command! [registry root {:keys [op workspace snapshot ack request path] :as command}]
  (locking registry
    (let [now (System/currentTimeMillis)]
      (swap! registry #(into {} (filter (fn [[_ client]] (< (- now (:seen client)) lease-ms))) %))
      (case op
        "workspaces"
        {:workspaces (mapv (fn [[id client]] (assoc (:snapshot client) :workspace id)) @registry)}

        "workspace-poll"
        (do
          (when-not (and (string? workspace) (not (str/blank? workspace))
                         (map? snapshot) (string? (:desktop snapshot)) (vector? (:panes snapshot))
                         (or (nil? (:closedBlocks snapshot)) (vector? (:closedBlocks snapshot))))
            (throw (ex-info "Invalid workspace heartbeat" {})))
          (let [existing (get @registry workspace)
                client (-> (or existing {:requests []})
                           (acknowledge (when existing ack)) (assoc :seen now :snapshot snapshot))
                index (first (keep-indexed #(when (contains? #{"queued" "delivered"} (:status %2)) %1) (:requests client)))
                client (if (some? index) (assoc-in client [:requests index :status] "delivered") client)]
            (swap! registry assoc workspace client)
            {:command (when (some? index) (get-in client [:requests index]))}))

        "load-file"
        (let [client (require-client @registry workspace)
              source (read-source root path)
              id (str (random-uuid))
              item (merge source {:request id :pane id :kind "load-file" :status "queued"
                                  :desktop (get-in client [:snapshot :desktop])})]
          (select-keys (enqueue! registry workspace client item)
                       [:request :pane :status :path]))

        "closed-blocks"
        (let [client (require-client @registry workspace)]
          {:closedBlocks (get-in client [:snapshot :closedBlocks] [])})

        "reopen-closed"
        (let [client (require-client @registry workspace)
              archive (:archive command)
              desktop (:desktop command)
              entries (get-in client [:snapshot :closedBlocks] [])]
          (when-not (and (string? archive) (some #(= archive (:id %)) entries))
            (throw (ex-info "Unknown closed block" {})))
          (when-not (= desktop (get-in client [:snapshot :desktop]))
            (throw (ex-info "Target desktop is not active in this workspace" {})))
          (let [id (str (random-uuid))
                item {:request id :pane id :archive archive :kind "reopen-closed"
                      :status "queued" :desktop desktop}]
            (select-keys (enqueue! registry workspace client item)
                         [:request :pane :archive :status])))

        "load-status"
        (request-status registry workspace request "load-file")

        "reopen-status"
        (request-status registry workspace request "reopen-closed")

        (throw (ex-info "Unknown workspace operation" {:op (:op command)}))))))
