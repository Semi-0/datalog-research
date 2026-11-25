(ns propagators.compiler-2.language.reader
  "Lain prefix quotation on top of the EDN reader."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]))

(def network-marker :compiler/network)
(def ^:private eof (Object.))

;; Match whole strings, comments, character literals and symbols first so that
;; a quote inside one (including the apostrophe in x') keeps its EDN meaning.
(def ^:private tokens
  #"(?s);[^\r\n]*|\"[^\"\\]*+(?:\\.[^\"\\]*+)*+\"|\\(?:[A-Za-z0-9]+|.)|#_|'|\(\s*::(?=\s)|[^\s,()\[\]{}\";\\'#][^\s,()\[\]{}\";\\]*")

(defn- reader-token [token]
  (cond
    (= "'" token) "#lain/quote "
    (str/starts-with? token "(") (str "(" network-marker)
    :else token))

(defn source-forms [source]
  (let [reader (PushbackReader. (StringReader. (str/replace source tokens reader-token)))
        opts {:eof eof :readers {'lain/quote #(list 'quote %)}}]
    (loop [forms []]
      (let [form (edn/read opts reader)]
        (if (identical? eof form)
          (if (seq forms) forms (throw (ex-info "empty compiler-2 source" {:source source})))
          (recur (conj forms form)))))))
