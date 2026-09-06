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
            [suji.methods.load :as load]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.recruit :as recruit]
            [suji.methods.segment :as segment]
            [suji.methods.spine :as spine]))

(def ^:private body (segment/build-body 70.0 1.70))
(def ^:private neutral
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0})

(defn- at [& {:as over}] (pose/solve-pose body (merge neutral over)))
(defn- arm [pose-data m] (get (att/arms pose-data 1.70) m))

(deftest neutral-arms-reproduce-the-constants-they-replaced
  ;; the anchor. ±3% — the offsets are stated to 4 decimals, not fitted exactly.
  ;;
  ;; THE TOLERANCE IS TAKEN ON THE MAGNITUDE since 2026-09-07, and that is a
  ;; correction rather than a loosening. `math/nearly=` compares `|a-b| < tol`, so
  ;; a negative `tol` makes it FALSE for every input — and `sternocleidomastoid`'s
  ;; calibration target is negative, because about C7 it is a flexor. Written the
  ;; old way this test would have failed on a correct value and there would have
  ;; been no way to tell that from failing on a wrong one.
  (doseq [m ["cervical_extensors" "anterior_deltoid/left" "anterior_deltoid/right"
             "erector_spinae"
             "semispinalis_capitis" "splenius_capitis" "sternocleidomastoid"]]
    (let [want (:moment-arm-m (get muscle/specs (:group (att/instance m))))
          got (arm (at) m)]
      (is (math/nearly= want got (* 0.03 (math/abs* want)))
          (str m ": neutral arm " got " must reproduce the tabulated " want)))))

;; --- the muscles that hold the head up ---------------------------------------
;;
;; Until 2026-09-07 nothing in this model reached the skull: the most cranial
;; attachment in the whole set was `levator_scapulae` at 0.22 of `head_neck` and
;; `spine/levels` puts C3/C4 at 0.24, so the top cervical level was crossed by
;; nothing. These pin the anatomy that closed it, and — more importantly — the
;; three ways it could have been closed dishonestly.

(def ^:private cranial
  "The three groups added 2026-09-07, by name."
  ["semispinalis_capitis" "splenius_capitis" "sternocleidomastoid"])

(deftest the-head-muscles-run-between-two-different-bones
  ;; THE FIRST WAY TO GET THIS WRONG, and the reason the suboccipitals were absent
  ;; until the neck had joints. A muscle with both ends on one bone cannot have an
  ;; angle-dependent arm —
  ;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names
  ;; that shape as the error that produced a constant-looking arm, and
  ;; `exactly-one-muscle-has-both-ends-on-one-segment-and-it-is-a-suspender` forbids
  ;; it globally. This says the positive half for these three: each runs thorax →
  ;; skull, so its arm CAN move, and it does.
  ;;
  ;; The insertion segment is `head` since 2026-09-07, which is the SAME PLACE it
  ;; was — `head` is the part of the old `head_neck` above the occipital condyles,
  ;; and the `:along` values were rescaled onto it.
  (doseq [m cranial]
    (let [spec (att/instance m)]
      (is (= "thorax_abdomen" (get-in spec [:origin :segment])) (str m " origin"))
      (is (= "head" (get-in spec [:insertion :segment])) (str m " insertion"))
      (let [arms (mapv #(math/round-to (arm (at :head-flexion-deg (double %)) m) 9)
                       [0 15 30])]
        (is (< 1 (count (distinct arms)))
            (str m ": its arm must move with head flexion, got " arms))))))

