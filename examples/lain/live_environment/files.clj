(ns examples.lain.live-environment.files
  "Experimental file transactions over a standard session and immutable Net."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [examples.lain.live-environment.ownership :as ownership]
            [examples.lain.live-environment.publication :as publication]
            [examples.lain.live-environment.retirement :as retirement]
            [examples.lain.live-environment.source :as source]
            [propagators.cell-evaluator :as evaluator]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.boundary.effects :as effects]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn canonical-path [file]
  (.getCanonicalPath (io/file file)))

(defn- bootstrap [state]
  (let [candidate (atom state)]
    (runtime/install-session-extension! candidate ttms/session-extension
                                        {:outbox-id (runtime-ids/boundary-outbox-id)})
    @candidate))

(defn- run-tasks [state tasks]
  (let [[network ledger] (ownership/run (::ledger state {:nodes {} :sources {}})
                                        (:program/net state) tasks)]
    (assoc state :program/net network ::ledger ledger)))

(defn- install-result [state lifetime installed]
  (let [before (:program/net state)
        ledger (ownership/record-installation (::ledger state {:nodes {} :sources {}})
                                               lifetime before (:net installed))]
    (run-tasks (assoc state :program/net (:net installed) ::ledger ledger)
               (:props installed))))

(defn- public-id [state name]
  (env/resolve-binding-id (:program/net state) (:program/env state) name))

