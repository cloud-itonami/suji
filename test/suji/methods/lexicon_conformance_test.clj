(ns suji.methods.lexicon-conformance-test
  "suji (筋) — does the emitter's output satisfy the PUBLISHED lexicons in data/lex?

  THE HOLE THIS FILLS. Until 2026-09-07 nothing in this repo compared an emitted
  value against a lexicon. `charter-invariants-test` had a test called
  `test-muscle-groups-are-mechanical-only` whose message called any difference
  \"drift\", but what it compared was the lexicon file against a five-element set
  typed into the test file — the emitter was not one of its inputs. So the emitter
  could grow from 5 muscle groups to 26 (48 bilateral instances), begin writing a
  `:not-computed` band the enum did not list, write a `-1.0` into a field the same
  lexicon declared `minimum 0`, and stop writing two `required` properties, and the
  suite stayed green through all of it. Worse: because the assertion was an
  EQUALITY against the snapshot, correcting the lexicon to describe reality FAILED
  the test whose stated purpose was to catch the lexicon being wrong. That is why
  the drift was never fixed.

  The invariant that is actually worth holding is that every value the emitter
  produces is admitted by the lexicon — checked by emitting from the reference
  scenarios and validating against the file. It grows with the model on its own.

  JVM-only by nature and not by accident: it reads repo files off disk. The
  validator itself (`datoms/validate-datoms`) is portable `.cljc`; only the slurping
  is here. Same reason `datoms-test` and `charter-invariants-test` are outside
  scripts/nbb_test.cljs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]
            [clojure.java.io :as io]
            [suji.methods.analyze :as analyze]
            [suji.methods.datoms :as datoms]
            [suji.methods.strain :as strain]))

(def ^:private lex-stems
  ["bodyModel" "postureScenario" "jointLoad" "muscleTension" "strainReport"
   "ergonomicComparison"])

