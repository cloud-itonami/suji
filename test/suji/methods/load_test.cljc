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
    (doseq [b segment/cervical-bases]
      (is (> (:mass-kg (segment/seg (segment/build-body 70) b)) 0)
          (str b " must have a mass")))
    ;; and the three together are still Winter's single head-and-neck row, which is
    ;; the property the Hansraj anchor rests on
    (is (math/nearly= (* 0.081 70.0) (segment/head-mass-kg 70.0) 1e-12)
        "the split must not have moved the mass above C7")))

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
                     (for [s (pose/segments-on p segment/cervical-bases)] [s (get w (:name s))]))))
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

(deftest test-the-cervical-angle-is-the-sagittal-tilt-and-the-bend-is-reported-elsewhere
  ;; `head-tilt-from-vertical-deg` returns the SAGITTAL tilt, which with lateral
  ;; bend is not the angle between the head's long axis and the vertical. That is a
  ;; division of labour rather than an approximation: the out-of-plane component is
  ;; `frontal-moments`' `:cervical-nm`, carried by the scalenes. Folding the bend
  ;; into the sagittal angle would charge one load to two equilibria — so this test
  ;; pins that bending does NOT move the sagittal number and DOES move the frontal
  ;; one.
  (let [body (segment/build-body 70.0 1.70)
        at (fn [bend] {:head-flexion-deg 20.0 :trunk-flexion-deg 10.0
                       :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0
                       :arms-supported false :trunk-lateral-bend-deg bend})
        tilt (fn [bend] (load/head-tilt-from-vertical-deg (pose/solve-pose body (at bend))))
        true-deg (fn [bend]
                   (let [d (:dir (pose/seg-at (pose/solve-pose body (at bend)) "head"))]
                     (* (/ 180.0 math/pi) (Math/acos (nth d 1)))))
        frontal (fn [bend] (:cervical-nm (load/frontal-moments body (at bend))))]
    (is (math/nearly= 30.0 (tilt 0.0) 1e-9) "trunk + head, with no bend")
    (is (math/nearly= (tilt 0.0) (tilt 30.0) 1e-9)
        (str "lateral bend must NOT move the sagittal angle, got " (tilt 30.0)))
    (is (math/nearly= (true-deg 0.0) (tilt 0.0) 1e-9)
        "with no bend the sagittal tilt IS the angle from vertical")
    (is (> (true-deg 30.0) (+ 10.0 (tilt 30.0)))
        (str "and with bend it is not: the head's axis is " (true-deg 30.0)
             "° off vertical where the sagittal tilt is " (tilt 30.0) "°"))
    (is (math/nearly= 0.0 (frontal 0.0) 1e-9) "no bend, no frontal cervical moment")
    (is (> (math/abs* (frontal 30.0)) 1.0)
        (str "and the bend's load is reported there instead, got " (frontal 30.0)))))

(deftest test-every-quantity-the-support-flag-reaches-responds-to-it
  ;; A COVERAGE TEST, and it exists because the flag reached the sagittal moments
  ;; and nothing else. Each of these is computed by different code; what they have
  ;; in common is that a desk under the forearms has to change all of them, and on
  ;; 2026-09-07 three of the six did not move by a single bit.
  ;;
  ;; The posture abducts and bends, so that the frontal quantities are non-zero at
  ;; all — an upright, unabducted posture reports 0.0 for every frontal term in
  ;; both support states, and a test written at one would have passed against the
  ;; defect.
  (let [body (segment/build-body 70.0 1.70)
        p (fn [sup] {:head-flexion-deg 0.0 :trunk-flexion-deg 20.0
                     :trunk-lateral-bend-deg 25.0
                     :shoulder-flexion-deg 20.0 :elbow-flexion-deg 90.0
                     :shoulder-abduction-deg 40.0 :wrist-extension-deg 0.0
                     :support :seated :arms-supported sup})
        solve (fn [sup] (load/solve-posture-loads body (p sup)))
        joint (fn [loads n] (:moment-nm (first (filter #(= n (:joint %)) (:joints loads)))))
        [a b] [(solve false) (solve true)]
        quantities {"shoulder (sagittal)" [(joint a "shoulder") (joint b "shoulder")]
                    "elbow (sagittal)" [(joint a "elbow") (joint b "elbow")]
                    "wrist (sagittal)" [(joint a "wrist") (joint b "wrist")]
                    "lumbosacral (sagittal)" [(joint a "lumbosacral") (joint b "lumbosacral")]
                    "shoulder (frontal)" [(get-in a [:frontal :shoulder-per-side :left])
                                          (get-in b [:frontal :shoulder-per-side :left])]
                    "lumbosacral (frontal)" [(get-in a [:frontal :lumbosacral-nm])
                                             (get-in b [:frontal :lumbosacral-nm])]}]
    (doseq [[label [unsup sup]] quantities]
      (is (> (Math/abs (- (double unsup) (double sup))) 1e-6)
          (str label " is identical supported and unsupported (" unsup " vs " sup
               ") — the desk does not reach it"))
      (is (< (Math/abs (double sup)) (Math/abs (double unsup)))
          (str label " must be SMALLER with the forearms rested, got "
               sup " against " unsup)))))
