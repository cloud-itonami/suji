(ns suji.methods.charter-invariants-test
  "suji (筋) — structural charter-invariant tests over the lexicons + kotoba schema.
  1:1 Clojure port of src/suji/methods/test_charter_invariants.cljc.

  The load-bearing invariant is G1 NON-DIAGNOSTIC (医師法 §17): a diagnosis/disease/
  prescription/treatment field must be STRUCTURALLY unrepresentable. These tests parse the
  actual EDN artifacts and assert (a) no clinical key appears anywhere, (b) every record is
  closed (additionalProperties=false), (c) muscle groups come from the mechanical set only.

  The lexicons / schema use real Clojure keywords, so they are read with clojure.edn."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private here (clojure.java.io/file "."))
(def ^:private lex-dir (clojure.java.io/file here "data" "lex"))
(def ^:private schema-path (str (clojure.java.io/file here "schema" "kotoba.edn")))

(def forbidden
  #{"diagnosis" "disease" "icd" "icd10" "prescription" "treatment"
    "medication" "condition" "pathology" "prognosis" "therapy"})

(def mechanical-groups
  #{"cervical-extensors" "upper-trapezius" "levator-scapulae"
    "anterior-deltoid" "erector-spinae"})

(defn- walk* [node]
  (cond
    (map? node) (mapcat (fn [[k v]] (cons [:key k] (walk* v))) node)
    (sequential? node) (mapcat walk* node)
    :else [[:scalar node]]))

(defn- lex-files []
  (sort (filter #(str/ends-with? (.getName %) ".edn")
                (seq (.listFiles lex-dir)))))

;; data/lex/*.edn is now Datomic/Datascript tx-data (a 1-entity vector, namespace-prefixed
;; keys, non-scalar values pr-str blobbed — Phase 4 fan-out, see schema.edn).
;; Reconstitute the original bare-keyword lexicon-doc shape here so every downstream
;; :id / :defs / get-in lookup below keeps working unchanged.
(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- reconstitute-entity [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn- load-lex [f] (reconstitute-entity (edn/read-string (slurp f))))

(deftest test-lexicons-parse-and-have-ids
  (let [files (lex-files)]
    (is (= (count files) 6) (str "expected 6 lexicons, found " (count files)))
    (doseq [f files]
      (let [doc (load-lex f)]
        (is (str/starts-with? (get doc :id "") "com.etzhayyim.suji.")
            (str (.getName f) " id"))))))

(deftest test-no-clinical-property-anywhere-in-lexicons
  (doseq [f (lex-files)]
    (let [doc (load-lex f)]
      (doseq [[_ val] (walk* doc)]
        (cond
          (string? val)
          (let [nm (str/lower-case (str/replace val #"^:+" ""))]
            (is (not (contains? forbidden nm))
                (str "G1 violation: clinical key '" val "' in " (.getName f))))
          (keyword? val)
          (let [nm (str/lower-case (name val))]
            (is (not (contains? forbidden nm))
                (str "G1 violation: clinical key '" val "' in " (.getName f)))))))))

(deftest test-records-are-closed
  (doseq [f (lex-files)]
    (let [doc (load-lex f)
          rec (get-in doc [:defs :main :record])]
      (is (= (get rec :additionalProperties) false) (str (.getName f) " must be closed")))))

(deftest test-schema-has-no-clinical-ident
  (let [schema (edn/read-string (slurp schema-path))]
    (doseq [entry (filter map? schema)]
      (let [ident (get entry :db/ident "")
            leaf (str/lower-case (last (str/split (str ident) #"/")))]
        (is (not (contains? forbidden leaf)) (str "G1 violation: schema ident " ident))))))

(deftest test-muscle-groups-are-mechanical-only
  (doseq [nm ["muscleTension" "strainReport"]]
    (let [doc (load-lex (clojure.java.io/file lex-dir (str nm ".edn")))
          props (get-in doc [:defs :main :record :properties])
          enum (set (get-in props [:group :enum]))]
      (is (= enum mechanical-groups) (str nm " group enum drifted: " enum)))))

(deftest test-bodyModel-carries-g4-encrypted-envelope
  (let [doc (load-lex (clojure.java.io/file lex-dir "bodyModel.edn"))
        props (get-in doc [:defs :main :record :properties])]
    (is (contains? props :encryptedPayloadCid))))

(deftest test-stiffness-is-bounded-and-self-referenced
  (let [doc (load-lex (clojure.java.io/file lex-dir "strainReport.edn"))
        props (get-in doc [:defs :main :record :properties])
        s (get props :stiffnessIndex)]
    (is (and (= (get s :minimum) 0) (= (get s :maximum) 1)))))
