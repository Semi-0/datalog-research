(ns experiments.recursive-semantic-views.review-probe
  "Executable architecture proposal only; not installed in a runtime."
  (:require [clojure.test :refer [deftest is run-tests]]
            [propagators.cell-evaluator :as evaluator]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.combinator :as combinator]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.datastructures.compound-object :as object]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support-collection :as collection]
            [propagators.datastructures.support :as support]
            [propagators.experimental.network-history :as history]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.semantic-trace.capture :as capture]))

;; Proposed information-domain adapter. Evidence stays opaque: only the
;; support collection's public constructor, join and projection are used.
(defn publication? [candidate]
  (= ::publication (object/slot-value candidate ::kind)))

(defn retained-evidence [publication]
  (object/slot-value publication ::evidence))

(defn publication [evidence]
  (let [projected (collection/strongest-value
                   evidence #(capture/join %1 %2 nil))
        layers {::kind ::publication
                ::evidence evidence
                :base (datum/layer-value projected :base)
                :support (datum/support-of projected)}]
    (object/compound-object
     (if (datum/layer-present? projected :premise-state)
       (assoc layers :premise-state
              (datum/layer-value projected :premise-state))
       layers))))

(defn evidence [candidate]
  (cond
    (publication? candidate) (retained-evidence candidate)
    (value/nothing? candidate) value/nothing
    (collection/content? candidate) candidate
    :else (throw (ex-info "Capture publication requires supported evidence"
                          {:candidate candidate}))))

(defn publication-pair? [left right _network]
  (or (publication? left) (publication? right)))

(defn join-publications [left right _network]
  (cond
    (value/contradiction? left) left
    (value/contradiction? right) right
    :else (publication
           (collection/merge-content (evidence left) (evidence right)))))

(def session-merge
  (combinator/branch
   publication-pair? join-publications
   capture/capture-pair? capture/join
   merge/cell-merge))

(defn sample-fragment [observation values]
  (capture/fragment
   {:observation observation
    :scopes {:root {:semantic-trace/graph true
                    :nodes {:n 'node}
                    :node-kinds {:n :cell}
                    :values values :edges []}}}))

(defn contribution [source epoch captured]
  (publication
   (collection/content
    {:base captured
     :support #{{:source source :timestamp epoch :premises-status :active}}})))

(defn same-root-evidence? [left right]
  (and (capture/fragment? left)
       (capture/fragment? right)
       (= (capture/support left) (capture/support right))
       (= (dissoc (capture/scope left :root) :semantic-capture/nested-scopes)
          (dissoc (capture/scope right :root) :semantic-capture/nested-scopes))))

(defn next-epoch [previous output]
  (inc (reduce max 0 (keep (fn [premise]
                            (when (= output (:source premise))
                              (:timestamp premise)))
                          (datum/support-of previous)))))

(defn make-root-capture-activation
  "Proposed tracer publisher. Captures only immutable root/output identities.
  Watch installation is a separate tracer responsibility; not implemented here."
  [root output]
  (fn [_inputs _outputs network]
    (let [previous (net/network-cell-strongest network output)
          known (datum/layer-value previous :base)
          epoch (next-epoch previous output)
          source (observation/frozen-snapshot
                  network {:output output :observation/id [output epoch]})
          captured (capture/capture-fragment
                    source {:root root :scope :root :refinement :initial})]
      (if (same-root-evidence? known captured)
        []
        [(message/message
          output
          (publication
           (collection/content
            {:base captured
             :support (support/combine
                       (capture/support captured)
                       #{{:source output :timestamp epoch
                          :premises-status :active}})})))
         (capture/retain-source-patch source captured)]))))

(defn meaning [publication]
  {:evidence (retained-evidence publication)
   :base (datum/layer-value publication :base)
   :support (datum/support-of publication)
   :states (datum/layer-value publication :premise-state)})

(deftest reproduce-the-current-loss
  (let [captured (sample-fragment :original {:n 1})
        supported (collection/content {:base captured :support #{}})]
    (is (capture/fragment? captured))
    (is (not (capture/fragment?
              (data/payload (merge/strongest-value supported net/empty-net)))))))

(deftest proposed-publication-survives-existing-strongest-and-evaluator
  (let [id (ids/new-node-id)
        captured (sample-fragment :original {:n 1})
        incoming (contribution id 1 captured)
        evaluate (evaluator/make-cell-evaluator session-merge)
        [_ settled] (evaluate (message/message id incoming)
                              (builder/install-cell net/empty-net id))
        content (net/network-cell-content settled id)
        strongest (net/network-cell-strongest settled id)]
    (is (publication? content))
    (is (= captured (data/payload strongest)))
    (is (= captured (datum/layer-value strongest :base)))
    (is (= (retained-evidence incoming) (retained-evidence content)))
    (is (= (datum/support-of incoming) (datum/support-of strongest)))
    (is (false? (merge/cell-updated?
                 (publication (retained-evidence incoming)) incoming net/empty-net)))))

(deftest publication-join-preserves-domain-laws-and-freshness
  (let [source (ids/new-node-id)
        a (contribution source 1 (sample-fragment :original {:a 1}))
        b (contribution source 1 (sample-fragment :original {:b 2}))
        c (contribution source 1 (sample-fragment :original {:c 3}))
        join #(session-merge %1 %2 net/empty-net)]
    (is (= (meaning (join a b)) (meaning (join b a))))
    (is (= (meaning a) (meaning (join a a))))
    (is (= (meaning (join (join a b) c))
           (meaning (join a (join b c)))))
    (is (= (meaning a) (meaning (join value/nothing a))))
    (is (= (meaning a) (meaning (join a value/nothing))))
    (let [newer (contribution source 2 (sample-fragment :later {:n 9}))
          result (join a newer)]
      (is (= :later (capture/observation-id (data/payload result))))
      (is (= (retained-evidence newer) (retained-evidence result))))
    (let [retraction (collection/content
                      {:premise-state #{{:source source :timestamp 2
                                        :premises-status :retracted}}})
          withdrawn (join a retraction)]
      (is (value/nothing? (data/payload withdrawn)))
      (is (seq (datum/layer-value withdrawn :premise-state)))
      (is (not (value/nothing? (retained-evidence withdrawn)))))
    (is (= 7 (session-merge 7 7 net/empty-net)))
    (is (= (merge/cell-merge 7 8 net/empty-net)
           (session-merge 7 8 net/empty-net)))))

(deftest existing-recording-retains-the-fragment-and-prior-frame
  (let [first-capture (sample-fragment :original {:n 1})
        refined (capture/join first-capture
                             (sample-fragment :original {:extra 2}) nil)
        previous (history/next-recording value/nothing first-capture #{} 100)
        next (history/next-recording previous refined #{} 200)]
    (is (= first-capture (:graph (first (history/field previous :history/frames)))))
    (is (= (history/field previous :history/frames)
           (subvec (history/field next :history/frames) 0 1)))
    (is (= refined (:graph (last (history/field next :history/frames)))))
    (is (= :original (capture/observation-id refined)))))

(deftest tracer-publication-has-no-effect-for-unchanged-evidence
  (let [root (ids/new-node-id)
        output (ids/new-node-id)
        network (-> net/empty-net
                    (builder/install-cell root 1 1)
                    (builder/install-cell output))
        activate (make-root-capture-activation root output)
        effects (activate [] [output] network)
        evaluate (evaluator/make-cell-evaluator session-merge)
        [_ settled] (evaluate (first effects) network)]
    (is (= 2 (count effects)))
    (is (capture/retain-source-patch? (second effects)))
    (is (capture/fragment? (data/payload
                           (net/network-cell-strongest settled output))))
    (is (empty? (activate [] [output] settled)))))

(defn -main [& _]
  (let [result (run-tests 'experiments.recursive-semantic-views.review-probe)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
