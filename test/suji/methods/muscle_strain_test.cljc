(ns suji.methods.muscle-strain-test
  "suji (筋) — muscle %MVC + Rohmert strain tests. 1:1 Clojure port of
  src/suji/methods/test_muscle_strain.cljc."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.math :as math]
            [suji.methods.analyze :as analyze]
            [suji.methods.load :as load]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]))

(deftest test-muscle-mvc-in-range-and-nonneg
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        loads (load/solve-posture-loads body p)
        tensions (muscle/solve-muscle-tensions body p loads)]
    (is (= (set (map :name tensions)) (set (keys muscle/specs))))
    (doseq [t tensions]
      (is (>= (:force-n t) 0))
      (is (and (<= 0 (:mvc-pct t)) (< (:mvc-pct t) 100))))))

(deftest test-endurance-falls-with-load
  (is (< (strain/endurance-minutes 50.0)
         (strain/endurance-minutes 25.0)
         (strain/endurance-minutes 15.0)))
  (is (math/infinite? (strain/endurance-minutes 5.0)))
  (is (< (Math/abs (- (strain/endurance-minutes 50.0) 1.0)) 0.6))
  (is (< (Math/abs (- (strain/endurance-minutes 25.0) 5.0)) 2.5)))

(deftest test-stiffness-grows-with-load-and-time
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        tensions (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p))
        high (first (filter #(= (:name %) "cervical_extensors") tensions))
        s-short (strain/muscle-strain high 10.0)
        s-long (strain/muscle-strain high 120.0)]
    (is (<= 0 (:stiffness-index s-short)))
    (is (<= (:stiffness-index s-short) (:stiffness-index s-long)))
    ;; The index is bounded above by 1 and mathematically never reaches it, but in
    ;; double precision it rounds to exactly 1.0 once the dose passes ~37 — which
    ;; this posture does. So the bound is <=, and the saturation must be REPORTED:
    ;; a ceiling presented as a measurement cannot be told apart from a value.
    (is (<= (:stiffness-index s-long) 1.0))
    (is (:saturated? s-long)
        "a load this high for two hours saturates the index and has to say so")))

(deftest test-saturation-is-flagged-only-when-it-happens
  ;; the flag has to discriminate, or it is decoration
  (let [low (strain/muscle-strain {:name "x" :mvc-pct 9.0} 30.0)
        high (strain/muscle-strain {:name "x" :mvc-pct 60.0} 240.0)]
    (is (< (:stiffness-index low) 1.0))
    (is (not (:saturated? low)) "a light load must not be reported as saturated")
    (is (:saturated? high))))

(deftest test-stiffness-band-thresholds
  (is (= (strain/stiffness-band 0.1) "low"))
  (is (= (strain/stiffness-band 0.3) "moderate"))
  (is (= (strain/stiffness-band 0.6) "high"))
  (is (= (strain/stiffness-band 0.9) "very-high")))

(deftest test-strain-rejects-negative-session
  (let [body (segment/build-body)
        t (first (muscle/solve-muscle-tensions
                  body (posture/posture-from-workstation posture/laptop-on-lap)
                  (load/solve-posture-loads body (posture/posture-from-workstation posture/laptop-on-lap))))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (strain/muscle-strain t -5.0)))))

(deftest test-end-to-end-lap-worse-than-monitor
  (let [results (analyze/analyze-all 70.0 1.70 120.0)
        lap (first (filter #(= (:workstation %) "laptop-on-lap") results))
        mon (first (filter #(= (:workstation %) "external-monitor+keyboard") results))]
    (is (> (:stiffness-index (analyze/worst-stiffness (:strains lap)))
           (:stiffness-index (analyze/worst-stiffness (:strains mon)))))
    (let [reduction (- 1 (/ (get-in mon [:loads :cervical :compressive-load-kgf])
                            (get-in lap [:loads :cervical :compressive-load-kgf])))]
      (is (> reduction 0.4)))))
