(ns graph.lain-editor.workspace.source-files-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.server :as server]
            [graph.lain-editor.workspace.source-files :as files]
            [graph.lain-editor.workspace.files :as workspace-files])
  (:import [java.nio.file Files Path FileVisitOption]
           [java.nio.file.attribute FileAttribute]
           [java.nio.charset StandardCharsets]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defn- with-directory [test]
  (let [root (Files/createTempDirectory "lain-source-save-" (make-array FileAttribute 0))]
    (try (test root)
         (finally
           (with-open [paths (Files/walk root (make-array FileVisitOption 0))]
             (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
               (Files/deleteIfExists path)))))))

(deftest creates-project-files-with-exact-draft-text-and-explicit-replacement
  (with-directory
    (fn [^Path root]
      (let [source "; 未実行の draft\n(unfinished"
            command {:path "nested/block.lain" :source source}
            saved (files/save-block! root command)
            target (.resolve root "nested/block.lain")]
        (is (= (:path command) (:path saved)))
        (is (= (alength (.getBytes source StandardCharsets/UTF_8)) (:bytes saved)))
        (is (= source (Files/readString target)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                              (files/save-block! root (assoc command :source "replacement"))))
        (is (= source (Files/readString target)))
        (files/save-block! root (assoc command :source "replacement" :replace true))
        (is (= "replacement" (Files/readString target)))
        (files/save-block! root {:path "empty.lain" :source ""})
        (is (= "" (Files/readString (.resolve root "empty.lain"))))))))

(deftest rejects-invalid-paths-and-data-before-writing
  (with-directory
    (fn [root]
      (doseq [path [nil "" "../escape.lain" (str (.resolve ^Path root "absolute.lain")) "code.clj"]]
        (is (thrown? clojure.lang.ExceptionInfo (files/save-block! root {:path path :source "draft"}))))
      (doseq [command [{:path "code.lain" :source nil}
                       {:path "code.lain" :source "draft" :replace "yes"}]]
        (is (thrown? clojure.lang.ExceptionInfo (files/save-block! root command))))
      (with-open [paths (Files/list root)] (is (zero? (.count paths)))))))

(deftest refuses-symbolic-link-directories-and-targets
  (with-directory
    (fn [^Path root]
      (with-directory
        (fn [^Path outside]
          (Files/createSymbolicLink (.resolve root "linked") outside (make-array FileAttribute 0))
          (Files/createSymbolicLink (.resolve root "file.lain") (.resolve outside "file.lain")
                                    (make-array FileAttribute 0))
          (doseq [path ["linked/block.lain" "file.lain"] replace [false true]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic links"
                                  (files/save-block! root {:path path :source "draft" :replace replace}))))
          (with-open [paths (Files/list outside)] (is (zero? (.count paths)))))))))

(deftest saving-a-block-does-not-require-or-change-runtime-sessions
  (let [editors (atom {"running" {:generation "keep"}}) layouts (atom {:keep "layout"})
        calls (atom []) command {:op "save-block" :pane "unrun" :path "draft.lain" :source "invalid (draft"}]
    (with-redefs [files/save-block! (fn [root request] (swap! calls conj [root request]) {:path (:path request)})]
      (is (= {:path "draft.lain"} (server/command! editors layouts command))))
    (is (= [[(System/getProperty "user.dir") command]] @calls))
    (is (= {"running" {:generation "keep"}} @editors))
    (is (= {:keep "layout"} @layouts))))

(deftest workspace-save-creates-a-current-json-target-and-requires-explicit-replacement
  (with-directory
    (fn [^Path root]
      (let [document (model/initial-workspace)
            path "examples/lain/saved/workspace.json"
            target (.resolve root path)
            saved (workspace-files/save-workspace!
                   root {:path path :workspace document :replace false})]
        (is (= path (:path saved)))
        (is (= document (json/read-json (Files/readString target))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                              (workspace-files/save-workspace!
                               root {:path path :workspace document :replace false})))
        (let [changed (assoc-in document [:panes 0 :title] "updated")]
          (workspace-files/save-workspace!
           root {:path path :workspace changed :replace true})
          (is (= changed (json/read-json (Files/readString target)))))))))

(deftest workspace-save-rejects-invalid-documents-and-unsafe-targets
  (with-directory
    (fn [root]
      (doseq [request [{:path "../workspace.json" :workspace (model/initial-workspace)}
                       {:path "workspace.txt" :workspace (model/initial-workspace)}
                       {:path "workspace.json" :workspace {:format "wrong"}}
                       {:path "workspace.json" :workspace (model/initial-workspace) :replace "yes"}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (workspace-files/save-workspace! root request))))
      (with-open [paths (Files/list root)] (is (zero? (.count paths)))))))

