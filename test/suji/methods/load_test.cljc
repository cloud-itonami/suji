(ns suji.methods.load-test
  "suji (筋) — load/segment physics tests, incl. the Hansraj 2014 validation anchor.
  1:1 Clojure port of src/suji/methods/test_load.cljc."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

(deftest test-segment-masses-sum-plausibly
  ;; One of each segment, so each PAIRED limb is counted once — this is a half-body
  ;; plus the midline, not a body. The bounds moved on 2026-09-07 when the lower
  ;; limb landed: with an arm and a leg the one-of-each sum is 0.789 of body mass,
  ;; where with an arm alone it was 0.628. `lower-limb-test`'s
  ;; `the-whole-body-is-accounted-for` states the exact claim — every paired
  ;; segment counted TWICE sums to 1.000 — which is the one that can be asserted
  ;; sharply rather than as a range.
  (let [body (segment/build-body 70.0 1.70)
        total (reduce + 0.0 (map :mass-kg (vals (:segments body))))]
    (is (< (* 0.7 70) total (* 0.85 70)))
    (is (> (:mass-kg (segment/seg (segment/build-body 70) "head_neck")) 0))))

(deftest test-head-mass-matches-hansraj-head
  ;; Hansraj uses a ~12 lb (5.44 kg) head; Winter's 8.1% at 67 kg ≈ 5.4 kg.
  (is (< (Math/abs (- (segment/head-mass-kg 67.0) 5.44)) 0.3)))

(deftest test-reproduces-hansraj-table
  ;; Cervical compressive load multiplier must track Hansraj (2014) within 10%.
  (let [head-w (* (segment/head-mass-kg 70.0) segment/gravity)
        expected {0 1.0, 15 2.25, 30 3.33, 45 4.08, 60 5.0}]
    (doseq [[deg mult] expected]
      (let [got (:multiplier-vs-head (load/cervical-load deg head-w))]
        (is (< (/ (Math/abs (- got mult)) mult) 0.10)
            (str deg "°: got " got ", expected " mult))))))

(deftest test-cervical-load-monotonic-in-flexion
  (let [head-w (* (segment/head-mass-kg 70.0) segment/gravity)
        loads (mapv #(:compressive-load-kgf (load/cervical-load % head-w)) (range 0 61 5))]
    (is (every? (fn [[a b]] (>= b a)) (map vector loads (rest loads))))))

(deftest test-cervical-load-rejects-bad-input
  (doseq [[bad-w bad-arm] [[-1.0 0.02] [50.0 0.0]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (load/cervical-load 30.0 bad-w 0.10 bad-arm)))))

(deftest test-laptop-lap-loads-more-than-eye-level-monitor
  (let [body (segment/build-body 70.0 1.70)
        lap (load/solve-posture-loads body (posture/posture-from-workstation posture/laptop-on-lap))
        mon (load/solve-posture-loads body (posture/posture-from-workstation posture/external-monitor-eye-level))]
    (is (> (get-in lap [:cervical :compressive-load-kgf])
           (get-in mon [:cervical :compressive-load-kgf])))
    (is (< (get-in mon [:cervical :multiplier-vs-head]) 2.0))
    (is (> (get-in lap [:cervical :multiplier-vs-head]) 3.0))))

(deftest test-unsupported-arms-load-shoulder-more
  (let [body (segment/build-body 70.0 1.70)
        sup (posture/posture-from-workstation posture/external-monitor-eye-level)
        unsup (posture/posture-from-workstation posture/laptop-on-lap)
        sh-sup (first (filter #(= (:joint %) "shoulder")
                              (:joints (load/solve-posture-loads body sup))))
        sh-unsup (first (filter #(= (:joint %) "shoulder")
                                (:joints (load/solve-posture-loads body unsup))))]
    (is (> (:moment-nm sh-unsup) (:moment-nm sh-sup)))))
