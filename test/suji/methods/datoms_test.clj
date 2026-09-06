(ns suji.methods.datoms-test
  "suji (筋) — analyze→kotoba Datom emitter tests (G9 drift-lock + G1). 1:1 Clojure port of
  src/suji/methods/test_datoms.cljc.

  The Python tests read schema.edn keeping keywords as \":…\" strings; the emitted datoms are
  also \":…\"-string-keyed. Here the schema is read with clojure.edn (real keywords) and each
  :db/ident is stringified back to its \":ns/name\" form to compare against the datom keys."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [suji.methods.analyze :as analyze]
            [suji.methods.datoms :as datoms]
            [suji.methods.load :as load]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]))

(def ^:private schema-path (str (clojure.java.io/file "schema" "kotoba.edn")))

(def forbidden
  #{"diagnosis" "disease" "icd" "icd10" "prescription" "treatment"
    "medication" "condition" "pathology" "prognosis"})

(defn- schema-idents []
  (into #{}
        (comp (filter map?)
              (filter #(contains? % :db/ident))
              (map #(str (:db/ident %))))   ;; :body/id → ":body/id"
        (edn/read-string (slurp schema-path))))

(defn- the-datoms []
  (datoms/results-to-datoms (analyze/analyze-all 70.0 1.70 120.0)))

(deftest test-every-attribute-is-declared-in-schema
  (let [declared (schema-idents)]
    (doseq [d (the-datoms), attr (keys d)]
      (is (contains? declared attr) (str "undeclared attribute emitted: " attr)))))

(deftest test-no-clinical-attribute-can-be-emitted
  (doseq [d (the-datoms), attr (keys d)]
    (let [leaf (str/lower-case (last (str/split attr #"/")))]
      (is (not (contains? forbidden leaf)) (str "G1 violation: " attr)))))

(deftest test-refs-resolve
  (let [ds (the-datoms)
        bodies (into #{} (keep #(get % ":body/id") ds))
        postures (into #{} (keep #(get % ":posture/id") ds))]
    (is (and (seq bodies) (seq postures)))
    (doseq [d ds]
      (when (contains? d ":posture/body")
        (is (contains? bodies (get d ":posture/body"))))
      (doseq [ref [":load/posture" ":muscle/posture" ":strain/posture"]]
        (when (contains? d ref)
          (is (contains? postures (get d ref)) (str "dangling " ref "=" (get d ref))))))))

(deftest test-cervical-load-datom-complete
  (let [cerv (filter #(= (get % ":load/joint") ":cervicothoracic") (the-datoms))]
    (is (seq cerv) "expected a cervicothoracic load datom per posture")
    (doseq [d cerv]
      (is (and (contains? d ":load/compressive-kgf") (contains? d ":load/mult-vs-head")))
      (is (> (get d ":load/compressive-kgf") 0)))))

(deftest test-the-endurance-field-says-which-kind-of-missing-number
  ;; ⚠ THIS TEST USED TO BE `…-infinity-encoded-as-minus-one`, and it ASSERTED the
  ;; defect: `(is (some #(= … -1.0) strains))`. Measured 2026-09-07 on the reference
  ;; scenarios, that -1.0 was standing for two opposite facts — 101 datoms where the
  ;; %MVC is below the model's endurance floor and the power fit returns ∞
  ;; (endurance effectively unlimited: the SAFEST case) and 28 where the model
  ;; refused the muscle and there is no dose at all. Same value, in a numeric field.
  ;; A consumer sorting the column ranks the least-loaded muscles next to the ones
  ;; nobody solved, which is the failure the browser band function had when a nil
  ;; fell into the LOWEST band.
  (let [ds (the-datoms)
        strains (filter #(contains? % ":strain/endurance-limit") ds)
        by (group-by #(get % ":strain/endurance-limit") strains)]
    (is (seq strains))
    ;; all three answers occur, so the assertions below can actually discriminate
    (doseq [k [":finite" ":unbounded" ":not-computed"]]
      (is (seq (get by k)) (str "no strain datom carries endurance-limit " k)))
    (is (every? #(pos? (get % ":strain/endurance-min" 0)) (get by ":finite"))
        "a :finite endurance must carry a positive number of minutes")
    (doseq [k [":unbounded" ":not-computed"]]
      (is (not-any? #(contains? % ":strain/endurance-min") (get by k))
          (str k " must carry no number at all, not a stand-in for one")))
    (is (not-any? (fn [d] (some #(and (number? %) (neg? %)) (vals d))) ds)
        "no emitted number is negative; a negative would be a sentinel")))

(deftest test-the-structure-and-the-side-are-separate-facts
  ;; THE DEFECT THIS IS WRITTEN AGAINST. Until 2026-09-07 `:muscle/group` carried
  ;; both (`:upper-trapezius/left`), so the published `group` enum was a list of
  ;; modelled INSTANCES rather than a vocabulary of mechanical structures: it grew
  ;; by two every time a muscle was made bilateral, and the field's grammar varied
  ;; by value, because the four midline groups carried no suffix and nothing in the
  ;; schema said when to expect one.
  (let [ds (the-datoms)
        muscles (filter #(contains? % ":muscle/id") ds)
        strains (filter #(contains? % ":strain/id") ds)]
    (is (seq muscles))
    (doseq [d (concat muscles strains)]
      (let [attr (if (contains? d ":muscle/id") ":muscle/group" ":strain/group")
            side (if (contains? d ":muscle/id") ":muscle/side" ":strain/side")]
        (is (not (str/includes? (get d attr) "/"))
            (str attr " must name a structure only, got " (get d attr)))
        (is (contains? #{":left" ":right" ":midline"} (get d side))
            (str side " must be present on every record, got " (pr-str (get d side))))))
    ;; and both sides of a paired group are present under the SAME group term —
    ;; the thing the concatenated key made impossible to ask
    (let [trap (filter #(= ":upper-trapezius" (get % ":muscle/group")) muscles)]
      (is (= #{":left" ":right"} (into #{} (map #(get % ":muscle/side")) trap))
          "a paired group must appear once per side under one group name"))
    (let [midline (filter #(= ":cervical-extensors" (get % ":muscle/group")) muscles)]
      (is (= #{":midline"} (into #{} (map #(get % ":muscle/side")) midline))))))

(deftest test-rendered-edn-reparses-to-same-count
  ;; The Python test round-trips through its own reader; here we assert the rendered EDN is
  ;; well-formed (parses to a list of N maps) + spot-check a bool/keyword survived.
  (let [ds (the-datoms)
        text (datoms/render-edn ds)
        back (edn/read-string text)]
    (is (and (vector? back) (= (count back) (count ds))))
    (let [body (first (filter #(contains? % :body/id) back))]
      (is (= (:body/representative body) true)))
    (let [cerv (first (filter #(= (:load/joint %) :cervicothoracic) back))]
      (is (= (:load/joint cerv) :cervicothoracic)))))

;; --- the support mode is part of the posture's identity ----------------------

(defn- scenario-for
  "One scenario result for a stated posture, the shape `scenario-datoms` consumes."
  [nm p]
  (let [body (segment/build-body 70.0 1.70)
        loads (load/solve-posture-loads body p)
        tensions (muscle/solve-muscle-tensions body p loads)]
    {:workstation nm :posture p :loads loads :tensions tensions
     :strains (strain/session-strain tensions 120.0)}))

(deftest test-the-emitted-posture-distinguishes-standing-from-sitting
  ;; THE DEFECT THIS IS WRITTEN AGAINST. Until 2026-09-07 the posture datom carried
  ;; head, trunk and shoulder angles and `arms-supported`, and nothing else. Two
  ;; postures with the same upper-body angles and different support modes load the
  ;; hip, knee and ankle by a factor of about thirty apart — and would have emitted
  ;; BYTE-IDENTICAL posture datoms. A replayable log that records two different
  ;; bodies as the same one is not replayable.
  (let [angles {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
                :elbow-flexion-deg 0.0 :wrist-extension-deg 0.0 :arms-supported false
                :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0}
        sit (datoms/scenario-datoms (scenario-for "x" (assoc angles :support :seated)) "b" 0)
        stand (datoms/scenario-datoms (scenario-for "x" (assoc angles :support :standing)) "b" 0)
        posture-of (fn [ds] (first (filter #(contains? % ":posture/id") ds)))
        ankle-of (fn [ds] (first (filter #(= ":ankle" (get % ":load/joint")) ds)))]
    (is (not= (posture-of sit) (posture-of stand))
        "the two postures must not emit the same datom")
    (is (= ":seated" (get (posture-of sit) ":posture/support")))
    (is (= ":standing" (get (posture-of stand) ":posture/support")))
    ;; and the difference it makes is emitted too, so a reader of the log can see
    ;; the consequence and not only the flag
    (is (number? (get (ankle-of sit) ":load/supported-weight-n")))
    (is (> (get (ankle-of stand) ":load/supported-weight-n")
           (* 20.0 (get (ankle-of sit) ":load/supported-weight-n")))
        (str "standing must transmit an order of magnitude more through the ankles: "
             (get (ankle-of stand) ":load/supported-weight-n") " vs "
             (get (ankle-of sit) ":load/supported-weight-n") " N"))
    ;; the lower-limb angles are in the log, so the posture can be rebuilt from it
    (doseq [k [":posture/hip-flex-deg" ":posture/knee-flex-deg" ":posture/ankle-dorsiflex-deg"]]
      (is (number? (get (posture-of stand) k)) (str k " must be emitted")))))

(deftest test-every-lower-limb-load-reaches-the-log
  ;; ⚠ THIS TEST USED TO BE CALLED `…-emitted-with-declared-keywords`, and it could
  ;; not fail for that reason. `joint-kw` maps a joint name to the keyword the
  ;; schema declares AND falls back to deriving one from the name, and for "hip"
  ;; both paths produce exactly ":hip" — so removing the three explicit entries
  ;; changed no output and the test stayed green. Measured 2026-09-07 by removing
  ;; them: 8 tests, 0 failures. A test that cannot fail for the reason it names is
  ;; a defect whatever it asserts, so it now names the claim it CAN discriminate.
  ;;
  ;; (The explicit entries are still there and still worth having — an explicit
  ;; table is easier to read than a fallback — but nothing here guards them, and
  ;; saying so is the difference between a safety net and a belief.)
  ;;
  ;; What IS checkable is that the lower limb's loads reach the log at all: a
  ;; solver that computes a hip moment and an emitter that drops it is the same
  ;; kind of silence this actor keeps finding.
  (let [ds (datoms/scenario-datoms
            (scenario-for "x" posture/quiet-standing) "b" 0)
        joints (into #{} (keep #(get % ":load/joint")) ds)]
    (doseq [j [":hip" ":knee" ":ankle"]]
      (is (contains? joints j) (str j " must appear in the emitted load datoms")))
    (is (contains? joints ":cervicothoracic") "and the old ones are still there")
    (is (= 8 (count (filter #(contains? % ":load/joint") ds)))
        (str "every joint the solver reports must be emitted exactly once, got "
             (mapv #(get % ":load/joint") (filter #(contains? % ":load/joint") ds))))))

(deftest test-the-group-and-side-are-carried-not-parsed-back-out-of-the-name
  ;; `attachment/instances` sets `:group` and `:side` as distinct keys, and the
  ;; dose layer used to drop them and carry only `:name` — so the emitter had to
  ;; recover the pair by looking for a "/" in a string. That is a parser for a
  ;; grammar nothing declares: an instance whose name happened to contain no "/"
  ;; silently became `:midline`, and one whose name contained two would have split
  ;; at the wrong place.
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        tensions (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p))
        strains (strain/session-strain tensions 120.0)]
    (is (seq strains) "no strains, so this asserts nothing")
    (is (every? :group strains)
        (str "dose rows without a group: "
             (pr-str (mapv :name (remove :group strains)))))
    (is (every? :side strains)
        (str "dose rows without a side: "
             (pr-str (mapv :name (remove :side strains)))))
    ;; and they agree with the tensions they came from, which is the point —
    ;; equal values, not a re-derivation that happens to match
    (let [by-name (into {} (map (juxt :name identity) tensions))]
      (doseq [s strains]
        (let [t (get by-name (:name s))]
          (is (= (:group t) (:group s)) (str (:name s) ": group differs from its tension"))
          (is (= (:side t) (:side s)) (str (:name s) ": side differs from its tension")))))))

(deftest test-a-record-without-a-group-is-refused-for-the-reason-it-names
  ;; The fallback is gone, so a producer that has not been updated must be told
  ;; so rather than have its side guessed. Pins the reason, not merely that it
  ;; threw: a nil-punning NullPointerException would also "throw".
  (let [e (try (datoms/instance-group+side {:name "upper_trapezius/left"})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e) "a record with no :group must be refused")
    (is (= :value-error (:type (ex-data e))))
    (is (= :group (:missing (ex-data e)))
        (str "refused for a different reason: " (pr-str (ex-data e))))))
