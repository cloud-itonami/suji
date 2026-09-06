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

(deftest test-endurance-infinity-encoded-as-minus-one
  (let [strains (filter #(contains? % ":strain/endurance-min") (the-datoms))]
    (is (some #(= (get % ":strain/endurance-min") -1.0) strains))
    (is (every? #(or (= (get % ":strain/endurance-min") -1.0)
                     (> (get % ":strain/endurance-min") 0)) strains))))

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

(deftest test-the-lower-limb-joints-are-emitted-with-declared-keywords
  ;; `joint-kw` maps a joint name to the keyword the schema declares, and falls back
  ;; to deriving one. The fallback exists because forgetting a new joint is the
  ;; obvious mistake — this asserts the three new ones were not forgotten, so the
  ;; fallback stays a safety net rather than the mechanism.
  (let [ds (datoms/scenario-datoms
            (scenario-for "x" posture/quiet-standing) "b" 0)
        joints (into #{} (keep #(get % ":load/joint")) ds)]
    (doseq [j [":hip" ":knee" ":ankle"]]
      (is (contains? joints j) (str j " must appear in the emitted load datoms")))
    (is (contains? joints ":cervicothoracic") "and the old ones are still there")))
