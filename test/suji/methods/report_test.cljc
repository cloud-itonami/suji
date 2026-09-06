(ns suji.methods.report-test
  "suji (筋) — the report the README tells you to run.

  IT DID NOT RUN. `clojure -M -m suji.methods.analyze` threw a
  NullPointerException out of `fmt-f`, and had been throwing since before
  2026-09-06: `render-report` reached straight for `(:mvc-pct s)` and
  `(:stiffness-index s)` and handed them to a formatter that calls `.doubleValue`,
  and a REFUSED muscle has neither while a LIGAMENT has no %MVC at all. Nothing in
  the suite called `render-report`, so the suite was green and the advertised
  entry point was broken — which is the same shape as `.cljc` that only claims to
  be portable, one layer up.

  These tests exist so that the report has to render."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [clojure.string :as str]
            [suji.methods.analyze :as analyze]
            [suji.methods.strain :as strain]))

(defn- rows
  "The muscle rows of the rendered report, as parsed cells — asserted on structure
  rather than on `includes?`, because the prose above the table uses the same
  words and a text search would stay green after the column disappeared."
  [text]
  (for [line (str/split-lines text)
        :when (and (str/starts-with? line "| ") (not (str/starts-with? line "|---")))
        :let [cells (mapv str/trim (str/split line #"\|"))]
        ;; `str/split` drops the trailing empty field, so a five-column row comes
        ;; back as six cells with an empty one in front
        :when (= 6 (count cells))
        ;; the header row has the same shape as a data row and is not one
        :when (not= "muscle" (nth cells 1))]
    {:name (nth cells 1) :mvc (nth cells 2) :endurance (nth cells 3)
     :stiffness (nth cells 4) :band (nth cells 5)}))

(deftest the-report-renders-at-all
  (let [text (analyze/render-report (analyze/analyze-all))]
    (is (string? text))
    (is (< 1000 (count text)) "and is a report, not an empty string")
    (doseq [w ["laptop-on-lap" "laptop-on-desk" "external-monitor+keyboard"]]
      (is (str/includes? text w) (str w " must appear")))))

(deftest a-muscle-the-model-refused-stays-in-the-table-with-a-dash
  ;; Dropping it would make a muscle the model could not solve read as a muscle
  ;; that was fine. Keeping it and formatting nil is what threw.
  (let [text (analyze/render-report (analyze/analyze-all))
        parsed (rows text)
        dashed (filter #(= "—" (:mvc %)) parsed)]
    (is (seq parsed) "the table must have rows at all")
    (is (seq dashed)
        (str "at least one muscle in the reference scenarios is refused or is a "
             "ligament, and must be shown with a dash rather than dropped. Rows: "
             (mapv :name parsed)))
    (doseq [r dashed]
      (is (= "not-computed" (:band r))
          (str (:name r) ": a row with no %MVC must carry the not-computed band, "
               "not `low` — which is the best case and would read as good news"))
      (is (= "—" (:stiffness r)) (str (:name r) ": and no stiffness index")))
    ;; and the rows that DO have a number are still formatted as numbers
    (let [numeric (remove #(= "—" (:mvc %)) parsed)]
      (is (seq numeric) "the table is not all dashes")
      (is (every? #(str/ends-with? (:mvc %) "%") numeric)))))

(deftest a-scenario-with-nothing-solvable-says-so-rather-than-printing-a-blank
  ;; `worst-stiffness` returns nil when no muscle at this posture could be solved,
  ;; and the report used to format that nil. A blank where a finding should be
  ;; reads as nothing being wrong.
  (let [refused (mapv (fn [n] (strain/muscle-strain {:name n :refused :acts-the-wrong-way} 120.0))
                      ["a" "b"])
        text (analyze/render-report [{:workstation "all-refused"
                                      :posture {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                                                :shoulder-flexion-deg 0.0 :arms-supported false}
                                      :loads {:cervical {:head-flexion-deg 0.0
                                                         :compressive-load-kgf 5.0
                                                         :multiplier-vs-head 1.0}}
                                      :tensions []
                                      :strains refused}])]
    (is (str/includes? text "could not solve any muscle")
        "a scenario with no solvable muscle must say so")
    (is (not (str/includes? text "worst: ****"))
        "and must not print an empty muscle name")
    ;; the same call exercises the OTHER unreachable-input path in this function:
    ;; the comparison baseline is looked up by the name "laptop-on-lap", and a
    ;; caller rendering any other set of results got nil and a NullPointerException
    (is (str/includes? text "Comparison")
        "the comparison section must render for a result set with no laptop-on-lap")))
