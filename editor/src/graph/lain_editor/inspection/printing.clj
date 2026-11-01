(ns graph.lain-editor.inspection.printing
  "Bounded diagnostic text and ordering keys for captured values.")

(defn printed
  "Bound diagnostic output during traversal of a captured value."
  [x]
  (let [buffer (StringBuilder.)
        exhausted (ex-info "Inspection print budget exhausted" {})
        append! (fn [value offset length]
                  (let [copied (min (- 12000 (.length buffer)) length)]
                    (when (pos? copied)
                      (if (string? value)
                        (.append buffer ^CharSequence value (int offset) (int (+ offset copied)))
                        (.append buffer ^chars value (int offset) (int copied))))
                    (when (< copied length) (throw exhausted))))
        writer (proxy [java.io.Writer] []
                 (write
                   ([value]
                    (let [value (if (number? value) (str (char value)) value)]
                      (append! value 0 (if (string? value) (count value) (alength ^chars value)))))
                   ([value offset length] (append! value offset length)))
                 (flush [] nil)
                 (close [] nil))]
    (try
      (binding [*out* writer *print-length* 30 *print-level* 5] (pr x))
      (.toString buffer)
      (catch clojure.lang.ExceptionInfo error
        (if (identical? exhausted error)
          (str buffer "…")
          (throw error))))))
