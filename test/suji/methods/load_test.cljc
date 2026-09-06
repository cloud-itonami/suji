(ns suji.methods.load-test
  "suji (筋) — load/segment physics tests, incl. the Hansraj 2014 validation anchor.
  1:1 Clojure port of src/suji/methods/test_load.cljc."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
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
  ;;
  ;; HANSRAJ IS MEASURED WITH THE TRUNK UPRIGHT, so this anchor constrains the
  ;; model along one line only — trunk = 0 — and says nothing about a leaning
  ;; trunk. The 2026-09-07 correction changes only which ANGLE the model is handed
  ;; (the head's tilt from vertical, which is trunk + head); at trunk = 0 the two
  ;; are the same number and this table is untouched. It is pinned exactly below,
  ;; because "within 10%" would not have noticed if it had moved.
  (let [head-w (* (segment/head-mass-kg 70.0) segment/gravity)
        expected {0 1.0, 15 2.25, 30 3.33, 45 4.08, 60 5.0}]
    (doseq [[deg mult] expected]
      (let [got (:multiplier-vs-head (load/cervical-load deg head-w))]
        (is (< (/ (Math/abs (- got mult)) mult) 0.10)
            (str deg "°: got " got ", expected " mult))))))

(deftest test-the-hansraj-multipliers-are-unchanged-to-the-bit
  ;; The validation anchor, pinned at full precision rather than to 10%. If a
  ;; change to this model moves any of these five numbers, it has moved the one
  ;; quantity in this library that answers to a published measurement.
  (let [head-w (* (segment/head-mass-kg 70.0) segment/gravity)]
    ;; measured on `origin/main` before the correction and again after it, on the
    ;; same 70 kg body: identical to the last bit, because the correction changes
    ;; only which angle `solve-posture-loads` HANDS this function, and Hansraj's
    ;; table is taken with the trunk upright, where that angle is unchanged.
    (doseq [[deg mult] {0 1.0, 15 2.260021051801672, 30 3.366025403784438,
                        45 4.242640687119285, 60 4.830127018922192}]
      (is (math/nearly= mult (:multiplier-vs-head (load/cervical-load (double deg) head-w)) 1e-12)
          (str deg "°: the Hansraj-calibrated multiplier moved")))))

(deftest test-cervical-load-follows-the-head-not-the-neck-angle
  ;; THE CONTROL THAT NAMES DEFECT 2. Gravity is world-fixed and `pose` places the
  ;; head at trunk + head, so these three postures put the head in the SAME place —
  ;; measured, the pose-derived moment about C7 is 8.1944 N·m in all three. The
  ;; model took `head-flexion-deg` straight, so it answered 4.8154, 2.7802 and
  ;; 0.0000: a person bent 60° at the waist with the neck in line was told their
  ;; cervical extensors were doing nothing at all.
  (let [body (segment/build-body 70.0 1.70)
        base {:shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :arms-supported false}
        at (fn [head trunk]
             (get-in (load/solve-posture-loads
                      body (merge base {:head-flexion-deg head :trunk-flexion-deg trunk}))
                     [:cervical :extensor-moment-nm]))
        ms [(at 60.0 0.0) (at 30.0 30.0) (at 0.0 60.0)]]
    (doseq [m (rest ms)]
      (is (math/nearly= (first ms) m 1e-9)
          (str "the same head placement must be the same cervical load, got " ms)))
    (is (> (first ms) 4.0) (str "and it is not zero: " ms))
    ;; and the true moment the placed head exerts about C7 is the same in all
    ;; three, which is what makes the claim above physics rather than arithmetic
    (let [truth (fn [head trunk]
                  (let [p (pose/solve-pose body (merge base {:head-flexion-deg head
                                                             :trunk-flexion-deg trunk}))
                        w (pose/segment-weights body p)]
                    (pose/gravitational-moment
                     (get-in p [:joints :c7])
                     (for [s (pose/segments-on p ["head_neck"])] [s (get w (:name s))]))))
          ts [(truth 60.0 0.0) (truth 30.0 30.0) (truth 0.0 60.0)]]
      (is (math/nearly= (first ts) (second ts) 1e-9))
      (is (math/nearly= (first ts) (nth ts 2) 1e-9)))))

(deftest test-a-leaning-trunk-is-not-a-cervical-holiday
  ;; the monotone form of the same defect: at a FIXED head angle, leaning the trunk
  ;; forward tilts the head with it and must raise the cervical load.
  (let [body (segment/build-body 70.0 1.70)
        ms (mapv (fn [trunk]
                   (get-in (load/solve-posture-loads
                            body {:head-flexion-deg 10.0 :trunk-flexion-deg trunk
                                  :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0
                                  :arms-supported false})
                           [:cervical :compressive-load-n]))
                 [0.0 15.0 30.0 45.0])]
    (is (every? (fn [[a b]] (< a b)) (partition 2 1 ms))
        (str "trunk flexion must raise the cervical load at a fixed head angle: " ms))))

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
