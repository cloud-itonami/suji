(ns suji.methods.load
  "suji (筋) — static inverse dynamics: posture → joint moments + spinal load. 1:1 Clojure
  port of `src/suji/methods/load.cljc` (ADR-2606061900). Stdlib only.

  The bones half. Given the sagittal joint angles (posture) and the segment masses
  (segment), it solves the STATIC inverse-dynamics problem: the gravitational moment each
  joint must resist to hold the posture against gravity. This is the gravity term of the
  Featherstone RNEA reduced to statics — the closed-form static special case.

  EMPIRICAL ANCHOR (G7/G10): the cervical (neck) model reproduces Hansraj (2014) forward-
  head-posture loads: neutral ≈ head weight, rising to ~5× head weight at 60° flexion.

  NON-DIAGNOSTIC (G1, 医師法 §17): every output is a mechanical quantity — a moment (N·m),
  a muscle force (N), a compressive load (N / kg-force). None is a diagnosis.

  Numerics: sin/cos resolve on both hosts; degree conversion goes through
  suji.methods.math (Math/toRadians is JVM-only — see that ns).
  Records are kebab-keyword maps; joints kept as an ordered vector (Python list order)."
  (:require [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

;; --- Cervical lever model (Hansraj-calibrated) -------------------------------
(def head-com-lever-m 0.10)     ;; effective horizontal lever of head CoM at full flexion
(def cervical-ext-arm-m 0.02)   ;; cervical extensor moment arm

(defn head-tilt-from-vertical-deg
  "The head's tilt from VERTICAL at a placed posture — read off the chain rather
  than re-added here, so it cannot drift from `pose`.

  THIS IS THE ANGLE GRAVITY SEES, and it is the whole of the correction made on
  2026-09-07. Gravity is world-fixed; a joint angle is not. `head-flexion-deg` is
  the head's angle relative to the TRUNK, and `pose/solve-pose` places the head at
  `trunk-flexion + head-flexion` from vertical — so the head of a person bent 60°
  at the waist with the neck in line hangs just as far in front of C7 as the head
  of an upright person flexed 60°, and asks the same of the cervical extensors.

  `cervical-load` used to be handed `head-flexion-deg` directly, so it reported
  EXACTLY ZERO cervical load for the second of those two people. Every reference
  workstation in `posture` has non-zero trunk flexion, so every cervical number
  this actor has ever produced was understated.

  IT IS THE SAGITTAL TILT, NOT THE ANGLE BETWEEN THE HEAD AND THE VERTICAL. With
  lateral bend the two differ: measured at head 20° / trunk 10° / bend 30°, this
  returns 30.0° while the placed head's long axis is 41.4° off vertical. That is
  deliberate rather than an approximation left in — `cervical-load` is a sagittal
  model, and the out-of-plane component is reported separately as
  `frontal-moments`' `:cervical-nm` (−4.10 N·m at that posture) and carried by the
  scalenes in `muscle`'s `:cervical-lateral-flexion` equilibrium. Folding the bend
  into this angle would charge the same load to two equilibria.

  IT READS THE SKULL SINCE 2026-09-07, not the head-and-neck block, because there
  is no head-and-neck block any more — `pose` places three cervical segments. The
  NUMBER did not move: `pose/cervical-partition`'s coefficients sum to 1.0 and
  `cervical-chain` sets the skull's tilt to `trunk + head-flexion` outright rather
  than accumulating it, so this returns exactly what it returned before the neck
  had joints, and the Hansraj-calibrated load with it. What DID change is that the
  lower cervical segment is now tilted MORE than this and the skull is extended
  back on the atlas — which is the forward-head shape, and which this scalar
  deliberately does not see."
  [pose-data]
  (:tilt-deg (pose/seg-at pose-data "head")))

(defn cervical-load
  "Forward-head-posture cervical load. Reproduces Hansraj (2014) (G7 anchor).

  `head-tilt-deg` is the head's tilt from VERTICAL, not its angle at the neck —
  see `head-tilt-from-vertical-deg`, which is what `solve-posture-loads` passes.
  The two are the same number when the trunk is upright, which is the condition
  Hansraj's table was measured under and therefore the only line along which this
  model is validated: the anchor constrains it at `trunk = 0` and says nothing
  about a leaning trunk. What responds to trunk flexion now is the ANGLE; the
  lever and the extensor arm are unchanged, so `test-reproduces-hansraj-table`
  reproduces to the last bit.

  THE LEVER IS FITTED, NOT GEOMETRIC. `head-com-lever-m` is 0.10 m — Hansraj's
  effective lever — where the placed head's own CoM sits 0.170 m along the
  cervical chain from C7. Measured 2026-09-07 at 60° of tilt, this model
  returns 4.815 N·m against the pose-derived 8.194 N·m. That gap is the
  calibration, it is not a bug, and closing it would break the one validated
  quantity in this library; `spine/cervical-cross-check` computes the same
  disagreement from the other end rather than hiding it."
  ([head-tilt-deg head-weight-n]
   (cervical-load head-tilt-deg head-weight-n head-com-lever-m cervical-ext-arm-m))
  ([head-tilt-deg head-weight-n head-com-lever-m extensor-arm-m]
   (when (<= head-weight-n 0)
     (throw (ex-info "head_weight_n must be positive" {:type :value-error})))
   (when (<= extensor-arm-m 0)
     (throw (ex-info "extensor_arm_m must be positive" {:type :value-error})))
   (let [theta (math/radians head-tilt-deg)
         rho (/ head-com-lever-m extensor-arm-m)
         moment (* head-weight-n head-com-lever-m (Math/sin theta))
         ext-force (/ moment extensor-arm-m)
         compressive (* head-weight-n (+ (* rho (Math/sin theta)) (Math/cos theta)))]
     ;; `:head-tilt-deg` and NOT `:head-flexion-deg`, which is what this key was
     ;; called until 2026-09-07. The rename is the point: a consumer that printed
     ;; it under the heading "head flexion" was printing a different quantity as
     ;; soon as the trunk leaned, and a silently-renamed key would have let
     ;; `analyze`'s report go on saying 44° for a head the model now tilts 63.5°.
     {:head-tilt-deg head-tilt-deg
      :head-weight-n head-weight-n
      :extensor-moment-nm moment
      :extensor-force-n ext-force
      :compressive-load-n compressive
      :compressive-load-kgf (/ compressive segment/gravity)
      :multiplier-vs-head (/ compressive head-weight-n)})))

(defn cervical-load-sensitivity
  "Marginal cervical compressive load per degree of forward head flexion AT a given posture — the
  local slope d(compressive-load)/d(flexion) of the Hansraj forward-head-posture model, by central
  finite difference. A purely MECHANICAL quantity (N of compressive load per additional degree;
  G1 non-diagnostic — no clinical key, never a prescription) and self-referenced to the SAME posture
  (G3 — the model's local derivative here, never a population rank). It makes a small posture
  change's MODELLED load effect legible: because the load curve is concave, the first degrees off
  neutral cost the most per degree. Returns {:head-flexion-deg :compressive-load-n :d-load-per-deg-n}.

  THE ANGLE IS THE HEAD'S TILT FROM VERTICAL, as it is for `cervical-load` (see
  `head-tilt-from-vertical-deg`). The SLOPE is unaffected by the 2026-09-07
  correction — tilt = trunk + head, so at a fixed trunk angle a degree of head
  flexion is a degree of tilt and d(load)/d(head-flexion) is unchanged — but WHERE
  on the curve a given posture sits is not: a leaning trunk has already spent part
  of the concave range, so the marginal cost of the next degree of head flexion is
  lower than this lens reported before. The key is still `:head-flexion-deg`
  because at a fixed trunk that IS what the derivative is taken with respect to."
  ([head-flexion-deg head-weight-n] (cervical-load-sensitivity head-flexion-deg head-weight-n 0.5))
  ([head-flexion-deg head-weight-n delta-deg]
   (let [load-at (fn [a] (:compressive-load-n (cervical-load a head-weight-n)))]
     {:head-flexion-deg head-flexion-deg
      :compressive-load-n (load-at head-flexion-deg)
      :d-load-per-deg-n (/ (- (load-at (+ head-flexion-deg delta-deg))
                              (load-at (- head-flexion-deg delta-deg)))
                           (* 2.0 delta-deg))})))


;; --- what the desk carries ---------------------------------------------------
;;
;; ONE PLACE DECIDES, and this is it. `:arms-supported` used to be read at five
;; call sites in this namespace with the answer written out by hand at each, and
;; not at all in `frontal-moments` or in `spine/above-fraction`. Five copies of a
;; two-element list is five places for the answer to drift, and it had already
;; drifted: measured 2026-09-07 at shoulder 20 deg / elbow 90 deg / abduction
;; 40 deg on a 70 kg / 1.70 m body, the SAGITTAL shoulder moment moved 9.9226 ->
;; 1.8126 N*m when the forearms were rested, while the FRONTAL shoulder moment
;; (-3.9184 N*m) and the L5/S1 weight-above (367.9455 N) were byte-identical
;; supported and unsupported. In the frontal plane and in the spine profile, a
;; forearm resting on a desk was still hanging in mid-air.

(def desk-borne-bases
  "The anthropometric segments a desk takes when the forearms rest on it.

  It is the forearm and the hand, and it is not the upper arm: a forearm lying on
  a desk still hangs off the shoulder girdle at its proximal end, and the upper
  arm above it is carried by the body in both support states. That is why
  `arms-supported` lowers the shoulder moment without abolishing it, and why the
  girdle still has something to suspend (`muscle/suspended-weight-n`)."
  #{"forearm" "hand"})

(defn body-carries?
  "Does the BODY still carry this anthropometric segment in this posture, or has
  the desk taken it?

  THE ONE QUESTION `:arms-supported` ASKS, so that every equilibrium in this model
  asks it of the same function. `arm-moment-about`, `elbow-moment`,
  `wrist-moment`, `lumbar-borne-bases` and `frontal-moments` all route through
  here, and `spine`'s `above-fraction` — which decides what loads each trunk
  level — is written to call it too.

  THE SUPPORTED CASE IS AN IDEALISATION, and it is the same one everywhere
  because it is stated once. The desk's upward reaction is taken to act AT the
  forearm and hand centres of mass, so those segments drop out of the free body
  entirely rather than leaving the small residual couple a real forearm resting on
  its ulnar border at one point leaves. It is the bound in which the desk takes
  everything it can; the true answer is between it and the unsupported one.

  `base` is the anthropometric segment name (`pose`'s `:base`), not the placed
  segment's unique `:name` — a bilateral pose has two `upper_arm`s and the desk
  does not distinguish them."
  [posture base]
  (not (and (:arms-supported posture) (contains? desk-borne-bases base))))

(defn carried-bases
  "Those of `bases` the body still carries in this posture, in the order given."
  [posture bases]
  (filterv #(body-carries? posture %) bases))

(def arm-bases
  "The arm, proximal to distal — everything hanging off one shoulder girdle."
  ["upper_arm" "forearm" "hand"])

;; --- Generic static joint moment (RNEA gravity term) -------------------------
(defn- ->joint-load
  ([joint moment-nm] (->joint-load joint moment-nm "" nil))
  ([joint moment-nm note] (->joint-load joint moment-nm note nil))
  ([joint moment-nm note per-side]
   (cond-> {:joint joint :moment-nm moment-nm :note note}
     per-side (assoc :per-side per-side))))

(defn- arm-moment-about
  "Sagittal moment about one shoulder from the arm segments the BODY is carrying —
  which is the whole arm, or the upper arm alone when the forearms rest on a desk.
  Which it is, is `body-carries?`'s answer and not a second copy of it."
  [body p side posture]
  (let [w (pose/segment-weights body p)
        carried (carried-bases posture arm-bases)
        joint (get-in p [:joints (keyword "shoulder" (name side))])]
    (pose/gravitational-moment
     joint
     (for [seg (pose/segments-on p carried side)] [seg (get w (:name seg))]))))

(defn shoulder-moment
  "Gravitational moment about the glenohumeral joints from the held-out arm(s).

  BILATERAL since 2026-09-06: the two sides are computed and SUMMED, where this
  used to compute one and multiply by two. Those agree exactly for a symmetric
  posture and disagree for every asymmetric one — and lateral bend is asymmetric
  by definition, so doubling one side could not represent it.

  GEOMETRY CORRECTION (2026-09-06, earlier the same day). This used to place the
  forearm and hand at `(90° − elbow-flexion)` from vertical, which is inverted at
  both ends of the range: a straight arm came out HORIZONTAL and the 90° elbow of
  a typing posture came out VERTICAL. The chain is placed by `pose/solve-pose` and
  the moment read off it as Σ weight × anterior lever, which is the definition of
  the RNEA gravity term rather than a re-derivation of it."
  ([body shoulder-flexion-deg elbow-flexion-deg arms-supported]
   (shoulder-moment body {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                          :shoulder-flexion-deg shoulder-flexion-deg
                          :elbow-flexion-deg elbow-flexion-deg
                          :arms-supported arms-supported}
                    :both))
  ([body posture _mode]
   (let [p (pose/solve-pose body posture)
         sup (:arms-supported posture)
         per-side (into {} (for [side [:left :right]]
                             [side (arm-moment-about body p side posture)]))]
     (->joint-load "shoulder" (+ (:left per-side) (:right per-side))
                   (if sup "forearms supported" "arms unsupported (hanging)")
                   per-side))))

(defn elbow-moment
  "Gravitational moment about each elbow from the forearm and hand hanging off it.

  The chain has placed an elbow since `pose` existed and nothing took a moment
  about it: the forearm and hand were carried by the shoulder's equilibrium and by
  nothing else, as though the elbow were welded. A typing posture holds them out
  at 90° all day, so this is not a small term — it is the one a desk worker is
  actually asking about.

  Resting the forearms transfers them to the desk, and then the elbow carries
  nothing, which is the whole of the `arms-supported` effect here."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        carried (carried-bases posture ["forearm" "hand"])]
    (into {}
          (for [side [:left :right]]
            [side (pose/gravitational-moment
                   (get-in p [:joints (keyword "elbow" (name side))])
                   (for [seg (pose/segments-on p carried side)]
                     [seg (get w (:name seg))]))]))))

(defn wrist-moment
  "Gravitational moment about each wrist from the hand.

  The last joint this chain placed and did not solve. The hand is small — about
  0.6% of body mass — but a keyboard posture holds it out horizontally for hours,
  and the wrist extensors holding it there are the muscles a typist complains
  about. Resting the forearms rests the hands with them.

  THE HIP USED TO BE EXCLUDED HERE, and this paragraph used to explain why: there
  was no thigh segment, so there was nothing for a hip moment to be a moment of.
  That was true and it is no longer — see `lower-limb-loads`. The exclusion was
  never about the hip being uninteresting; it was about an absent segment, which is
  exactly the kind of gap that gets read as a decision if nobody comes back."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        carried (carried-bases posture ["hand"])]
    (into {}
          (for [side [:left :right]]
            [side (pose/gravitational-moment
                   (get-in p [:joints (keyword "wrist" (name side))])
                   (for [seg (pose/segments-on p carried side)]
                     [seg (get w (:name seg))]))]))))

(def trunk-borne-bases
  "Everything above L5/S1 that the lumbar spine has to hold up, before the desk is
  asked what it is taking: the trunk above the level, the head on top of it, and
  both arms — which hang from the shoulder girdle, and the girdle is carried by
  the thorax, so every gram of arm reaches the ground through the lumbar spine.

  `head_neck` became three segments on 2026-09-07 and all three are here: the
  lumbar spine carries the whole complex above C7 exactly as it carried the block."
  (into (into ["thorax_abdomen"] segment/cervical-bases) arm-bases))

(defn lumbar-borne-bases
  "What the lumbar spine carries at L5/S1 in this posture.

  IT WAS A MAP KEYED BY SUPPORT STATE until 2026-09-07, which is to say it was a
  second, hand-written copy of the answer `body-carries?` gives — and a copy is
  where two paths drift apart. It is derived now, so the desk cannot take the
  forearm out of this moment and leave it in the frontal one.

  The idealisation, and the fact that the upper arm still hangs from the girdle
  either way, are stated once in `body-carries?` and hold here unchanged."
  [posture]
  (carried-bases posture trunk-borne-bases))

(defn lumbosacral-moment
  "Gravitational moment about L5/S1 from everything the lumbar spine carries: the
  leaned trunk, the head above it, and both arms hanging off the girdle.

  READ OFF THE PLACED CHAIN, since 2026-09-07. It used to be hand algebra that
  predated `pose`, and it was wrong in two ways that a lever formula written per
  segment is exactly the way to be wrong:

    - THE HEAD HAD NO LEVER OF ITS OWN. Its weight was placed AT C7 —
      `L_thorax × sin(trunk)` — so a head flexed on an upright trunk contributed
      nothing at all. Measured on a 70 kg / 1.70 m body: trunk 0°, head 45° gave
      0.000 N·m against the pose-derived 6.691 N·m. Zero, for a head whose centre
      of mass is 12 cm in front of the joint.
    - THERE WAS NO ARM TERM, and it never read `:arms-supported`, although its own
      docstring said `head-arm load`. Two arms are 10% of body mass hanging from a
      girdle the lumbar spine holds up.

  Measured before and after, same body, `[head/trunk/shoulder/elbow]`:

      posture             was       now      thorax    head     arms
      0/0/0/0             0.000     0.000     0.000    0.000    0.000
      45/0/0/0            0.000     6.691     0.000    6.691    0.000
      0/60/0/0           75.240   112.541    51.664   31.771   29.107
      43.5/20/15/90      29.715    58.500    20.404   17.779   20.317

  The last row is `laptop-on-lap`, the posture this actor exists to describe: the
  understatement was 49%. This moment is the ENTIRE load of the `:trunk-extension`
  equilibrium, so it set erector spinae %MVC and, through `spine`, every lumbar
  compression this model has ever reported."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        supported (boolean (:arms-supported posture))
        bases (lumbar-borne-bases posture)
        m (pose/gravitational-moment
           (get-in p [:joints :l5s1])
           (for [seg (pose/segments-on p bases)] [seg (get w (:name seg))]))]
    (->joint-load "lumbosacral" m
                  (if supported
                    "trunk + head + upper arms (forearms rest on the desk)"
                    "trunk + head + both arms"))))

(defn frontal-moments
  "The FRONTAL-plane (about X) gravitational moments this posture creates.

  Zero for a symmetric posture — the two arms hang at ±half the biacromial breadth
  from the midline and their moments about the spine cancel exactly — and non-zero
  as soon as the trunk bends laterally or the arms abduct unequally. Before the
  model was bilateral it placed ONE arm and doubled it, so there was nothing for
  that arm's frontal moment to cancel against and this number was an artefact of
  the modelling rather than of the posture.

  `muscle/solve-muscle-tensions` CARRIES these now — `:shoulder-per-side` is the
  load of its `:shoulder-abduction` task and `:lumbosacral-nm` and `:cervical-nm`
  are the loads of its two lateral-flexion tasks — where until 2026-09-06 there
  was no frontal-plane musculature at all and this docstring said they were
  reported and not solved. What `muscle/tension-summary` still refuses to call
  complete is a REFUSAL, which is a different thing: a muscle whose leverage this
  model cannot resolve at this posture."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        frontal (fn [joint-point segs]
                  (nth (pose/gravitational-moment-vec
                        joint-point (for [seg segs] [seg (get w (:name seg))]))
                       0))
        ;; THE DESK REACHES THE FRONTAL PLANE TOO, since 2026-09-07. This line
        ;; used to be the literal `["upper_arm" "forearm" "hand"]`, the one place
        ;; in this namespace that never read `:arms-supported` at all, and the
        ;; consequence was that `arms-supported` moved the sagittal shoulder
        ;; moment from 9.9226 to 1.8126 N*m while leaving the frontal one at
        ;; -3.9184 N*m to the last bit. A forearm cannot rest on a desk in one
        ;; plane and hang in mid-air in the other; it is the same forearm and the
        ;; same desk. `middle_deltoid`, which carries this moment in
        ;; `muscle/solve-muscle-tensions`, therefore reported the identical %MVC
        ;; supported and unsupported — the flag reached the abduction equilibrium
        ;; nowhere at all.
        carried-arm-bases (carried-bases posture arm-bases)]
    {;; PER SIDE, not summed. The two shoulders are different joints with
     ;; different muscles; a symmetric abduction gives each of them a real frontal
     ;; moment, and adding them cancels to zero and reports that abducting both
     ;; arms asks nothing of either shoulder. The lumbosacral term IS a sum,
     ;; because there is one spine and the two arms load it about the same axis.
     :shoulder-per-side (into {} (for [side [:left :right]]
                                   [side (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                  (pose/segments-on p carried-arm-bases side))]))
     :shoulder-nm (reduce max 0.0
                          (for [side [:left :right]]
                            (math/abs* (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                (pose/segments-on p carried-arm-bases side)))))
     ;; the whole complex above C7, which is three segments since the neck was
     ;; split; laterally bent they are still collinear, because `pose` gives all
     ;; three the same lateral-bend angle — the model has no cervical side-bending
     ;; rhythm and does not pretend to.
     :cervical-nm (frontal (get-in p [:joints :c7])
                           (pose/segments-on p segment/cervical-bases))
     ;; the same list `lumbosacral-moment` uses, from the same function: what the
     ;; lumbar spine is holding up does not depend on which plane you ask about it in
     :lumbosacral-nm (frontal (get-in p [:joints :l5s1])
                              (pose/segments-on p (lumbar-borne-bases posture)))}))

