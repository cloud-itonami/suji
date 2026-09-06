(ns suji.methods.attachment-test
  "Muscle paths, and the two ways a straight-line model goes wrong.

  The calibration check is the important one: the offsets in `attachment/muscles`
  exist to reproduce, at the neutral posture, the constant moment arms this actor
  used before the anatomy existed. If someone edits an attachment and the neutral
  leverage moves, every %MVC this actor has ever reported silently changes meaning.
  That is what these pin."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.attachment :as att]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.load]
            [suji.methods.pose :as pose]
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
