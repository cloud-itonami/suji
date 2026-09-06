(ns suji.methods.spine
  "suji (筋) — compression, level by level. Pure `.cljc`, stdlib only, no I/O.

  WHY. This actor reported ONE spinal number: the cervical compressive load, a
  lumped value calibrated against Hansraj (2014). It is the right number and it is
  the only one — it says nothing about where along the neck the load peaks, and
  nothing at all about the lumbar spine, where the same posture puts far more force
  through a joint that is also far larger. Neither does a force alone: a disc's
  tolerance is a STRESS, and 500 N through a cervical disc and 500 N through a
  lumbar one are not the same event.

  WHAT IT COMPUTES. For each intervertebral level, the axial force is

      Σ (weight of everything above it, projected on the spine's local axis)
    + Σ (force of every muscle whose line crosses it, projected on the same axis)

  and the stress is that force divided by the level's disc area. The tissue term is
  reported split into `:muscle-n` and `:ligament-n`, because past flexion-relaxation
  the second one IS the answer and calling it muscle would be a different claim.
  The muscle term usually dominates — an extensor works at a short moment arm, so holding a small
  external moment costs a large force, and all of that force presses the joint
  together. That is why a flexed posture loads a spine so much more than the
  weight it carries would suggest, and a model that omitted the muscle term would
  understate every posture by a factor.

  WHAT IT IS NOT. Disc areas are representative adult values scaled with stature,
  not measurements of anybody (G7). Mass above a level is taken as uniform along
  the segment; the segment's own centre of mass is NOT uniform, and this is stated
  rather than hidden — it biases levels near a segment's ends. NON-DIAGNOSTIC
  (G1): a stress is a stress.

  ⚠ THERE IS A LORDOSIS SINCE 2026-09-08 AND STILL NO SHEAR, and the second half
  of that got worse when the first half became true. This paragraph used to say
  `there is no curvature: the model's spine is four straight segments and no arc,
  so it has no lordosis and no shear component`, and the two clauses were
  consistent — a spine stacked vertically under a vertical gravity has nothing to
  shear it. `:pelvic-tilt-deg` tilts the lumbar levels now, so the weight above one
  of them no longer acts along its axis: `weight-above-n` takes the component that
  does, `w x (axis . up)`, and the transverse component is SHEAR that nothing here
  carries. Measured on Wilke's body at 46.5 deg of lordosis, the weight term at
  L4/L5 falls 348.862 N -> 320.531 N, so 28.3 N of real load leaves the model at
  that level and arrives nowhere. It is named rather than absorbed because the
  missing term is a load on structures this model does not have — the facet joints
  and the anulus. `a-tilted-level-drops-its-shear-and-nothing-carries-it` derives
  it from the chord rule rather than pinning the number.

  THE SPINE IS STILL FIVE STRAIGHT SEGMENTS AND NO ARC. Lordosis enters as the
  CHORD of an arc (`pose/lumbar-chord-tilt-deg`), so the lumbar spine tilts as one
  rigid body and the five lumbar levels still share one orientation. What changed
  is whose orientation it is: it was the thorax's and it is the lumbar's own.

  ⚠ TWO THINGS ABOUT THE CERVICAL ROWS, since 2026-09-07 changed one of them and
  not the other.

  The five lower rows — C7/T1 through C3/C4 — still SHARE ONE ORIENTATION.
  `lower_cervical` is C3 to C7 and is one rigid body, so those five are five samples
  of it. What is no longer true, and was until the neck was split, is that the SKULL
  shares that frame: the head and the atlas-axis block move on their own joints now,
  so a forward-head posture reaches these levels as a shape rather than as a tilt.

  C2/C3 HAS NO MUSCLE SOLVED AT IT. It is a real disc — the most cranial one there
  is — and four muscles cross it, but every one of them belongs to another joint's
  equilibrium, so C2/C3's compression is a LOWER BOUND.

  ⚠ THE REASON GIVEN HERE WAS WRONG UNTIL 2026-09-08. This paragraph said the
  blocker was that the muscles which act on the upper cervical spine specifically
  (rectus capitis anterior and lateralis, longus capitis, the cervicis fascicles
  ending on C2) are not in this model. Two of those were added on 2026-09-08 and
  this row is still unsolved, because they act about the ATLANTO-OCCIPITAL joint —
  a name on that list was not a muscle at this one.

  What blocks it is PROVENANCE, measured in
  `spine-test/the-segmentation-can-express-a-c2c3-muscle` and
  `spine-test/nothing-is-solved-at-c2c3-and-the-reason-is-provenance`. The
  segmentation can express all three muscles that act here — semispinalis cervicis,
  the cervical multifidus and the superior oblique part of longus colli all have
  their ends on different segments of this model and all have moment arms about
  `:c2c3` that move — but none of their cross-sections is published in the source
  every measured PCSA here comes from, and the lumped `cervical_extensors` already
  declares that it stands for two of them off an unprovenanced 12.0 cm². The lower
  bound is one measurement away, not one segmentation away.

  What DID move on 2026-09-08 is the composition of the crossing set: the flexor
  `longus_capitis` runs up the front of the column to the basiocciput, so this row
  now carries an ANTERIOR line for the first time. `attachment-test`'s
  `awaiting-muscles` still names the joint.

  VALIDATION STATUS. Two cross-checks live here and they answer different questions.
  `cervical-cross-check` compares this profile against the lumped cervical model
  that IS validated (Hansraj 2014) and reports which of the two carries that
  validation. `lumbar-cross-check` compares the lumbar profile against a published
  in-vivo measurement (Wilke 1999). Neither makes this profile validated; the
  second one measures, in newtons, how far from a measurement it is.

  BOTH WERE RE-MEASURED ON 2026-09-07, when `crosses?` stopped deciding by height.
  The cervical ratio fell from 2.38 to 1.70 because the C7/T1 row lost muscles that
  do not reach a neck. The lumbar figure did not move at all — 350.887 N before and
  after — because at Wilke's zero-flexion posture the crossing set at L4/L5 was
  already empty under both rules: the muscles carrying force there are the girdle
  trio and three leg muscles per side, and none of them ever passed either test."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

(def reference-stature-m 1.70)

(def levels
  "Intervertebral levels, proximal to distal along each segment.

  `:along` is the fraction of that segment's length from its PROXIMAL joint, so
  L5/S1 is at 0.0 of the trunk and C7/T1 at 0.0 of the neck. `:disc-area-cm2` is a
  representative adult cross-section at reference stature; it scales with
  stature² because an area does."
  ;; --- the lumbar levels, re-based 2026-09-08 ----------------------------------
  ;; These five used to sit on `thorax_abdomen`, which ran L5/S1 to C7, at 0.00 /
  ;; 0.07 / 0.14 / 0.21 / 0.28 of it. They now sit on `lumbar`, which spans exactly
  ;; 0.00-0.35 of that old segment, so each `:along` is the old one divided by 0.35
  ;; and every level is in the same place on the same body at the neutral posture.
  ;; Their disc areas are unchanged.
  ;;
  ;; WHAT MOVED IS THEIR ORIENTATION, and that is the whole reason for the split.
  ;; All five still share ONE segment's frame — `lumbar` is L5/S1 to T12/L1 and is
  ;; one rigid body — so a reader should not take five lumbar rows as five
  ;; independently oriented joints. What is no longer true is that the frame is the
  ;; THORAX's: the pelvis can rotate now, the lumbar spine's lower end turns with
  ;; it, and these five levels tilt with the lordosis that produces. Sitting and
  ;; standing can differ above L5/S1, which is what `lumbar-cross-check` needed and
  ;; could not have.
  [{:name "L5/S1" :region :lumbar :segment "lumbar" :along 0.0 :disc-area-cm2 18.0}
   {:name "L4/L5" :region :lumbar :segment "lumbar" :along 0.2 :disc-area-cm2 17.0}
   {:name "L3/L4" :region :lumbar :segment "lumbar" :along 0.4 :disc-area-cm2 16.0}
   {:name "L2/L3" :region :lumbar :segment "lumbar" :along 0.6 :disc-area-cm2 15.0}
   {:name "L1/L2" :region :lumbar :segment "lumbar" :along 0.8 :disc-area-cm2 14.0}
   ;; --- the cervical levels, re-based 2026-09-07 --------------------------------
   ;; These five used to sit on ONE segment, `head_neck`, at 0.00 / 0.06 / 0.12 /
   ;; 0.18 / 0.24 of it. They now sit on `lower_cervical`, which spans exactly
   ;; 0.00-0.30 of that old segment, so each `:along` is the old one divided by
   ;; 0.30 and every level is in the same place on the same body. Their disc areas
   ;; are unchanged.
   ;;
   ;; WHAT MOVED IS THEIR ORIENTATION, and that is the whole reason for the split.
   ;; All five still share one segment's frame — `lower_cervical` is C3 to C7 and
   ;; is still rigid — so a reader should not take five distinct cervical rows as
   ;; five independently oriented joints. What is no longer true is that the SKULL
   ;; shares that frame: the head and the atlas-axis block above these levels now
   ;; move separately, so the weight and the muscle lines these levels carry
   ;; respond to a forward-head posture instead of to a rigid tilt.
   {:name "C7/T1" :region :cervical :segment "lower_cervical" :along 0.0 :disc-area-cm2 5.0}
   {:name "C6/C7" :region :cervical :segment "lower_cervical" :along 0.2 :disc-area-cm2 4.6}
   {:name "C5/C6" :region :cervical :segment "lower_cervical" :along 0.4 :disc-area-cm2 4.3}
   {:name "C4/C5" :region :cervical :segment "lower_cervical" :along 0.6 :disc-area-cm2 4.0}
   {:name "C3/C4" :region :cervical :segment "lower_cervical" :along 0.8 :disc-area-cm2 3.8}
   ;; C2/C3 IS NEW, and it is what the split bought at this end of the model. It is
   ;; a real intervertebral disc — the most cranial one there is; there is no disc
   ;; between C1 and C2 or between C1 and the occiput, which is why those two
   ;; joints appear in `pose` and NOT here. Stated at 0.0 of `upper_cervical` for
   ;; the same reason C7/T1 is stated at 0.0 of the segment above it and L5/S1 at
   ;; 0.0 of the trunk: `crosses?` decides a level exactly at a branch joint with a
   ;; strict `>`, so a level written at 1.0 of the segment BELOW would put
   ;; everything above it on the wrong side of the cut.
   ;;
   ;; Its disc area continues the series above (5.0, 4.6, 4.3, 4.0, 3.8 → 3.6):
   ;; representative at reference stature, scaled with stature² like the rest, and
   ;; not a measurement of anybody.
   {:name "C2/C3" :region :cervical :segment "upper_cervical" :along 0.0 :disc-area-cm2 3.6}])

(defn disc-area-m2
  "Disc area in m², scaled with stature² from the reference."
  [level stature-m]
  (let [k (/ stature-m reference-stature-m)]
    (* (:disc-area-cm2 level) 1e-4 k k)))

(defn level-point
  "World position of a level, and the spine's local axis there (pointing up the
  chain, i.e. the direction compression acts along)."
  [pose-data level]
  (let [{:keys [proximal frame length-m]} (pose/seg-at pose-data (:segment level))]
    {:point (math/v+ proximal (math/v* (:long frame) (* (:along level) length-m)))
     :axis (:long frame)}))

;; --- the shape of the skeleton, which two questions below both need ---------
;;
;; Where a level sits and what a muscle spans are the same question asked from
;; two ends, so they read the same structure. Before 2026-09-07 they read two
;; different ones: `above-fraction` had a rank table and `crosses?` had world
;; heights, and only one of them was right.

(defn- placed-segment-name
  "The name of the placed segment an attachment site rides on, for this muscle's
  side. Resolved exactly the way `attachment/site-point` resolves it — a paired
  segment gets the side suffix, a midline one does not — because a rule about
  where a site SITS and a rule about which bone it is ON must not be able to
  disagree about which bone that is."
  [tree site side]
  (let [base (:segment site)
        placed (when side (pose/placed-name base side))]
    (if (and placed (contains? tree placed)) placed base)))

(defn- attachment-tree
  "`placed segment name -> {:segment parent :along fraction}`, read off the pose.

  REFUSES a pose whose segments do not carry `:attaches-to` rather than reading
  the missing key as `this segment hangs from nothing`. A root and an unanswered
  question look identical once `nil` has been returned, and the whole point of
  this namespace's 2026-09-07 repair is that a test which cannot see its input
  must not return what a test that looked and found nothing returns."
  [pose-data]
  (doseq [seg (:segments pose-data)]
    (when-not (contains? seg :attaches-to)
      (throw (ex-info (str "this pose does not say what its segments hang from; "
                           "spine crossing cannot be decided from world height alone")
                      {:type :value-error :segment (:name seg)}))))
  (into {} (map (juxt :name :attaches-to)) (:segments pose-data)))

(defn- side-of-level
  "Which side of a level a point on the skeleton lies on: `:proximal` (toward
  L5/S1 and everything hanging below it) or `:distal` (further up the spine than
  the level, or out along a limb that branches above it).

  A level is a CUT of the skeleton. Take the point out and the body falls into two
  pieces; this says which piece a site is in. Walk from the site toward the root,
  and at each step carry the fraction at which the segment you are leaving hangs
  on the next one. If the walk reaches the level's own segment, the answer is
  whether the fraction you arrived with is past the level. If it reaches the root
  without ever touching the level's segment, the level was never between this site
  and the root, so the site is on the proximal side.

  THE CONSEQUENCE IS THE FIX. A hand and a forearm both reach the root through
  `upper_arm -> thorax_abdomen (1.0)`, and the walk never enters the neck, so
  both are `:proximal` to every cervical level and a wrist extensor crosses none
  of them. It does not matter how high the world puts them."
  [tree level {:keys [segment along]}]
  (let [target (:segment level)
        cut (:along level)]
    (loop [seg segment, at along, seen #{}]
      (cond
        (= seg target) (if (> at cut) :distal :proximal)
        (contains? seen seg)
        (throw (ex-info "the skeleton's attachment chain contains a cycle"
                        {:type :value-error :segment seg}))
        (not (contains? tree seg))
        (throw (ex-info (str "no placed segment named " (pr-str seg)
                             "; a muscle cannot be placed on a bone the pose does "
                             "not have")
                        {:type :value-error :segment seg}))
        :else (if-let [{p :segment a :along} (get tree seg)]
                (recur p a (conj seen seg))
                :proximal)))))

;; --- what sits above a level -------------------------------------------------

(defn- above-fraction
  "How much of `seg` sits above `level` — 1.0 for a segment the level's cut leaves
  entirely up the chain, 0.0 for one it leaves entirely below, and the remaining
  fraction for the segment the level is on.

  DERIVED FROM THE SAME CUT AS `crosses?` SINCE 2026-09-07, and that is the point
  of the rewrite rather than a side effect. This used to be a rank table —
  mapping thorax_abdomen to rank 0 and the neck to rank 1, with a branch for
  segments the table did not know, which meant an unrecognised segment was an ARM
  and had to be told about legs separately through `segment/below-l5s1`. It answered correctly, and it
  answered from a second hand-written copy of the skeleton's shape sitting in the
  same file as the first. Two copies of a topology is one place for it to be
  corrected and one place for it to be forgotten; there is now one, and it is the
  one `pose` states.

  Take the segment's two ends and ask which side of the cut each is on. Both above
  the cut is 1.0, both below is 0.0, and a segment the cut passes through
  contributes the part of it that is above — which for the level's own segment is
  `1 − :along`.

  UNIFORM along the segment, which is not how mass is actually distributed (see
  `segment`'s `:com-frac`). Stated here because it biases levels near a segment's
  ends, and a reader comparing two adjacent levels should know the bias is in the
  model rather than in the body.

  Measured 2026-09-07: this returns the same value as the rank table for every
  segment at every level across the eight reference postures — the weight term did
  not move by one bit.

  AND THE DESK EXISTS HERE NOW, which it did not until 2026-09-07. The cut says an
  arm hangs from the girdle and therefore sits above every trunk level, and that is
  right *unless the forearm is lying on a desk*, in which case the desk holds it up
  and the lumbar spine does not. `load/body-carries?` is the one place that
  question is answered — `arm-moment-about`, `elbow-moment`, `wrist-moment`,
  `lumbar-borne-bases` and `frontal-moments` all route through it — and this was
  the last equilibrium in the model that did not ask it. Measured: L5/S1 drops
  366.5454 N -> 336.4558 N at `laptop-on-desk`, the weight of two forearms and two
  hands times the level axis's vertical component, and the same drop appears at
  every lumbar level because the cut gives an arm 1.0 at all of them.

  It is asked of `:base`, the anthropometric name, because the desk does not
  distinguish a left forearm from a right one."
  [posture tree level seg]
  (if-not (load/body-carries? posture (:base seg))
    0.0
    (let [name (:name seg)
          end (fn [along] (side-of-level tree level {:segment name :along along}))]
      (case [(end 0.0) (end 1.0)]
        [:distal :distal] 1.0
        [:proximal :proximal] 0.0
        (max 0.0 (- 1.0 (:along level)))))))

(defn- weight-above-n
  "Axial component of the weight sitting above a level.

  TAKES THE POSTURE as well as the pose, because `:arms-supported` is a fact about
  the posture that the placed geometry does not carry — a forearm resting on a desk
  is in the same place as one held there."
  [body posture pose-data level]
  (let [w (pose/segment-weights body pose-data)
        tree (attachment-tree pose-data)
        {:keys [axis]} (level-point pose-data level)]
    (reduce + 0.0
            (for [seg (:segments pose-data)
                  :let [f (above-fraction posture tree level seg)]
                  :when (pos? f)]
              ;; gravity is [0,-w,0]; its compressive component along the spine
              ;; axis is w × (axis · up)
              (* f (get w (:name seg)) (nth axis 1))))))

(defn- crosses?
  "Does this muscle's line of force pass THROUGH the level?

  It does when its two attachments end up on opposite sides of the cut the level
  makes in the skeleton — one piece of the body on each side, so the force has to
  be transmitted across the joint to get from one attachment to the other.

  IT USED TO BE A HALF-SPACE TEST ON HEIGHT, which is a different question and
  answered it wrongly. It projected both attachment points onto the spine's local
  axis at the level and asked whether they straddled it, so anything whose ends
  happened to sit at different heights counted, whether or not its line went
  anywhere near a spine. Measured 2026-09-07 at `laptop-on-lap` before the repair:
  the C3/C4 row was carried ENTIRELY by `wrist_extensors/left` and
  `wrist_extensors/right` (61.4 N of 61.4 N), C5/C6 and C4/C5 were 64% wrist
  extensor, and at 60 degrees of trunk flexion `vasti` and `tibialis_anterior`
  appeared at L1/L2 while `vasti` carried 117.7 N of the 186.0 N at C3/C4. A wrist
  extensor transmits its force to the forearm; a vastus transmits it to the tibia.
  Neither one passes through anybody's neck, and neither should ever have been
  able to, at any height, in any posture.

  DERIVED, NOT LISTED. There is no set of spinal muscles here and no name is
  tested. The answer comes from two things the model already states: which bone
  each attachment rides on and how far along it (`attachment/muscles`), and which
  bone hangs from which and where (`pose`'s `:attaches-to`). A muscle whose whole
  path lives on the arm chain cannot reach a cervical level because the arm chain
  reaches the spine at the top of the THORAX, and that is a fact about the
  skeleton rather than a judgement about the muscle. Adding a muscle, moving an
  attachment or re-hanging a limb changes the answer without anything here being
  edited.

  WHAT IT STILL CANNOT SEE. An attachment is a point, so crossing is
  all-or-nothing where a real muscle tapers over several vertebrae —
  `attachment-steps` reports where that shows. And a level that sits exactly at a
  branch joint is decided by `>` rather than by anatomy: the pelvis hangs at
  `thorax_abdomen` 0.0 and L5/S1 IS `thorax_abdomen` 0.0, so the pelvis is placed
  below L5/S1, which is right — the sacrum is below that disc. Nothing here knows
  that; it follows from the strict comparison, and a level added at exactly 1.0 of
  the trunk would need the question asked again."
  [tree level muscle]
  (let [side (:side muscle)
        seg-of (fn [site] {:segment (placed-segment-name tree site side)
                           :along (:along site)})]
    (not= (side-of-level tree level (seg-of (:origin muscle)))
          (side-of-level tree level (seg-of (:insertion muscle))))))

(defn levels-crossed
  "Which levels a muscle's line of force passes through, in `levels` order.

  Public because the rule is the interesting part and it should be possible to ask
  it about a muscle WITHOUT running a whole profile — including about a muscle that
  is not in `attachment/instances`. That is what makes the rule checkable as a
  rule: hand it two attachment sites and it answers from the sites, so a test can
  move an attachment and watch the answer follow, rather than confirming that a
  name this file already knows still gets the treatment this file already gives it.

  `muscle` needs only `:origin` and `:insertion` — each `{:segment … :along …}` —
  and `:side` if it rides on a paired segment."
  [pose-data muscle]
  (let [tree (attachment-tree pose-data)]
    (filterv #(crosses? tree % muscle) levels)))

(defn- tissue-compression-n
  "Axial component of the forces crossing the level, split by what produced them.

  `{:muscle-n … :ligament-n …}`. It used to be one number called `:muscle-n`, and
  once the posterior ligamentous system landed that name stopped being true:
  measured 2026-09-06 at 60 degrees of trunk flexion, the term was 3,904 N of
  which 3,989 N came from the ligament — the muscles were contributing a NEGATIVE
  net component and the ligament was pressing the joint together on its own. A
  reader taking `:muscle-n` literally would have read a spine compressed by muscle
  where it is compressed by tissue, which is a different statement about the
  posture and about what would change it."
  [pose-data stature-m level tensions]
  (let [{:keys [axis]} (level-point pose-data level)
        ;; ONE tree for the whole level, not one per muscle: it is a property of
        ;; the pose, and rebuilding it inside the predicate would also rebuild its
        ;; refusal, so a pose that could not be read would be re-refused forty
        ;; times over.
        tree (attachment-tree pose-data)
        by-name (into {} (map (juxt :name identity)) tensions)
        contribution
        (fn [m]
          (let [t (by-name (:name m))
                f (:force-n t)]
            (when (and f (pos? f) (crosses? tree level m))
              (let [{:keys [dir]} (attachment/line-of-action pose-data stature-m m)]
                (when dir
                  ;; only the component ALONG the spine compresses it; the
                  ;; transverse component is shear, which this model does not carry
                  (* f (math/abs* (math/vdot dir axis))))))))
        muscles (vec (sort-by first
                              (keep (fn [m]
                                      (when-not (:ligament? m)
                                        (when-let [n (contribution m)]
                                          [(:name m) n])))
                                    attachment/instances)))]
    ;; `:muscle-crossing` is what makes `attachment-steps` a detector rather than
    ;; a guess. `crosses?` is all-or-nothing — a muscle attaches at a POINT, so it
    ;; either spans the level in full or not at all, and there is no taper for a
    ;; magnitude threshold to sit inside. Carrying the names and the newtons means
    ;; the step detector can say WHICH muscle stopped crossing and HOW MUCH force
    ;; went with it, instead of judging a percentage.
    {:muscle-crossing muscles
     :muscle-n (reduce + 0.0 (map second muscles))
     :ligament-n (reduce + 0.0 (keep #(when (:ligament? %) (contribution %))
                                     attachment/instances))}))

(defn level-compression
  "Compression at one level: {:name :region :force-n :stress-mpa :weight-n :muscle-n}.

  TAKES THE POSTURE since 2026-09-07 — an arity change, and the only caller in this
  tree is `profile`, which already had it. `:arms-supported` is not recoverable
  from a solved pose, and without it this level counted a forearm lying on a desk
  as hanging from the shoulder girdle."
  [body posture pose-data tensions level]
  (let [stature-m (:stature-m body)
        weight (weight-above-n body posture pose-data level)
        {:keys [muscle-n ligament-n muscle-crossing]}
        (tissue-compression-n pose-data stature-m level tensions)
        force (+ weight muscle-n ligament-n)
        area (disc-area-m2 level stature-m)]
    {:name (:name level)
     :region (:region level)
     :weight-n weight
     :muscle-n muscle-n
     :muscle-crossing muscle-crossing
     :ligament-n ligament-n
     :force-n force
     :disc-area-cm2 (* 1e4 area)
     ;; N / m² = Pa; ÷1e6 = MPa, which is the unit disc tolerances are quoted in
     :stress-mpa (/ force area 1e6)}))

(defn profile
  "Compression at every level, in the order `levels` declares."
  [body posture tensions]
  (let [p (pose/solve-pose body posture)]
    (mapv #(level-compression body posture p tensions %) levels)))

(defn cervical-cross-check
  "Compare this namespace's C7/T1 force against `load/cervical-load`'s lumped,
  Hansraj-calibrated one, and report the ratio.

  THEY DISAGREE, and which one is trustworthy is not symmetric. The lumped model
  is the leg this actor VALIDATED — it reproduces the published forward-head table
  to within 10% — and it gets there with an effective lever fitted to that table.
  This namespace instead adds the muscle force computed from the muscle's actual
  geometric moment arm, which at large flexion is much shorter and therefore
  demands much more force, all of which presses the joint together.

  UNTIL 2026-09-07 THAT WAS NOT THE WHOLE CAUSE. `crosses?` was a half-space test
  on height, so the C7/T1 muscle term also contained 62.4 N per side of anterior
  deltoid and 30.7 N per side of wrist extensor — 186 N of 588 N that is not
  transmitted through a neck. The ratio was therefore not measuring the quantity
  this docstring named. Measured at laptop-on-lap, 70 kg / 1.70 m: 273.6 N lumped
  and 651.1 N by level before the repair (ratio 2.38), 273.6 N lumped and 464.9 N
  by level after it (ratio 1.70).

  THE PROFILE MOVING CLOSER TO THE VALIDATED LEG IS NOT EVIDENCE THAT IT GOT
  BETTER. It is the arithmetic consequence of removing forces that were never in
  the neck. Agreement obtained by fixing an unrelated defect is not a validation,
  and `:validated :lumped` still says which of the two carries one.

  Neither number is offered as the right one here. What is offered is the ratio,
  so a consumer cannot read the level profile as though it inherited the lumped
  model's validation — it did not. The cervical leg remains the validated one; the
  level profile is mechanically constructed and UNVALIDATED, and this function
  exists so that saying so is a computation rather than a sentence somebody has to
  remember to keep true."
  [body posture tensions cervical-load]
  (let [rows (profile body posture tensions)
        c7 (first (filter #(= "C7/T1" (:name %)) rows))
        lumped (:compressive-load-n cervical-load)]
    {:level-force-n (:force-n c7)
     :lumped-force-n lumped
     :ratio (when (and lumped (pos? lumped)) (/ (:force-n c7) lumped))
     :validated :lumped
     :note "the lumped cervical model is the validated one (Hansraj 2014); the level profile is unvalidated"}))

;; --- the lumbar spine, against the literature --------------------------------
;;
;; The lumbar spine is the most-measured structure in this field, so unlike most
;; of what this actor computes there is real data to answer to. Everything below
;; carries its own provenance: the citation, the URL that was actually fetched,
;; and whether the full text or only an abstract was read. A reference value with
;; no provenance is indistinguishable from an invented one.

(def nachemson-pressure-index
  "Nachemson's PRESSURE INDEX — the number that turns an intradiscal pressure into
  a compressive force. It is the largest single assumption in `lumbar-cross-check`
  and it is stated separately so that it can be argued with.

  A PRESSURE IS NOT A FORCE. Wilke measured megapascals in a nucleus; this model
  computes newtons through a joint. Nachemson measured, in vitro, the quotient
  between the pressure recorded in the nucleus and the pressure applied to the
  whole disc, and named it the pressure index (Acta Orthop Scand XXVIII p.282,
  formula p.285):

      I = Pn / (P / Ad)      hence      P = Pn × Ad / I

  where Pn is the nucleus pressure, P the force applied to the whole disc and Ad
  the disc's total cross-sectional area.

  THE VALUE AND ITS SPREAD. Table 5 (p.281) tabulates the index by interspace and
  state of disc. For NORMAL lumbar discs: L1 1.6, L2 1.7, L3 1.5, L4 1.7 — so
  1.5 to 1.7, and the prose at p.285 uses 1.5 as the normal-disc figure. The
  degenerated column (1.2–1.5) is deliberately not used here: Wilke selected a
  disc with `no signs of degeneration or dehydration`.

  IT IS A MODELLING ASSUMPTION, NOT A MEASUREMENT. The index was measured on
  cadaver discs under pure axial load. Using it on an in-vivo pressure assumes the
  same proportionality holds in a living spine that is also being squeezed by its
  own muscles. Wilke's paper performs no such conversion and does not endorse one.
  Every force this namespace derives from a pressure inherits that assumption, and
  `lumbar-cross-check` propagates the 1.5–1.7 range into the reference's spread
  rather than hiding it behind the single number."
  {:mean 1.5
   :range [1.5 1.7]
   :normal-by-interspace {"L1" 1.6 "L2" 1.7 "L3" 1.5 "L4" 1.7}
   :citation (str "Nachemson A. Measurement of Intradiscal Pressure. "
                  "Acta Orthopaedica Scandinavica XXVIII:269-289. "
                  "Definition p.282, formula p.285, Table 5 p.281.")
   :url "https://actaorthop.org/actao/article/download/30762/35650/84307"
   :obtained :full-text})

(defn pressure->compressive-force-n
  "Intradiscal pressure (MPa) → compressive force (N), through Nachemson's index.

      force = pressure × disc area ÷ index

  1 MPa is 1 N/mm², so a pressure in MPa times an area in mm² is already newtons;
  the index then divides. Carrying an assumption as an argument rather than a
  constant is the point — a caller who wants the degenerated-disc index, or none
  at all, passes it and the result says which was used."
  [pressure-mpa disc-area-mm2 index]
  (when (or (nil? pressure-mpa) (nil? disc-area-mm2) (nil? index) (zero? index))
    (throw (ex-info "pressure, area and a non-zero index are all required"
                    {:type :value-error :pressure-mpa pressure-mpa
                     :disc-area-mm2 disc-area-mm2 :index index})))
  (/ (* pressure-mpa disc-area-mm2) index))

(def ^:private wilke-1999
  {:citation (str "Wilke H-J, Neef P, Caimi M, Hoogland T, Claes LE. "
                  "New in vivo measurements of pressures in the intervertebral disc "
                  "in daily life. Spine 1999;24(8):755-762.")
   :url "https://www.fonar.com/pdf/spine_vol_24.No.8.pdf"
   :obtained :full-text
   :level "L4/L5"
   ;; p.756: `was 45 years old, weighed 70 kg, stood 1.68 m`
   :subject {:mass-kg 70.0 :stature-m 1.68}
   ;; p.756: `The cross-sectional area was 1800 mm².`
   :disc-area-mm2 1800.0
   ;; p.761, the authors' own limitation
   :caveat (str "one subject, one disc, one day: `this study was performed with only "
                "one subject, thereby limiting the significance of the data to the "
                "individual trends observed` (p.761)")})

(def lumbar-references
  "In-vivo lumbar reference values, each with the provenance of the number.

  All four are from Table 1 (p.757) of Wilke et al. 1999, the in-vivo L4/L5
  telemetry study. The table gives twenty-nine entries; these are the ones this
  actor could have anything to say about, and only the FIRST of them is actually
  comparable — see `:posture` and `:not-comparable`.

  WHY MOST OF THEM ARE NOT COMPARABLE. A pressure is measured at a posture, and
  Wilke reports the pressure without reporting the trunk angle. To compare the
  model against `sitting with maximum flexion` this namespace would have to CHOOSE
  a trunk angle, and choosing it is the one move that would turn this file from a
  validation into a fit. So those entries carry the published pressure — a real
  number, worth having — and refuse to produce a ratio.

  The comparable one is `sitting relaxed, without backrest`, because Wilke states
  the posture as well as the pressure: `Relaxed sitting on a stool with a normally
  straight back` (p.758). A normally straight back is zero trunk flexion, which is
  a posture this model can hold without anybody choosing an angle."
  [{:id :wilke-1999-sitting-relaxed-no-backrest
    :label "sitting relaxed, without backrest"
    :pressure-mpa 0.46
    ;; p.758: `produced a pressure peak of 0.45 to 0.50 MPa`
    :pressure-range-mpa [0.45 0.50]
    :posture {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
              :elbow-flexion-deg 0.0 :shoulder-elevation-deg 0.0
              :wrist-extension-deg 0.0 :arms-supported false
              :support :seated :hip-flexion-deg 90.0 :knee-flexion-deg 90.0
              :ankle-dorsiflexion-deg 0.0
              :pelvic-tilt-deg 0.0}
    :posture-basis (str "p.758 `Relaxed sitting on a stool with a normally straight "
                        "back` — a straight back is zero trunk flexion. The paper does "
                        "not state where the arms were; they hang, which is what a "
                        "stool with no armrests leaves them doing.")
    ;; ADDED 2026-09-08, and it is a confession as much as a field. Until the
    ;; lumbar spine could have a lordosis, this posture was fully determined by
    ;; Wilke's own words. It is not any more: the model needs a lordosis, and
    ;; Wilke does not state one. What makes this entry stay comparable is that the
    ;; number is ZERO and is measured — Cho et al. 2015 radiograph a stool at
    ;; 0.6 deg (SD 3.6) — so the model's own neutral is the posture, and nothing
    ;; here was chosen to make the comparison come out anywhere.
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 0.0
     :measured-lordosis-deg 0.6
     :from :cho-2015-stool
     :note (str "Wilke states the posture but no lumbar lordosis. Cho et al. 2015 "
                "measure a stool at 0.6 deg (SD 3.6) in 30 healthy volunteers of "
                "similar build — straight to inside its own scatter, which is this "
                "model's neutral.")}}
   {:id :wilke-1999-sitting-maximum-flexion
    :label "sitting with maximum flexion"
    :pressure-mpa 0.83
    :not-comparable :posture-angle-not-stated-in-source
    :not-comparable-note (str "p.758 gives the pressure and the activity (`simulating, "
                              "for instance, the position of tying shoes`) but no trunk "
                              "angle. Picking one would be fitting, not validating.")}
   {:id :wilke-1999-standing-bent-forward
    :label "standing, bent forward"
    :pressure-mpa 1.10
    :not-comparable :posture-angle-not-stated-in-source
    :not-comparable-note "Table 1 p.757 gives the pressure; no trunk angle is stated."}
   {:id :wilke-1999-relaxed-standing
    :label "relaxed standing"
    :pressure-mpa 0.50
    ;; p.758: `In relaxed standing, intradiscal pressure was reproducibly 0.48 to 0.50 MPa.`
    :pressure-range-mpa [0.48 0.50]
    ;; COMPARABLE SINCE 2026-09-08, AND ITS HISTORY IS THE POINT. It has been
    ;; refused twice for two different reasons, each true when it was written:
    ;;
    ;;   until 2026-09-07  `this model cannot stand` — no thigh segment, no support
    ;;                     mode. It got both.
    ;;   until 2026-09-08  `it returns the same 351 N for standing and for sitting`,
    ;;                     because sitting and standing differed only BELOW L5/S1
    ;;                     and the lumbar spine could not tell. A missing segment
    ;;                     had become a missing degree of freedom.
    ;;
    ;; The trunk is split at T12/L1 now and the pelvis rotates, so what separates
    ;; the two postures — pelvic tilt and the lordosis that goes with it — is
    ;; something the model can hold. The angles below L5/S1 are `quiet-standing`'s
    ;; and they are IRRELEVANT to this level, which is measured rather than
    ;; asserted: `the-lower-limb-does-not-reach-l4l5` shows sitting and standing at
    ;; the same lordosis give the same force to the bit. The whole difference is
    ;; the lordosis.
    :posture {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
              :elbow-flexion-deg 0.0 :shoulder-elevation-deg 0.0
              :wrist-extension-deg 0.0 :arms-supported false
              :support :standing :hip-flexion-deg 0.0 :knee-flexion-deg 5.0
              :ankle-dorsiflexion-deg 5.0
              :pelvic-tilt-deg 46.5}
    :posture-basis (str "Table 1 p.757 / p.758 `relaxed standing`. The upper body is "
                        "held at exactly the angles the sitting entry holds it at, so "
                        "that the only thing that differs between the two comparisons "
                        "is the lordosis. The lower limb is `posture/quiet-standing`.")
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 46.5
     :measured-lordosis-deg 47.1
     :from :cho-2015-standing
     :note (str "Wilke states the posture and no lumbar lordosis. Cho et al. 2015 "
                "measure standing at 47.1 deg (SD 10.5) and a stool at 0.6, so the "
                "tilt from this model's straight neutral is 46.5. It is READ from a "
                "published measurement of the same named posture, not chosen — but "
                "it is read from a DIFFERENT paper and a different cohort from the "
                "pressure it is compared against, and the SD is 10.5 deg. Both "
                "entries carry an imported lordosis now; this one is the one where "
                "it is not zero.")}}])

(defn reference-by-id
  "The entry in `lumbar-references` with this id, or nil."
  [id]
  (first (filter #(= id (:id %)) lumbar-references)))

(def default-lumbar-reference-id :wilke-1999-sitting-relaxed-no-backrest)

(defn lumbar-cross-check
  "Compare the lumbar level profile against a published in-vivo measurement, and
  report the disagreement rather than removing it.

  IN THE SHAPE OF `cervical-cross-check`, WITH THE OPPOSITE ASYMMETRY. There, the
  validated side was this actor's own lumped model. Here the validated side is the
  LITERATURE — an in-vivo pressure telemetered from a living L4/L5 disc — and the
  level profile is the unvalidated one. `:validated` says so in both.

  IT TAKES NO BODY AND NO POSTURE, ON PURPOSE. A reference value is stated for a
  particular subject at a particular posture, and the model must be scaled to the
  reference rather than the reference to the model. Wilke's subject was 70 kg and
  1.68 m; this function builds THAT body and holds THAT posture, so a caller
  cannot compare a 90 kg body against a 70 kg measurement by accident.

  WHAT IS COMPARED. Force against force, in newtons. The reference's pressure is
  converted once, by `pressure->compressive-force-n` through
  `nachemson-pressure-index`, using the disc area Wilke MEASURED (1800 mm²) rather
  than the one this model assumes. Comparing forces keeps the disc-area difference
  out of the comparison; `:model-disc-area-mm2` and `:reference-disc-area-mm2` are
  both reported so a reader can see the difference that was kept out of it.

  THE SPREAD. `:reference-force-range-n` combines both spreads the sources state —
  Wilke's own reproducibility range for the posture, and Nachemson's index range
  for normal lumbar discs — so `:within-reference-spread?` is a comparison against
  the reference's own uncertainty and not against a tolerance chosen here.

  A reference the source does not pin a posture to returns `:could-not-obtain`
  with the reason, and no ratio. That is an answer, and it is a different answer
  from agreement."
  ([] (lumbar-cross-check (reference-by-id default-lumbar-reference-id)))
  ([reference]
   (let [{:keys [level subject disc-area-mm2 citation url obtained caveat]} wilke-1999
         base {:reference-id (:id reference)
               :reference-label (:label reference)
               :level level
               :subject subject
               :reference-pressure-mpa (:pressure-mpa reference)
               :reference-disc-area-mm2 disc-area-mm2
               :pressure-index nachemson-pressure-index
               :validated :reference
               :model-validated? false
               :citation citation
               :url url
               :obtained obtained
               :reference-caveat caveat}]
     (if-let [why (:not-comparable reference)]
       (assoc base
              :could-not-obtain why
              :could-not-obtain-note (:not-comparable-note reference)
              :model-force-n nil
              :ratio nil)
       (let [posture (:posture reference)
             body (segment/build-body (:mass-kg subject) (:stature-m subject))
             loads (load/solve-posture-loads body posture)
             tensions (muscle/solve-muscle-tensions body posture loads)
             row (first (filter #(= level (:name %)) (profile body posture tensions)))
             model-n (:force-n row)
             [i-lo i-hi] (:range nachemson-pressure-index)
             [p-lo p-hi] (or (:pressure-range-mpa reference)
                             [(:pressure-mpa reference) (:pressure-mpa reference)])
             ref-n (pressure->compressive-force-n (:pressure-mpa reference)
                                                  disc-area-mm2
                                                  (:mean nachemson-pressure-index))
             lo-n (pressure->compressive-force-n p-lo disc-area-mm2 i-hi)
             hi-n (pressure->compressive-force-n p-hi disc-area-mm2 i-lo)]
         (assoc base
                :posture posture
                :posture-basis (:posture-basis reference)
                ;; the input the reference does not state, carried out with the
                ;; answer rather than left in the table for somebody to find
                :parameter-not-in-source (:parameter-not-in-source reference)
                :lumbar-lordosis-deg (pose/lumbar-lordosis-deg posture)
                :model-force-n model-n
                :model-weight-n (:weight-n row)
                :model-muscle-n (:muscle-n row)
                :model-ligament-n (:ligament-n row)
                :model-stress-mpa (:stress-mpa row)
                :model-disc-area-mm2 (* 100.0 (:disc-area-cm2 row))
                :reference-force-n ref-n
                :reference-force-range-n [lo-n hi-n]
                :ratio (/ model-n ref-n)
                :within-reference-spread? (and (>= model-n lo-n) (<= model-n hi-n))
                :direction (cond (< model-n lo-n) :model-below-reference
                                 (> model-n hi-n) :model-above-reference
                                 :else :within-reference-spread)))))))

(defn sitting-standing-comparison
  "Wilke measured relaxed standing and relaxed sitting APART — 0.50 MPa against
  0.46 — and until 2026-09-08 this model returned the same force for both. This
  reports what it returns now, and whether that is an improvement.

  IT IS NOT A VALIDATION AND THE ARITHMETIC BELOW IS WHY. Three things are
  reported and they answer different questions:

    :model-difference-n      standing minus sitting, this model
    :reference-difference-n  standing minus sitting, Wilke, through the same
                             pressure index the rest of this namespace uses
    :difference-ratio        the first over the second

  THE DIRECTION AGREEING IS NOT EVIDENCE, and this is the most important sentence
  in the function. In this model ANY lordosis, of either sign, moves the mass above
  a lumbar level off the load line and therefore raises the compression there. The
  straighter of two postures is the lighter one whichever posture that is. So the
  model was going to say `standing loads L4/L5 more than sitting` the moment it was
  told standing is the more lordotic posture — and it would have said `sitting loads
  it more` just as confidently had the two measurements been the other way round.
  A mechanism that cannot produce the opposite answer has not predicted this one.

  WHAT IS EVIDENCE IS THE SIZE, and the size is wrong by most of an order of
  magnitude. See `lordosis-matching-reference-difference-deg`.

  BOTH SIDES CARRY AN IMPORTED LORDOSIS (`:parameter-not-in-source` on each), so
  `:model-validated?` is false here exactly as it is everywhere else in this
  namespace."
  []
  (let [sit (lumbar-cross-check (reference-by-id :wilke-1999-sitting-relaxed-no-backrest))
        stand (lumbar-cross-check (reference-by-id :wilke-1999-relaxed-standing))
        d-model (- (:model-force-n stand) (:model-force-n sit))
        d-ref (- (:reference-force-n stand) (:reference-force-n sit))]
    {:sitting sit
     :standing stand
     :model-difference-n d-model
     :reference-difference-n d-ref
     :difference-ratio (when-not (zero? d-ref) (/ d-model d-ref))
     :same-direction? (or (and (pos? d-model) (pos? d-ref))
                          (and (neg? d-model) (neg? d-ref)))
     :direction-is-not-evidence
     (str "any lordosis of either sign raises compression in this model, so the "
          "sign of this difference follows from which posture was given the "
          "smaller lordosis and could not have come out the other way")
     :validated :reference
     :model-validated? false}))

(defn lordosis-matching-reference-difference-deg
  "How much lordosis this model needs at L4/L5 to reproduce Wilke's own
  standing-minus-sitting difference — a DIAGNOSTIC, and deliberately not a
  constant anything uses.

  Bisected on `:pelvic-tilt-deg` against the standing reference's own posture,
  everything else held. Put beside the 46.5 deg Cho measured, it says how far off
  this model's sensitivity to lordosis is; that quotient is the finding, and
  installing the answer as the model's lordosis would be the fudge factor this
  repo has refused four times.

  Returns nil if the difference is not bracketed within the interval searched,
  rather than returning an endpoint — an answer that could not be obtained must
  not look like one that was."
  []
  (let [ref (reference-by-id :wilke-1999-relaxed-standing)
        sit (reference-by-id :wilke-1999-sitting-relaxed-no-backrest)
        {:keys [subject]} wilke-1999
        body (segment/build-body (:mass-kg subject) (:stature-m subject))
        at (fn [tilt]
             (let [pst (assoc (:posture ref) :pelvic-tilt-deg tilt)
                   loads (load/solve-posture-loads body pst)
                   tensions (muscle/solve-muscle-tensions body pst loads)]
               (:force-n (first (filter #(= "L4/L5" (:name %))
                                        (profile body pst tensions))))))
        base (at 0.0)
        target (- (pressure->compressive-force-n (:pressure-mpa ref)
                                                 (:disc-area-mm2 wilke-1999)
                                                 (:mean nachemson-pressure-index))
                  (pressure->compressive-force-n (:pressure-mpa sit)
                                                 (:disc-area-mm2 wilke-1999)
                                                 (:mean nachemson-pressure-index)))
        hi 46.5]
    (when (and (< (- (at 0.0) base) target) (>= (- (at hi) base) target))
      (loop [lo 0.0 hi hi n 0]
        (if (> n 60)
          (* 0.5 (+ lo hi))
          (let [mid (* 0.5 (+ lo hi))]
            (if (< (- (at mid) base) target)
              (recur mid hi (inc n))
              (recur lo mid (inc n)))))))))

(def niosh-1981-compression-criteria
  "The two lumbar compressive forces NIOSH names for workplace DESIGN.

  Not a validation and not about a person. These are occupational design
  thresholds for the L5/S1 disc, quoted here so that a force this model produces
  can be placed on the scale the literature uses. NON-DIAGNOSTIC (G1): comparing a
  newton to a published newton is arithmetic; nothing here says anything about
  anybody's health.

  STATED IN KILOGRAM-FORCE, NOT NEWTONS. The 1981 Work Practices Guide (p.36,
  `Biomechanical design criteria`) says `jobs which place more than 650 kg
  compressive force on the low-back are hazardous to all but the healthiest of
  workers` and that `a much lower level of 350 kg or lower should be viewed as an
  upper limit`. The familiar 3400 N and 6400 N are those two figures times g. The
  1993 revision quotes the lower one directly as `3·4 kN (770 lbs)` (Waters et al.
  Table 1 p.751) and kept it: `the 1991 NIOSH committee decided to maintain the
  1981 biomechanical criterion of 3·4 kN` (p.755).

  The labels `action limit` and `maximum permissible limit` are commonly attached
  to these two numbers. The pages read here do not use those words for them, so
  they are not used here either."
  {:design-upper-limit-kgf 350.0
   :hazardous-above-kgf 650.0
   :design-upper-limit-n (* 350.0 segment/gravity)
   :hazardous-above-n (* 650.0 segment/gravity)
   :level "L5/S1"
   :citations
   [{:citation (str "NIOSH. Work Practices Guide for Manual Lifting. "
                    "DHHS (NIOSH) Publication No. 81-122, 1981, p.36.")
     :url "https://stacks.cdc.gov/view/cdc/209417/cdc_209417_DS1.pdf"
     :obtained :full-text
     :note "scanned; p.36 read as an image — the document has no text layer"}
    {:citation (str "Waters TR, Putz-Anderson V, Garg A, Fine LJ. Revised NIOSH "
                    "equation for the design and evaluation of manual lifting tasks. "
                    "Ergonomics 1993;36(7):749-776, Table 1 p.751 and section 3 p.753.")
     :url "https://stacks.cdc.gov/view/cdc/205542/cdc_205542_DS1.pdf"
     :obtained :full-text}]})

(defn niosh-compression-comparison
  "Place an L5/S1 force on the NIOSH design scale. Arithmetic, not a verdict.

  Returns the force, both published thresholds in newtons, and the ratio to each.
  `:above-design-upper-limit?` and `:above-hazardous?` are comparisons of two
  numbers; what to do about either is a question for somebody who is allowed to
  answer it, which is not this actor (G1)."
  [force-n]
  (let [{:keys [design-upper-limit-n hazardous-above-n]} niosh-1981-compression-criteria]
    {:force-n force-n
     :design-upper-limit-n design-upper-limit-n
     :hazardous-above-n hazardous-above-n
     :ratio-to-design-upper-limit (/ force-n design-upper-limit-n)
     :ratio-to-hazardous (/ force-n hazardous-above-n)
     :above-design-upper-limit? (> force-n design-upper-limit-n)
     :above-hazardous? (> force-n hazardous-above-n)
     :source niosh-1981-compression-criteria}))

(defn attachment-steps
  "Levels where a muscle's WHOLE contribution disappears between neighbours — the
  artefact of point attachments.

  A real muscle attaches over a RANGE of vertebrae, so its contribution tapers
  along the spine. This model attaches it at a point, so a level just past that
  point loses the whole force at once. Reporting which levels those are is the
  difference between a reader seeing a step and a reader believing a spine.

  THERE IS NO THRESHOLD HERE, AND THAT IS THE FIX. Until 2026-09-07 this asked
  whether `:muscle-n` was EXACTLY 0.0 at one level and positive at the one before.
  Passive tension made that unreachable — a stretched muscle contributes wherever
  it crosses — so the predicate stopped being able to fire on real data while its
  unit test went on exercising it against constructed rows that do reach zero.
  Measured 2026-09-07 at `laptop-on-lap`: the muscle term falls 482.86 N → 4.19 N
  between L2/L3 and L1/L2, a 99.1% step, and the old predicate returned `[]`. The
  README repeated the silence as a finding.

  Replacing `= 0.0` with `< some fraction` would have swapped an unreachable
  constant for an invented one. It is not needed: `crosses?` is all-or-nothing,
  because a point attachment is either above the level or below it. So the model
  already knows exactly which muscles stop crossing, and `:muscle-crossing`
  carries them. A step is the loss of a whole muscle — a fact, not a magnitude —
  and the newtons are reported alongside it so a reader can weigh it.

  ONLY WITHIN A REGION. This model has lumbar and cervical levels and nothing
  between them: L1/L2 and C7/T1 are adjacent in the vector and are not neighbours
  in a spine, and every muscle changes between them. Pairing them would report the
  model's missing thoracic levels as an attachment artefact, which is a different
  defect wearing this one's name.

  Each entry is `{:after :at :lost :lost-n :muscle-n-before :muscle-n-after}`;
  `:lost` names the instances and `:lost-n` is the force they were carrying at the
  lower level.

  REFUSES rows that do not carry `:muscle-crossing` rather than reporting no steps
  — a detector that cannot see its input must not return the same value as one
  that looked and found nothing."
  [profile-rows]
  (doseq [r profile-rows]
    (when-not (and (contains? r :muscle-crossing) (contains? r :region))
      (throw (ex-info (str "attachment-steps needs :muscle-crossing and :region on "
                           "every row; it cannot report the absence of steps in "
                           "rows it cannot read")
                      {:type :value-error :row r}))))
  (vec (for [[a b] (partition 2 1 profile-rows)
             :when (= (:region a) (:region b))
             :let [before (into {} (:muscle-crossing a))
                   after (set (map first (:muscle-crossing b)))
                   lost (vec (remove after (map first (:muscle-crossing a))))]
             :when (seq lost)]
         {:after (:name a)
          :at (:name b)
          :lost lost
          :lost-n (reduce + 0.0 (map before lost))
          :muscle-n-before (:muscle-n a)
          :muscle-n-after (:muscle-n b)})))

(defn peak
  "The level carrying the highest stress."
  [profile-rows]
  (when (seq profile-rows)
    (apply max-key :stress-mpa profile-rows)))
