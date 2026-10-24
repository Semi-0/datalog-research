(ns graph.lain-editor.build
  "Stable backend entrypoint for the independently maintained frontend build provider.")

(defn -main [& args]
  (apply (requiring-resolve 'lain-editor.build/-main) args))