(deftest workspace-save-command-does-not-change-runtime-sessions
  (let [editors (atom {"running" {:generation "keep"}}) layouts (atom {:keep "layout"})
        calls (atom []) command {:op "save-workspace" :path "workspace.json"
                                 :workspace (model/initial-workspace) :replace true}]
    (with-redefs [workspace-files/save-workspace!
                  (fn [root request]
                    (swap! calls conj [root request])
                    {:path (:path request)})]
      (is (= {:path "workspace.json"} (server/command! editors layouts command))))
    (is (= [[(System/getProperty "user.dir") command]] @calls))
    (is (= {"running" {:generation "keep"}} @editors))
    (is (= {:keep "layout"} @layouts))))

(defn- post [base origin command]
  (let [request (-> (HttpRequest/newBuilder (URI. (str base "/api")))
                    (.header "Content-Type" "application/json") (.header "Origin" origin)
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-json command))) .build)]
    (with-open [client (HttpClient/newHttpClient)]
      (let [response (.send client request (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
        {:status (.statusCode response) :body (json/read-json (.body response))}))))

(deftest http-save-writes-the-selected-draft-and-rejects-cross-origin-requests
  (with-directory
    (fn [^Path root]
      (let [service (server/start! 0) base (str "http://127.0.0.1:" (:port service))
            save files/save-block! command {:op "save-block" :pane "unrun" :path "draft.lain" :source "unfinished (code"}]
        (try
          (with-redefs [files/save-block! (fn [_ request] (save root request))]
            (let [response (post base base command)]
              (is (= 200 (:status response)))
              (is (true? (get-in response [:body :ok])))
              (is (= "draft.lain" (get-in response [:body :result :path])))
              (is (= (:source command) (Files/readString (.resolve root "draft.lain"))))
              (is (empty? @(:editors service))))
            (is (= 400 (:status (post base "http://another-origin.test" (assoc command :path "blocked.lain")))))
            (is (not (Files/exists (.resolve root "blocked.lain") (make-array java.nio.file.LinkOption 0)))))
          (finally ((:close service))))))))

(deftest server-file-browser-is-rooted-and-never-executes-opened-code
  (with-directory
    (fn [^Path root]
      (Files/createDirectories (.resolve root "nested") (make-array FileAttribute 0))
      (Files/writeString (.resolve root "draft.lain") "uncompiled (draft" (make-array java.nio.file.OpenOption 0))
      (Files/writeString (.resolve root "notes.txt") "plain text" (make-array java.nio.file.OpenOption 0))
      (Files/writeString (.resolve root ".hidden.lain") "private" (make-array java.nio.file.OpenOption 0))
      (let [document (model/initial-workspace)
            _ (workspace-files/save-workspace! root {:path "nested/layout.json" :workspace document})
            editors (atom {})
            command! (fn [command] (binding [server/*project-root* (str root)] (server/command! editors command)))
            listed (command! {:op "browse-files" :path "" :kind "source"})]
        (is (= ["nested" "draft.lain" "notes.txt"] (mapv :name (:entries listed))))
        (is (= "" (:path listed)))
        (is (nil? (:parent listed)))
        (is (= "uncompiled (draft" (:source (command! {:op "open-file" :path "draft.lain"}))))
        (is (= "plain text" (:source (command! {:op "open-file" :path "notes.txt"}))))
        (is (= ["layout.json"] (mapv :name (:entries (command! {:op "browse-files" :path "nested" :kind "workspace"})))))
        (is (= "" (:parent (command! {:op "browse-files" :path "nested" :kind "source"}))))
        (is (= document (:workspace (command! {:op "open-workspace" :path "nested/layout.json"}))))
        (is (empty? @editors))
        (is (empty? @(graph.lain-editor.runtime.session/owner-catalog editors)))
        (doseq [command [{:op "browse-files" :path ".."}
                         {:op "browse-files" :path (str root)}
                         {:op "open-file" :path "../draft.lain"}
                         {:op "open-file" :path "nested/layout.json"}
                         {:op "open-workspace" :path "draft.lain"}
                         {:op "open-workspace" :path "missing.json"}]]
          (is (thrown? Exception (command! command))))))))

(deftest server-browse-and-open-reject-symlink-escapes-and-invalid-workspaces
  (with-directory
    (fn [^Path root]
      (with-directory
        (fn [^Path outside]
          (Files/writeString (.resolve outside "outside.lain") "secret" (make-array java.nio.file.OpenOption 0))
          (Files/createSymbolicLink (.resolve root "link") outside (make-array FileAttribute 0))
          (Files/createSymbolicLink (.resolve root "linked.lain") (.resolve outside "outside.lain") (make-array FileAttribute 0))
          (Files/writeString (.resolve root "invalid.json") "{\"not\":\"a workspace\"}" (make-array java.nio.file.OpenOption 0))
          (binding [server/*project-root* (str root)]
            (let [editors (atom {}) command! #(server/command! editors %)]
              (is (empty? (:entries (command! {:op "browse-files" :kind "source"}))))
              (doseq [command [{:op "browse-files" :path "link"}
                               {:op "open-file" :path "linked.lain"}
                               {:op "open-file" :path "link/outside.lain"}
                               {:op "open-workspace" :path "invalid.json"}]]
                (is (thrown? Exception (command! command))))
              (is (= "secret" (Files/readString (.resolve outside "outside.lain")))))))))))
