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
  ;; THE FIRST WAY TO GET THIS WRONG, and the reason the suboccipitals are absent.
  ;; A suboccipital runs from C1 or C2 to the occiput; this model has ONE rigid
  ;; `head_neck` segment and no atlanto-occipital joint, so both of its ends would
  ;; ride on that segment and
  ;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names
  ;; that shape as the error that produced a constant-looking arm. It would BE that
  ;; error here rather than the exception `middle_trapezius` earned, because a
  ;; suboccipital is solved as a MOMENT about an axis the segment carries, not as a
  ;; suspension cosine against the world vertical which it does not.
  ;;
  ;; `exactly-one-muscle-has-both-ends-on-one-segment-and-it-is-a-suspender` already
  ;; forbids the shape globally. This says the positive half for these three: each
  ;; runs thorax → head, so its arm CAN move, and it does.
  (doseq [m cranial]
    (let [spec (att/instance m)]
      (is (= "thorax_abdomen" (get-in spec [:origin :segment])) (str m " origin"))
      (is (= "head_neck" (get-in spec [:insertion :segment])) (str m " insertion"))
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
  (let [c34 (:along (first (filter #(= "C3/C4" (:name %)) spine/levels)))]
    (is (= 0.24 c34) "the premise: C3/C4 is at 0.24 of the head_neck segment")
    (doseq [m cranial]
      (is (> (get-in (att/instance m) [:insertion :along]) c34)
          (str m " must insert above C3/C4 at " c34)))
    ;; and the lumped group, whose source string used to claim the skull, does not
    (is (< (get-in (att/instance "cervical_extensors") [:insertion :along]) 0.06)
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
    (is (= 5 (count cerv)) "the premise: five cervical levels")
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

  EMPTY, as of 2026-09-07. It held the six lower-limb joints for exactly as long
  as it took to give them muscles. It used to be the sentence `the hip is
  deliberately unsolved, because a seated model has no thigh` — true when it was
  written, false the moment `segment/build-body` grew a thigh, and it would have
  gone on reading as a decision. A set that has to be emptied is harder to forget
  than a paragraph that has to be reread; leaving it here, empty, is what makes
  the next gap cheap to state."
  #{})

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
