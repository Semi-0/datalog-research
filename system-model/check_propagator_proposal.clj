(ns system-model.check-propagator-proposal
  "Read-only model checks using the installed Kiroshi validator and proposer."
  (:require [agent-memory.db :as db]
            [agent-memory.model :as model]
            [agent-memory.validation :as validation]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]))

(def repository "/Users/pandilyn/Documents/ChatGPT/datalog research")
(def proposal-path "system-model/propagator-infrastructure-proposal.edn")
(def required-checks
  [:fact-shape :relations :hierarchy :evidence-paths :supersession :candidate-overlap])

(defn require-condition [condition description data]
  (when-not condition
    (throw (ex-info description data))))

(defn check-proposal []
  (let [started (java.util.Date.)
        proposal (edn/read-string (slurp (io/file repository proposal-path)))
        facts (mapv validation/normalize-memory (:facts proposal))
        index (into {} (map (juxt :id identity)) facts)
        components (filterv #(= :architecture-component (:type %)) facts)
        boundaries (filterv #(= :boundary-assignment (:type %)) facts)
        parents (group-by (comp first :child-component) boundaries)
        root :component/propagator-infrastructure
        context (db/open repository)
        before (db/status context)]
    (require-condition (= (count facts) (count index)) "Duplicate fact IDs" {})
    (doseq [fact facts]
      (require-condition (and (inst? (:created-at fact)) (seq (:scope fact))
                              (= :candidate (:lifecycle fact)))
                         "Missing metadata or non-candidate fact" {:id (:id fact)})
      (doseq [[relation target-type] validation/relation-types
              target (get fact relation)]
        (require-condition (contains? index target) "Dangling relation" {:target target})
        (when target-type
          (require-condition (= target-type (:type (index target)))
                             "Wrong relation target kind" {:target target})))
      (doseq [tag (:direct-tags fact)]
        (require-condition (= :tag (:type (index tag))) "Missing tag" {:tag tag}))
      (doseq [prior (:supersedes fact)]
        (require-condition (and (not= prior (:id fact))
                                (= (:type fact) (:type (index prior))))
                           "Invalid supersession target" {:id (:id fact) :prior prior}))
      (when (contains? #{:architecture-component :boundary-assignment :principle}
                       (:type fact))
        (require-condition (seq (:supported-by fact)) "Missing support" {:id (:id fact)}))
      (when (contains? #{:system :purpose :principle :constraint :decision :assumption
                         :failure-mode :open-question :evidence} (:type fact))
        (require-condition (seq (:applies-to fact)) "Missing applicability" {:id (:id fact)}))
      (when (= :constraint (:type fact))
        (require-condition
         (some #(and (= :principle (:type %))
                     (some #{(:id fact)} (:motivates %))) facts)
         "Unmotivated constraint" {:id (:id fact)}))
      (when (= :evidence (:type fact))
        (require-condition (seq (:implemented-in fact)) "Missing evidence files" {})
        (doseq [path (:implemented-in fact)]
          (require-condition
           (zero? (:exit (shell/sh "git" "ls-files" "--error-unmatch" "--" path
                                  :dir repository)))
           "Evidence file is not tracked" {:path path})))
      (when (not= :evidence (:type fact))
        (require-condition (empty? (:implemented-in fact)) "Path outside Evidence" {})))
    (doseq [component components]
      (let [id (:id component)]
        (if (= id root)
          (require-condition (empty? (parents id)) "Root has parent" {})
          (require-condition (= 1 (count (parents id))) "Non-root parent count" {:id id}))
        (loop [current id visited #{}]
          (require-condition (not (contains? visited current)) "Hierarchy cycle" {:id id})
          (when (not= current root)
            (let [parent (first (:parent-component (first (parents current))))]
              (require-condition (some? parent) "Orphan component" {:id current})
              (recur parent (conj visited current)))))))
    (let [overlaps (mapv #(select-keys (model/propose context %)
                                     [:recommendation :exact-id-match :semantic-neighbors
                                      :persisted]) facts)
          after (db/status context)]
      (require-condition (= (:types before) (:types after)) "Model facts changed" {})
      (require-condition (every? #(false? (:persisted %)) overlaps) "Persisted proposal" {})
      {:started-at started :finished-at (java.util.Date.)
       :head (:git-head after) :required-checks required-checks
       :checks (zipmap required-checks (repeat :passed))
       :facts (count facts) :by-kind (frequencies (map :type facts))
       :components (count components) :boundaries (count boundaries)
       :overlaps (frequencies (map :recommendation overlaps))
       :model-facts-before (:types before) :model-facts-after (:types after)
       :evidence-blobs
       (into (sorted-map)
             (map (fn [path]
                    [path (:out (shell/sh "git" "rev-parse" (str "HEAD:" path)
                                          :dir repository))]))
             (distinct (mapcat :implemented-in facts)))})))

(pprint/pprint (check-proposal))
(shutdown-agents)
