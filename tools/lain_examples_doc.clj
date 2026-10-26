(ns tools.lain-examples-doc
  "Runs the examples in propagators/doc/how-to-think-in-lain-examples.md.

  A fenced block opened as ```lain starts a fresh compiler-2 session; ```lain+
  appends to the session started by the previous block.  Inside a block, a line
  of the form

    ;; => name value

  asserts that the session binding `name` projects to the EDN literal `value`.
  Run with:

    clojure -M -m tools.lain-examples-doc [path]"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [tools.lain-example-runner :as runner]))

(def default-path "propagators/doc/how-to-think-in-lain-examples.md")

(defn- expectation-line? [line]
  (str/starts-with? line ";; =>"))

(defn- parse-expectation [line]
  (let [[sym & value] (str/split (str/trim (subs line 5)) #"\s+")]
    {:binding (symbol sym)
     :expected (edn/read-string (str/join " " value))}))

(defn parse
  "Returns [{:section :index :source :expects}...] for every runnable block."
  [text]
  (loop [lines (str/split-lines text)
         section nil
         block nil
         blocks []]
    (cond
      (empty? lines) (cond-> blocks block (#(conj % block)))
      ;; heading
      (and (str/starts-with? (first lines) "#") (not block))
      (recur (rest lines) (str/trim (first lines)) nil blocks)
      ;; block start
      (and (str/starts-with? (first lines) "```lain") (not block))
      (recur (rest lines) section {:section section :info (str/trim (first lines))
                                            :source [] :expects []} blocks)
      ;; block end
      (and (= "```" (str/trim (first lines))) block)
      (recur (rest lines) section nil (conj blocks block))
      block
      (let [line (first lines)]
        (recur (rest lines)
               section
               (if (expectation-line? line)
                 (update block :expects conj (parse-expectation line))
                 (update block :source conj line))
               blocks))
      :else (recur (rest lines) section nil blocks))))

(defn- read-block [parsed]
  {:source (str/join "\n" (:source parsed))
   :expects (:expects parsed)
   :continues? (str/starts-with? (str/replace (:info parsed) #"`" "") "lain+")})

(defn run
  "Executes every block in `text`, returning one result map per block."
  [text]
  (let [parsed (filter :source (parse text))]
    (loop [blocks (map (fn [p] (assoc (read-block p) :section (:section p) :raw p)) parsed)
           session nil
           results []]
      (if (empty? blocks)
        (vec results)
        (let [{:keys [source continues? section expects]} (first blocks)
              [session outcome]
              (try
                (let [session (if (and continues? session) session (runner/new-session))]
                  [session (runner/load-source! session source)])
                (catch Throwable t
                  [session {:error (ex-message t)}]))]
          (if (:error outcome)
            (recur (rest blocks)
                   session
                   (conj results {:section section :status "load-error" :detail (:error outcome)}))
            (let [failures
                   (keep (fn [{:keys [binding expected]}]
                           (try
                             (let [actual (runner/project session binding)]
                               (when-not (= actual expected)
                                 {:binding binding :expected expected :actual actual}))
                             (catch Throwable t
                               {:binding binding :expected expected :actual (ex-message t)})))
                         expects)]
              (recur (rest blocks)
                     session
                     (conj results (if (seq failures)
                                      {:section section :status "fail" :failures failures}
                                      {:section section
                                       :status "pass"
                                       :checked (count expects)}))))))))))

(defn check
  "Returns {:pass n :fail n :results [...]}"
  [path]
  (let [results (run (slurp path))
        failures (filter #(not= "pass" (:status %)) results)]
    {:pass (count (remove #(not= "pass" (:status %)) results))
     :fail (count failures)
     :results results}))

(defn -main [& args]
  (let [path (or (first args) default-path)
        {:keys [pass fail results]} (check path)]
    (doseq [r results]
      (case (:status r)
        "pass" (println "ok   " (:section r) "-" (:checked r) "assertion(s)")
        "fail" (do (println "FAIL " (:section r))
                   (doseq [f (:failures r)]
                     (println "      " (:binding f) "expected" (:expected f)
                              "got" (:actual f))))
        (do (println "ERROR" (:section r))
            (println "      " (:detail r)))))
    (println (str pass " block(s) passed, " fail " failed"))
    (when (pos? fail)
      (System/exit 1))))