(def lower-limb-chain
  "Each lower-limb joint, the segments DISTAL to it, and the name it is reported
  under. Proximal to distal.

  The list is the same for standing and for sitting, which is the point: the two
  support modes do not disagree about what the leg is made of. They disagree about
  whether the floor is pushing on the end of it."
  [{:joint :hip   :name "hip"   :distal ["thigh" "shank" "foot"]}
   {:joint :knee  :name "knee"  :distal ["shank" "foot"]}
   {:joint :ankle :name "ankle" :distal ["foot"]}])

(defn- lower-limb-forces
  "Every external force on the free body distal to one lower-limb joint, as
  `[point force-vector]` pairs — the segment weights, plus the ground reaction when
  the body is standing on that foot."
  [body pose-data posture w joint-spec side]
  (let [standing (posture/standing? posture)
        seated-hip-is-empty (and (= :hip (:joint joint-spec))
                                 (posture/thigh-supported? posture))
        weights (when-not seated-hip-is-empty
                  (for [seg (pose/segments-on pose-data (:distal joint-spec) side)]
                    [(:com seg) [0.0 (- (get w (:name seg))) 0.0]]))
        cop (when (and standing (not seated-hip-is-empty))
              (pose/centre-of-pressure body pose-data side))]
    (cond-> (vec weights)
      cop (conj [cop [0.0 (* 0.5 (:weight-n (pose/whole-body-com body pose-data))) 0.0]]))))

