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
            [suji.methods.attachment :as att]
            [suji.methods.load :as load]
            [suji.methods.muscle :as muscle]
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
    (is (= ["cervicothoracic" "atlanto-occipital" "shoulder" "elbow" "wrist"
            "lumbosacral" "hip" "knee" "ankle"]
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

;; --- the muscles -------------------------------------------------------------

(defn- tensions-at [posture]
  (let [l (load/solve-posture-loads body posture)
        ts (muscle/solve-muscle-tensions body posture l)]
    {:loads l
     :tensions ts
     :summary (muscle/tension-summary ts l)
     :by (into {} (map (juxt :name identity)) ts)}))

(deftest soleus-works-in-quiet-standing
  ;; THE PREDICTION THE LOWER LIMB WAS ASKED TO MAKE. In quiet standing the ground
  ;; reaction passes anterior to the ankle, so the plantarflexors are never off.
  ;; A model that reports soleus silent in quiet standing is wrong, and it is
  ;; wrong in a way that reads as a clean answer.
  ;;
  ;; THE CONTROL is the perfect vertical stack, where soleus SHOULD be silent —
  ;; because a body balanced exactly over its ankles asks nothing of its calves.
  ;; Without that control this test would pass against a model that gave soleus a
  ;; floor, which is the cheapest way to make this number come out non-zero and
  ;; the one that would mean nothing.
  (let [quiet (:by (tensions-at posture/quiet-standing))
        flat (:by (tensions-at posture/standing-neutral))
        sol (quiet "soleus/left")]
    (is (number? (:mvc-pct sol))
        (str "soleus must have a %MVC at all in quiet standing, got " (select-keys sol [:refused :note])))
    (is (< 1.0 (:mvc-pct sol) 15.0)
        (str "and it must be low but NOT zero — a few %MVC is what quiet standing "
             "is measured to cost the calf. Got " (:mvc-pct sol) " %"))
    (is (> (:force-n sol) 50.0)
        (str "which is a real force, not a rounding artefact: " (:force-n sol) " N"))
    ;; the control
    (is (< (:force-n (flat "soleus/left")) 5.0)
        (str "stacked perfectly over the ankles, soleus has nothing to do: "
             (:force-n (flat "soleus/left")) " N"))
    (is (> (:force-n sol) (* 20.0 (max 1.0 (:force-n (flat "soleus/left")))))
        "so the demand comes from the lean, not from a constant")
    ;; and the one-joint / two-joint pair are both on, with soleus doing more —
    ;; it is the larger muscle and the one that does not care about the knee
    (is (pos? (:force-n (quiet "gastrocnemius/left"))))
    (is (> (:force-n sol) (:force-n (quiet "gastrocnemius/left")))
        "soleus carries more of quiet standing than gastrocnemius does")))

(deftest a-deep-squat-loads-the-quadriceps-and-standing-does-not
  ;; THE CONTROL IS THAT STANDING IS LOW, asserted on the standing number. Written
  ;; the other way round — squat is high — it would pass against a model that
  ;; loaded the quadriceps in every posture.
  (let [squat (:by (tensions-at posture/deep-squat))
        quiet (:by (tensions-at posture/quiet-standing))]
    ;; ⚠ WHAT MOVED ON 2026-09-08. This used to assert that the vasti are the
    ;; ANTAGONIST in quiet standing — refused, no %MVC at all — because the knee's
    ;; demand there is flexor and `share-signed` refuses the side that is not
    ;; resisting. The coupled solve does not decide the knee on its own: the same
    ;; multipliers price the hip and the ankle, and the cheapest way to close all
    ;; three is a small vasti force (47.54 N, 1.10% MVC) rather than none.
    ;;
    ;; That is a co-contraction the uncoupled model could not express, and it is
    ;; SMALL — which is the assertion, because a large one would be a finding
    ;; about the model and not about standing.
    (is (nil? (:refused (quiet "vasti/left")))
        (str "the coupled solve computes a force for the vasti in standing: "
             (select-keys (quiet "vasti/left") [:refused :force-n :mvc-pct])))
    (is (< (:mvc-pct (quiet "vasti/left")) 3.0)
        (str "and it must be a co-contraction and not a load: "
             (:mvc-pct (quiet "vasti/left")) " %MVC"))
    (is (> (:mvc-pct (squat "vasti/left")) (* 10.0 (:mvc-pct (quiet "vasti/left"))))
        (str "a squat must ask them for more than an order of magnitude more: "
             (:mvc-pct (squat "vasti/left")) " vs " (:mvc-pct (quiet "vasti/left"))))
    (is (> (:force-n (squat "vasti/left")) 500.0)
        (str "a deep squat is what a quadriceps is for: " (:force-n (squat "vasti/left")) " N"))
    (is (> (:mvc-pct (squat "vasti/left")) 15.0)
        (str "and it is a large fraction of what they can produce: "
             (:mvc-pct (squat "vasti/left")) " %"))
    ;; ⚠ AND SO DID RECTUS FEMORIS, in the other direction. Solved at the knee
    ;; alone it took 86.54 N in a deep squat; solved with the hip in the problem
    ;; the optimum switches it OFF, because every newton it puts into extending
    ;; the knee also flexes a hip that gravity is already flexing. That is the
    ;; trade the closed form could not see, and it is why the assertion is now
    ;; that it is inactive rather than that it is pulling.
    (is (:inactive? (squat "rectus_femoris/left"))
        (str "the coupled optimum switches rectus femoris off in a deep squat: "
             (select-keys (squat "rectus_femoris/left") [:active-n :price :inactive?])))
    (is (neg? (:price (squat "rectus_femoris/left")))
        (str "and the reason is its price, not a refusal: "
             (:price (squat "rectus_femoris/left"))))
    ;; the hip extensor is still doing the hip extension, which is the half that
    ;; must NOT have moved
    (is (> (:force-n (squat "gluteus_maximus/left")) 500.0)
        "and a squat is a hip extension as much as a knee extension")
    ;; the mechanism, stated as the sign of the joint moment rather than inferred
    (let [k (fn [r] (:left (:per-side (joint (:loads r) "knee"))))]
      (is (neg? (k (tensions-at posture/quiet-standing))))
      (is (pos? (k (tensions-at posture/deep-squat)))
          "the knee moment reverses sign, which is why the muscle changes"))))

(deftest the-lower-limb-muscles-pull-the-way-they-are-named
  ;; Sign errors in a moment arm look like numbers, not like errors. At the
  ;; anatomical neutral every one of these has a known sense, and getting one
  ;; backwards would silently hand a joint's load to its antagonist.
  (let [p (pose/solve-pose body att/reference-posture)
        arm (fn [n] (get (att/arms p 1.70) n))]
    (doseq [[n sense] [["gluteus_maximus/left" :neg]   ;; hip extensor
                       ["iliopsoas/left" :pos]         ;; hip flexor
                       ["vasti/left" :pos]             ;; knee extensor
                       ["rectus_femoris/left" :pos]    ;; knee extensor
                       ["hamstrings/left" :neg]        ;; knee flexor
                       ["gastrocnemius/left" :neg]     ;; ankle plantarflexor
                       ["soleus/left" :neg]            ;; ankle plantarflexor
                       ["tibialis_anterior/left" :pos]]] ;; ankle dorsiflexor
      (is (if (= :pos sense) (pos? (arm n)) (neg? (arm n)))
          (str n " must act " (name sense) " at neutral, got " (arm n))))
    ;; and each pair really opposes, which is what makes the equilibrium solvable
    (doseq [[a b] [["gluteus_maximus/left" "iliopsoas/left"]
                   ["vasti/left" "hamstrings/left"]
                   ["soleus/left" "tibialis_anterior/left"]]]
      (is (neg? (* (arm a) (arm b))) (str a " and " b " must oppose")))
    ;; the two sides mirror in the sagittal plane: a sagittal moment does NOT
    ;; reverse under the mirror, unlike a frontal one
    (doseq [g ["gluteus_maximus" "vasti" "soleus" "tibialis_anterior"]]
      (is (math/nearly= (arm (str g "/left")) (arm (str g "/right")) 1e-9)
          (str g ": a sagittal arm is the same on both sides")))))

(deftest neutral-lower-limb-arms-reproduce-their-calibration-targets
  ;; The same anchor the upper limb has. The offsets in `attachment/muscles` exist
  ;; to put each neutral arm on the representative value `muscle/specs` records;
  ;; if an edit moves the neutral leverage, every lower-limb %MVC silently changes
  ;; meaning.
  (let [p (pose/solve-pose body att/reference-posture)]
    (doseq [g ["gluteus_maximus" "iliopsoas" "vasti" "rectus_femoris"
               "hamstrings" "gastrocnemius" "soleus" "tibialis_anterior"]]
      (let [want (:moment-arm-m (get muscle/specs g))
            got (math/abs* (get (att/arms p 1.70) (str g "/left")))]
        (is (number? want) (str g " must record its calibration target"))
        (is (math/nearly= want got (* 0.06 want))
            (str g ": neutral arm " got " must reproduce the stated " want))))))

(deftest the-patella-floors-the-quadriceps-arm
  ;; The patella is a sesamoid the extensor tendon passes OVER, so it holds the
  ;; line of action away from the knee centre — the same wrapping machinery as the
  ;; humeral head, not a second one. Without it the chord swings toward the joint
  ;; as the knee flexes and the force needed to hold a squat diverges.
  (let [spec (att/instance "vasti/left")
        r (get-in spec [:wrap :radius-m])
        at (fn [d] (let [p (pose/solve-pose body (merge att/reference-posture
                                                        {:hip-flexion-deg 85.0
                                                         :knee-flexion-deg (double d)}))]
                     (att/moment-arm-detail p 1.70 spec (get-in p [:joints :knee/left])
                                            att/flexion-axis)))
        dets (mapv at (range 0 141 10))]
    (is (some? r) "the quadriceps must declare a wrapping surface")
    (is (not (:retinaculum (:wrap spec)))
        "a patella is passed OVER, so it floors the arm rather than pinning it")
    (is (every? #(>= (:arm %) (- r 1e-12)) dets)
        (str "no arm may fall below the patellar radius " r ": " (mapv :arm dets)))
    (is (every? #(pos? (:arm %)) dets) "and the extensor stays an extensor")
    (is (some :wrapped? dets)
        "the surface must actually take over somewhere, or it is not doing anything")
    (is (some (complement :wrapped?) dets)
        "and must not take over everywhere, or it has flattened the variation")
    ;; the defect it removes: without it, the chord alone goes below the floor
    (is (some #(< (:straight %) r) dets)
        (str "the straight chord does fall under the radius, which is the reason "
             "the surface is declared: " (mapv :straight dets)))))

(deftest gluteus-maximus-stays-an-extensor-through-a-squat
  ;; THE DEFECT MEASURED WHILE ADDING THE HIP. The straight chord's hip-extension
  ;; arm falls from 60 mm at neutral through ZERO near 55° of flexion and is
  ;; POSITIVE by 85° — so a straight-line model reports the principal hip extensor
  ;; as a flexor in exactly the posture the muscle exists for, nobody is left to
  ;; carry the squat's hip moment, and `recruit` declines the whole equilibrium.
  (let [spec (att/instance "gluteus_maximus/left")
        at (fn [d] (let [p (pose/solve-pose body (merge att/reference-posture
                                                        {:hip-flexion-deg (double d)}))]
                     (att/moment-arm-detail p 1.70 spec (get-in p [:joints :hip/left])
                                            att/flexion-axis)))
        dets (mapv at [0 15 30 45 60 75 85 100])]
    (is (every? #(neg? (:arm %)) dets)
        (str "an extensor stays an extensor through the range: " (mapv :arm dets)))
    (is (some #(pos? (:straight %)) dets)
        (str "and the straight chord does cross over, which is why the wrap is "
             "declared rather than assumed: " (mapv :straight dets)))
    ;; and the consequence at the level a consumer sees
    (is (:complete? (:summary (tensions-at posture/deep-squat)))
        "so the squat's hip moment is carried rather than declined")
    (is (pos? (:force-n ((:by (tensions-at posture/deep-squat)) "gluteus_maximus/left")))
        "by the muscle whose job it is")))

(deftest the-retinaculum-pins-the-tibialis-anterior
  ;; The extensor retinacula strap the tendon against the front of the ankle, so
  ;; the arm is pinned in BOTH directions rather than merely floored — the
  ;; distinction the wrist already makes. A straight chord to a mid-foot insertion
  ;; gives 53 mm of leverage, half again what a dorsiflexor is measured to have,
  ;; because bowstringing forward is exactly what the retinaculum prevents.
  (let [spec (att/instance "tibialis_anterior/left")
        r (get-in spec [:wrap :radius-m])
        dets (mapv (fn [d]
                     (let [p (pose/solve-pose body (merge att/reference-posture
                                                          {:ankle-dorsiflexion-deg (double d)}))]
                       (att/moment-arm-detail p 1.70 spec (get-in p [:joints :ankle/left])
                                              att/flexion-axis)))
                   [-20 -10 0 10 20 30])]
    (is (:retinaculum (:wrap spec)) "tibialis anterior is held by a retinaculum")
    (is (apply = (mapv #(math/round-to (:arm %) 9) dets))
        (str "a pinned arm does not vary: " (mapv :arm dets)))
    (is (math/nearly= r (:arm (first dets)) 1e-9) "and is pinned at the stated radius")
    (is (> (:straight (nth dets 2)) (* 1.3 r))
        (str "the unpinned chord would give half again as much: "
             (:straight (nth dets 2)) " against " r))))

(deftest the-two-joint-muscles-are-solved-at-both-joints-at-once
  ;; WHAT THIS TEST USED TO ASSERT. A muscle spanning two joints appears in two
  ;; equilibria at once and the two are coupled; `recruit/share`'s
  ;; Crowninshield–Brand closed form solves ONE equality constraint, so each
  ;; two-joint muscle was solved where it was the primary actor and the moment it
  ;; was simultaneously exerting at its other joint was COMPUTED and reported as
  ;; unfed. The assertion was that the unfed moment is non-zero somewhere — `or the
  ;; model is claiming an approximation it never makes and a coupled solve would
  ;; give identical output`. Measured at `deep-squat`: 3.6348762211480548 N·m at
  ;; each hip.
  ;;
  ;; `recruit/solve` is that coupled solve, so the assertion inverts. The hip, the
  ;; knee and the ankle of one leg are ONE equilibrium: the same force appears in
  ;; all three constraints and satisfies all three. What is asserted here now is
  ;; that (1) the second joint is still reported, because the number is worth
  ;; seeing, (2) the row says it was FED, and (3) the equilibrium at that joint
  ;; actually holds — which is a stronger statement than the old one and is the
  ;; whole content of the change.
  (let [{:keys [by summary]} (tensions-at posture/deep-squat)
        two-joint ["rectus_femoris/left" "hamstrings/left" "gastrocnemius/left"]]
    (doseq [n two-joint]
      (let [t (by n)
            inst (att/instance n)]
        (is (some? (get-in inst [:crosses :joint])) (str n " must declare its second joint"))
        (is (= (:crosses-joint t) (get-in inst [:crosses :joint]))
            (str n " must report which joint it also crosses"))
        (is (number? (:secondary-arm-m t)) (str n " must report its arm there"))
        (is (:secondary-fed? t)
            (str n ": its second joint must be one of the constraints its own solve "
                 "satisfied, got " (pr-str (select-keys t [:crosses-joint
                                                           :coupled-joints
                                                           :secondary-fed?]))))
        (when (number? (:force-n t))
          (is (math/nearly= (* (:secondary-arm-m t) (:force-n t))
                            (:secondary-moment-nm t) 1e-9)
              (str n ": the reported secondary moment must be arm × force")))))
    ;; the hip's equilibrium is solved WITH the two muscles that cross it — the
    ;; exact opposite of what this block asserted before
    (let [t (by "rectus_femoris/left")]
      (is (= [:hip/left :knee/left :ankle/left] (vec (:coupled-joints t)))
          (str "rectus femoris is solved over all three joints of its leg: "
               (:coupled-joints t)))
      (is (contains? (:coeffs t) :hip/left)
          (str "and its hip arm is in the constraint matrix: " (:coeffs t))))
    ;; the three equilibria hold simultaneously, which is what `:two-joint-unfed-nm`
    ;; used to measure the failure of
    (doseq [j [:hip/left :knee/left :ankle/left :hip/right :knee/right :ankle/right]]
      (is (math/nearly= 0.0 (get-in summary [:coupled-residual-nm j]) 1e-9)
          (str j " must be balanced: " (:coupled-residual-nm summary))))
    ;; and the unfed total no longer contains a lower-limb joint at all. What is
    ;; left in it is `:c2c3`, which no equilibrium in this model covers.
    (is (map? (:two-joint-unfed-nm summary)))
    (is (not (contains? (:two-joint-unfed-nm summary) :hip/left))
        (str "the hip is fed now, so it must not be counted as unfed: "
             (:two-joint-unfed-nm summary)))
    (is (= #{:c2c3 :c7} (set (keys (:two-joint-unfed-nm summary))))
        (str "the joints still unfed are the two the coupled groups do not reach: "
             "`:c2c3`, which has no equilibrium at all, and `:c7`, which has one "
             "the girdle suspension muscles cannot join because their own balance "
             "is a force and not a moment — see "
             "`muscle-test/the-girdle-suspension-muscles-load-c7-and-are-not-in-its-group`. "
             "Got " (:two-joint-unfed-nm summary)))
    ;; THE DISCRIMINATING HALF. A coupled solve that had simply switched the
    ;; two-joint muscles off would satisfy every residual above. The hamstrings
    ;; must actually be recruited in a squat — they extend the hip and flex the
    ;; knee at the same time, which is exactly the trade the uncoupled solve could
    ;; not represent: it refused them as acting the wrong way at the knee.
    (is (pos? (:active-n (by "hamstrings/left")))
        (str "the hamstrings must be recruited in a deep squat: "
             (by "hamstrings/left")))
    (is (< (:force-n (by "gluteus_maximus/left")) 1072.0)
        (str "and the gluteus maximus must be doing LESS than the uncoupled solve "
             "gave it (1072.30 N), because the hamstrings are helping at the hip: "
             (:force-n (by "gluteus_maximus/left"))))))

(deftest every-lower-limb-load-in-range-is-carried
  ;; The coverage sweep, which is what the upper limb's frontal-plane work also
  ;; ended in. If a posture in the range the lower limb accepts produces a load no
  ;; muscle here can take, the model must say so rather than round it away.
  ;;
  ;; Out-of-balance postures are counted and REPORTED rather than excluded: a body
  ;; whose line of gravity has left its feet is falling, and the fact that a fifth
  ;; of a naive sweep is in that state is a property of sweeping angles
  ;; independently, not a defect.
  (let [postures (for [support [:standing :seated]
                       hip [0.0 20.0 45.0 70.0 90.0]
                       knee [0.0 20.0 45.0 70.0 90.0 110.0]
                       ankle [-15.0 0.0 15.0 30.0]]
                   (merge att/reference-posture
                          {:support support :arms-supported false
                           :trunk-flexion-deg 10.0 :head-flexion-deg 5.0
                           :shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                           :hip-flexion-deg hip :knee-flexion-deg knee
                           :ankle-dorsiflexion-deg ankle}))
        results (mapv (fn [pp]
                        (let [r (tensions-at pp)]
                          {:posture pp
                           :complete? (:complete? (:summary r))
                           :balanced? (get-in r [:loads :support :cop-inside-base?])}))
                      postures)
        incomplete (filterv (complement :complete?) results)]
    (is (= 240 (count results)) "the sweep must actually run")
    (is (empty? incomplete)
        (str (count incomplete) " of " (count results)
             " swept postures have a load nobody carries, e.g. "
             (select-keys (:posture (first incomplete))
                          [:support :hip-flexion-deg :knee-flexion-deg :ankle-dorsiflexion-deg])))
    (is (pos? (count (filterv #(false? (:balanced? %)) results)))
        "and some of them are postures a body could not hold, which the model says")))

;; --- the posture the workstation model could not say -------------------------

(deftest an-upright-unsupported-seated-posture-is-expressible
  ;; `posture-from-workstation` derives the trunk angle from ONE bit — whether the
  ;; back is supported — so the only trunk angles it can produce are 5° and 20°.
  ;; There was no way through it to say `sitting upright with no backrest`, which
  ;; is an ordinary posture and the one an in-vivo lumbar pressure is usually
  ;; measured in; a caller who wanted it wrote the whole map out by hand and
  ;; re-stated the seated lower limb from memory.
  ;;
  ;; The premise is asserted, not assumed: if the workstation model ever gains a
  ;; third trunk angle this test should be revisited rather than silently kept.
  (let [ws-trunks (into #{} (map #(:trunk-flexion-deg (posture/posture-from-workstation %)))
                        posture/reference-workstations)]
    (is (= #{5.0 20.0} ws-trunks)
        (str "the workstation model offers exactly two trunk angles: " ws-trunks))
    (is (not (contains? ws-trunks 0.0))
        "and upright is not one of them, which is why the constructor exists"))
  (let [p (posture/seated-posture)]
    (is (= 0.0 (:trunk-flexion-deg p)) "upright")
    (is (false? (:arms-supported p)) "unsupported")
    (is (= :seated (posture/support-mode p)))
    ;; and it is a whole posture, not a fragment: it solves without a merge
    (let [l (at p)]
      (is (number? (:moment-nm (joint l "lumbosacral"))))
      (is (math/nearly= 0.0 (:moment-nm (joint l "lumbosacral")) 1e-9)
          "an upright trunk carries no gravitational L5/S1 moment")
      (is (< (:left (:supported-weight-n (joint l "knee"))) (* 0.10 body-weight-n))
          "and it is seated, so the knee carries a limb rather than a body")))
  ;; the overrides do what they say
  (is (= 45.0 (:trunk-flexion-deg (posture/seated-posture :trunk-flexion-deg 45.0))))
  (is (true? (:arms-supported (posture/seated-posture :arms-supported true)))))
