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
            [suji.methods.datoms :as datoms]))

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