(defn lower-limb-loads
  "Hip, knee and ankle: the moment each has to hold and the axial force each has to
  transmit, per side.

  THE SUPPORT MODE IS THE WHOLE ANSWER. `posture/support-mode` explains why in
  words; here is what it costs. The free body distal to each joint holds the same
  segments in both modes. Standing adds ONE force — the ground reaction, half the
  body's weight, pushing up at the centre of pressure — and that force is about
  thirty times the weight of the foot it acts on. Every difference between the
  statics of standing and the statics of sitting in this model is that one term,
  which is why it is added by a `cond->` rather than by a second code path: a
  second code path is a place for the two to drift.

  WHERE THE GROUND REACTION ACTS is not a parameter of this function. Static
  equilibrium puts the centre of pressure under the line of gravity, and
  `pose/centre-of-pressure` computes it there. The consequence is that a
  perfectly-stacked standing posture reports almost no ankle moment (true — a body
  balanced over its ankles asks nothing of its calves) and a posture that leans
  reports one proportional to the lean (also true, and it is what makes soleus work
  in quiet standing).

  WHAT IT DOES NOT DO. It does not refuse a posture whose centre of pressure falls
  outside the feet; it reports `:cop-inside-base?` and leaves the judgement to the
  consumer, because a body outside its base of support is a real thing — it is a
  step, or a fall — and a static model can describe the instant without claiming
  the body can hold it. It splits the ground reaction equally between the two feet,
  so single-leg stance is out of range. And it solves the SAGITTAL plane only;
  `:frontal-per-side` is reported and no muscle in this model carries it, exactly
  as the upper limb's frontal moments were reported before muscles for them
  existed.

  Returns
  `{:joints [{:joint :moment-nm :per-side :frontal-per-side :supported-weight-n}…]
    :support {…}}`."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        {com-point :point body-weight :weight-n} (pose/whole-body-com body p)
        base (pose/base-of-support p)
        standing (posture/standing? posture)
        per-joint
        (fn [spec]
          (let [f (into {} (for [side [:left :right]]
                             [side (lower-limb-forces body p posture w spec side)]))
                vecs (into {} (for [side [:left :right]]
                                [side (pose/external-moment-vec
                                       (get-in p [:joints (keyword (name (:joint spec))
                                                                   (name side))])
                                       (get f side))]))]
            {:name (name (:joint spec))
             :moment-nm (+ (nth (:left vecs) 2) (nth (:right vecs) 2))
             :per-side (into {} (for [side [:left :right]] [side (nth (side vecs) 2)]))
             :frontal-per-side (into {} (for [side [:left :right]] [side (nth (side vecs) 0)]))
             ;; the axial force the joint transmits: the joint reaction balances the
             ;; net vertical external force on the free body below it
             :supported-weight-n
             (into {} (for [side [:left :right]]
                        [side (math/abs* (reduce + 0.0 (map #(second (second %))
                                                            (get f side))))]))}))]
    {:joints (mapv per-joint lower-limb-chain)
     :support {:mode (posture/support-mode posture)
               :thigh-supported (posture/thigh-supported? posture)
               :body-weight-n body-weight
               :com-x com-point
               :ground-reaction-per-foot-n (if standing (* 0.5 body-weight) 0.0)
               :base-of-support base
               :cop-inside-base?
               (when (and standing base)
                 (<= (:back base) (first com-point) (:front base)))}}))

