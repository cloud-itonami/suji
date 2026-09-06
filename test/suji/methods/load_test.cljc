(ns suji.methods.load-test
  "suji (筋) — load/segment physics tests, incl. the Hansraj 2014 validation anchor.
  1:1 Clojure port of src/suji/methods/test_load.cljc."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
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
    ;; ⚠ AND THE POSE-DERIVED MOMENT IS NO LONGER IDENTICAL IN ALL THREE, since
    ;; 2026-09-07. That used to be the second half of this test and it was true of a
    ;; RIGID neck: one block tilted `trunk + head` from vertical is in the same
    ;; place however the two are divided. With three cervical segments it is not,
    ;; and the difference is the thing the split was for — `:head-flexion-deg` bends
    ;; the column and `:trunk-flexion-deg` does not, so a person bent 60 deg at the
    ;; waist with the neck IN LINE has a straight neck and a person flexing their
    ;; head 60 deg has a curved one. Measured on a 70 kg / 1.70 m body:
    ;;
    ;;     head 60 / trunk  0   8.3348 N.m   the neck bends through the whole 60
    ;;     head 30 / trunk 30   8.2693 N.m
    ;;     head  0 / trunk 60   8.1944 N.m   the neck is straight; the old value
    ;;
    ;; 1.7% apart, and the third is bit-identical to what all three used to be,
    ;; which is the control: with no head flexion there is no intra-cervical bend
    ;; and the split changes nothing. The CERVICAL LOAD above is still identical in
    ;; all three, because it reads the head\'s tilt from vertical and that is
    ;; unchanged — which is the claim this test is named for.
    (let [truth (fn [head trunk]
                  (let [p (pose/solve-pose body (merge base {:head-flexion-deg head
                                                             :trunk-flexion-deg trunk}))
                        w (pose/segment-weights body p)]
                    (pose/gravitational-moment
                     (get-in p [:joints :c7])
                     (for [s (pose/segments-on p segment/cervical-bases)] [s (get w (:name s))]))))
          ts [(truth 60.0 0.0) (truth 30.0 30.0) (truth 0.0 60.0)]]
      (is (math/nearly= 8.3348 (first ts) 1e-4) (str "head 60 / trunk 0: " ts))
      (is (math/nearly= 8.2693 (second ts) 1e-4) (str "head 30 / trunk 30: " ts))
      (is (math/nearly= 8.1944 (nth ts 2) 1e-4) (str "head 0 / trunk 60: " ts))
      ;; ordered, and by little: the more of the angle that is HEAD flexion, the
      ;; further forward the bent column carries the mass above C7
      (is (> (first ts) (second ts) (nth ts 2))
          (str "head flexion must cost more about C7 than the same trunk lean: " ts))
      (is (< (/ (- (first ts) (nth ts 2)) (nth ts 2)) 0.02)
          (str "and only by about 2 percent, because the partition is small: " ts)))))

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

;; --- what the cervical split had to leave alone (2026-09-07) ------------------

