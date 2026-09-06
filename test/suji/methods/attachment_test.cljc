(ns suji.methods.attachment-test
  "Muscle paths, and the two ways a straight-line model goes wrong.

  The calibration check is the important one: the offsets in `attachment/muscles`
  exist to reproduce, at the neutral posture, the constant moment arms this actor
  used before the anatomy existed. If someone edits an attachment and the neutral
  leverage moves, every %MVC this actor has ever reported silently changes meaning.
  That is what these pin."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [clojure.set]
            [suji.methods.attachment :as att]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.load]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

(def ^:private body (segment/build-body 70.0 1.70))
(def ^:private neutral
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0})

(defn- at [& {:as over}] (pose/solve-pose body (merge neutral over)))
(defn- arm [pose-data m] (get (att/arms pose-data 1.70) m))

(deftest neutral-arms-reproduce-the-constants-they-replaced
  ;; the anchor. ±3% — the offsets are stated to 4 decimals, not fitted exactly.
  (doseq [m ["cervical_extensors" "anterior_deltoid/left" "anterior_deltoid/right"
             "erector_spinae"]]
    (let [want (:moment-arm-m (get muscle/specs (:group (att/instance m))))
          got (arm (at) m)]
      (is (math/nearly= want got (* 0.03 want))
          (str m ": neutral arm " got " must reproduce the tabulated " want)))))

(deftest an-extensor-stays-an-extensor-through-its-range
  ;; THE DEFECT THIS CATCHES. A straight line from a high insertion crosses to the
  ;; wrong side of the joint as the joint flexes, and the model then reports the
  ;; extensors as flexors — a sign error that looks like a number. Measured while
  ;; building this: a cervical insertion at 0.34 of the head segment flipped sign
  ;; at 30° of head flexion, and an erector insertion at 0.62 of the trunk flipped
  ;; near 55° of trunk flexion.
  (doseq [[m key degs] [["cervical_extensors" :head-flexion-deg [0 15 30 45 60]]
                        ["erector_spinae" :trunk-flexion-deg [0 15 30 45 60]]]]
    (doseq [d degs]
      (let [a (arm (at key (double d)) m)]
        (is (and a (pos? a))
            (str m " at " d "°: an extensor cannot have a non-positive arm, got " a))))))