(defn- ensure-public [state names]
  (reduce
   (fn [current name]
     (if (public-id current name)
       current
       (let [id (ids/new-node-id)
             declared (env/declare-bindings (:program/net current) (:program/env current)
                                             [[name (env/cell-binding id)]])]
         (-> current
             (assoc :program/net (:net declared))
             (update ::public (fnil conj #{}) id)
             (run-tasks (:props declared))))))
   state (sort-by str names)))

(defn- check-claims! [state file names]
  (doseq [name names]
    (let [id (public-id state name)
          owner (get (::claims state) id)]
      (when (or (not (contains? (::public state) id))
                (and owner (not= file owner)))
        (throw (ex-info "File export conflicts with an existing binding"
                        {:file file :name name :owner owner}))))))

(defn- check-effect-bindings! [state names]
  (let [effects (into #{} (map :effect/symbol)
                      (mapcat #(get-in % [:declaration :extension/effects])
                              (vals (:session/extensions state))))
        used (set/intersection effects names)]
    (when (seq used)
      (throw (ex-info "File experiment does not yet own runtime effect retirement"
                      {:bindings used})))))

(defn- withdraw [state file epoch]
  (let [entry (get-in state [::files file])
        [tasks network] (evaluator/evaluate-all
                         (publication/withdrawal (vals (:exports entry)) epoch)
                         (:program/net state))]
    (run-tasks (assoc state :program/net network) tasks)))

(defn- retire-file [state file]
  (if-let [entry (get-in state [::files file])]
    (let [ledger (::ledger state)
          removed (ownership/retired-nodes ledger (:generation entry))
          network (retirement/retire (:program/net state) ledger removed)]
      (-> state
          (assoc :program/net network ::ledger (ownership/forget ledger removed)
                 ::last-retired removed)
          (update ::claims #(apply dissoc % (vals (:exports entry))))
          (update ::files dissoc file)))
    (assoc state ::last-retired #{})))

(defn- compile-file [state context inspected]
  (let [{:keys [file revision generation]} context
        names (:exports inspected)
        bindings (into (mapv (fn [name] [name (env/cell-binding (ids/new-node-id))]) names)
                       (map (fn [name] [name (env/cell-binding (public-id state name))]))
                       (:imports inspected))
        declared (env/declare-root (:program/net state) (ids/new-node-id) bindings)
        compiled (compiler/compile-program
                  (if (seq (:forms inspected)) (:forms inspected) [nil]) (:env declared)
                  {:net (:net declared) :environment-props (:props declared)
                   :seed [::file file revision]})
        installed (install-result state #{generation} compiled)
        exports (into {} (map (fn [name]
                               [name {:private (env/resolve-binding-id
                                                (:program/net installed) (:env compiled) name)
                                      :public (public-id installed name)}])) names)]
    {:state installed :exports exports :environment (:env compiled)}))

(defn- check-import-writes! [before after names]
  (doseq [name names]
    (let [id (public-id before name)]
      (when-not (= (net/network-cell-content (:program/net before) id)
                   (net/network-cell-content (:program/net after) id))
        (throw (ex-info "File preparation wrote an imported public cell"
                        {:name name :id id}))))))

(defn- publish-file [state context exports]
  (let [{:keys [epoch generation]} context
        installed (publication/install (:program/net state) (vals exports) epoch)
        sources (reduce (fn [ledger {:keys [public]}]
                          (assoc-in ledger [:sources public epoch] generation))
                        (::ledger state) (vals exports))
        recorded (ownership/record-installation sources #{generation}
                                                (:program/net state) (:network installed))]
    (run-tasks (assoc state :program/net (:network installed) ::ledger recorded)
               (:tasks installed))))

(defn- check-effects! [before after]
  (when-not (= (effects/outbox-effects (:program/net before))
               (effects/outbox-effects (:program/net after)))
    (throw (ex-info "File experiment does not yet own runtime effect retirement" {}))))

(defn prepare-replacement
  "Pure candidate preparation apart from execution-local receipt collection.
  No external effect drain and no live session mutation occurs here."
  [before {:keys [file source expected-revision]}]
  (when-not (string? source)
    (throw (ex-info "File source must be a string" {:file file :source-type (type source)})))
  (let [file (canonical-path file)
        prior (get-in before [::files file])
        revision (get-in before [::revisions file] 0)]
    (when (and (some? expected-revision) (not= expected-revision revision))
      (throw (ex-info "Stale file revision" {:file file :expected expected-revision :actual revision})))
    (if (= source (:source prior))
      {:state before :receipt {:status :unchanged :file file :revision revision :retired #{}}}
      (let [inspected (source/inspect source)
            state (ensure-public (bootstrap before) (set/union (:exports inspected) (:imports inspected)))
            _ (check-claims! state file (:exports inspected))
            _ (check-effect-bindings! state (:imports inspected))
            epoch (+ 2 (::tick state 0))
            generation [file (inc revision)]
            context {:file file :revision (inc revision) :generation generation :epoch epoch}
            withdrawn (withdraw state file (dec epoch))
            retired-state (retire-file withdrawn file)
            {:keys [state exports environment]} (compile-file retired-state context inspected)
            _ (check-import-writes! retired-state state (:imports inspected))
            published (publish-file state context exports)
            {:keys [retired reused]}
            (retirement/classify-reuse (:program/net before) (:program/net published)
                                       (::last-retired published))
            _ (check-effects! before published)
            public (into {} (map (fn [[name ids]] [name (:public ids)])) exports)
            completed (-> published
                          (assoc ::tick epoch ::last-retired retired ::last-reused reused)
                          (assoc-in [::revisions file] (inc revision))
                          (assoc-in [::files file] {:source source :revision (inc revision)
                                                    :generation generation :epoch epoch
                                                    :environment environment :exports public})
                          (update ::claims merge (zipmap (vals public) (repeat file))))]
        {:state completed
         :receipt {:status :replaced :file file :revision (inc revision) :epoch epoch
                   :exports public :retired retired :reused reused}}))))

(defn- commit! [session before after]
  (when-not (compare-and-set! session before after)
    (throw (ex-info "Session changed during file preparation" {}))))

(defn replace-file!
  "Replace only this file. A failed candidate leaves the exact live state intact.
  Omit :source to read the file once at the runtime boundary."
  [session request]
  (let [request (if (contains? request :source)
                  request
                  (assoc request :source (slurp (:file request))))]
    (locking session
      (let [before @session
            {:keys [state receipt]} (prepare-replacement before request)]
        (commit! session before state)
        receipt))))

(defn remove-file! [session file]
  (locking session
    (let [file (canonical-path file)
          before @session]
      (if-let [entry (get-in before [::files file])]
        (let [epoch (inc (::tick before 0))
              candidate (retire-file (withdraw before file epoch) file)]
          (commit! session before (assoc candidate ::tick epoch))
          {:status :removed :file file :revision (:revision entry)
           :retired (::last-retired candidate)})
        {:status :absent :file file :retired #{}}))))

(defn snapshot [session]
  @session)

(defn value-at [session name]
  (let [state @session]
    (if-let [id (public-id state name)]
      (net/network-cell-strongest (:program/net state) id)
      (throw (ex-info "Unknown public binding" {:name name})))))