(deftest the-split-is-winters-one-row-divided-and-not-changed
  ;; Winter's Table 4.1 has ONE row above the thorax — `Head and neck`, 0.081 of body
  ;; mass — and this model now has three segments there. The three have to be that
  ;; row and nothing else: the whole-body mass table sums to 1.000 only if they do,
  ;; and `load/cervical-load` is handed their sum as Hansraj's head weight.
  (let [b (segment/build-body 70.0 1.70)
        segs (mapv #(segment/seg b %) segment/cervical-bases)]
    (is (= 3 (count segs)) "three segments, C7 to the vertex")
    (is (math/nearly= (* 0.081 70.0) (reduce + 0.0 (map :mass-kg segs)) 1e-12)
        (str "their masses must be Winter's 0.081: " (mapv :mass-kg segs)))
    (is (math/nearly= (* 0.182 1.70) (reduce + 0.0 (map :length-m segs)) 1e-12)
        (str "and their lengths Drillis & Contini's 0.182 H: " (mapv :length-m segs)))))

(deftest the-split-keeps-the-complex-centre-of-mass
  ;; The head's `:com-frac` is DERIVED from this rather than chosen, so this is the
  ;; equation it was solved from, asserted back. At the neutral posture the three
  ;; segments are collinear, so their combined centre of mass has to land exactly
  ;; where the single `head_neck` segment's did — 0.55 of C7→vertex from C7. That is
  ;; what keeps the gravitational moment about C7 unchanged for an unflexed neck,
  ;; and it is the reason a change to `segment/head-share-of-complex` needs nothing
  ;; else edited.
  (let [b (segment/build-body 70.0 1.70)
        p (pose/solve-pose b {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                                 :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0})
        c7 (get-in p [:joints :c7])
        w (pose/segment-weights b p)
        segs (pose/segments-on p segment/cervical-bases)
        total (reduce + 0.0 (map #(get w (:name %)) segs))
        ;; height of the combined centre of mass above C7
        com-y (/ (reduce + 0.0 (for [s segs] (* (get w (:name s)) (- (second (:com s))
                                                                     (second c7)))))
                 total)]
    (is (math/nearly= (* 0.55 0.182 1.70) com-y 1e-12)
        (str "the three together must sit where the one segment did: " com-y
             " vs " (* 0.55 0.182 1.70)))))

(deftest the-hansraj-cervical-load-is-unchanged-by-the-split
  ;; THE THING THAT WAS NOT ALLOWED TO MOVE. `load/cervical-load` takes the head's
  ;; tilt from vertical and the weight above C7; the split preserved both exactly —
  ;; the partition sums to 1.0 so the skull still lands at trunk + head flexion, and
  ;; the three masses still sum to Winter's 0.081 — so every value it produces is
  ;; bit-identical to the values measured on `origin/main` before the neck had any
  ;; joints in it. Measured there on a 70 kg / 1.70 m body at the three reference
  ;; workstations by running `origin/main` itself out of `git archive`, and pinned
  ;; here as full doubles rather than to a tolerance.
  (let [b (segment/build-body 70.0 1.70)]
    (doseq [[ws tilt compressive]
            [[posture/laptop-on-lap 63.5 273.61858521664936]
             [posture/laptop-on-desk 32.0 194.4819901245166]
             [posture/external-monitor-eye-level 10.0 103.0363709306259]]]
      (let [c (:cervical (load/solve-posture-loads
                          b (posture/posture-from-workstation ws)))]
        (is (= tilt (:head-tilt-deg c))
            (str (:name ws) ": the head's tilt from vertical must not have moved"))
        (is (math/nearly= compressive (:compressive-load-n c) 1e-9)
            (str (:name ws) ": the Hansraj-calibrated compressive load must not "
                 "have moved, got " (:compressive-load-n c)))))
    ;; and the head weight it is handed is still the WHOLE complex above C7, which
    ;; is what Hansraj's 12-lb head means here. Handing it the skull alone after the
    ;; split would have cut this by a fifth and looked like nothing.
    (is (math/nearly= 55.6037055 (:head-weight-n
                                  (:cervical (load/solve-posture-loads
                                              b (posture/posture-from-workstation
                                                 posture/laptop-on-lap))))
                      1e-9)
        "the head weight is the three cervical segments, not the skull")))

(deftest the-capitis-muscles-over-supply-the-atlanto-occipital-joint
  ;; THE RESULT THE SPLIT PRODUCED, and the reason the suboccipitals are given a
  ;; residual rather than the whole demand. Semispinalis capitis and splenius
  ;; capitis are sized by the load at C7 and insert on the occiput, so about the
  ;; joint above them they are already exerting more than the skull's weight asks
  ;; for. Measured at laptop-on-lap on a 70 kg / 1.70 m body: 2.648 N·m demanded,
  ;; 6.223 N·m supplied — a factor of 2.35.
  ;;
  ;; Charging the suboccipitals the whole 2.648 made rectus capitis posterior major
  ;; the worst-loaded muscle in the entire report at 51% MVC. That is what this test
  ;; exists to stop coming back.
  (let [b (segment/build-body 70.0 1.70)
        pst (posture/posture-from-workstation posture/laptop-on-lap)
        loads (load/solve-posture-loads b pst)
        tens (muscle/solve-muscle-tensions b pst loads)
        forces (into {} (for [t tens :when (contains? load/capitis-groups (:group t))]
                          [(:group t) (:force-n t)]))
        ao (load/atlanto-occipital-moment b pst forces)]
    (is (= 2 (count forces)) (str "the premise: two capitis muscles solved at C7: " forces))
    (is (math/nearly= 2.648 (:moment-nm ao) 0.001)
        (str "the skull about the condyles: " ao))
    (is (math/nearly= 6.223 (:capitis-nm ao) 0.001)
        (str "what the C7-solved muscles are already exerting there: " ao))
    (is (zero? (:residual-nm ao))
        "so nothing is left for the suboccipitals at a desk posture")
    (is (> (:over-supplied-nm ao) 3.0)
        (str "and the surplus is reported rather than dropped: " ao))))

(deftest the-suboccipitals-carry-load-where-the-residual-is-positive
  ;; THE OTHER DIRECTION, without which the test above only shows a muscle that
  ;; never works. Head held back on a deeply flexed trunk — looking forward from a
  ;; bend — is the posture where the C7 demand falls faster than the skull's own
  ;; moment does, so the capitis muscles stop covering the joint above them and the
  ;; suboccipitals have something to do. Swept 2026-09-07 over head −60…60 and trunk
  ;; 0…75 in 5 deg steps: 17 of 400 postures have a positive residual, all of them
  ;; head −40…−60 on a trunk flexed 60…75.
  (let [b (segment/build-body 70.0 1.70)
        pst {:head-flexion-deg -55.0 :trunk-flexion-deg 75.0
             :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :arms-supported false}
        loads (load/solve-posture-loads b pst)
        tens (muscle/solve-muscle-tensions b pst loads)
        by #(first (filter (fn [t] (= % (:group t))) tens))
        forces (into {} (for [t tens :when (contains? load/capitis-groups (:group t))]
                          [(:group t) (:force-n t)]))
        ao (load/atlanto-occipital-moment b pst forces)]
    (is (pos? (:residual-nm ao)) (str "the premise: a positive residual here: " ao))
    (doseq [m ["rectus_capitis_posterior_major" "rectus_capitis_posterior_minor"
               "obliquus_capitis_superior"]]
      (is (pos? (:force-n (by m)))
          (str m " must carry force where the residual is positive: " (by m)))
      (is (pos? (:mvc-pct (by m)))
          (str m " must report a %MVC rather than a refusal: " (by m))))
    ;; and the same three carry nothing at a desk, which is the discriminating half
    (let [desk (posture/posture-from-workstation posture/laptop-on-lap)
          dl (load/solve-posture-loads b desk)
          dt (muscle/solve-muscle-tensions b desk dl)
          dby #(first (filter (fn [t] (= % (:group t))) dt))]
      (doseq [m ["rectus_capitis_posterior_major" "rectus_capitis_posterior_minor"
                 "obliquus_capitis_superior"]]
        (is (zero? (:force-n (dby m)))
            (str m " carries nothing at a laptop posture: " (dby m)))
        (is (not (:refused (dby m)))
            (str m " is not REFUSED there — a zero load is a placed load, and the "
                 "difference is between `nothing is asked of it here` and `this "
                 "model could not answer`: " (dby m)))))))
