(ns propagators.compiler-2.runtime.session.program.source
  "Compiler source normalization helpers for compiler-2 runtime."
  (:require [propagators.compiler-2.language.reader :as reader]
            [clojure.walk :as walk]
            [propagators.compiler-2.runtime.tui.block-model :as block-model]
            [propagators.compiler-2.language.parser :as compiler-parser]))

(def block-by-index block-model/block-by-index)

(defn top-level-form-head
  [source]
  (try
    (let [form (compiler-parser/read-form source)]
      (when (seq? form)
        (first form)))
    (catch Throwable _
      nil)))

(def ^:private declaration-heads
  '#{define})

(def ^:private boundary-effect-heads
  '#{be:block be:event-block-at translate xr-io io:xr
     slider-io slider-panel-io io:slider io:slider-panel
     io:slider-panels io:slider-panel-name
     load-primitive-environment load-lain save-environment
     load-blocks save-blocks})

(def ^:private transport-heads '#{-> <->})

(defn- source-form [source]
  (try
    (compiler-parser/read-form source)
    (catch Throwable _ nil)))

(defn top-level-declaration? [source]
  (contains? declaration-heads (top-level-form-head source)))

(defn explicit-output-form?
  "True when a form deliberately targets an external/runtime destination.

  `->` and `<->` are ordinary sync expressions, not declarations.  Their
  destination is nevertheless explicit, so the TUI must not add a second
  implicit next-block destination."
  [source]
  (let [form (source-form source)
        head (when (seq? form) (first form))]
    (or (contains? boundary-effect-heads head)
        (and (contains? '#{block-at be:block-at} head)
             (>= (count form) 4))
        (contains? transport-heads head))))

(defn trace-source? [source]
  (= 'trace (top-level-form-head source)))

(def read-source-forms reader/source-forms)

(defn- quoted? [form] (and (seq? form) (= 'quote (first form))))

(defn executable-nodes [form]
  (tree-seq #(and (coll? %) (not (quoted? %))) seq form))

(defn trace-form? [source]
  (try
    (let [form (compiler-parser/read-form source)]
      (boolean
       (some (fn [x]
               (and (seq? x)
                    (= 'trace (first x))))
             (executable-nodes form))))
    (catch Throwable _
      false)))

(defn- wrap-trace-target [form]
  (if (and (seq? form) (= 'trace (first form)) (symbol? (second form)))
    (list* 'trace (list 'trace-target (name (second form)) (second form)) (nnext form))
    form))

(defn- rewrite-traces [form]
  (if (quoted? form) form (walk/walk rewrite-traces wrap-trace-target form)))

(defn normalize-trace-source [source]
  (try
    (pr-str (rewrite-traces (compiler-parser/read-form source)))
    (catch Throwable _ source)))

(defn auto-output-display-form
  ([source target-index]
   (auto-output-display-form source target-index false))
  ([source target-index event-trace?]
   (let [form (source-form source)
         trace-output (when (and event-trace?
                                 (seq? form)
                                 (= 'trace (first form)))
                        (last form))]
     (format "(%s %% %d %s)"
             (if trace-output 'be:event-block-at 'be:block-at)
             target-index
             (or trace-output '__runtime_out)))))

(defn auto-output-source [state block source]
  (if (or (top-level-declaration? source)
          (explicit-output-form? source)
          (nil? (block-by-index state (:client-id block) (inc (:index block)))))
    source
    (let [target-index (inc (:index block))
          versioned? (= :versioned-premise
                        (get-in state [:tuis (:client-id block) :mode]))]
      (format "(let-cell [__runtime_out]
                 (-> %s __runtime_out)
                 %s
                 __runtime_out)"
              source
              (auto-output-display-form source target-index versioned?)))))