(defn atlanto-occipital-moment
  "The static moment about the occipital condyles: the skull, about its own joint.

  IT DID NOT EXIST BEFORE 2026-09-07 AND IT COULD NOT HAVE. The joint did not
  exist: `head_neck` ran from C7 to the vertex as one body, so there was no free
  body consisting of the head alone and nothing to take a moment about. That is
  why this actor had no suboccipital muscle — not because they were judged small
  (Kamibayashi & Richmond measure them at 3.75 cm² per side) but because there was
  nowhere to put them.

  WHAT IT IS NOT. It is not the cervical load and it is not a share of it. The
  cervical load is the whole complex above C7 about C7, calibrated to Hansraj; this
  is the SKULL about the condyles, computed from the placed chain with no fitted
  lever anywhere in it. The two answer different questions and this one is much the
  smaller — the skull is 80% of the complex but its lever about the condyles is a
  third of the complex's lever about C7.

  WHAT IT USED TO CARRY, AND WHY IT DOES NOT ANY MORE. Until 2026-09-08 this
  function took a third argument, the forces of the two capitis muscles, and
  returned a DECOMPOSITION: `:capitis-nm`, `:residual-nm`, `:over-supplied-nm`,
  and a split of the last into a gravitational half and a decomposition half.
  All of that existed for one reason — semispinalis capitis and splenius capitis
  run from the thorax to the occiput, so they pull on this joint as well as on the
  cervicothoracic junction where they were solved, and `recruit`'s closed form
  takes ONE constraint, so a muscle had to belong to one task. Handing the
  suboccipitals the whole gravitational moment would have charged them for work
  those two were already doing; handing them the leftover charged them for
  nothing, because the leftover was usually negative. At `laptop-on-lap` on a
  70 kg / 1.70 m body the demand was 2.648 N·m and the two capitis muscles were
  exerting 6.223 — 2.35 times it — and absorbing the 3.575 N·m surplus would have
  cost 1.85 times every newton the modelled upper cervical flexors can produce.

  `recruit/solve` satisfies this joint and the cervicothoracic junction at the
  same time (see `muscle/coupled-groups`), so there is no leftover to hand
  anybody: the forces that balance one balance the other, by construction. What
  this function returns is the gravitational moment, which was always the honest
  quantity — it is computed from the placed chain with no muscle force in it, so
  it is free of every decomposition this model makes — and the muscle side of the
  equilibrium is the solve's business. `muscle/tension-summary` reports the
  residual as `:coupled-residual-nm`, which is zero to floating point.

  A NEGATIVE MOMENT IS A FLEXION DEMAND and not an error: it means the skull's
  centre of mass sits BEHIND the occipital condyles, which is a head tipped back —
  looking up, or held against a headrest — and there the joint's flexors are the
  ones with work to do. The coupled solve reads that off the sign; nothing has to
  split it into two tasks any more."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        joint (get-in p [:joints :atlanto-occipital])
        skull (pose/segments-on p ["head"])
        m (pose/gravitational-moment joint (for [s skull] [s (get w (:name s))]))]
    {:joint "atlanto-occipital"
     :moment-nm m
     :skull-weight-n (reduce + 0.0 (map #(get w (:name %)) skull))
     :note "the skull about the occipital condyles; no fitted lever"}))