(deftest the-head-muscles-attach-above-the-top-cervical-level
  ;; THE SECOND WAY: attach them to the skull in the docstring and to the base of
  ;; the neck in the data. `cervical_extensors` did exactly that — its `:source`
  ;; said `occipital insertion` while its insertion sat at 0.05 of `head_neck`,
  ;; 15.5 mm above C7 and below C6/C7 at 18.6 mm — and that sentence is why nobody
  ;; looked for the gap. So the claim is checked against `spine/levels` rather than
  ;; against a comment.
  ;;
  ;; It is asked of the SKELETON now rather than of two numbers on one segment,
  ;; because since 2026-09-07 the insertion and the level are not on the same bone:
  ;; `spine/levels-crossed` walks the attachment tree, so an insertion that landed
  ;; below C3/C4 would fail to cross it and be reported here.
  (let [p (pose/solve-pose body neutral)
        c34 (first (filter #(= "C3/C4" (:name %)) spine/levels))]
    (is (= "lower_cervical" (:segment c34))
        "the premise: C3/C4 sits on the lower cervical segment")
    (is (= 0.8 (:along c34))
        "at 0.8 of it, which is the 0.24 of the old head_neck segment it always was")
    (doseq [m cranial]
      (is (some #(= "C3/C4" (:name %)) (spine/levels-crossed p (att/instance m)))
          (str m " must cross C3/C4 — it inserts on the skull, above every level")))
    ;; and the lumped group, whose source string used to claim the skull, does not
    (is (= "lower_cervical" (get-in (att/instance "cervical_extensors") [:insertion :segment]))
        "cervical_extensors inserts on the lower cervical column, not on the skull")
    (is (not (some #(= "C6/C7" (:name %))
                   (spine/levels-crossed p (att/instance "cervical_extensors"))))
        "cervical_extensors inserts below C6/C7 — it is not, and never was, occipital")))

(deftest the-head-extensors-stay-extensors-through-a-forward-head-posture
  ;; THE THIRD WAY, and the one the geometry actually forces. A high insertion
  ;; swings a long way forward as the head folds: measured 2026-09-07, the
  ;; UNWRAPPED chord of `semispinalis_capitis` is +29.8 mm at neutral and −10.3 mm
  ;; at 45° of head flexion, so without a wrapping surface the model's principal
  ;; head extensor is reported as a FLEXOR in the posture this actor exists to
  ;; describe. Both halves are asserted: the straight line does cross (so the wrap
  ;; is not decoration), and the reported arm does not.
  (doseq [m ["semispinalis_capitis" "splenius_capitis"]]
    (let [spec (att/instance m)
          r (get-in spec [:wrap :radius-m])
          dets (mapv (fn [d]
                       (let [p (at :head-flexion-deg (double d))]
                         (att/moment-arm-detail p 1.70 spec (get-in p [:joints :c7]))))
                     [0 15 30 45 60 75])]
      (is (= 0.012 r)
          (str m ": one column, one radius — the same cervical column "
               "cervical_extensors wraps, which declares "
               (get-in (att/instance "cervical_extensors") [:wrap :radius-m])))
      (is (some #(neg? (:straight %)) dets)
          (str m ": the straight chord must cross to the wrong side somewhere, or "
               "the wrapping surface is not doing anything: "
               (mapv #(math/round-to (:straight %) 5) dets)))
      (is (every? #(>= (:arm %) (- r 1e-12)) dets)
          (str m ": but no reported arm may fall below the column radius: "
               (mapv #(math/round-to (:arm %) 5) dets)))
      (is (some (complement :wrapped?) dets)
          (str m ": and the chord must beat the floor somewhere, or the floor is a "
               "tabulated arm wearing a wrap — which is what middle_deltoid's "
               "0.022 m was")))))

(deftest the-superficial-extensor-has-the-longer-arm
  ;; Sourced ordering, not a sourced value. Vasavada (Rothman-Simeone The Spine,
  ;; chapter 3, p.68) states that `the semispinalis capitis has shorter fascicle
  ;; lengths, but also a smaller moment arm than the splenius capitis`. The two
  ;; targets here are representative; their ORDER is not, and getting it backwards
  ;; would hand the bigger muscle the better leverage as well and let the criterion
  ;; give it almost everything.
  (let [p (at)]
    (is (> (arm p "splenius_capitis") (arm p "semispinalis_capitis"))
        (str "splenius " (arm p "splenius_capitis")
             " must exceed semispinalis " (arm p "semispinalis_capitis")))))

(deftest the-flexor-is-refused-for-being-a-flexor
  ;; The sternocleidomastoid is an antagonist in every posture this actor reports,
  ;; and that is the correct answer rather than a failure — but WHICH refusal it is
  ;; matters, because the three kinds have three different fixes.
  ;; `:acts-the-wrong-way` says the posture has left the range this line of action
  ;; represents and no wrapping surface changes it. `:no-line-of-action` would say
  ;; the geometry is degenerate, and `:coefficient-below-floor` would say a
  ;; wrapping surface is missing. Asserting only "refused" cannot tell them apart,
  ;; which is the shape `recruit`'s own docstring warns about.
  (let [posture (posture/posture-from-workstation posture/laptop-on-lap)
        l (suji.methods.load/solve-posture-loads body posture)
        ts (muscle/solve-muscle-tensions body posture l)
        by (into {} (map (juxt :name identity)) ts)
        scm (by "sternocleidomastoid")]
    (is (= :acts-the-wrong-way (:refused scm))
        (str "the sternocleidomastoid must be refused for pulling the wrong way, "
             "not for having no line of action: " (pr-str scm)))
    (is (neg? (:coeff scm))
        (str "and the reason must be visible in the coefficient: " (:coeff scm)))
    (is (:antagonist? scm)
        "the cervical extension load WAS placed, so this is an antagonist and not a gap")
    (is (nil? (:mvc-pct scm)) "a refused muscle gets no %MVC")
    ;; the discriminating half: the extensors in the SAME task are not refused, so
    ;; this is a statement about one muscle's direction and not about the task
    ;; having failed
    (doseq [m ["cervical_extensors" "semispinalis_capitis" "splenius_capitis"]]
      (is (pos? (:mvc-pct (by m))) (str m " carries load in the same equilibrium")))
    (is (:complete? (muscle/tension-summary ts l))
        "and an antagonist must not make the solve incomplete")))

(deftest the-head-muscles-cross-every-cervical-level
  ;; The point of the whole exercise, asked of `spine`'s rule rather than of a
  ;; number: `levels-crossed` decides from which bone each site rides on and which
  ;; bone hangs from which, so this is a test of the anatomy and not of a list.
  (let [p (pose/solve-pose body (merge neutral {:head-flexion-deg 30.0}))
        cerv (mapv :name (filter #(= :cervical (:region %)) spine/levels))]
    ;; six since 2026-09-07: C2/C3 became expressible when the neck was split, and
    ;; these three cross it too, because all three insert above it on the skull
    (is (= 6 (count cerv)) "the premise: six cervical levels")
    (doseq [m cranial]
      (is (= cerv (mapv :name (spine/levels-crossed p (att/instance m))))
          (str m " must span every cervical level")))
    ;; and none of them reaches the lumbar spine, which would mean a muscle running
    ;; from the skull past L1/L2 — the mirror of the wrist extensor that used to
    ;; load a neck
    (doseq [m cranial]
      (is (empty? (filter #(= :lumbar (:region %))
                          (spine/levels-crossed p (att/instance m))))
          (str m " must not cross a lumbar level")))
    ;; the lumped group still crosses exactly one, which is the measurement that
    ;; makes "added alongside" rather than "carved out of" the honest description
    (is (= ["C7/T1"] (mapv :name (spine/levels-crossed
                                  p (att/instance "cervical_extensors"))))
        "cervical_extensors spans the cervicothoracic junction and nothing above it")))

(deftest the-neck-pcsa-is-measured-and-the-numbers-are-the-source-s
  ;; PCSA here is one of only two measured columns in this actor (the other is the
  ;; lower limb's, from Ward et al. 2009). Kamibayashi LK & Richmond FJR,
  ;; "Morphometry of human neck muscles", Spine 23(12):1314–1323, 1998, read as
  ;; Table 3-3 of Vasavada's chapter 3 of Rothman-Simeone The Spine (full text
  ;; obtained 2026-09-07 from
  ;; https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf). Per-side means:
  ;; semispinalis capitis 5.40, splenius 4.26, sternocleidomastoideus 3.72 cm².
  ;; A midline group in this model carries the BILATERAL sum, so each is doubled —
  ;; and a doubling done in the head is exactly the kind of arithmetic that rots,
  ;; so it is written out here.
  (doseq [[m per-side] [["semispinalis_capitis" 5.40]
                        ["splenius_capitis" 4.26]
                        ["sternocleidomastoid" 3.72]]]
    (is (math/nearly= (* 2.0 per-side) (:pcsa-cm2 (get muscle/specs m)) 1e-9)
        (str m ": " per-side " cm² per side × 2 sides"))
    (is (= :midline (:side (att/instance m)))
        (str m " is midline, which is what makes the bilateral sum the right number")))
  ;; the double-counting decision, as arithmetic rather than as prose: the lumped
  ;; group is UNCHANGED, so the model's cervical extensor cross-section is the sum
  ;; of the three. If a later change carves the lump up instead, this fails and the
  ;; decision has to be restated rather than drifted into.
  (is (math/nearly= 12.0 (:pcsa-cm2 (get muscle/specs "cervical_extensors")) 1e-9)
      "the lumped group was not reduced; the capitis muscles were added alongside it")
  (is (math/nearly= 31.32
                    (reduce + 0.0 (map #(:pcsa-cm2 (get muscle/specs %))
                                       ["cervical_extensors" "semispinalis_capitis"
                                        "splenius_capitis"]))
                    1e-9)
      "12.00 + 10.80 + 8.52 = 31.32 cm² of cervical extensor, a factor of 2.61"))

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

(def ^:private posture-grid
  "Postures spanning every degree of freedom `pose` accepts, two or three values
  each. Small enough to run on both hosts, and it moves EVERY joint — a grid that
  held one still would report the muscles crossing it as constant and be reporting
  its own gaps."
  (for [hf [0.0 30.0] tf [0.0 45.0] sf [0.0 90.0] ab [0.0 60.0] ef [0.0 90.0]
        lb [-20.0 20.0] we [0.0 30.0] hr [0.0 30.0]
        hip [0.0 60.0] knee [0.0 60.0] ank [-20.0 20.0]]
    {:head-flexion-deg hf :trunk-flexion-deg tf :shoulder-flexion-deg sf
     :elbow-flexion-deg ef :shoulder-abduction-deg ab :trunk-lateral-bend-deg lb
     :wrist-extension-deg we :head-rotation-deg hr
     :hip-flexion-deg hip :knee-flexion-deg knee :ankle-dorsiflexion-deg ank}))

(deftest exactly-one-muscle-has-both-ends-on-one-segment-and-it-is-a-suspender
  ;; THE VERDICT ON A SUSPECTED DEFECT, pinned so it stays answered.
  ;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm`
  ;; names this shape as the error that produced a constant-looking arm, and
  ;; `middle_trapezius` has it: both of its sites are on `thorax_abdomen`, so its
  ;; length is 1.0000 × optimal at every posture and its passive tension is
  ;; identically zero.
  ;;
  ;; It is NOT the error, for one reason and one reason only: a moment arm is
  ;; taken about a joint the segment carries, and a suspension coefficient is a
  ;; cosine against the world vertical, which it does not. So this test allows the
  ;; shape for a suspension muscle and forbids it for everything else — a MOMENT
  ;; muscle that acquired both ends on one bone is the bug, and would be caught
  ;; here rather than showing up as a suspiciously flat column.
  (let [same-bone (filterv #(= (get-in % [:origin :segment]) (get-in % [:insertion :segment]))
                           att/instances)]
    (is (= #{"middle_trapezius"} (set (map :group same-bone)))
        (str "only the middle trapezius may have both ends on one segment: "
             (mapv :name same-bone)))
    (doseq [m same-bone]
      (is (= :scapular-suspension (:task m))
          (str (:name m) ": both ends on one bone is only defensible for a "
               "suspension task, whose coefficient is not a moment arm")))))

(deftest the-middle-trapezius-length-is-constant-and-the-file-says-why
  ;; The measured half of the verdict above. Both are asserted, because either one
  ;; alone is misleading: that the length never moves (a limitation), and that the
  ;; coefficient does (which is why the muscle is still worth having).
  (let [opt (att/optimal-lengths (pose/solve-pose body att/reference-posture) 1.70)
        ratios (mapv (fn [p]
                       (let [ls (att/lengths (pose/solve-pose body p) 1.70)]
                         (/ (get ls "middle_trapezius/left")
                            (get opt "middle_trapezius/left"))))
                     posture-grid)
        cosines (mapv #(att/effectiveness (pose/solve-pose body %) 1.70
                                          (att/instance "middle_trapezius/left"))
                      posture-grid)]
    ;; an evidence floor: a grid that quietly emptied must not pass by having
    ;; nothing to check
    (is (< 100 (count ratios)) (str "the grid has to have postures in it: " (count ratios)))
    (is (every? #(math/nearly= 1.0 % 1e-9) ratios)
        (str "this model cannot move the middle trapezius: it has no scapula. "
             "ratios spanned " [(apply min ratios) (apply max ratios)]))
    ;; therefore its force-length factor is 1 and its passive tension 0, always,
    ;; and its reported %MVC is a lower bound rather than an estimate
    (is (every? #(= 1.0 (muscle/force-length-factor % 1.0)) ratios)
        "so the length can never reduce its available force")
    ;; and the coefficient it is actually solved with DOES move, which is what
    ;; makes it a muscle rather than a column of the same number
    (is (< 1 (count (distinct (mapv #(math/round-to % 6) cosines))))
        "but its suspension coefficient varies, because the vertical is not on the thorax")
    (is (> (- (apply max cosines) (apply min cosines)) 0.1)
        (str "and by enough to matter: " [(apply min cosines) (apply max cosines)]))))

(deftest supporting-the-forearms-unloads-the-girdle
  ;; A BRANCH THAT COULD NOT FIRE, until 2026-09-07. `muscle/suspended-weight-n`
  ;; read the flag from `(meta p)`, and `pose` calls `with-meta` nowhere and never
  ;; has — so the lookup returned nil at every call and the function's whole
  ;; documented effect was unreachable. Measured on this body at `laptop-on-desk`:
  ;; supported and unsupported both gave 34.32 N, which is the UNSUPPORTED answer.
  ;; The desk transferred nothing. Nothing downstream was wrong because
  ;; `solve-muscle-tensions` used a private twin that took the flag properly, and
  ;; that duplicate body is the other half of the defect — it is what let the
  ;; public one rot with nobody noticing.
  ;;
  ;; The numbers are pinned rather than merely compared, because `supported <
  ;; unsupported` would also pass against a model that shaved a gram off.
  (let [ws (posture/posture-from-workstation posture/laptop-on-desk)
        w (fn [sup] (muscle/suspended-weight-n
                     body (pose/solve-pose body (assoc ws :arms-supported sup)) :left sup))
        g (fn [n] (* (:mass-kg (segment/seg body n)) segment/gravity))]
    (is (math/nearly= (+ (g "upper_arm") (g "forearm") (g "hand")) (w false) 1e-9)
        (str "an unsupported girdle hangs the whole arm: " (w false) " N"))
    (is (math/nearly= (g "upper_arm") (w true) 1e-9)
        (str "resting the forearms transfers two segments to the desk: " (w true) " N"))
    (is (> (w false) (* 1.5 (w true)))
        (str "which is a large difference and not a rounding one: "
             (w false) " vs " (w true) " N"))))

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

(deftest the-abduction-arm-is-not-a-constant
  ;; THE DEFECT THIS CATCHES, and it is the one this whole namespace exists to
  ;; prevent. `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm`
  ;; asserts that the ANTERIOR deltoid varies; the same assertion for the middle
  ;; deltoid was never written, and until 2026-09-07 it would have failed. Its
  ;; wrap radius was 0.022 m, which is not a bone radius at all — it is the top of
  ;; the `~20-25 mm` moment-arm range its own `:source` quoted, used as a floor.
  ;; A floor taken from the arm's own target sits ABOVE the arm, so it binds
  ;; everywhere: measured over 3,072 postures the wrap was in force 3,072 times
  ;; and the arm was −0.022 m at all of them. A moment arm that never moves is a
  ;; table, and a table is what this file replaced.
  (let [arms (mapv #(arm (at :shoulder-abduction-deg (double %)) "middle_deltoid/left")
                   [0 15 30 45 60 75 90])]
    (is (< 1 (count (distinct (mapv #(math/round-to % 9) arms))))
        (str "the middle deltoid's abduction arm must move with the joint: " arms))
    ;; and by enough to matter — a variation of a few microns would satisfy the
    ;; line above while still being a constant to any consumer
    (is (> (/ (apply max (map math/abs* arms)) (apply min (map math/abs* arms))) 1.2)
        (str "and by enough that a constant would not have done: " arms))))

(deftest the-abduction-floor-is-the-humeral-head-and-it-is-a-floor
  ;; A wrapping surface is a FLOOR the chord can beat (`moment-arm-detail`). A
  ;; floor that is in force at every posture is not a floor, it is a tabulated
  ;; value wearing one — which is exactly what 0.022 m produced. Both halves are
  ;; asserted here: the radius is the humeral head's, shared with the anterior
  ;; deltoid because it is the same bone; and the chord actually beats it
  ;; somewhere, so the wrap is doing the job it claims.
  (let [md (att/instance "middle_deltoid/left")
        ad (att/instance "anterior_deltoid/left")
        r (get-in md [:wrap :radius-m])
        dets (mapv (fn [d]
                     (let [p (at :shoulder-abduction-deg (double d))]
                       (att/moment-arm-detail p 1.70 md (get-in p [:joints :shoulder/left])
                                              att/frontal-axis)))
                   (range 0 181 5))]
    (is (= r (get-in ad [:wrap :radius-m]))
        (str "one bone, one radius: the two deltoids wrap the same humeral head, "
             "and declared " r " and " (get-in ad [:wrap :radius-m])))
    (is (some (complement :wrapped?) dets)
        "the chord must beat the floor somewhere, or the floor is a table")
    (is (some :wrapped? dets)
        "and the floor must bind somewhere, or it is not doing anything")
    ;; the floor is a floor: nothing may be reported closer to the joint than the
    ;; bone the tendon lies on
    (is (every? #(>= (math/abs* (:arm %)) (- r 1e-12)) dets)
        (str "no arm may fall below the head radius " r ": "
             (mapv #(math/round-to (:arm %) 5) dets)))))

(deftest the-abductor-does-not-become-an-adductor-in-its-own-range
  ;; WHAT THE CONSTANT WAS HIDING. With the acromion at the joint's own height the
  ;; chord's abduction leverage was largest at 0° and fell through zero near 78°,
  ;; so the model's principal abductor was an ADDUCTOR through the top half of its
  ;; range. Nobody could see it, because the floor was above the chord's whole
  ;; range and reported ±22 mm at every posture. Sign, then shape.
  (doseq [d [0 30 60 90 120 150 180]]
    (let [a (arm (at :shoulder-abduction-deg (double d)) "middle_deltoid/left")]
      (is (neg? a) (str d "°: the left middle deltoid abducts, got " a))))
  ;; and the shape: leverage is poorest at the extremes and best in mid-range,
  ;; which is the opposite of what an origin level with the joint produced
  (let [mag #(math/abs* (arm (at :shoulder-abduction-deg (double %)) "middle_deltoid/left"))]
    (is (> (mag 45) (mag 0)) "leverage rises off the side of the body")
    (is (> (mag 45) (mag 120)) "and falls again toward the top of the range")))

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

  It held the six lower-limb joints for exactly as long as it took to give them
  muscles, and was empty for part of one day. It used to be the sentence `the hip
  is deliberately unsolved, because a seated model has no thigh` — true when it was
  written, false the moment `segment/build-body` grew a thigh, and it would have
  gone on reading as a decision. A set that has to be emptied is harder to forget
  than a paragraph that has to be reread; that is what makes the next gap cheap to
  state, and here it is.

  `:c2c3` is the joint between the atlas-axis block and the lower cervical column,
  placed by `pose` when the neck was split on 2026-09-07 and solved by nobody.
  Muscles CROSS it — semispinalis capitis, splenius capitis and sternocleidomastoid
  run past it from the thorax to the skull, and since 2026-09-08 `longus_capitis`
  runs past it up the front — but none of them is solved AT it: each belongs to one
  task, and none of those tasks is this joint.

  ⚠ THE REASON WAS WRONG UNTIL 2026-09-08 AND IT MATTERED. This docstring used to
  end: Filling it needs the muscles that act on the upper cervical spine
  specifically (rectus capitis anterior and lateralis, longus capitis, the
  semispinalis and multifidus cervicis fascicles that end on C2), none of which this
  model has. I.e. the blocker was said to be a shortage of anatomy. Two of those muscles were
  added on 2026-09-08 and the joint is still unsolved, because they act about the
  ATLANTO-OCCIPITAL joint; a name on that list was not a muscle at this one.

  What actually blocks it is measured in
  `spine-test/the-segmentation-can-express-a-c2c3-muscle` and
  `spine-test/nothing-is-solved-at-c2c3-and-the-reason-is-provenance`, and it is
  not the segmentation. All three muscles that act here in anatomy — semispinalis
  cervicis (thoracic transverse processes to the C2 spinous), the cervical
  multifidus (one or two segments, C3/C4 articular processes to the C2 spinous) and
  the superior oblique part of longus colli (lower cervical transverse processes to
  the anterior tubercle of the atlas) — have their two ends on DIFFERENT segments of
  this model, so none of them is the shape
  `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names as
  the error, and each has an arm about `:c2c3` that moves when the joint moves. A
  one-level multifidus crosses C2/C3 and nothing else.

  The blocker is PROVENANCE, in two parts. (1) Kamibayashi & Richmond 1998 Table
  3-3 — the source every measured PCSA in this model comes from — has fourteen rows
  and none of the three is among them, checked in the fetched full text. (2) The
  lumped `cervical_extensors` already declares in its own `:source` that it stands
  for semispinalis cervicis and multifidus, and its 12.0 cm² is itself
  unprovenanced, so carving them out would need a number to divide that does not
  exist. Adding them anyway would double-count against a lump nobody can check.

  So `:c2c3` stays here, and what would close it is a source with a cross-section
  for the deep cervical extensors — not a segmentation change and not a wrapping
  surface.

  WHAT ITS ABSENCE COSTS. The C2/C3 level in `spine/profile` carries a weight term
  and the muscle lines that happen to cross it, and no force from any muscle whose
  job is to hold that joint — so its compression is a LOWER bound. It is the one
  place in the cervical profile where the model is knowingly short of a muscle
  rather than short of a measurement."
  #{:c2c3})

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

;; --- the suboccipitals -------------------------------------------------------
;;
;; The point of splitting `head_neck` on 2026-09-07. Until then these could not be
;; written down at all: with one rigid head-and-neck segment both ends of each rode
;; on the same bone, which is the shape
;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names
;; as the error that produces a constant-looking moment arm. The file said so and
;; ended `What is missing is a JOINT, not an attachment, and no attachment can
;; supply it`. These pin that the joint arrived and that the muscles it carries are
;; not decoration.

(def ^:private suboccipital
  ["rectus_capitis_posterior_major" "rectus_capitis_posterior_minor"
   "obliquus_capitis_superior"])

(deftest the-suboccipitals-run-between-the-atlas-axis-block-and-the-skull
  ;; The claim `the-head-muscles-run-between-two-different-bones` makes for the
  ;; capitis muscles, made here for the three the split unlocked — and it is the
  ;; claim that could not be made before, so it is the direct test of what the
  ;; split bought.
  (doseq [m suboccipital]
    (let [spec (att/instance m)]
      (is (= "upper_cervical" (get-in spec [:origin :segment]))
          (str m " originates on the atlas-axis block"))
      (is (= "head" (get-in spec [:insertion :segment]))
          (str m " inserts on the skull"))
      (is (= :atlanto-occipital (:acts-about spec))
          (str m " acts about the joint the split created"))
      (is (= :atlanto-occipital-extension (:task spec))
          (str m " belongs to that joint's equilibrium")))))

(deftest the-suboccipital-moment-arms-vary-with-posture
  ;; THE TEST THE ABSENCE NOTE PREDICTED WOULD FAIL BEFORE THE SPLIT. With both ends
  ;; on one segment the arm cannot move at any angle; with a joint between them it
  ;; does. Measured on a 70 kg / 1.70 m body over −15 to 60 deg of head flexion:
  ;;
  ;;     rectus capitis posterior major   27.39 → 28.46 mm
  ;;     rectus capitis posterior minor   22.55 → 23.51 mm
  ;;     obliquus capitis superior        12.66 → 13.63 mm
  ;;
  ;; It is a 4% swing and not more, and the reason is stated rather than hidden: the
  ;; atlanto-occipital joint takes only about 15% of the head's flexion, so over the
  ;; model's whole input range it rotates 9 deg. A short rotation moves a moment arm
  ;; a short way. What matters is that it moves at all, monotonically, and in the
  ;; direction the geometry requires — the joint EXTENDS as the head flexes on the
  ;; trunk, which carries the insertion further behind the joint centre.
  (doseq [m suboccipital]
    (let [arms (mapv #(arm (at :head-flexion-deg (double %)) m) [-15 0 15 30 45 60])]
      (is (= 6 (count (distinct arms)))
          (str m ": every posture must give a different arm, got " arms))
      (is (every? (fn [[a b]] (< a b)) (partition 2 1 arms))
          (str m ": and the arm must grow as the head flexes, got " arms))
      (is (every? pos? arms)
          (str m ": it is an extensor at every posture, got " arms))))
  ;; and it is the HEAD's angle that moves them, not the trunk's — the joint is
  ;; between the skull and the atlas, and `:trunk-flexion-deg` carries the whole
  ;; chain rigidly. A test that swept trunk flexion would report no variation and
  ;; look like this defect coming back.
  ;; (to 1e-12 rather than to the bit: the frame is built by rotating a basis, so
  ;; a leaned trunk carries the same geometry through different trigonometry and
  ;; the last bits differ.)
  (doseq [m suboccipital]
    (let [xs (mapv #(arm (at :trunk-flexion-deg (double %)) m) [0 20 45])]
      (is (every? #(math/nearly= (first xs) % 1e-12) xs)
          (str m ": leaning the trunk must not move an atlanto-occipital arm, got "
               xs)))))

(deftest the-suboccipital-lengths-are-checked-against-the-measurement
  ;; CALIBRATED AGAINST A MEASURED LENGTH RATHER THAN AN INVENTED MOMENT ARM, which
  ;; is a departure from the rest of this file and is stated where the muscles are.
  ;; Everywhere else the offsets are chosen to reproduce a constant this actor
  ;; already used; there is no such constant for these three, and inventing a
  ;; moment-arm target to calibrate to would be a number pretending to be an anchor.
  ;; Kamibayashi & Richmond publish a length range for each (Table 3-3, `MUSCLE
  ;; LENGTH (cm)`), so the sites are placed from bony landmarks and the resulting
  ;; line length is compared against the cadaver measurement.
  ;;
  ;; IT REPORTS THE DISAGREEMENT RATHER THAN REMOVING IT. Measured at the neutral
  ;; posture on a 70 kg / 1.70 m body, against the published range:
  ;;
  ;;     rectus capitis posterior major   41.5 mm   vs 30-48 mm   inside
  ;;     rectus capitis posterior minor   23.0 mm   vs 26-31 mm   3.0 mm SHORT
  ;;     obliquus capitis superior        39.5 mm   vs 43-57 mm   3.5 mm SHORT
  ;;
  ;; Both shortfalls have the same cause and it is not these muscles: this model's
  ;; `upper_cervical` is 37 mm at reference stature where an atlas plus axis is
  ;; nearer 50, because `segment` cuts the neck at the model's own uniform 18.6 mm
  ;; level spacing and C2 with its dens is taller than a typical vertebra. Anything
  ;; spanning this joint comes out short. It is not corrected by a factor nobody
  ;; measured; the bound below allows the known 3.5 mm and would fail on more.
  (let [p (at)
        len (fn [m] (* 1000.0 (:length-m (att/line-of-action p 1.70 (att/instance m)))))]
    (doseq [[m lo hi] [["rectus_capitis_posterior_major" 30.0 48.0]
                       ["rectus_capitis_posterior_minor" 26.0 31.0]
                       ["obliquus_capitis_superior" 43.0 57.0]]]
      (let [got (len m)]
        ;; inside the measured range, or short of it by no more than the 4 mm the
        ;; segment's own length error accounts for
        (is (and (> got (- lo 4.0)) (< got (+ hi 4.0)))
            (str m ": modelled " got " mm against a measured " lo "-" hi " mm"))))
    ;; and the direction of the error is stated, not just its size: the one whose
    ;; origin sits low on the axis, and so spans most of the joint's real height, is
    ;; INSIDE the measured range; the two that hang off the atlas are short
    (is (and (> (len "rectus_capitis_posterior_major") 30.0)
             (< (len "rectus_capitis_posterior_major") 48.0))
        (str "rectus capitis posterior major is inside its range: "
             (len "rectus_capitis_posterior_major") " mm"))
    (doseq [[m lo] [["rectus_capitis_posterior_minor" 26.0]
                    ["obliquus_capitis_superior" 43.0]]]
      (is (< (len m) lo)
          (str m " is short of its measured range, and the model says why: "
               (len m) " mm against " lo " mm")))))

(deftest the-suboccipital-wrap-floor-never-binds
  ;; Each of these declares the same 0.012 m column wrap the other posterior
  ;; cervical muscles do — one bone, one radius. A wrapping surface whose radius was
  ;; taken from a moment-arm target rather than from the bone binds at every posture
  ;; and quietly restores the constant this whole namespace exists to remove; that
  ;; is what `middle_deltoid` did, and this file says so. So the floor has to be
  ;; shown NOT to bind, rather than assumed harmless.
  (doseq [m suboccipital
          h [-15.0 0.0 15.0 30.0 45.0 60.0]]
    (let [p (at :head-flexion-deg h)
          d (att/moment-arm-detail p 1.70 (att/instance m)
                                   (get-in p [:joints :atlanto-occipital]))]
      (is (false? (:wrapped? d))
          (str m " at head " h " deg wrapped, so its arm is the radius and not the "
               "geometry: " d))
      (is (> (Math/abs (:straight d)) (:radius-m d))
          (str m " at head " h " deg: the chord must beat the floor, " d)))))

;; --- the upper cervical flexors ----------------------------------------------
;;
;; Added 2026-09-08. The gap they close was named by the file that created it:
;; `load/atlanto-occipital-moment` said "what a real neck balances that with is its
;; upper cervical FLEXORS — longus capitis, rectus capitis anterior and lateralis —
;; and this model has none of them", and it had none. Reproduced before the block
;; existed: every muscle whose `:acts-about` was `:atlanto-occipital` was one of the
;; three suboccipital EXTENSORS, every one of them was in
;; `:atlanto-occipital-extension`, and no task anywhere in the model carried a
;; flexion moment about any cervical joint.

(def ^:private upper-cervical-flexors
  ["longus_capitis" "rectus_capitis_anterior"])

(deftest the-upper-cervical-flexors-run-between-two-different-bones
  ;; The claim the suboccipitals make on the extension side, made here on the
  ;; flexion side. A muscle with both ends on one bone rotates rigidly with it and
  ;; its moment arm cannot move — see
  ;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm`.
  (doseq [m upper-cervical-flexors]
    (let [spec (att/instance m)]
      (is (not= (get-in spec [:origin :segment]) (get-in spec [:insertion :segment]))
          (str m " must span two bones"))
      (is (= "head" (get-in spec [:insertion :segment]))
          (str m " inserts on the skull"))
      (is (= :atlanto-occipital (:acts-about spec))
          (str m " acts about the atlanto-occipital joint"))
      (is (= :atlanto-occipital-flexion (:task spec))
          (str m " belongs to that joint's FLEXION equilibrium"))))
  ;; and the joint now has muscles on both sides of it, which is the whole point
  (let [about-ao (filter #(= :atlanto-occipital (:acts-about %)) att/instances)
        tasks (set (map :task about-ao))]
    (is (= #{:atlanto-occipital-extension :atlanto-occipital-flexion} tasks)
        (str "the atlanto-occipital joint must be able to state both directions, got "
             tasks))))

(deftest the-upper-cervical-flexor-arms-are-flexion-arms-and-they-vary
  ;; TWO CLAIMS, and they are separate. The first is the SIGN: a flexor's geometric
  ;; moment arm about this joint is negative in the convention
  ;; `straight-moment-arm` uses (positive is extension), and `attachment/task-sense`
  ;; is what turns that into the positive coefficient `recruit` needs. Asserting only
  ;; the coefficient would pass for a muscle placed behind the joint with the sign
  ;; table hiding it.
  (doseq [m upper-cervical-flexors]
    (let [geom (mapv #(let [p (at :head-flexion-deg (double %))]
                        (att/moment-arm p 1.70 (att/instance m)
                                        (get-in p [:joints :atlanto-occipital])))
                     [-15 0 15 30 45 60])
          coeff (mapv #(arm (at :head-flexion-deg (double %)) m) [-15 0 15 30 45 60])]
      (is (every? neg? geom)
          (str m ": its geometric arm must be a FLEXION arm at every posture, got " geom))
      (is (every? pos? coeff)
          (str m ": and its task coefficient must be positive so recruit can use it, got "
               coeff))
      (is (= 6 (count (distinct coeff)))
          (str m ": every posture must give a different arm, got " coeff))
      ;; the atlanto-occipital joint EXTENDS as the head flexes on the trunk, which
      ;; carries an anterior insertion TOWARD the joint centre — so a flexor's arm
      ;; shrinks where the suboccipital extensors' grow. Opposite directions from
      ;; the same rotation is the check that the geometry is doing the work.
      (is (every? (fn [[a b]] (> a b)) (partition 2 1 coeff))
          (str m ": and it must shrink as the head flexes, got " coeff))))
  ;; and it is the HEAD's angle that moves them, not the trunk's — the joint is
  ;; between the skull and the atlas, and `:trunk-flexion-deg` carries the whole
  ;; chain rigidly. A test that swept trunk flexion would report no variation and
  ;; would look exactly like a muscle with both ends on one bone.
  (doseq [m upper-cervical-flexors]
    (let [xs (mapv #(arm (at :trunk-flexion-deg (double %)) m) [0 20 45])]
      (is (every? #(math/nearly= (first xs) % 1e-12) xs)
          (str m ": leaning the trunk must not move an atlanto-occipital arm, got " xs)))))

(deftest the-longus-capitis-length-is-checked-against-the-measurement
  ;; CALIBRATED AGAINST A MEASURED LENGTH RATHER THAN AN INVENTED MOMENT ARM, the
  ;; same departure the suboccipitals make and for the same reason: there is no
  ;; constant this actor already used about this joint, so a moment-arm target would
  ;; be a number pretending to be an anchor. Kamibayashi & Richmond 1998 Table 3-3
  ;; publishes a muscle length for longus capitis — 7.8-11.1 cm, mean 9.2 (1.4) —
  ;; so the sites are placed from bony landmarks and the line length is compared.
  ;;
  ;; Measured 2026-09-08 at neutral on a 70 kg / 1.70 m body: 91.75 mm against a
  ;; measured mean of 92 mm. It is reported, not tuned — the assertion is the
  ;; published RANGE, not the mean, so a change that moved it a centimetre and
  ;; still landed inside the cadaver spread would pass, which is the correct
  ;; strength for a schematic line of action.
  (let [len (fn [m] (* 1000.0 (:length-m (att/line-of-action (at) 1.70 (att/instance m)))))]
    (let [got (len "longus_capitis")]
      (is (and (> got 78.0) (< got 111.0))
          (str "longus_capitis: modelled " got " mm against a measured 78-111 mm "
               "(Kamibayashi & Richmond 1998 Table 3-3, N=7)")))
    ;; rectus capitis anterior has NO measured length in that table, because the
    ;; table does not contain the muscle at all — so there is nothing to check it
    ;; against and this test does not pretend there is. What can be said is that it
    ;; is the short one: it connects C1 to the skull and crosses no disc, and a
    ;; longus capitis that came out shorter than it would mean an attachment had
    ;; been edited onto the wrong bone.
    (is (> (len "longus_capitis") (* 3.0 (len "rectus_capitis_anterior")))
        (str "longus_capitis " (len "longus_capitis") " mm must be much longer than "
             "rectus_capitis_anterior " (len "rectus_capitis_anterior") " mm"))))

(deftest the-sternocleidomastoid-is-an-upper-cervical-extensor-not-a-flexor
  ;; WHY THE MODEL NEEDED NEW MUSCLES RATHER THAN A RE-LABELLING. The
  ;; sternocleidomastoid is this model's existing neck flexor and the obvious
  ;; candidate to re-task onto the atlanto-occipital joint. The geometry says no,
  ;; twice over, and both refusals are pinned by their REASON and not by their
  ;; outcome — a test that only asserted "refused" would pass on a degenerate line
  ;; of action, which is a different defect with a different fix.
  ;;
  ;; It inserts on the MASTOID PROCESS, which is behind the occipital condyles, so
  ;; about that joint it EXTENDS the head. That is also the mechanism of the
  ;; forward-head posture it is famous for: lower cervical flexion with upper
  ;; cervical extension.
  (let [ao-arm (fn [h] (let [p (at :head-flexion-deg (double h))]
                         (att/moment-arm p 1.70 (att/instance "sternocleidomastoid")
                                         (get-in p [:joints :atlanto-occipital]))))
        arms (mapv ao-arm [-15 0 15 30 45 60])]
    (is (every? pos? arms)
        (str "the sternocleidomastoid is an EXTENSOR about the atlanto-occipital "
             "joint at every posture, got " arms))
    (is (every? #(< % recruit/min-coeff) arms)
        (str "and its leverage there is below the model's floor " recruit/min-coeff
             " at every posture, got " arms))
    ;; tasked as a flexor it would be refused for acting the wrong way ...
    (let [as-flexor (recruit/share [{:name "sternocleidomastoid" :f-max-n 446.4
                                     :coeff (- (first arms))}]
                                   1.0)]
      (is (= :acts-the-wrong-way (:refused (first as-flexor)))
          (str "tasked as an upper cervical flexor it is refused for acting the "
               "wrong way: " as-flexor)))
    ;; ... and tasked as an extensor there it would be refused for the floor. Both
    ;; refusals are correct and they have different fixes, which is why the reason
    ;; literal is what is pinned.
    (let [as-extensor (recruit/share [{:name "sternocleidomastoid" :f-max-n 446.4
                                       :coeff (first arms)}]
                                     1.0)]
      (is (= :coefficient-below-floor (:refused (first as-extensor)))
          (str "tasked as an upper cervical extensor it is refused for the floor: "
               as-extensor)))))

(deftest a-head-tipped-back-loads-the-upper-cervical-flexors
  ;; THE POSTURE THE MODEL COULD NOT REPRESENT. With the head tipped back the
  ;; skull's centre of mass sits BEHIND the occipital condyles, gravity extends the
  ;; head, and something has to flex it — a head resting against a headrest is held
  ;; there by exactly this. Before 2026-09-08 the moment was computed (-0.766 N·m at
  ;; head -15 deg on a 70 kg / 1.70 m body) and no muscle in the model could resist
  ;; it: `:over-supplied-nm` reported it and nothing carried it.
  (let [p (posture/seated-posture :head-flexion-deg -15.0)
        loads (suji.methods.load/solve-posture-loads body p)
        tens (muscle/solve-muscle-tensions body p loads)
        by (into {} (map (juxt :name identity)) tens)
        ao (:atlanto-occipital loads)]
    (is (neg? (:moment-nm ao))
        (str "gravity must EXTEND the head here, got " (:moment-nm ao) " N·m"))
    (doseq [m upper-cervical-flexors]
      (is (pos? (:active-n (by m)))
          (str m " must carry it, got " (by m))))
    ;; and the suboccipital extensors are asked for nothing — a PLACED zero, not a
    ;; refusal, which is the distinction `atlanto-occipital-moment` exists to keep
    (doseq [m ["rectus_capitis_posterior_major" "rectus_capitis_posterior_minor"
               "obliquus_capitis_superior"]]
      (is (nil? (:refused (by m))) (str m " is not refused here: " (by m)))
      (is (math/nearly= 0.0 (:active-n (by m)) 1e-12)
          (str m " is asked for nothing here: " (by m))))
    ;; the whole demand is gravity, with no decomposition surplus in it at all,
    ;; which is what makes this posture the control for the desk ones below
    (is (math/nearly= 0.0 (:task-decomposition-surplus-nm (by "longus_capitis")) 1e-12)
        (str "no capitis surplus at a tipped-back head: " (by "longus_capitis")))
    (is (math/nearly= (- (:moment-nm ao))
                      (:task-load-nm (by "longus_capitis")) 1e-12)
        "the flexion task's load is exactly the gravitational moment")))

(deftest the-atlanto-occipital-flexion-load-is-gravity-and-not-the-surplus
  ;; THE DECISION THIS BLOCK TURNS ON. `:over-supplied-nm` is two different things
  ;; added together: the moment gravity applies when the head is tipped back, and
  ;; the surplus the two capitis muscles leave because they were sized at C7 and
  ;; solved without this joint's constraint. Only the first is a load on a person,
  ;; and the flexion task is given only the first.
  (doseq [w posture/reference-workstations]
    (let [p (posture/posture-from-workstation w)
          ao (load/atlanto-occipital-moment
              body p
              (into {} (for [t (muscle/solve-muscle-tensions
                                body p (suji.methods.load/solve-posture-loads body p))
                             :when (load/capitis-groups (:group t))]
                         [(:group t) (:force-n t)])))]
      ;; the split is exact, by construction, and that is asserted rather than assumed
      (is (math/nearly= (:over-supplied-nm ao)
                        (+ (:gravitational-flexion-nm ao) (:decomposition-surplus-nm ao))
                        1e-12)
          (str (:name w) ": the two halves must sum to the whole: " ao))
      (is (>= (:decomposition-surplus-nm ao) 0.0)
          (str (:name w) ": a surplus is not negative: " ao))
      ;; at every reference workstation the head is tipped FORWARD, so gravity asks
      ;; the flexors for nothing and the whole of `:over-supplied-nm` is the model's
      ;; own inconsistency
      (is (math/nearly= 0.0 (:gravitational-flexion-nm ao) 1e-12)
          (str (:name w) ": the head is tipped forward, so gravity asks the flexors "
               "for nothing: " ao))
      (is (pos? (:decomposition-surplus-nm ao))
          (str (:name w) ": and the surplus is all of it: " ao)))))

(deftest the-surplus-is-larger-than-the-flexors-that-would-carry-it
  ;; WHY THE SURPLUS IS REPORTED AND NOT ASSIGNED, as a computation rather than as a
  ;; sentence somebody has to remember to keep true.
  ;;
  ;; Assigning it was tried first. `longus_capitis` came out at 185% MVC at
  ;; `laptop-on-lap`, 97% at `laptop-on-desk`, and the worst-loaded muscle in the
  ;; whole report at all three reference workstations — a headline manufactured by a
  ;; decomposition, which is the same thing `atlanto-occipital-moment` refused when
  ;; it declined to charge the suboccipitals this joint's whole demand.
  ;;
  ;; So the surplus is quoted in the units that make its size legible — %MVC of the
  ;; muscles that would have to absorb it, through the same criterion — and over 100
  ;; is the finding: the model's inconsistency at this joint is bigger than the
  ;; anatomy that would have to carry it, so it is evidence about the decomposition
  ;; and not about a neck.
  (let [p (posture/posture-from-workstation posture/laptop-on-lap)
        loads (suji.methods.load/solve-posture-loads body p)
        tens (muscle/solve-muscle-tensions body p loads)
        by (into {} (map (juxt :name identity)) tens)
        s (muscle/tension-summary tens loads)]
    (is (> (:atlanto-occipital-surplus-mvc-pct s) 100.0)
        (str "the surplus exceeds what the flexors could produce: "
             (:atlanto-occipital-surplus-mvc-pct s) " %MVC"))
    ;; and it is NOT charged to anybody: no muscle is over MVC, and the worst
    ;; muscle in the report is not one of the flexors
    (is (zero? (:over-mvc s)) (str "nothing is over MVC at this posture: " s))
    (doseq [m upper-cervical-flexors]
      (is (< (:mvc-pct (by m)) 1.0)
          (str m " is not charged the surplus, got " (:mvc-pct (by m)) " %MVC")))
    (is (> (:atlanto-occipital-surplus-mvc-pct s) (:max-mvc-pct s))
        (str "the surplus would dominate every real load in this posture, which is "
             "why it is reported rather than assigned: surplus "
             (:atlanto-occipital-surplus-mvc-pct s) " vs worst muscle "
             (:max-mvc-pct s)))))

(deftest the-flexors-carry-only-where-gravity-flexes-the-head
  ;; THE SWEEP, so the previous test's single posture cannot stand for the model.
  ;; The claim is an equivalence and not a count: the upper cervical flexors take
  ;; active force in exactly the postures where the skull's centre of mass is behind
  ;; the occipital condyles, and nowhere else. A flexor that carried in a posture
  ;; where gravity flexes the head the other way would be co-contraction the static
  ;; optimum does not predict; one that carried in none would be decoration.
  ;;
  ;; Measured 2026-09-08 over a 365-posture sweep on a 70 kg / 1.70 m body: active
  ;; in 40 of them, all of them head-tilt-negative, peaking at 37.59% MVC for
  ;; longus capitis at head -15 deg with the trunk upright.
  (let [rows (for [hf [-15.0 -10.0 -5.0 0.0 5.0 15.0 30.0 45.0 60.0]
                   tf [0.0 5.0 20.0 45.0]]
               (let [p (posture/seated-posture :head-flexion-deg hf :trunk-flexion-deg tf)
                     loads (suji.methods.load/solve-posture-loads body p)
                     tens (muscle/solve-muscle-tensions body p loads)
                     by (into {} (map (juxt :name identity)) tens)]
                 {:hf hf :tf tf
                  :tilt (+ hf tf)
                  :active (reduce + 0.0 (map #(or (:active-n (by %)) 0.0)
                                             upper-cervical-flexors))
                  :refused (keep #(:refused (by %)) upper-cervical-flexors)}))
        carrying (filter #(pos? (:active %)) rows)]
    (is (seq carrying) "the flexors must carry somewhere")
    (is (every? #(neg? (:tilt %)) carrying)
        (str "they carry only where the head is tipped BACK: "
             (mapv (juxt :hf :tf :tilt) carrying)))
    (is (every? #(pos? (:active %)) (filter #(neg? (:tilt %)) rows))
        (str "and they carry everywhere it is: "
             (mapv (juxt :hf :tf :active) (filter #(neg? (:tilt %)) rows))))
    ;; they are never REFUSED — a refusal would mean the model could not answer,
    ;; and a placed load of zero is a different statement from that
    (is (every? #(empty? (:refused %)) rows)
        (str "an upper cervical flexor is never refused, only unasked: "
             (remove #(empty? (:refused %)) rows)))))
