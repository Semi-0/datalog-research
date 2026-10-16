(ns propagators.experimental.file-retirement-safety-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [examples.lain.live-environment.files :as files]
            [examples.lain.live-environment.ownership :as ownership]
            [examples.lain.live-environment.retirement :as retirement]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.runtime :as runtime]
            [propagators.compiler-2.runtime.ids :as runtime-ids]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.experimental.visualization.data :as data]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.runner :as runner]))

(defn- request [name source]
  {:file (str "/tmp/lain-retirement/" name ".lain") :source source})

(defn- load-source [session name source]
  (files/replace-file! session (request name source)))

(defn- payload [session name]
  (data/payload (files/value-at session name)))

(defn forbidden-effect [_services _session _request]
  (throw (ex-info "Preparation must never invoke this handler" {})))

(deftest effect-publication-is-rejected-before-commit
  (let [session (runtime/new-session)
        bundle (extension/extension-bundle
                {:id ::effect :bindings []
                 :effects [{:effect/symbol 'external-effect
                            :boundary/port :environment :boundary/kind ::effect
                            :effect/arities #{0}
                            :effect/normalize (fn [_ _] {:status :ready :payload {}})
                            :effect/identity-parts (constantly [])
                            :effect/handler-symbol
                            'propagators.experimental.file-retirement-safety-test/forbidden-effect}]})]
    (runtime/install-session-extension! session bundle {:outbox-id (runtime-ids/boundary-outbox-id)})
    (let [before @session]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime effect retirement"
                            (load-source session "effect" "(external-effect)")))
      (is (identical? before @session)))))

(deftest bidirectional-cycle-remains-idempotent-across-replacement
  (let [session (runtime/new-session)]
    (load-source session "a" "(define x 10)")
    (load-source session "b" "(define y) (<-> x y) (define answer (+ y 1))")
    (is (= 11 (payload session 'answer)))
    (load-source session "a" "(define x 20)")
    (is (= 21 (payload session 'answer)))
    (let [before @session]
      (is (= :unchanged (:status (load-source session "a" "(define x 20)"))))
      (is (identical? before @session)))
    (load-source session "b" "(define y) (<-> x y) (define answer (+ y 2))")
    (is (= 22 (payload session 'answer)))))

(deftest reactive-branch-changes-with-the-replaced-source
  (let [session (runtime/new-session)]
    (load-source session "a" "(define x 10)")
    (load-source session "b" "(define answer (if (> x 5) (+ x 1) (* x 2)))")
    (is (= 11 (payload session 'answer)))
    (load-source session "a" "(define x 2)")
    (is (= 4 (payload session 'answer)))
    (load-source session "a" "(define x 20)")
    (is (= 21 (payload session 'answer)))))

(deftest private-compound-list-computation-can-be-replaced
  (let [session (runtime/new-session)]
    (load-source session "list" "(define answer (car (cdr (list 1 2 3))))")
    (is (= 2 (payload session 'answer)))
    (let [changed (load-source session "list" "(define answer (car (cdr (list 4 5 6))))")]
      (is (= 5 (payload session 'answer)))
      (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired changed))))))

(deftest callable-definition-replacement-updates-multiple-surviving-callers
  (let [session (runtime/new-session)]
    (load-source session "a" "(define f (network [v] (+ v 1)))")
    (load-source session "b" "(define first-answer (f 10))")
    (load-source session "c" "(define second-answer (f 20))")
    (let [counts (mapv (fn [n]
                         (let [changed (load-source session "a"
                                                    (str "(define f (network [v] (+ v " n ")))"))]
                           (is (= (+ 10 n) (payload session 'first-answer)))
                           (is (= (+ 20 n) (payload session 'second-answer)))
                           (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired changed)))
                           (count (:env (:program/net @session)))))
                       (range 2 5))]
      (is (apply = counts)))))

(deftest unmanaged-dictionary-reference-prevents-retirement
  (let [session (runtime/new-session)
        _ (load-source session "a" "(define x 10)")
        private-id (first (keys (get-in @session [::files/ledger :nodes])))]
    (swap! session assoc-in [:program/net :dict ::foreign] private-id)
    (let [before @session]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unmanaged dictionary entry"
                            (load-source session "a" "(define x 20)")))
      (is (identical? before @session)))))

(deftest invalid-input-never-changes-the-session
  (let [session (runtime/new-session)
        before @session]
    (doseq [source [nil 42 '(define x 10) "(define x 1) (define x 2)"]]
      (is (thrown? Exception (load-source session "bad" source)))
      (is (identical? before @session)))
    (is (= :absent (:status (files/remove-file! session "/tmp/lain-retirement/absent.lain"))))
    (is (identical? before @session))))

(deftest empty-file-replacement-withdraws-all-exports
  (let [session (runtime/new-session)]
    (load-source session "a" "(define x 10)")
    (let [empty-file (load-source session "a" "")]
      (is (= {} (:exports empty-file)))
      (is (seq (:retired empty-file)))
      (is (not-any? #(contains? (:env (:program/net @session)) %) (:retired empty-file))))))

(deftest multi-file-chain-withdraws-and-recovers-without-recompiling
  (let [session (runtime/new-session)]
    (load-source session "a" "(define x 10)")
    (load-source session "b" "(define y (+ x 1))")
    (load-source session "c" "(define answer (* y 2))")
    (let [b (get-in @session [::files/files (files/canonical-path (:file (request "b" "")))])
          c (get-in @session [::files/files (files/canonical-path (:file (request "c" "")))])]
      (is (= 22 (payload session 'answer)))
      (load-source session "a" "(define x 20)")
      (is (= 42 (payload session 'answer)))
      (files/remove-file! session (:file (request "a" "")))
      (is (value/unusable? (files/value-at session 'answer)))
      (load-source session "a" "(define x 30)")
      (is (= 62 (payload session 'answer)))
      (is (= b (get-in @session [::files/files (files/canonical-path (:file (request "b" "")))])))
      (is (= c (get-in @session [::files/files (files/canonical-path (:file (request "c" "")))]))))))

(deftest competing-revisions-have-one-winner
  (let [session (runtime/new-session)
        _ (load-source session "a" "(define x 10)")
        edit (fn [n]
               (future
                 (try
                   (files/replace-file! session (assoc (request "a" (str "(define x " n ")"))
                                                       :expected-revision 1))
                   (catch clojure.lang.ExceptionInfo error {:error (ex-message error)}))))
        results (mapv #(deref % 20000 :timeout) [(edit 20) (edit 30)])]
    (is (= 1 (count (filter #(= :replaced (:status %)) results))))
    (is (= 1 (count (filter #(= "Stale file revision" (:error %)) results))))
    (is (contains? #{20 30} (payload session 'x)))))

(deftest an-intervening-session-update-cannot-be-overwritten
  (doseq [operation [(fn [session] (load-source session "a" "(define x 20)"))
                    (fn [session] (files/remove-file! session (:file (request "a" ""))))]]
    (let [session (runtime/new-session)
          _ (load-source session "a" "(define x 10)")
          interrupted (atom false)
          interrupt (fn [advance]
                      (fn [state continuations]
                        (when (compare-and-set! interrupted false true)
                          (swap! session assoc ::external-update :retained))
                        (advance state continuations)))]
      (binding [runner/*advance-transform* interrupt]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Session changed during file preparation"
                              (operation session))))
      (is @interrupted)
      (is (= :retained (::external-update @session)))
      (is (= 10 (payload session 'x))))))

(deftest actual-file-read-and-canonical-alias
  (let [file (java.io.File/createTempFile "lain-replacement-" ".lain")
        session (runtime/new-session)]
    (try
      (spit file "(define x 10)")
      (let [first-load (files/replace-file! session {:file file})
            alias (io/file (.getParentFile file) "." (.getName file))]
        (spit file "(define x 20)")
        (let [second-load (files/replace-file! session {:file alias})]
          (is (= (:file first-load) (:file second-load)))
          (is (= 2 (:revision second-load)))
          (is (= 20 (payload session 'x)))))
      (finally (.delete file)))))

(deftest foreign-propagator-reference-prevents-retirement
  (let [session (runtime/new-session)
        _ (load-source session "a" "(define x 10)")
        private-id (first (keys (get-in @session [::files/ledger :nodes])))
        [_ altered] ((prop/construct-propagator ::foreign (fn [_ _ _] []) [private-id] [])
                     (:program/net @session))]
    (swap! session assoc :program/net altered)
    (let [before @session]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Surviving propagator depends"
                            (load-source session "a" "(define x 20)")))
      (is (identical? before @session))
      (is (= 10 (payload session 'x))))))

(deftest changed-activation-receipt-prevents-retirement
  (let [session (runtime/new-session)
        _ (load-source session "a" "(define x 10)")
        id (first (keep (fn [[id receipt]] (when (:activation receipt) id))
                        (get-in @session [::files/ledger :nodes])))]
    (swap! session update-in [:program/net :env id] assoc :activate (fn [_ _ _] []))
    (let [before @session]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"activation no longer matches"
                            (load-source session "a" "(define x 20)")))
      (is (identical? before @session)))))

(deftest qualified-relationship-removal-preserves-other-occurrences
  (let [removed (ids/new-node-id)
        kept (ids/new-node-id)
        outer (relationship/node-key [:outer] removed)
        parent (relationship/node-key [:outer] kept)
        nested (relationship/node-key [:outer kept] removed)
        relationships (-> {} (relationship/relate parent outer) (relationship/relate parent nested))
        network (-> net/empty-net
                    ((cell/construct-cell removed)) second
                    ((cell/construct-cell kept)) second
                    (assoc :relationship relationships))
        retired (retirement/retire network {:nodes {removed {:lifetime #{:file}}}} #{removed})]
    (is (not (contains? (:env retired) removed)))
    (is (= #{nested} (relationship/children (:relationship retired) parent)))
    (is (= #{parent} (relationship/parents (:relationship retired) nested)))
    (is (not (contains? (:relationship retired) outer)))))

(deftest same-id-reuse-requires-an-identical-cell-value
  (let [id (ids/new-node-id)
        [_ network] ((cell/construct-cell id :constant 10 10) net/empty-net)
        changed (assoc-in network [:env id] (cell/cell :constant 20 20))]
    (is (= {:retired #{} :reused #{id}}
           (retirement/classify-reuse network network #{id})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed retired topology"
                          (retirement/classify-reuse network changed #{id}))))
  (let [[id network] ((prop/construct-propagator ::test (fn [_ _ _] []) [] []) net/empty-net)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed retired topology"
                          (retirement/classify-reuse network network #{id})))))

(deftest caller-transform-is-preserved
  (let [calls (atom 0)
        observer (fn [advance]
                   (fn [state continuations]
                     (swap! calls inc)
                     (advance state continuations)))]
    (binding [runner/*advance-transform* observer]
      (is (= net/empty-net (first (ownership/run {:nodes {}} net/empty-net [])))))
    (is (= 1 @calls))))

(deftest unsupported-callable-retirement-is-atomic
  (let [session (runtime/new-session)]
    (load-source session "a" "(define f (network [v] (+ v 1)))")
    (load-source session "b" "(define answer (f 10))")
    (is (= 11 (payload session 'answer)))
    (load-source session "a" "(define f (network [v] (+ v 2)))")
    (is (= 12 (payload session 'answer)))
    (let [before @session]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Surviving active value references"
                            (load-source session "b" "(define answer (f 20))")))
      (is (identical? before @session)))))