(defn solve-posture-loads
  "Full static inverse-dynamics solve for a posture (the RNEA gravity term).

  `:frontal` carries the frontal-plane moments, which no muscle in this model
  carries — see `frontal-moments`."
  [body posture]
  (let [p (pose/solve-pose body posture)
        head-w (* (segment/head-mass-kg (:total-mass-kg body)) segment/gravity)
        ;; the head's tilt from VERTICAL, not its angle at the neck. Passing
        ;; `(:head-flexion-deg posture)` here — which is what this line did until
        ;; 2026-09-07 — told the cervical model that a person bent 60° at the waist
        ;; with the neck in line was holding their head straight up.
        cerv (cervical-load (head-tilt-from-vertical-deg p) head-w)
        ao (atlanto-occipital-moment body posture)
        joints [(->joint-load "cervicothoracic" (:extensor-moment-nm cerv)
                              "cervical extensor moment")
                (->joint-load "atlanto-occipital" (:moment-nm ao)
                              "the skull about the occipital condyles")
                (shoulder-moment body posture :both)
                (let [per-side (elbow-moment body posture)]
                  (->joint-load "elbow" (+ (:left per-side) (:right per-side))
                                (if (:arms-supported posture)
                                  "forearms rest on the desk"
                                  "forearm + hand held out")
                                per-side))
                (let [per-side (wrist-moment body posture)]
                  (->joint-load "wrist" (+ (:left per-side) (:right per-side))
                                (if (:arms-supported posture)
                                  "hands rest with the forearms"
                                  "hand held out")
                                per-side))
                (lumbosacral-moment body posture)]
        lower (lower-limb-loads body posture)
        ;; the lower-limb entries carry two keys the upper-limb ones do not
        ;; (`:supported-weight-n` and `:frontal-per-side`) and are otherwise the
        ;; same shape, so a consumer that walks `:joints` reading `:joint` and
        ;; `:moment-nm` — which is every consumer this actor has — needs no special
        ;; case for them. That was the requirement.
        lower-joints (mapv (fn [j]
                             (-> j
                                 (assoc :joint (:name j)
                                        :note (if (= :standing (get-in lower [:support :mode]))
                                                "standing: the ground reaction under this foot"
                                                "seated: only what hangs below this joint"))
                                 (dissoc :name)))
                           (:joints lower))]
    {:cervical cerv
     :atlanto-occipital ao
     :joints (into (vec joints) lower-joints)
     :frontal (frontal-moments body posture)
     :support (:support lower)}))