(defn- record-of
  "The `:main :record` map out of one data/lex file. The files are Datomic tx-data
  with the defs map pr-str'd into a string value (Phase 4 fan-out), so this undoes
  both wrappers."
  [stem]
  (let [entity (first (edn/read-string (slurp (io/file "data" "lex" (str stem ".edn")))))
        defs-key (first (filter #(str/ends-with? (str %) "/defs") (keys entity)))]
    (get-in (edn/read-string (get entity defs-key)) [:main :record])))

(defn- records [] (into {} (map (juxt identity record-of)) lex-stems))

(defn- the-datoms []
  (datoms/results-to-datoms (analyze/analyze-all 70.0 1.70 120.0)))

;; --- the invariant -----------------------------------------------------------

(deftest test-every-emitted-value-is-admitted-by-its-lexicon
  (let [ds (the-datoms)
        violations (datoms/validate-datoms ds (records))]
    (is (pos? (count ds)) "the reference scenarios must emit something to check")
    (is (= [] violations)
        (str (count violations) " emitted value(s) the published lexicons do not admit:\n"
             (str/join "\n" (map pr-str (take 20 violations)))))))

(deftest test-every-bound-property-is-declared-in-its-lexicon
  ;; An attribute bound to a property that does not exist would only surface when
  ;; that attribute is emitted; `:body/encrypted-cid` is bound and (for a
  ;; representative body) never emitted, so without this the binding could rot.
  (let [recs (records)]
    (doseq [[kind {:keys [lexicon attrs]}] datoms/lexicon-bindings
            [attr prop] attrs]
      (is (contains? (:properties (get recs lexicon)) prop)
          (str kind " binds " attr " → " prop ", which " lexicon " does not declare")))))

;; --- the closed vocabularies -------------------------------------------------
;;
;; `validate-datoms` checks one direction (nothing emitted is outside the enum). An
;; enum can still be a stale SUPERSET, which is how a five-name list survived a
;; twenty-six-group model. For the vocabularies that are closed by construction the
;; enum should be exactly what exists.

(defn- enum-of [recs stem prop]
  (set (get-in recs [stem :properties prop :enum])))

(deftest test-closed-vocabularies-are-exactly-what-the-emitter-produces
  (let [ds (the-datoms)
        recs (records)]
    (doseq [[stem prop attr]
            [["muscleTension" :group ":muscle/group"]
             ["muscleTension" :side  ":muscle/side"]
             ["strainReport"  :group ":strain/group"]
             ["strainReport"  :side  ":strain/side"]
             ["strainReport"  :enduranceLimit ":strain/endurance-limit"]
             ["jointLoad"     :joint ":load/joint"]]]
      (let [declared (enum-of recs stem prop)
            emitted (datoms/emitted-vocabulary ds attr)]
        (is (= declared emitted)
            (str stem "/" (name prop) " enum is not the vocabulary the model names."
                 "\n  declared but never emitted: " (pr-str (sort (remove emitted declared)))
                 "\n  emitted but not declared:   " (pr-str (sort (remove declared emitted)))))))))

(deftest test-band-enum-is-exactly-what-the-band-function-can-return
  ;; The band enum is not closed by what the reference scenarios happen to hit —
  ;; `moderate` and `high` are legitimate and no reference posture lands in them —
  ;; so it is pinned to the function's own range instead. This is the assertion that
  ;; would have caught `:not-computed` on the day it was added, in either direction.
  (let [reachable (into #{} (map #(str ":" (strain/stiffness-band %)))
                        [nil 0.0 0.19 0.2 0.44 0.45 0.69 0.7 1.0])]
    (is (= (enum-of (records) "strainReport" :band) reachable))))

;; --- no sentinels ------------------------------------------------------------

(deftest test-no-numeric-field-carries-a-stand-in-for-absence
  ;; -1.0 meant three different things in two fields: "the model refused this
  ;; muscle", "endurance is effectively unlimited" (the SAFEST case), and in
  ;; stiffness it was outside the field's own declared [0,1]. A consumer sorting
  ;; either column numerically ranked the least-loaded muscles next to the ones
  ;; nobody solved — the same failure as a band function returning the LOWEST band
  ;; for a nil.
  (let [ds (the-datoms)
        numeric (for [d ds, [a v] d :when (number? v)] [a v])]
    (is (seq numeric))
    (doseq [[a v] numeric]
      (is (not (neg? v)) (str a " emitted a negative number (" v
                              "); no field in this emitter has a negative quantity, "
                              "so a negative is a sentinel")))))

(deftest test-absence-is-said-and-not-encoded
  (let [ds (the-datoms)
        strains (filter #(contains? % ":strain/endurance-limit") ds)
        by-limit (group-by #(get % ":strain/endurance-limit") strains)]
    (testing "all three kinds of endurance answer actually occur, so this can discriminate"
      (doseq [k [":finite" ":unbounded" ":not-computed"]]
        (is (seq (get by-limit k)) (str "no strain datom with endurance-limit " k))))
    (testing "the number is present exactly when it is a number"
      (doseq [d (get by-limit ":finite")]
        (is (pos? (get d ":strain/endurance-min" 0)) (str "finite but no minutes: " (get d ":strain/id"))))
      (doseq [k [":unbounded" ":not-computed"], d (get by-limit k)]
        (is (not (contains? d ":strain/endurance-min"))
            (str k " must not carry a number: " (get d ":strain/id")))))
    (testing "a stiffness index the model did not compute is absent, and the band says so"
      (doseq [d strains]
        (is (= (contains? d ":strain/stiffness")
               (not= ":not-computed" (get d ":strain/band")))
            (str "stiffness presence must agree with the band: " (get d ":strain/id")))))))

;; --- the validator has to be able to fail ------------------------------------

(deftest test-the-validator-discriminates
  ;; A conformance test that only ever sees conformant input is indistinguishable
  ;; from one that returns [] unconditionally. Each case below is the shipped defect
  ;; it is named after, reconstructed.
  (let [recs (records)
        good (first (filter #(contains? % ":muscle/mvc-pct") (the-datoms)))
        kinds (fn [d] (set (map :violation (datoms/validate-datoms [d] recs))))]
    (is (= #{} (kinds good)) "the control must be clean")
    (testing "a group value outside the enum — the drift the snapshot test could not see"
      (is (contains? (kinds (assoc good ":muscle/group" ":gluteus-medius")) :value-not-in-enum)))
    (testing "an undeclared property — additionalProperties=false says unrepresentable"
      (is (contains? (kinds (assoc good ":muscle/note" "x")) :unbound-attribute)))
    (testing "a required property absent"
      (is (contains? (kinds (dissoc good ":muscle/side")) :missing-required)))
    (testing "a number where a string is declared"
      (is (contains? (kinds (assoc good ":muscle/group" 1)) :type-mismatch)))
    (testing "the -1.0 stiffness sentinel, against its own declared minimum"
      (let [st (first (filter #(contains? % ":strain/stiffness") (the-datoms)))]
        (is (contains? (kinds (assoc st ":strain/stiffness" -1.0)) :below-minimum))
        (is (contains? (kinds (assoc st ":strain/stiffness" 1.5)) :above-maximum))))
    (testing "a datom with no identity attribute is reported, not skipped"
      (is (contains? (kinds {":muscle/group" ":vasti"}) :no-identity-attribute)))))
