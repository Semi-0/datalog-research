(ns mini_vm
  (:require [clojure.string :as str]))

;; A small immutable register-machine VM in the style of SICP section 5.2.
;;
;; SICP's assembler creates instruction pairs and later mutates each pair's cdr
;; with an execution procedure. Here an instruction is an immutable vector
;; `[text execution-proc-delay]`, and `update-insts` returns a new vector of
;; updated instructions. Labels and the `pc` register are instruction indexes.

(def tag first)

(defn tagged? [sym]
  (fn [exp] (= sym (tag exp))))

(def const? (tagged? 'const))
(def reg? (tagged? 'reg))
(def label? (tagged? 'label))
(def op? (tagged? 'op))

(def make-registers hash-map)
(def make-labels hash-map)
(def empty-stack [])

(defn register-value [registers reg]
  (get registers reg))

(defn register-set [registers reg value]
  (assoc registers reg value))

(defn label-value [labels label]
  (get labels label))

(defn labels-extend [labels label value]
  (assoc labels label value))

(defn push-stack [stack value]
  (conj stack value))

(defn pop-stack [stack]
  (peek stack))

(defn rest-stack [stack]
  (pop stack))

(defn make-instruction
  ([text] [text nil])
  ([text execution-proc] [text execution-proc]))

(def instruction-text first)

(defn instruction-execution-proc [inst]
  (let [proc (second inst)]
    (if (instance? clojure.lang.Delay proc)
      @proc
      proc)))

(defn with-instruction-execution-proc [inst proc]
  (assoc inst 1 proc))

(defn make-empty-machine []
  {:registers {'pc 0
               'flag false}
   :labels {}
   :stack empty-stack
   :instruction-sequence []
   :operations {}})

(def make-new-machine make-empty-machine)

(declare machine-allocate-register
         machine-install-operations
         install-controller)

(defn make-machine
  "Build a runnable immutable machine from register names, operations, and controller text."
  [register-names operations controller-text]
  (let [machine (-> (make-empty-machine)
                    (as-> m (reduce machine-allocate-register m register-names))
                    (machine-install-operations operations))]
    (install-controller machine controller-text)))

(defn machine-registers [machine] (:registers machine))
(defn machine-labels [machine] (:labels machine))
(defn machine-stack [machine] (:stack machine))
(defn machine-instruction-sequence [machine] (:instruction-sequence machine))
(defn machine-operations [machine] (:operations machine))

(defn machine-lookup-register [machine register]
  (if (contains? (:registers machine) register)
    (get-in machine [:registers register])
    (throw (ex-info "Unknown register" {:register register}))))

(defn machine-set-register [machine register value]
  (if (contains? (:registers machine) register)
    (assoc-in machine [:registers register] value)
    (throw (ex-info "Unknown register" {:register register}))))

(defn machine-allocate-register
  ([machine register] (machine-allocate-register machine register nil))
  ([machine register value]
   (if (contains? (:registers machine) register)
     machine
     (assoc-in machine [:registers register] value))))

(def get-register machine-lookup-register)
(def get-register-contents machine-lookup-register)
(def set-register-contents machine-set-register)

(defn machine-label-set [machine label value]
  (assoc-in machine [:labels label] value))

(defn machine-push-stack [machine value]
  (update machine :stack push-stack value))

(defn machine-pop-stack [machine]
  (let [stack (machine-stack machine)]
    (when (empty? stack)
      (throw (ex-info "Cannot restore from empty stack" {})))
    [(pop-stack stack) (assoc machine :stack (rest-stack stack))]))

(defn machine-install-operation [machine [op-name op-proc]]
  (assoc-in machine [:operations op-name] op-proc))

(defn machine-install-operations [machine operations]
  (reduce machine-install-operation machine operations))

(defn advance-pc [machine]
  (update-in machine [:registers 'pc] inc))

(defn- format-registers [machine]
  (->> (machine-registers machine)
       (sort-by (comp str key))
       (map (fn [[reg value]] (str reg "=" (pr-str value))))
       (str/join ", ")))

(defn- trace-execution-step [step pc instruction before after]
  (println (format "step %03d | pc %02d | %s"
                   step
                   pc
                   (pr-str (instruction-text instruction))))
  (println (str "  before  regs: " (format-registers before)
                " | stack: " (pr-str (machine-stack before))))
  (println (str "  after   regs: " (format-registers after)
                " | stack: " (pr-str (machine-stack after)))))

(defn- execute-machine* [machine trace-fn]
  (loop [m machine
         step 0]
    (let [pc (machine-lookup-register m 'pc)
          instructions (machine-instruction-sequence m)]
      (if (or (nil? pc) (>= pc (count instructions)))
        m
        (let [instruction (nth instructions pc)
              proc (instruction-execution-proc instruction)]
          (when-not proc
            (throw (ex-info "Instruction has no execution procedure"
                            {:instruction (instruction-text instruction)})))
          (let [m* (proc m)]
            (when trace-fn
              (trace-fn step pc instruction m m*))
            (recur m* (inc step))))))))

(defn execute-machine [machine]
  (execute-machine* machine nil))

(defn execute-machine-traced [machine]
  (execute-machine* machine trace-execution-step))

(def start execute-machine)
(def start-traced execute-machine-traced)

(defn extract-labels
  "Parse controller text into instruction values and label indexes.

  Symbols are labels. Non-symbol forms are instruction texts. The callback gets
  `[insts labels]`, where labels map names to instruction indexes.
  "
  [text receive]
  (loop [remaining text
         insts []
         labels {}]
    (if (empty? remaining)
      (receive insts labels)
      (let [next-item (first remaining)]
        (if (symbol? next-item)
          (recur (rest remaining)
                 insts
                 (labels-extend labels next-item (count insts)))
          (recur (rest remaining)
                 (conj insts (make-instruction next-item))
                 labels))))))

(defn label-address [labels label]
  (let [idx (label-value labels label)]
    (when (nil? idx)
      (throw (ex-info "Unknown label" {:label label})))
    idx))

(defn operation-proc [ops op-name]
  (or (get ops op-name)
      (throw (ex-info "Unknown operation" {:op op-name}))))

(declare primitive-exp-proc)

(defn operation-exp-proc [exp labels machine]
  (let [op-name (second (first exp))
        op-proc (operation-proc (machine-operations machine) op-name)
        arg-procs (mapv #(primitive-exp-proc % labels machine) (rest exp))]
    (fn [runtime-machine]
      (apply op-proc (map #(% runtime-machine) arg-procs)))))

(defn primitive-exp-proc [exp labels machine]
  (cond
    (const? exp) (let [value (second exp)]
                   (fn [_] value))
    (reg? exp) (let [reg (second exp)]
                 (machine-lookup-register machine reg)
                 (fn [runtime-machine]
                   (machine-lookup-register runtime-machine reg)))
    (label? exp) (let [idx (label-address labels (second exp))]
                   (fn [_] idx))
    :else (throw (ex-info "Invalid primitive expression" {:exp exp}))))

(defn value-exp-proc [exp labels machine]
  (if (and (seq? exp) (seq? (first exp)) (op? (first exp)))
    (operation-exp-proc exp labels machine)
    (primitive-exp-proc exp labels machine)))

(defn- value-expression [parts]
  (if (= 1 (count parts))
    (first parts)
    parts))

(defn assign-execution-procedure [[_ target & value-exp] labels machine]
  (machine-lookup-register machine target)
  (let [value-proc (value-exp-proc (value-expression value-exp) labels machine)]
    (fn [runtime-machine]
      (-> runtime-machine
          (machine-set-register target (value-proc runtime-machine))
          advance-pc))))

(defn test-execution-procedure [[_ & condition-exp] labels machine]
  (let [condition-proc (operation-exp-proc condition-exp labels machine)]
    (fn [runtime-machine]
      (-> runtime-machine
          (machine-set-register 'flag (condition-proc runtime-machine))
          advance-pc))))

(defn branch-execution-procedure [[_ dest] labels]
  (when-not (label? dest)
    (throw (ex-info "Bad branch instruction" {:dest dest})))
  (let [idx (label-address labels (second dest))]
    (fn [machine]
      (if (machine-lookup-register machine 'flag)
        (machine-set-register machine 'pc idx)
        (advance-pc machine)))))

(defn goto-execution-procedure [[_ dest] labels machine]
  (cond
    (label? dest)
    (let [idx (label-address labels (second dest))]
      (fn [runtime-machine]
        (machine-set-register runtime-machine 'pc idx)))

    (reg? dest)
    (let [reg (second dest)]
      (machine-lookup-register machine reg)
      (fn [runtime-machine]
        (machine-set-register runtime-machine 'pc
                              (machine-lookup-register runtime-machine reg))))

    :else
    (throw (ex-info "Bad goto instruction" {:dest dest}))))

(defn save-execution-procedure [[_ reg]]
  (fn [machine]
    (-> machine
        (machine-push-stack (machine-lookup-register machine reg))
        advance-pc)))

(defn restore-execution-procedure [[_ reg]]
  (fn [machine]
    (let [[value machine'] (machine-pop-stack machine)]
      (-> machine'
          (machine-set-register reg value)
          advance-pc))))

(defn perform-execution-procedure [[_ & action-exp] labels machine]
  (let [action-proc (operation-exp-proc action-exp labels machine)]
    (fn [runtime-machine]
      (action-proc runtime-machine)
      (advance-pc runtime-machine))))

(defn make-execution-procedure [inst-text labels machine]
  (case (tag inst-text)
    assign (assign-execution-procedure inst-text labels machine)
    test (test-execution-procedure inst-text labels machine)
    branch (branch-execution-procedure inst-text labels)
    goto (goto-execution-procedure inst-text labels machine)
    save (save-execution-procedure inst-text)
    restore (restore-execution-procedure inst-text)
    perform (perform-execution-procedure inst-text labels machine)
    (throw (ex-info "Unknown instruction type" {:instruction inst-text}))))

(defn update-insts
  "Return a new instruction vector with execution procedures attached.

  The SICP version mutates instruction pairs with `set-cdr!`. This version keeps
  the same two-phase assembler idea but returns updated immutable instruction
  values. The execution procedure itself is delayed so instruction construction
  remains separate from instruction analysis.
  "
  [insts labels machine]
  (mapv (fn [inst]
          (with-instruction-execution-proc
            inst
            (delay
              (make-execution-procedure
               (instruction-text inst)
               labels
               machine))))
        insts))

(defn update-instructions [insts labels machine]
  (update-insts insts labels machine))

(defn assemble [controller-text machine]
  (extract-labels controller-text
                  (fn [insts labels]
                    (let [insts* (update-insts insts labels machine)]
                      {:instructions insts*
                       :labels labels}))))

(defn install-controller [machine controller-text]
  (let [{:keys [instructions labels]} (assemble controller-text machine)]
    (-> machine
        (assoc :instruction-sequence instructions)
        (assoc :labels labels)
        (machine-set-register 'pc 0))))



(def fib-controller
  '[fib-loop
    (test (op <) (reg n) (const 2))
    (branch (label immediate-answer))

    (save continue)
    (assign continue (label afterfib-n-1))
    (save n)
    (assign n (op -) (reg n) (const 1))
    (goto (label fib-loop))

    afterfib-n-1
    (restore n)
    (restore continue)
    (assign n (op -) (reg n) (const 2))
    (save continue)
    (assign continue (label afterfib-n-2))
    (save val)
    (goto (label fib-loop))

    afterfib-n-2
    (assign n (reg val))
    (restore val)
    (restore continue)
    (assign val (op +) (reg val) (reg n))
    (goto (reg continue))

    immediate-answer
    (assign val (reg n))
    (goto (reg continue))

    fib-done])


(def fib-machine
  (make-machine
   '[n val continue]
   {'< < '- - '+ +}
   fib-controller))
