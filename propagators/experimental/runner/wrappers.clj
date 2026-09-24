(ns propagators.experimental.runner.wrappers
  "Optional decorators for experimental iterators.")

(defn guarded
  "Run `guard` before `f` with the same arguments."
  [guard f]
  (fn [& args]
    (apply guard args)
    (apply f args)))

(defn observe-iterator
  "Observe entry and the selected iterator continuation."
  [observe advance]
  (fn [state {:keys [done fail continue]}]
    (observe {:event :iteration/started
              :state state})
    (advance
     state
     {:done (fn [network]
              (observe {:event :iteration/completed
                        :network network})
              (done network))
      :fail (fn [error]
              (observe {:event :iteration/failed
                        :error error})
              (fail error))
      :continue (fn [next-state]
                  (observe {:event :iteration/continued
                            :state next-state})
                  (continue next-state))})))
