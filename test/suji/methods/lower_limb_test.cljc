(ns suji.methods.lower-limb-test
  "suji (筋) — the lower limb, and the one thing about it that a plausible-looking
  model gets wrong.

  Every test here is a pair. The lower limb's loads are not determined by the
  posture — they are determined by the posture AND by what the body is resting on
  — so a test that only measures one support mode is measuring a field name. Each
  claim is therefore stated against its control: standing against seated, a squat
  against standing, a lean against a perfect stack."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.spine :as spine]))

(def ^:private body (segment/build-body 70.0 1.70))
(def ^:private body-weight-n (* 70.0 segment/gravity))

(defn- joint [loads nm] (first (filter #(= nm (:joint %)) (:joints loads))))
(defn- at [posture] (load/solve-posture-loads body posture))

;; --- the anthropometry -------------------------------------------------------

(deftest the-whole-body-is-accounted-for
  ;; The property that decides which anthropometric table the lower limb may come
  ;; from. Winter's fractions cover the body EXACTLY once when each paired segment
  ;; is counted twice; de Leva's re-cut thigh (14.16% against Winter's 10.0%) does
  ;; not compose with them, and a mixed table sums to about 1.08.
  ;;
  ;; This is not bookkeeping. The total is the magnitude of the ground reaction,
  ;; so an 8% error here is an 8% error in every standing moment the model
  ;; produces — and it would look entirely reasonable.
  (let [b (segment/build-body 70.0 1.70)
        total (reduce + 0.0 (for [[_ s] (:segments b)]
                              (* (if (:paired s) 2.0 1.0) (:mass-kg s))))]
    (is (math/nearly= 70.0 total 1e-9)
        (str "the nine segments must account for the whole 70 kg, got " total))
    ;; and the placed chain agrees with the table, which is a different claim:
    ;; it is the one that fails if `pose` places a leg once instead of twice
    (is (math/nearly= body-weight-n
                      (:weight-n (pose/whole-body-com b (pose/solve-pose b posture/standing-neutral)))
                      1e-6)
        "the placed chain must weigh the same as the body it was built from")))

(deftest the-lower-limb-segments-are-there-and-paired
  (let [p (pose/solve-pose body posture/standing-neutral)]
    (doseq [base ["thigh" "shank" "foot"]
            side ["left" "right"]]
      (is (some? (pose/seg-at p (str base "/" side)))
          (str base "/" side " must be placed")))
    (doseq [j [:hip/left :hip/right :knee/left :knee/right :ankle/left :ankle/right]]
      (is (some? (get-in p [:joints j])) (str j " must be a joint")))))

;; --- the crux: what the support mode costs -----------------------------------

(deftest standing-puts-the-whole-body-weight-through-the-ankles
  ;; THE CLAIM, stated as the arithmetic actually says it: the two ankles together
  ;; transmit the whole body's weight less the feet below them, and each takes
  ;; half. A body standing on two legs is not standing on one.
  ;;
  ;; THE CONTROL is the same posture seated. If this test measured only the
  ;; standing number it would pass just as happily against a model that assigned
  ;; body weight to the ankle unconditionally — which is the exact defect the
  ;; support mode exists to prevent.
  (let [stand (at posture/standing-neutral)
        sit (at (assoc posture/standing-neutral :support :seated))
        feet-n (* 2.0 0.0145 body-weight-n)
        w (fn [loads] (:supported-weight-n (joint loads "ankle")))]
    (is (math/nearly= (- body-weight-n feet-n)
                      (+ (:left (w stand)) (:right (w stand)))
                      1.0)
        (str "standing: the two ankles carry the body less its feet, got " (w stand)))
    (is (math/nearly= (:left (w stand)) (:right (w stand)) 1e-9)
        "and share it equally in a symmetric stance")
    (is (> (:left (w stand)) (* 0.45 body-weight-n))
        "so each ankle is carrying about half a body")
    ;; the control
    (is (< (+ (:left (w sit)) (:right (w sit))) (* 0.05 body-weight-n))
        (str "seated: the chair has it, and the ankles carry only the feet, got " (w sit)))
    (is (> (/ (:left (w stand)) (:left (w sit))) 20.0)
        "the two modes differ by more than an order of magnitude, not by a nuance")))

(deftest seated-does-not-put-body-weight-through-the-knee
  ;; The failure this is written against is silent: a model that ran the trunk's
  ;; weight down through a seated leg would report a few hundred newtons at every
  ;; desk posture, which looks like a knee. The control is the same body standing,
  ;; where a few hundred newtons is the right answer.
  (let [desk (posture/posture-from-workstation posture/laptop-on-desk)
        sit (at desk)
        stand (at (assoc desk :support :standing))
        w (fn [loads] (:left (:supported-weight-n (joint loads "knee"))))]
    (is (< (w sit) (* 0.10 body-weight-n))
        (str "a seated knee carries the shank and the foot, nothing else: " (w sit) " N"))
    (is (> (w stand) (* 0.35 body-weight-n))
        (str "a standing knee carries a body: " (w stand) " N"))
    ;; and the hip of a seated person is emptier still, because the seat is under
    ;; the thigh — see `posture/thigh-supported?`
    (is (math/nearly= 0.0 (:left (:supported-weight-n (joint sit "hip"))) 1e-9)
        "the seat carries the thigh, so the seated hip transmits nothing")
    (is (> (:left (:supported-weight-n (joint stand "hip"))) (* 0.30 body-weight-n))
        "and the standing hip carries the head, arms and trunk")))

(deftest the-two-support-modes-differ-by-exactly-one-force
  ;; The structural claim `posture/support-mode` makes in words. The free body
  ;; distal to a joint holds the same segments in both modes; standing adds the
  ;; ground reaction and nothing else. If the two modes ever diverge by anything
  ;; but that term, one of them has grown a second code path.
  (let [angles (dissoc posture/deep-squat :support)
        stand (at (assoc angles :support :standing))
        sit (at (assoc angles :support :seated :thigh-supported false))
        p (pose/solve-pose body angles)
        cop (pose/centre-of-pressure body p :left)
        grf (* 0.5 body-weight-n)]
    (doseq [[nm jk] [["ankle" :ankle/left] ["knee" :knee/left] ["hip" :hip/left]]]
      (let [predicted (nth (pose/external-moment-vec (get-in p [:joints jk])
                                                     [[cop [0.0 grf 0.0]]])
                           2)
            measured (- (:left (:per-side (joint stand nm)))
                        (:left (:per-side (joint sit nm))))]
        (is (math/nearly= predicted measured 1e-9)
            (str nm ": standing − seated must be the ground reaction's moment and "
                 "nothing else; predicted " predicted " measured " measured))))))

;; --- quiet standing ----------------------------------------------------------

(deftest quiet-standing-carries-the-line-of-gravity-in-front-of-the-ankle
  ;; THE CHECKABLE PREDICTION this lower limb was asked for. In quiet standing the
  ;; ground reaction passes a few centimetres anterior to the ankle joint, so the
  ;; plantarflexors work continuously. The mechanism is the shank's forward lean
  ;; over a flat foot, and NOT a constant added to the ankle — which is what the
  ;; control demonstrates: with the chain stacked perfectly vertically the model
  ;; reports almost nothing, because a body balanced over its ankles needs almost
  ;; nothing from its calves.
  (let [lean (pose/solve-pose body posture/quiet-standing)
        flat (pose/solve-pose body posture/standing-neutral)
        offset (fn [p] (- (first (:point (pose/whole-body-com body p)))
                          (first (get-in p [:joints :ankle/left]))))]
    (is (< 0.02 (offset lean) 0.06)
        (str "quiet standing puts the line of gravity 2-6 cm in front of the ankle, got "
             (offset lean) " m"))
    (is (< (math/abs* (offset flat)) 0.01)
        (str "and a perfect vertical stack puts it over the ankle, got " (offset flat) " m"))
    ;; the moment follows, and its SIGN is the claim: a negative sagittal moment
    ;; about the ankle is the one a posterior muscle resists
    (let [m (:left (:per-side (joint (at posture/quiet-standing) "ankle")))]
      (is (neg? m) (str "the demand is on the muscles BEHIND the ankle, got " m " N·m"))
      (is (< 5.0 (math/abs* m) 25.0)
          (str "and is a real, low, non-zero load — 10-20 N·m per ankle is what "
               "quiet standing is measured to cost. Got " m " N·m")))
    (is (> (math/abs* (:left (:per-side (joint (at posture/quiet-standing) "ankle"))))
           (* 10.0 (math/abs* (:left (:per-side (joint (at posture/standing-neutral) "ankle"))))))
        "and the lean is where it comes from")))

(deftest a-standing-posture-keeps-its-line-of-gravity-over-its-feet
  ;; Static equilibrium is not optional: a body whose centre of pressure has left
  ;; its base of support is not standing, it is falling. The model reports this
  ;; rather than refusing, so the reference postures had better satisfy it.
  (doseq [p posture/reference-standing-postures]
    (is (true? (get-in (at p) [:support :cop-inside-base?]))
        (str (:name p) " must be a posture a body can actually hold")))
  ;; the control: a posture that leans past the toes is reported as one nobody can
  ;; hold, rather than being handed back with a straight face
  (let [toppling (assoc posture/quiet-standing :trunk-flexion-deg 70.0 :hip-flexion-deg 0.0)]
    (is (false? (get-in (at toppling) [:support :cop-inside-base?]))
        "a trunk folded 70° over locked legs puts the mass past the toes")))

;; --- what the squat does that standing does not ------------------------------

(deftest a-deep-squat-loads-the-knee-and-standing-does-not
  ;; The knee's moment reverses SIGN between the two, and that is the whole story
  ;; of why quadriceps are silent in standing and dominant in a squat. Standing,
  ;; the ground reaction passes in front of the knee and the joint rests back
  ;; against its own posterior structures. Squatting, the knee has travelled
  ;; forward past the foot, so the same force passes well behind it.
  ;;
  ;; The control is that STANDING IS LOW — asserted on the standing number, not
  ;; inferred from the squat being high.
  (let [squat (:left (:per-side (joint (at posture/deep-squat) "knee")))
        quiet (:left (:per-side (joint (at posture/quiet-standing) "knee")))
        p (pose/solve-pose body posture/deep-squat)]
    (is (< (math/abs* quiet) 5.0)
        (str "quiet standing barely loads the knee at all: " quiet " N·m"))
    (is (> squat 30.0)
        (str "a deep squat loads it heavily, in the extensor sense: " squat " N·m"))
    (is (pos? (* squat (- quiet)))
        "and in the opposite sense to standing, which is why the muscle changes")
    (is (> (first (get-in p [:joints :knee/left]))
           (+ 0.10 (first (get-in p [:joints :ankle/left]))))
        "the mechanism: in a squat the knee has travelled well forward of the ankle")))

;; --- the leg is not part of the spine ----------------------------------------

(deftest the-legs-do-not-load-the-lumbar-spine
  ;; Measured while adding them. `spine/above-fraction` decides how much of a
  ;; segment sits above a level from a rank table of the two SPINAL segments, and
  ;; treated everything it did not recognise as sitting above every trunk level.
  ;; That was right for the arms and wrong for the legs the moment they existed:
  ;; a third of body mass was silently added to L5/S1.
  (let [upright (merge posture/standing-neutral {:trunk-flexion-deg 0.0})
        rows (spine/profile body upright [])
        l5s1 (first (filter #(= "L5/S1" (:name %)) rows))
        hat (* body-weight-n (+ 0.081 0.355 (* 2.0 (+ 0.028 0.016 0.006))))
        legs (* body-weight-n (* 2.0 (+ 0.100 0.0465 0.0145)))]
    (is (math/nearly= hat (:weight-n l5s1) 1.0)
        (str "L5/S1 carries the head, arms and trunk — " hat " N — and got "
             (:weight-n l5s1) " N"))
    (is (< (:weight-n l5s1) (- (+ hat legs) 1.0))
        (str "and specifically NOT the " legs " N of leg hanging below it"))))

;; --- the shape a consumer sees -----------------------------------------------

(deftest the-lower-limb-reads-like-every-other-joint
  ;; The integration requirement: an existing consumer walks `:joints` reading
  ;; `:joint` and `:moment-nm`, and must not need to know a lower limb was added.
  (let [loads (at posture/quiet-standing)
        names (mapv :joint (:joints loads))]
    (is (= ["cervicothoracic" "shoulder" "elbow" "wrist" "lumbosacral" "hip" "knee" "ankle"]
           names)
        (str "the lower limb appends to the same vector, in chain order: " names))
    (doseq [j (:joints loads)]
      (is (string? (:joint j)) "every entry names its joint as a string")
      (is (number? (:moment-nm j)) (str (:joint j) " must report a moment"))
      (is (string? (:note j)) (str (:joint j) " must say what it is carrying")))
    ;; and the extra keys are additive, not a different shape
    (doseq [nm ["hip" "knee" "ankle"]]
      (let [j (joint loads nm)]
        (is (map? (:per-side j)) (str nm " reports per side, like the shoulder does"))
        (is (map? (:supported-weight-n j)) (str nm " reports the axial force it transmits"))))))