(deftest leverage-actually-changes-with-the-joint
  ;; the whole point of computing arms instead of tabulating them. The fall is no
  ;; longer monotone all the way, because the wrapping surface floors it — that
  ;; plateau is the correct behaviour and is checked separately below.
  (let [as (mapv #(arm (at :head-flexion-deg (double %)) "cervical_extensors") [0 20 40 60])]
    (is (every? (fn [[a b]] (>= a b)) (partition 2 1 as))
        (str "the cervical extensor never gains leverage as the head flexes: " as))
    (is (> (first as) (last as)) "and it does lose some")
    (is (> (/ (first as) (last as)) 1.5)
        (str "the change has to be large enough to matter, or a constant would "
             "have done: " as))))

;; --- wrapping surfaces -------------------------------------------------------

(deftest a-wrapped-arm-floors-at-the-radius-and-never-crosses-zero
  ;; THE DEFECT THIS REMOVES. A straight chord between two attachment points can
  ;; pass through the joint it acts about; the arm then goes to zero and the force
  ;; needed to hold any moment diverges. This model's anterior deltoid did exactly
  ;; that at 90° of shoulder flexion — an ordinary posture — and `recruit` had to
  ;; decline it. A muscle lying on bone wraps instead, and every tangent to a
  ;; circle of radius R is R from its centre, so the arm floors at R.
  (let [spec (att/instance "anterior_deltoid/left")
        r (get-in spec [:wrap :radius-m])
        arms (mapv #(arm (at :shoulder-flexion-deg (double %)) "anterior_deltoid/left")
                   (range 0 181 10))]
    (is (some? r) "the deltoid must declare a wrapping surface")
    (is (every? #(>= % (- r 1e-12)) arms)
        (str "no arm may fall below the wrap radius " r ": " arms))
    (is (every? pos? arms) "and none may cross zero")
    (is (= r (apply min arms)) "the floor is exactly the declared radius")))

(deftest wrapping-does-not-fight-the-straight-line-where-the-straight-line-is-better
  ;; the surface must take over only where the chord gives LESS than the radius,
  ;; or it would flatten the whole curve and hide the variation it exists to keep
  (doseq [d [0 15 30 45]]
    (let [det (att/moment-arm-detail (at :shoulder-flexion-deg (double d)) 1.70
                                     (att/instance "anterior_deltoid/left")
                                     (get-in (at :shoulder-flexion-deg (double d)) [:joints :shoulder/left]))]
      (is (not (:wrapped? det)) (str d "°: the chord still has the better leverage here"))
      (is (= (:arm det) (:straight det))))))

(deftest the-two-branches-agree-where-they-meet
  ;; a discontinuity here would be a step change in required force at one angle
  (let [spec (att/instance "anterior_deltoid/left")
        step (fn [a b] (Math/abs (- a b)))
        arms (mapv #(arm (at :shoulder-flexion-deg (double %)) "anterior_deltoid/left")
                   (range 40 80 2))]
    (is (every? #(< % 1e-3) (map (fn [[a b]] (step a b)) (partition 2 1 arms)))
        (str "crossing into the wrap must not jump: " arms))))

(deftest a-wrapped-flexor-does-not-become-an-extensor-when-the-chord-swings-past
  ;; THE REGRESSION. Testing `|straight| < R` instead of `sign*straight < R` looks
  ;; right and is not: once the chord passes far enough to the wrong side its
  ;; MAGNITUDE exceeds R again, wrapping switches off, and the model hands back a
  ;; straight-line arm with the sign flipped. Measured 2026-09-06: the anterior
  ;; deltoid became an extensor at 130° of shoulder flexion.
  (doseq [d [100 110 120 130 150 180]]
    (let [a (arm (at :shoulder-flexion-deg (double d)) "anterior_deltoid/left")]
      (is (pos? a) (str d "°: a wrapped flexor stays a flexor, got " a)))))

(deftest a-muscle-with-no-wrap-declared-is-left-alone
  ;; erector spinae has no wrapping surface in this model; its arm must be the
  ;; straight-line arm, unfloored, so that a future regression there is visible
  (let [spec (att/instance "erector_spinae")]
    (is (nil? (:wrap spec)))
    (doseq [d [0 20 40 60]]
      (let [p (at :trunk-flexion-deg (double d))
            det (att/moment-arm-detail p 1.70 spec (get-in p [:joints :l5s1]))]
        (is (not (:wrapped? det)))
        (is (= (:arm det) (:straight det)))))))

(deftest a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm
  ;; stated as a test because it is the error that produced a constant-looking arm
  ;; from geometry that was supposed to vary: a line whose endpoints both ride on
  ;; the same segment rotates rigidly with it.
  (let [same-bone {:acts-about :shoulder/left :task :shoulder-flexion
                   :side :left
                   :origin {:segment "upper_arm" :along 0.0 :ant 0.018 :lat 0.0}
                   :insertion {:segment "upper_arm" :along 0.42 :ant 0.006 :lat 0.0}}
        arms (mapv (fn [d]
                     (let [p (at :shoulder-flexion-deg (double d))]
                       (att/moment-arm p 1.70 same-bone (get-in p [:joints :shoulder/left]))))
                   [0 30 60])]
    (is (apply = (mapv #(math/round-to % 9) arms))
        (str "both ends on one bone => a constant arm, which is the bug: " arms))
    ;; and the real anterior deltoid, whose origin is on the trunk, does vary
    (is (not (apply = (mapv #(math/round-to (arm (at :shoulder-flexion-deg (double %)) "anterior_deltoid/left") 9)
                            [0 30 60]))))))

(deftest suspension-muscles-are-not-given-a-moment-arm
  ;; asking for the shoulder moment arm of a suspension muscle returns a number,
  ;; and at neutral it is NEGATIVE — it would claim these muscles flex the joint
  ;; they are holding up. `effectiveness` must hand back the cosine instead.
  (let [p (at)]
    (doseq [m ["upper_trapezius/left" "levator_scapulae/left"]]
      (let [spec (att/instance m)
            moment (att/moment-arm p 1.70 spec (get-in p [:joints :shoulder/left]))
            cosine (att/effectiveness p 1.70 spec)]
        (is (neg? moment) (str m ": the moment-arm reading is the wrong question, and negative"))
        (is (<= 0.0 cosine 1.0) (str m ": a direction cosine, in [0,1]"))
        (is (= cosine (arm p m)) (str m ": `arms` must report the cosine, not the moment"))))
    ;; levator scapulae runs more vertically than upper trapezius, so it is the
    ;; better suspender — this is what makes the two distinguishable at all
    (is (> (arm p "levator_scapulae/left") (arm p "upper_trapezius/left")))))

(deftest attachments-ride-on-the-bones
  ;; an attachment stated in local coordinates has to move when the bone moves,
  ;; and stay at a fixed distance from its own segment's proximal joint
  (let [spec (att/instance "cervical_extensors")
        d (fn [posture]
            (let [p (pose/solve-pose body posture)
                  site (att/site-point p 1.70 (:insertion spec))
                  c7 (get-in p [:joints :c7])]
              (math/vlen (math/v- site c7))))]
    (is (math/nearly= (d neutral) (d (merge neutral {:head-flexion-deg 50.0})) 1e-9)
        "flexing the head must not change how far the insertion is from C7")
    (is (not= (att/site-point (pose/solve-pose body neutral) 1.70 (:insertion spec))
              (att/site-point (pose/solve-pose body (merge neutral {:head-flexion-deg 50.0}))
                              1.70 (:insertion spec)))
        "but it must move it")))

(deftest offsets-scale-with-the-body
  ;; offsets are fractions of stature, so a taller model gets proportionally
  ;; longer levers rather than the same centimetres
  (let [tall (segment/build-body 70.0 2.00)
        p-s (pose/solve-pose body neutral)
        p-t (pose/solve-pose tall neutral)
        a-s (get (att/arms p-s 1.70) "erector_spinae")
        a-t (get (att/arms p-t 2.00) "erector_spinae")]
    (is (> a-t a-s))
    (is (math/nearly= (/ 2.00 1.70) (/ a-t a-s) 0.05)
        "the arm should scale with stature, not with something else")))

(deftest a-frontal-load-is-now-carried
  ;; This test used to assert the opposite. Until the frontal muscles landed, this
  ;; actor computed the frontal-plane moment and reported that nobody carried it;
  ;; the honest output then was `:unassigned-frontal-nm`. There are muscles for it
  ;; now, and the assertion inverts rather than disappearing — a load that used to
  ;; be unplaceable must be shown to be placed.
  (let [sagittal (merge neutral {:shoulder-flexion-deg 20.0 :elbow-flexion-deg 90.0})
        abducted (assoc sagittal :shoulder-abduction-deg 40.0)
        bent (assoc sagittal :trunk-lateral-bend-deg 25.0)
        run (fn [posture]
              (let [l (suji.methods.load/solve-posture-loads body posture)
                    ts (muscle/solve-muscle-tensions body posture l)]
                {:summary (muscle/tension-summary ts l) :tensions ts :loads l}))
        carrying (fn [r nm] (:mvc-pct (first (filter #(= nm (:name %)) (:tensions r)))))]
    ;; symmetric: no frontal load at all, and nothing refused for want of one
    (let [r (run sagittal)]
      (is (math/nearly= 0.0 (get-in r [:loads :frontal :lumbosacral-nm]) 1e-9))
      (is (:complete? (:summary r))))
    ;; abduction: each shoulder gets its own frontal moment, and the deltoids take it
    (let [r (run abducted)]
      (is (> (math/abs* (get-in r [:loads :frontal :shoulder-per-side :left])) 1.0))
      (is (pos? (carrying r "middle_deltoid/left")))
      (is (pos? (carrying r "middle_deltoid/right")))
      (is (:complete? (:summary r)) "the abduction load is placed, not reported unassigned"))
    ;; lateral bend: the spine's frontal moment goes to the lateral flexors on the
    ;; resisting side, and the other side is an antagonist rather than a gap
    (let [r (run bent)
          lateral (filter #(#{"quadratus_lumborum" "obliques"} (:group %)) (:tensions r))]
      (is (> (math/abs* (get-in r [:loads :frontal :lumbosacral-nm])) 10.0))
      (is (some :force-n lateral) "somebody carries the lateral-flexion moment")
      (is (every? #(or (:force-n %) (:antagonist? %)) lateral)
          "and whoever does not is the antagonist, not an unanswered load")
      (is (:complete? (:summary r))))))

(deftest the-abductor-and-the-adductor-are-opposites
  ;; They share a task precisely because they act in opposite senses; if both came
  ;; out the same sign the equilibrium would have no candidate for one direction.
  (doseq [ab [0.0 30.0 60.0]]
    (let [p (at :shoulder-abduction-deg ab)
          d (arm p "middle_deltoid/left")
          l (arm p "latissimus_dorsi/left")]
      (is (neg? (* d l)) (str ab "°: deltoid " d " and latissimus " l " must oppose")))))

(deftest the-two-sides-of-a-frontal-pair-mirror
  (let [p (at)]
    (doseq [g ["middle_deltoid" "quadratus_lumborum" "obliques" "scalenes" "latissimus_dorsi"]]
      (is (math/nearly= (arm p (str g "/left")) (- (arm p (str g "/right"))) 1e-9)
          (str g ": a frontal moment reverses under the mirror")))))

(deftest a-load-that-is-zero-has-no-resisting-side
  ;; A symmetric posture computes a frontal moment of -1e-16, not 0.0. Reading the
  ;; resisting side off those last bits picks one at random and refuses the other
  ;; as acting the wrong way — for a load that is not there. The muscles of a
  ;; mirror pair must come out symmetric when the posture is.
  (let [posture (merge neutral {:shoulder-flexion-deg 20.0 :elbow-flexion-deg 90.0})
        l (suji.methods.load/solve-posture-loads body posture)
        ts (muscle/solve-muscle-tensions body posture l)
        by (into {} (map (juxt :name identity)) ts)]
    (is (< (math/abs* (get-in l [:frontal :shoulder-per-side :left])) 1e-9)
        "the premise: this posture's frontal load is zero to within rounding")
    (doseq [g ["middle_deltoid" "latissimus_dorsi" "quadratus_lumborum" "obliques" "scalenes"]]
      (let [lft (by (str g "/left")) rgt (by (str g "/right"))]
        (is (= (boolean (:refused lft)) (boolean (:refused rgt)))
            (str g ": a symmetric posture must not refuse one side and not the other"))
        (when (and (:mvc-pct lft) (:mvc-pct rgt))
          (is (math/nearly= (:mvc-pct lft) (:mvc-pct rgt) 1e-9)
              (str g ": and must load the two sides equally")))))))

;; --- the elbow ---------------------------------------------------------------

(deftest the-elbow-has-an-equilibrium-at-all
  ;; THE GAP THIS CLOSES. `pose` has placed an elbow since the pose layer existed
  ;; and no muscle acted about it: the forearm and hand hung off a joint whose
  ;; equilibrium nobody solved, as though it were welded. Every joint the
  ;; kinematics places should have kinetics, or the model should say which do not.
  (let [acted (set (map :acts-about att/instances))]
    (is (contains? acted :elbow/left))
    (is (contains? acted :elbow/right))))

(deftest holding-the-forearm-out-loads-the-elbow-flexors
  (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                :arms-supported false})
        l (suji.methods.load/solve-posture-loads body posture)
        ts (muscle/solve-muscle-tensions body posture l)
        by (into {} (map (juxt :name identity)) ts)
        elbow (first (filter #(= "elbow" (:joint %)) (:joints l)))]
    (is (> (:moment-nm elbow) 1.0) "a held-out forearm is a real elbow moment")
    (is (pos? (:mvc-pct (by "biceps_brachii/left"))))
    (is (pos? (:mvc-pct (by "brachialis/left"))))
    (is (:antagonist? (by "triceps_brachii/left"))
        "the extensor is the antagonist here, not a gap")))

(deftest resting-the-forearms-empties-the-elbow
  ;; the whole of the arms-supported effect at this joint, stated as which
  ;; segments are still hanging rather than as a multiplier
  (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                :arms-supported true})
        l (suji.methods.load/solve-posture-loads body posture)
        elbow (first (filter #(= "elbow" (:joint %)) (:joints l)))]
    (is (math/nearly= 0.0 (:moment-nm elbow) 1e-12)
        "a forearm on the desk is carried by the desk")))

(deftest the-elbow-flexors-and-the-extensor-oppose
  (doseq [e [0 45 90 135]]
    (let [p (at :shoulder-flexion-deg 15.0 :elbow-flexion-deg (double e))
          bi (arm p "biceps_brachii/left")
          br (arm p "brachialis/left")
          tri (arm p "triceps_brachii/left")]
      (is (pos? bi) (str e "°: biceps is a flexor"))
      (is (pos? br) (str e "°: brachialis is a flexor"))
      (is (neg? tri) (str e "°: triceps is an extensor, got " tri)))))

(deftest the-biceps-leverage-peaks-in-mid-range
  ;; a constant arm would be flat; the shape is what makes computing it worth it
  (let [as (mapv #(arm (at :shoulder-flexion-deg 15.0 :elbow-flexion-deg (double %))
                       "biceps_brachii/left")
                 [0 30 60 90 120])
        peak (apply max as)]
    (is (> peak (first as)) "leverage rises off full extension")
    (is (> peak (last as)) "and falls again toward full flexion")
    (is (> (/ peak (first as)) 1.2) "by enough to matter")))

(deftest the-bigger-flexor-takes-the-bigger-share
  ;; brachialis has the larger cross-section and the shorter arm; the criterion
  ;; weighs both, and this pins which way it comes out
  (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                :arms-supported false})
        l (suji.methods.load/solve-posture-loads body posture)
        by (into {} (map (juxt :name identity)) (muscle/solve-muscle-tensions body posture l))]
    (is (> (:force-n (by "brachialis/left")) (:force-n (by "biceps_brachii/left")))
        "the larger muscle carries more force")
    (is (< (:mvc-pct (by "brachialis/left")) (:mvc-pct (by "biceps_brachii/left")))
        "and is nonetheless working at a lower fraction of its maximum")))

;; --- the wrist ---------------------------------------------------------------

(def ^:private landmarks
  "Points `pose` places that are NOT joints, so nothing is expected to take a
  moment about them. `:pelvis-base` is the bottom of the pelvis segment on the
  midline — the femoral heads are `:hip/left` and `:hip/right`, to either side of
  it — and the heels and toes are the two edges of the base of support."
  #{:vertex :pelvis-base :heel/left :heel/right :toe/left :toe/right})

(def ^:private awaiting-muscles
  "Joints the kinematics places and the kinetics does not solve YET.

  This set is the honest form of the gap. It used to be the sentence `the hip is
  deliberately unsolved, because a seated model has no thigh` — which was true when
  it was written, stopped being true the moment `segment/build-body` grew a thigh,
  and would have gone on reading as a decision. A set that has to be emptied is
  harder to forget than a paragraph that has to be reread."
  #{:hip/left :hip/right :knee/left :knee/right :ankle/left :ankle/right})

(deftest every-placed-joint-has-an-equilibrium-or-is-named-as-a-gap
  ;; The coverage question, asked of the data rather than of a comment: which
  ;; joints does the kinematics place, and which does the kinetics solve?
  (let [p (pose/solve-pose body (merge neutral {:elbow-flexion-deg 90.0}))
        placed (set (keys (:joints p)))
        acted (set (map :acts-about att/instances))
        skeletal (clojure.set/difference placed landmarks awaiting-muscles)]
    (doseq [j skeletal]
      (is (contains? acted j) (str j " is placed by the kinematics and must be solved")))
    (doseq [j awaiting-muscles]
      (is (contains? placed j)
          (str j " is listed as awaiting muscles but is not even placed")))
    (doseq [j landmarks]
      (is (contains? placed j) (str j " is listed as a landmark but is not placed")))))

(deftest a-held-out-hand-loads-the-wrist-extensors
  ;; palm down, forearm horizontal: gravity drops the hand, and the muscles that
  ;; hold it up are the EXTENSORS. This is the muscle group a keyboard posture
  ;; loads, and getting the side wrong would put the load on the idle one.
  (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                :wrist-extension-deg 15.0 :arms-supported false})
        l (suji.methods.load/solve-posture-loads body posture)
        by (into {} (map (juxt :name identity)) (muscle/solve-muscle-tensions body posture l))
        wrist (first (filter #(= "wrist" (:joint %)) (:joints l)))]
    (is (> (:moment-nm wrist) 0.1) "a held-out hand is a real wrist moment")
    (is (pos? (:mvc-pct (by "wrist_extensors/left"))) "the extensors carry it")
    (is (:antagonist? (by "wrist_flexors/left")) "and the flexors are the antagonist")))

(deftest resting-the-forearms-rests-the-hands
  (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                :arms-supported true})
        l (suji.methods.load/solve-posture-loads body posture)
        wrist (first (filter #(= "wrist" (:joint %)) (:joints l)))]
    (is (math/nearly= 0.0 (:moment-nm wrist) 1e-12))))

(deftest the-wrist-actually-articulates
  ;; THE GAP THIS CLOSES, and it is the mirror of the elbow's. The hand used to
  ;; continue the forearm rigidly, so the wrist had kinetics and no kinematics.
  ;; The joint moves now — and the MOMENT moves with it, which is where the
  ;; ergonomics lives. The moment ARMS do not, and that is the anatomy rather
  ;; than a missing joint: see `the-retinaculum-pins-the-arm`.
  (let [hand-y #(nth (:com (pose/seg-at (at :shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                            :wrist-extension-deg (double %))
                                        "hand/left")) 1)]
    (is (> (hand-y 40) (hand-y 0))
        "extension lifts the hand relative to the forearm"))
  (let [moment #(let [l (suji.methods.load/solve-posture-loads
                         body (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                              :wrist-extension-deg (double %) :arms-supported false}))]
                  (:left (:per-side (first (filter (fn [j] (= "wrist" (:joint j))) (:joints l))))))
        ms (mapv moment [0 15 30 45])]
    (is (apply distinct? ms) (str "the wrist moment must change with the angle: " ms))))

(deftest the-retinaculum-pins-the-arm
  ;; A wrapping surface the tendon passes OVER floors the moment arm and lets the
  ;; chord win when the chord gives more. A retinaculum straps the tendon against
  ;; the bone, so the arm is pinned in BOTH directions — which is why the wrist's
  ;; moment arms are near-constant through its range where the elbow's are not.
  ;; Without the distinction the extensor arm grew from 11 mm to 29 mm across 45°
  ;; of extension, which no retinaculum would allow.
  (doseq [nm ["wrist_extensors" "wrist_flexors"]]
    (let [spec (att/instance (str nm "/left"))
          r (get-in spec [:wrap :radius-m])
          arms (mapv #(arm (at :shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                               :wrist-extension-deg (double %))
                           (str nm "/left"))
                     [0 15 30 45 60])]
      (is (:retinaculum (:wrap spec)) (str nm " is held by a retinaculum"))
      (is (apply = (mapv #(math/round-to (math/abs* %) 9) arms))
          (str nm ": a pinned arm does not vary: " arms))
      (is (math/nearly= r (math/abs* (first arms)) 1e-9)
          (str nm ": and it is pinned at the stated radius"))))
  ;; the elbow is NOT pinned — its wrapping surface only floors the arm
  (let [bi (mapv #(arm (at :shoulder-flexion-deg 15.0 :elbow-flexion-deg (double %))
                       "biceps_brachii/left")
                 [0 30 60 90])]
    (is (not (apply = bi)) (str "the elbow's arms still vary: " bi))))

(deftest the-extensors-carry-and-the-flexors-do-not
  (doseq [we [0 15 30]]
    (let [posture (merge neutral {:shoulder-flexion-deg 15.0 :elbow-flexion-deg 90.0
                                  :wrist-extension-deg (double we) :arms-supported false})
          l (suji.methods.load/solve-posture-loads body posture)
          by (into {} (map (juxt :name identity)) (muscle/solve-muscle-tensions body posture l))]
      (is (pos? (:mvc-pct (by "wrist_extensors/left")))
          (str we "°: the extensors hold the hand up"))
      (is (:antagonist? (by "wrist_flexors/left"))
          (str we "°: and the flexors are idle")))))

(deftest the-workstation-model-supplies-a-wrist-angle
  ;; the input existed nowhere until the wrist had an equilibrium to spend it on
  (doseq [w posture/reference-workstations]
    (let [p (posture/posture-from-workstation w)]
      (is (number? (:wrist-extension-deg p)) (str (:name w) " must state a wrist angle"))
      (is (<= 0.0 (:wrist-extension-deg p) 35.0))))
  ;; a keyboard above elbow height extends the wrist further
  (let [high (posture/posture-from-workstation
              {:name "high" :screen-below-eye-cm 20.0 :keyboard-above-elbow-cm 12.0
               :back-supported true :arms-supported true})
        level (posture/posture-from-workstation
               {:name "level" :screen-below-eye-cm 20.0 :keyboard-above-elbow-cm 0.0
                :back-supported true :arms-supported true})]
    (is (> (:wrist-extension-deg high) (:wrist-extension-deg level)))))
