(ns suji.methods._edn
  "suji (筋) — minimal EDN reader (subset: [] {} :kw \"str\" num bool nil). 1:1 Clojure
  port of `src/suji/methods/_edn.cljc` (ADR-2606061900). Stdlib only.

  Ported from watatsuna/tsumugi (the same `_edn.cljc` family as ake/keizu/noroshi). Keeps
  keywords as their \":ns/name\" STRINGS, NOT Clojure keywords — the seed/manifest/schema
  records are string-keyed (\":body/id\", \":actor/cells\", \":db/ident\", …) and the Python
  `:`-strings stay strings (root CLAUDE.md convention), so this loader yields the same string
  shape the Python `load_edn` does.

  Stdlib only (regex tokenizer); file I/O at the #?(:clj) edge."
  (:require [kotoba.lang.text :as str]))

;; ── tokenizer (mirror of the Python _TOK regex) ───────────────────────────
;; Matches: whitespace/commas | ; comment | one of [ ] { } | "string" | bare atom.

(def ^:private token-re
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn- tokens
  "Lazy seq of significant tokens (capture group 1; whitespace/comments dropped)."
  [s]
  (keep (fn [match] (when (vector? match) (second match)))
        (re-seq token-re s)))

(defn- unescape-string
  "Strip the surrounding quotes and unescape \\\" and \\\\ (mirrors the Python atom path)."
  [t]
  (-> (subs t 1 (dec (count t)))
      (str/replace "\\\"" "\"")
      (str/replace "\\\\" "\\")))

(defn- parse-long* [^String t]
  #?(:clj (try (Long/parseLong t) (catch Exception _ nil))
     :cljs (let [n (js/parseInt t 10)] (when (and (not (js/isNaN n)) (re-matches #"[-+]?\d+" t)) n))))

(defn- parse-double* [^String t]
  #?(:clj (try (Double/parseDouble t) (catch Exception _ nil))
     :cljs (let [n (js/parseFloat t)] (when (and (not (js/isNaN n)) (re-matches #"[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?" t)) n))))

(defn- atom* [t]
  (cond
    (str/starts-with? t "\"") (unescape-string t)
    (= t "true")  true
    (= t "false") false
    (= t "nil")   nil
    (str/starts-with? t ":") t           ;; keyword kept as ":ns/name" STRING
    :else (or (parse-long* t) (parse-double* t) t)))

;; ── recursive-descent parser over a mutable token cursor ──────────────────
;; `state` is an atom holding the remaining token seq; matches the Python `next(it)`.

(def ^:private END ::end)

(defn- next-tok! [state]
  (let [ts @state]
    (when (empty? ts)
      (throw (ex-info "suji._edn: unexpected end of input" {})))
    (reset! state (rest ts))
    (first ts)))

(declare parse-form)

(defn- parse-form [state]
  (let [t (next-tok! state)]
    (cond
      (= t "[") (loop [out []]
                  (let [x (parse-form state)]
                    (if (= x END) out (recur (conj out x)))))
      (= t "{") (loop [out {}]
                  (let [k (parse-form state)]
                    (if (= k END)
                      out
                      (let [v (parse-form state)]
                        (recur (assoc out k v))))))
      (or (= t "]") (= t "}")) END
      :else (atom* t))))

(defn parse-edn
  "Parse a full EDN string into nested vectors/maps/atoms (keywords as \":…\" strings)."
  [s]
  (parse-form (atom (tokens s))))

#?(:clj
   (defn load-edn
     "Read + parse an EDN file at `path` (string or java.io.File). The Clojure
     equivalent of `_edn.cljc`'s `load_edn` — keywords kept as \":ns/name\" strings."
     [path]
     (parse-edn (slurp path))))
