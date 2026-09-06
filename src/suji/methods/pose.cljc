(ns suji.methods.pose
  "suji (筋) — forward kinematics: sagittal joint angles → world-space segment
  placement. Pure `.cljc`, stdlib only, no I/O, no rendering.

  WHY IT IS SEPARATE FROM `load`. Until 2026-09-06 the geometry of the chain lived
  inside the moment formulas as hand-written lever algebra — `length × com-frac ×
  sin(flexion)` per segment, with each joint's chain re-derived at the call site.
  That is fine while there is one chain and one consumer, and it went wrong the way
  duplicated geometry always goes wrong: the forearm was placed with
  `(- 90.0 elbow-flexion)`, which puts a 90°-flexed elbow's forearm VERTICAL, hanging
  straight down from the elbow, instead of reaching forward over a keyboard. The
  forearm and hand then contributed no lever of their own to the shoulder, so a
  typing posture's shoulder moment was under-stated. See `shoulder-moment`.

  Placing the chain once, in world space, removes the class: a moment is then
  Σ weight × (x_com − x_joint), which is the definition rather than a re-derivation
  of it. The same placement is what a renderer needs, so the picture and the physics
  cannot drift apart — they are the same data.

  FRAME. Right-handed, metres, origin at L5/S1:

      +X  anterior (the direction the person faces)
      +Y  superior (up)
      +Z  to the person's left

  The sagittal plane is XY. Flexion is a rotation about Z, abduction and lateral
  bend are rotations about X, and axial rotation is about the segment's own long
  axis. A segment is stored with both endpoints, so a consumer never re-derives one
  from an angle; `:euler-z` is handed to renderers that want a sagittal transform.

  EVERY SEGMENT CARRIES A FRAME, not just a direction (2026-09-06). A direction is
  enough to place a rod and hang a mass on it; it is NOT enough to place a muscle,
  because a muscle attaches at a point offset from the bone's axis and the size of
  that offset in the plane of the joint IS the moment arm. `:frame` is an
  orthonormal basis `{:long :ant :lat}` in the segment's own terms, so an
  attachment is stated once in local coordinates and lands correctly at every
  posture rather than being re-tabulated per angle.

  Out-of-plane input is optional and defaults to zero, so a purely sagittal posture
  places exactly as it did before frames existed.

  NON-DIAGNOSTIC (G1): a coordinate is a coordinate. Nothing here is a finding.
  REPRESENTATIVE (G7): lengths come from `segment`, i.e. Winter/Drillis regressions
  on stature — a population average, not a scan of anybody."
  (:require [suji.methods.math :as math]
            [suji.methods.segment :as segment]))

(defn- dir-from-vertical
  "Unit direction of a segment tilted `deg` forward (+X) from vertical.
  `up?` true → the segment rises from its proximal joint (trunk, head);
  false → it descends (arm chain)."
  [deg up?]
  (let [t (math/radians deg)
        s (Math/sin t)
        c (Math/cos t)]
    [s (if up? c (- c)) 0.0]))

(defn- rotate-frame
  "Apply flexion (about Z), abduction/lateral bend (about X) and axial rotation
  (about the segment's own long axis, after the first two) to a base frame.

  Order is fixed and stated rather than inferred: flexion, then abduction, then
  axial. Euler angles do not commute, so an unstated order is an unstated model."
  [{:keys [long ant lat]} flex-deg abduct-deg axial-deg z-sign]
  (let [rz (fn [v] (math/rot-z v (* z-sign flex-deg)))
        rx (fn [v] (math/rot-x v abduct-deg))
        [long' ant' lat'] (map (comp rx rz) [long ant lat])
        ;; axial rotation is about the segment's OWN long axis, which only exists
        ;; after the first two rotations have placed it
        axial (fn [v] (if (zero? axial-deg)
                        v
                        (let [t (math/radians axial-deg)
                              c (Math/cos t) s (Math/sin t)
                              k long']
                          ;; Rodrigues about k
                          (math/v+ (math/v+ (math/v* v c)
                                            (math/v* (math/vcross k v) s))
                                   (math/v* k (* (math/vdot k v) (- 1.0 c)))))))]
    {:long long' :ant (axial ant') :lat (axial lat')}))

(defn segment-frame
  "Orthonormal frame for a segment.

  `up?` says whether the segment rises from its proximal joint (trunk, head) or
  descends from it (the arm chain); that flips the sense in which a forward
  flexion rotates the long axis, which is geometry rather than convention —
  rotating about +Z carries +Y toward −X and −Y toward +X."
  [flex-deg abduct-deg axial-deg up?]
  (let [base {:long (if up? [0.0 1.0 0.0] [0.0 -1.0 0.0])
              :ant [1.0 0.0 0.0]
              :lat [0.0 0.0 1.0]}]
    (rotate-frame base flex-deg abduct-deg axial-deg (if up? -1.0 1.0))))

(defn placed-name
  "The unique name of a placed segment. A midline segment keeps its anthropometric
  name; a paired one is suffixed with its side, because a bilateral model has two
  of them and `seg-at` has to be able to say which."
  [base side]
  (if (= :midline side) base (str base "/" (name side))))

(defn- place
  "One placed segment: proximal point, frame, length → the record every consumer
  reads. `tilt-deg` is kept so a renderer can build a sagittal transform without
  inverting the direction, and so a test can state the intent it is checking.

  `:base` is the anthropometric segment this was built from and `:name` is unique
  within the pose; `segment-weights` reads `:base`, everything that has to talk
  about one particular arm reads `:name`."
  [base side proximal frame length-m com-frac tilt-deg up?]
  (let [name (placed-name base side)
        dir (:long frame)
        distal (math/v+ proximal (math/v* dir length-m))
        com (math/v+ proximal (math/v* dir (* com-frac length-m)))]
    {:name name
     :base base
     :side side
     :proximal proximal
     :distal distal
     :com com
     :dir dir
     :frame frame
     :length-m length-m
     :com-frac com-frac
     :tilt-deg tilt-deg
     ;; Rotation about −Z takes +Y (or −Y) onto `dir`; a renderer applies this to a
     ;; unit cylinder aligned with its own up axis. Sagittal only — a renderer that
     ;; needs the out-of-plane placement reads `:frame` or the endpoints.
     :euler-z (math/radians (if up? (- tilt-deg) (- 180.0 tilt-deg)))}))

(defn- hangs-from
  "Say which placed segment this one hangs from, and WHERE along that segment it
  hangs — `{:segment name :along fraction}`, or nil for the one segment that is
  the root of the chain.

  ADDED 2026-09-07, and it is a pure addition: nothing that read a placed segment
  before reads `:attaches-to`, and no value that was already there changed. It
  exists because `solve-pose` has always KNOWN this — it threads `c7` into
  the cervical chain and into each `arm-chain`, and `pelvis-seg`'s distal into each
  `leg-chain` — and then threw the fact away, leaving every consumer to guess the
  skeleton's shape from world coordinates. `spine/crosses?` guessed it from
  HEIGHT, and so counted a wrist extensor as loading somebody's neck.

  `:along` is a fraction of the PARENT's length from the parent's proximal joint,
  the same convention `attachment` states its sites in, so the two compose without
  a conversion. The lateral offsets that put a glenohumeral joint half a
  biacromial breadth from the midline, or a femoral head half an interhip breadth
  from it, are deliberately NOT represented here: this says which bone carries
  which, not where the joint centre sits, and `:proximal` already says the latter
  exactly."
  [seg parent-name along]
  (assoc seg :attaches-to (when parent-name {:segment parent-name :along along})))

(def atlanto-occipital-rom-deg
  "Sagittal range of the atlanto-occipital joint, in degrees — 14.5.

  MEASURED, and it is the only sourced number in the partition below. Bogduk N,
  Mercer S, `Biomechanics of the cervical spine. I: Normal kinematics`, Clinical
  Biomechanics 15(9):633-648, 2000: \"Most studies agree that the average range of
  motion is 14-15 deg (Table 1)\" (p.639). Full text read 2026-09-07 from
  https://squareonephysio.com.au/wp-content/uploads/2021/08/Bogduk-2000-Biomechanics-Cervical-Spine.pdf
  — not an abstract. Table 1 collects Brocher 14.3 (range 0-25), Lewit & Krausova
  15, Markuske 14.5, Lind et al. 14 (SD 15), Kottke & Mundale (range 0-22), and
  Fielding 35, which Bogduk calls \"distinctly out of character\" and which is
  excluded here for that reason and no other. The variance is enormous — Lind's
  coefficient of variation exceeds 100% — so this is a population centre, not a
  bound on anybody."
  14.5)

(def lower-cervical-rom-deg
  "Sagittal range of C3/C4 through C6/C7 added up — the joints INSIDE this model's
  `lower_cervical` segment plus the one at its base — in degrees.

  Bogduk & Mercer 2000 Table 5, the Dvorak et al. column (N=28, the study Bogduk
  says is one of only two with stated observer error): C3-4 15 (SD 3), C4-5 19
  (SD 4), C5-6 20 (SD 4), C6-7 19 (SD 4). C7/T1 is not in the table, so this sum
  omits it and is therefore an UNDERSTATEMENT of what the model's C7 joint stands
  for — which biases the partition slightly toward the C2/C3 joint."
  73.0)

(def c2c3-rom-deg
  "Sagittal range of the C2/C3 disc, in degrees. Bogduk & Mercer 2000 Table 5,
  Dvorak et al.: 10 (SD 3) — the smallest of the lower cervical segments."
  10.0)

(def cervical-partition
  "How ONE posture input, `:head-flexion-deg`, becomes THREE joint angles.

  THE INPUT KEEPS ITS MEANING EXACTLY. `:head-flexion-deg` has always been the
  angle of the head relative to the trunk, and `solve-pose` has always placed the
  head at `trunk-flexion + head-flexion` from vertical. The three coefficients
  here SUM TO 1.0 by construction, so the skull still lands at exactly
  `trunk-flexion + head-flexion`, `load/head-tilt-from-vertical-deg` still returns
  exactly that number, and the Hansraj-calibrated cervical load is unchanged to
  the bit. `the-partition-sums-to-one` and
  `the-head-still-tilts-by-trunk-plus-head-flexion` are the two assertions that
  keep it true; no new posture key was added, and none is needed.

  THE SHAPE IT PRODUCES IS THE FORWARD-HEAD POSTURE. The atlanto-occipital
  coefficient is NEGATIVE: as the head flexes on the trunk, the occiput EXTENDS on
  the atlas and the cervical column below flexes by MORE than the head does. That
  is what a person at a low screen actually does — the chin tucks under and the
  head tips back to keep the eyes level — and it is what a single rigid `head_neck`
  segment could not represent at any angle.

  IT IS NOT INVENTED, BUT IT IS NOT A REGRESSION EITHER. Bogduk & Mercer 2000
  describe the reversal directly, from van Mameren's cineradiography of 10 normal
  subjects (p.643): flexion is initiated in the lower cervical spine, and in the
  final phase \"C0-C2 typically exhibits a reversal of motion (i.e. extension)\".
  They also state the reason a partition cannot simply be read off the ranges:
  \"the total range of motion of the neck is not the arithmetic sum of its
  intersegmental ranges of motion\". So the DIRECTION of each coefficient is
  sourced and the SIZE of them is representative — the reversal is given the
  atlanto-occipital joint's own share of the cervical sagittal range
  (14.5 of 97.5 deg, about 15%), and the remainder is divided between the two
  flexing joints in proportion to the ranges Bogduk tabulates.

  WHAT THAT COSTS, stated rather than hidden. A fixed proportion is a linear
  approximation to a motion that is emphatically not linear — van Mameren's
  subjects move the lower cervical spine first, then the upper, then the lower
  again, and some of them REVERSE C6/C7 mid-excursion. This model has one number
  per posture and cannot represent a sequence. What it can now represent, and
  could not before, is the SHAPE at the end of it.

  A CHECKABLE CONSEQUENCE: at the model's maximum head-flexion input of 60 deg the
  derived atlanto-occipital extension is 9.0 deg, inside the 14.5 deg the joint
  has. `the-derived-atlanto-occipital-angle-stays-inside-its-published-range`
  asserts it across every reference posture, so a partition that asked the joint
  for motion it does not have would fail rather than be reported."
  (let [flexing (+ lower-cervical-rom-deg c2c3-rom-deg)
        reversal (/ atlanto-occipital-rom-deg
                    (+ atlanto-occipital-rom-deg flexing))
        forward (+ 1.0 reversal)]
    {:lower (* forward (/ lower-cervical-rom-deg flexing))
     :upper (* forward (/ c2c3-rom-deg flexing))
     :head (- reversal)}))

(defn lumbar-chord-tilt-deg
  "Tilt from vertical of the LUMBAR segment, given the thorax's tilt and the
  pelvic tilt — `trunk-flexion + pelvic-tilt/2`.

  THIS IS THE DEGREE OF FREEDOM THE TRUNK SPLIT EXISTS FOR, so it is worth being
  exact about what is derived and what is assumed.

  WHAT THE PELVIS DOES TO THE LUMBAR SPINE. The lumbar spine runs between two
  endplates. Its lower end is the S1 endplate, which is part of the pelvis and
  turns with it; its upper end is the L1/T12 endplate, which this model gives to
  the thorax. Rotating the pelvis anteriorly by `p` therefore rotates the LOWER
  end anteriorly by `p` and leaves the upper end where the thorax put it. The
  angle between the two ends — which is what a radiologist calls lumbar lordosis,
  measured Cobb between the L1 and S1 endplates — becomes exactly `p`.

  WHY THE CHORD IS THE MEAN. A rigid segment between two ends that differ by `p`
  has to be given ONE direction, and the model has one lumbar segment. For a
  circular arc — constant curvature, the simplest curve with those two tangents —
  the chord bisects the two end tangents, so its tilt is their mean:
  `((trunk + p) + trunk) / 2 = trunk + p/2`. Nothing is chosen here; a different
  curve would give a different chord, and constant curvature is the assumption,
  stated.

  ZERO PELVIC TILT IS A STRAIGHT LUMBAR SPINE, AND THAT NEUTRAL IS MEASURED
  RATHER THAN CONVENIENT. The model's neutral has the lumbar collinear with the
  thorax, i.e. zero lordosis, and the posture this actor has to answer for at
  L4/L5 is Wilke's `relaxed sitting on a stool with a normally straight back`.
  Cho et al. 2015 radiographed 30 healthy volunteers in five sitting positions and
  measured lumbar lordosis ON A STOOL at 0.6 deg (SD 3.6) — straight to inside its
  own scatter. So the model's neutral IS the reference posture, and
  `spine/lumbar-cross-check` at Wilke's own posture does not move because the
  pelvis exists. See `posture/lumbar-lordosis` for the citation and for the
  standing figure.

  WHAT IT CANNOT DO. This model has no PELVIC INCIDENCE — the morphological
  constant that fixes how much sacral slope a particular pelvis has — because it
  has no sacral endplate and no femoral-head geometry, only a rod from L5/S1 to
  the hip axis. So `:pelvic-tilt-deg` is a CHANGE in pelvic orientation away from
  the straight-lumbar neutral, not an absolute pelvic tilt in the
  Duval-Beaupere sense, and the model can compare two postures without being able
  to state either one's SS or PT. Wilke's comparison is a difference too, so this
  is the quantity the cross-check needs and not a lesser substitute for it.

  AND THE ONE THING IT GETS OBVIOUSLY WRONG. L5/S1 is the root of this chain and
  does not move, so tilting the lumbar chord carries the whole body above it
  forward or back through space. A real body compensates elsewhere and keeps its
  line of gravity over its feet; this one does not, and `load/lower-limb-loads`
  will report `:cop-inside-base? false` for a standing posture given enough
  lordosis. That is the model failing to state a posture, and it says so."
  [trunk-flexion-deg pelvic-tilt-deg]
  (+ trunk-flexion-deg (* 0.5 pelvic-tilt-deg)))

(defn lumbar-lordosis-deg
  "The angle between the lumbar spine's two ends, in degrees — the quantity a
  radiograph reports as lumbar lordosis (Cobb, L1 superior endplate to S1
  superior endplate).

  It IS `:pelvic-tilt-deg`, because the thorax holds the upper end and the pelvis
  turns the lower one. Reported as its own function rather than left implicit so
  that a consumer can compare it against a published lordosis without having to
  know that the two are the same number in this model — and so that the day the
  thorax stops holding the upper end, this stops being the identity and the
  callers do not have to be found."
  [posture]
  (or (:pelvic-tilt-deg posture) 0.0))

(defn- cervical-chain
  "Place the three cervical segments, from C7 upward.

  `head-flex` is the posture's `:head-flexion-deg` — the head on the trunk — and
  `trunk-tilt` is where the trunk left off. Each segment's tilt from vertical is
  the previous one's plus its share of `head-flex`, so the chain is stated as a
  chain and the partition appears once."
  [body {:keys [lower upper]} c7 trunk-tilt head-flex lateral head-rot]
  (let [lc (segment/seg body "lower_cervical")
        uc (segment/seg body "upper_cervical")
        hd (segment/seg body "head")
        lc-tilt (+ trunk-tilt (* lower head-flex))
        uc-tilt (+ lc-tilt (* upper head-flex))
        ;; THE SKULL'S TILT IS SET, NOT ACCUMULATED, and the difference is a
        ;; floating-point one that matters. The partition's three coefficients sum
        ;; to 1.0 in exact arithmetic and to 0.9999999999999999 in a double, so
        ;; adding the third share to the second segment's tilt puts a head asked
        ;; for 63.5 deg at 63.49999999999999 — and `load/cervical-load` reads that
        ;; number, so the one validated quantity in this library would wobble in
        ;; its last bits for no reason anybody could see. Setting it makes
        ;; `trunk + head-flexion` exact and pushes the rounding into the
        ;; atlanto-occipital ANGLE instead, which is a derived quantity with no
        ;; anchor. `:head` is therefore not read here; `the-partition-sums-to-one`
        ;; is what keeps it consistent with the two that are.
        hd-tilt (+ trunk-tilt head-flex)
        lc-seg (hangs-from (place "lower_cervical" :midline c7
                                  (segment-frame lc-tilt lateral 0.0 true)
                                  (:length-m lc) (:com-frac lc) lc-tilt true)
                           "thorax" 1.0)
        c2c3 (:distal lc-seg)
        uc-seg (hangs-from (place "upper_cervical" :midline c2c3
                                  (segment-frame uc-tilt lateral 0.0 true)
                                  (:length-m uc) (:com-frac uc) uc-tilt true)
                           "lower_cervical" 1.0)
        ao (:distal uc-seg)
        ;; axial rotation of the head on the neck rides on the SKULL, which is the
        ;; bone that turns; before the split it rode on the whole block, which
        ;; turned the neck with it.
        hd-seg (hangs-from (place "head" :midline ao
                                  (segment-frame hd-tilt lateral head-rot true)
                                  (:length-m hd) (:com-frac hd) hd-tilt true)
                           "upper_cervical" 1.0)]
    {:c2c3 c2c3 :atlanto-occipital ao :vertex (:distal hd-seg)
     :segments [lc-seg uc-seg hd-seg]}))

(def biacromial-frac
  "Shoulder (biacromial) breadth as a fraction of stature — Winter/Drillis. Half of
  it is how far each glenohumeral joint sits from the midline.

  A one-sided model could ignore this, because a lever measured about the shoulder
  itself does not care where the shoulder is. A BILATERAL model cannot: the two
  arms hang at ±this from the midline, and that is exactly what makes their
  frontal-plane moments about L5/S1 cancel when the posture is symmetric and stop
  cancelling when it is not. Without it, lateral bend would move the picture and
  change nothing in the frontal plane."
  0.245)

(defn- arm-chain
  "Place one arm, from the girdle outward. `side-sign` is +1 for the person's left
  (+Z) and −1 for the right; it mirrors both the lateral offset of the shoulder and
  the sense of abduction, so that abduction always carries each arm AWAY from the
  midline rather than both of them the same way."
  [body {:keys [shoulder-flexion-deg elbow-flexion-deg wrist-extension-deg]}
   c7 lat-axis abduct side side-sign stature-m]
  (let [ua (segment/seg body "upper_arm")
        fa (segment/seg body "forearm")
        hand (segment/seg body "hand")
        shoulder (math/v+ c7 (math/v* lat-axis (* side-sign 0.5 biacromial-frac stature-m)))
        ;; abduction lifts the arm away from the midline on this side
        ua-frame (segment-frame shoulder-flexion-deg (* (- side-sign) abduct) 0.0 false)
        ;; the arm hangs from the GIRDLE, which this model rides on the top of the
        ;; trunk: the shoulder is c7 offset laterally, so the upper arm attaches to
        ;; `thorax` at 1.0 of its length and to nothing cervical at all.
        ua-seg (hangs-from (place "upper_arm" side shoulder ua-frame (:length-m ua)
                                  (:com-frac ua) shoulder-flexion-deg false)
                           "thorax" 1.0)
        elbow (:distal ua-seg)
        ;; Elbow flexion is the angle BETWEEN the forearm and the upper arm (0° =
        ;; straight arm hanging, 90° = right angle), so the forearm's tilt from
        ;; vertical is the upper arm's tilt PLUS the elbow angle.
        fa-tilt (+ shoulder-flexion-deg elbow-flexion-deg)
        fa-frame (segment-frame fa-tilt (* (- side-sign) abduct) 0.0 false)
        fa-seg (hangs-from (place "forearm" side elbow fa-frame (:length-m fa)
                                  (:com-frac fa) fa-tilt false)
                           (placed-name "upper_arm" side) 1.0)
        wrist (:distal fa-seg)
        ;; WRIST EXTENSION, added 2026-09-06. The hand used to continue the forearm
        ;; rigidly, which gave the wrist muscles a moment arm that could not change
        ;; with the joint — the joint had kinetics and no kinematics, which is the
        ;; mirror of the gap the elbow had. Extension lifts the hand relative to
        ;; the forearm (the direction a keyboard puts it), so it SUBTRACTS from the
        ;; tilt measured down from vertical.
        wrist-ext (or wrist-extension-deg 0.0)
        hand-tilt (+ fa-tilt wrist-ext)
        hand-frame (segment-frame hand-tilt (* (- side-sign) abduct) 0.0 false)
        hand-seg (hangs-from (place "hand" side wrist hand-frame (:length-m hand)
                                    (:com-frac hand) hand-tilt false)
                             (placed-name "forearm" side) 1.0)]
    {:shoulder shoulder :elbow elbow :wrist wrist
     :segments [ua-seg fa-seg hand-seg]}))

(def interhip-frac
  "Distance between the two hip joint centres as a fraction of stature. Half of it
  is how far each femoral head sits from the midline — the lower limb's answer to
  `biacromial-frac`, and stated separately rather than reused because a pelvis is
  not a shoulder girdle.

  Representative (0.10 H, so 0.17 m apart at 1.70 m stature), not measured. It has
  no effect on any sagittal moment — the two legs are symmetric about the midline
  and a sagittal lever is a difference in x — and it is here because the frontal
  component of the ground reaction about the hip is not zero once it exists."
  0.10)

(def heel-frac
  "Where along the foot the ankle joint sits, as a fraction of foot length from
  the heel. Representative ~0.25.

  THIS IS THE MECHANISM OF QUIET STANDING, so it is not an ornament. The foot is
  the only segment in this model whose proximal point is NOT its joint: it runs
  heel-to-toe and the ankle sits a quarter of the way along it. Behind the ankle
  there is heel, in front of it there is forefoot, and the base of support a
  standing body balances over is that whole span. Start the foot at the ankle
  instead and the base of support begins at the joint, so the ground reaction can
  never pass behind it, and the model can only ever say that the plantarflexors
  are working — including in the postures where they are not."
  0.25)

(defn- leg-chain
  "Place one leg, from the pelvis outward. `side-sign` is +1 for the person's left
  (+Z) and −1 for the right, exactly as `arm-chain` uses it.

  THE THREE ANGLES, and what each is measured against:

    :hip-flexion-deg          the thigh's tilt anterior from straight down. This
                              model's pelvis never rotates — `solve-pose` places
                              it vertically below L5/S1 at every posture — so the
                              tilt from vertical and the anatomical hip angle are
                              the same number here, which they would not be in a
                              model with a mobile pelvis.
    :knee-flexion-deg         the shank swung POSTERIOR relative to the thigh, so
                              the shank's tilt is the thigh's MINUS this. The knee
                              bends one way; a negative value is hyperextension
                              and the model will place it rather than refuse.
    :ankle-dorsiflexion-deg   the foot rotated toes-up relative to the shank. The
                              foot's tilt is the shank's plus 90° (a foot at right
                              angles to the shank lies flat when the shank is
                              vertical) plus this.

  A flat foot in standing is therefore `dorsiflexion = knee-flexion −
  hip-flexion`, which is why `posture/quiet-standing` carries a few degrees of
  each rather than zeros: a person standing still leans the shank forward over the
  foot, and that lean is where their weight goes in front of the ankle.

  NO ABDUCTION. The lower limb is placed in the sagittal plane only; there is no
  `:hip-abduction-deg`. `arm-chain` has one, and the difference is real rather
  than an oversight — the frontal-plane loads this actor solves are the ones a
  desk posture creates, and no muscle here abducts a hip."
  [body {:keys [hip-flexion-deg knee-flexion-deg ankle-dorsiflexion-deg]}
   pelvis-seg lat-axis side side-sign stature-m]
  (let [th (segment/seg body "thigh")
        sh (segment/seg body "shank")
        ft (segment/seg body "foot")
        hip-flex (or hip-flexion-deg 0.0)
        knee-flex (or knee-flexion-deg 0.0)
        dorsi (or ankle-dorsiflexion-deg 0.0)
        hip (math/v+ (:distal pelvis-seg)
                     (math/v* lat-axis (* side-sign 0.5 interhip-frac stature-m)))
        th-tilt hip-flex
        th-frame (segment-frame th-tilt 0.0 0.0 false)
        th-seg (hangs-from (place "thigh" side hip th-frame (:length-m th)
                                  (:com-frac th) th-tilt false)
                           "pelvis" 1.0)
        knee (:distal th-seg)
        sh-tilt (- th-tilt knee-flex)
        sh-frame (segment-frame sh-tilt 0.0 0.0 false)
        sh-seg (hangs-from (place "shank" side knee sh-frame (:length-m sh)
                                  (:com-frac sh) sh-tilt false)
                           (placed-name "thigh" side) 1.0)
        ankle (:distal sh-seg)
        ft-tilt (+ sh-tilt 90.0 dorsi)
        ft-frame (segment-frame ft-tilt 0.0 0.0 false)
        ;; the foot's proximal point is the HEEL, and the ankle sits `heel-frac`
        ;; along it — see `heel-frac`. Every other segment in this model starts at
        ;; its own joint; this one does not, and a consumer that assumes otherwise
        ;; puts the base of support in the wrong place.
        heel (math/v- ankle (math/v* (:long ft-frame) (* heel-frac (:length-m ft))))
        ;; the foot hangs from the shank at the ANKLE, which is the shank's distal
        ;; end — 1.0 of the shank — even though it is `heel-frac` along the foot
        ;; rather than at the foot's own proximal point.
        ft-seg (hangs-from (place "foot" side heel ft-frame (:length-m ft)
                                  (:com-frac ft) ft-tilt false)
                           (placed-name "shank" side) 1.0)]
    {:hip hip :knee knee :ankle ankle :heel heel :toe (:distal ft-seg)
     :segments [th-seg sh-seg ft-seg]}))

(defn solve-pose
  "Place the whole chain in world space for a body + posture.

  Returns {:joints {…point} :segments [placed…] :sides #{…} :frame {…}}. Joint keys
  are the anatomical landmarks the moment solver takes moments about; segments are
  in proximal-to-distal order, midline first, then each arm, then each leg.

  BILATERAL since 2026-09-06. The model used to place ONE arm and multiply its
  load by two, which is exact for a symmetric posture and silently wrong for every
  other one: lateral bend is asymmetric by definition, and a single side could
  neither represent it nor say that it could not. It also meant the two arms'
  frontal-plane moments about the spine had nothing to cancel against. Both arms
  are placed now, each at half the biacromial breadth from the midline, and the
  loads are summed rather than doubled — which gives the same answer as before
  wherever the posture is symmetric, and a different and correct one where it is
  not.

  Out-of-plane angles are optional and default to zero:
    :trunk-lateral-bend-deg   trunk away from the midline (about X)
    :shoulder-abduction-deg   arms away from the midline, each on its own side
    :head-rotation-deg        axial rotation of the head on the neck
    :wrist-extension-deg      hand lifted relative to the forearm (a keyboard's
                              usual 15-25 deg)

  THE PELVIS ROTATES, added 2026-09-08, and it too defaults to zero so that every
  number this actor produced before it existed is unchanged:

    :pelvic-tilt-deg          ANTERIOR pelvic tilt. It rotates the pelvis (and so
                              the hips, and so both legs) and it rotates the
                              lumbar spine's lower end, which gives the lumbar
                              spine a lordosis equal to it and an orientation that
                              is no longer the thorax's. See
                              `lumbar-chord-tilt-deg`, which is where all of that
                              is derived, and `lumbar-lordosis-deg`.

  THE LOWER LIMB, added 2026-09-07, is likewise optional and defaults to zero, so
  a posture that names none of it places both legs straight down and every number
  this actor produced before it existed is unchanged:

    :hip-flexion-deg          thigh anterior from straight down
    :knee-flexion-deg         shank posterior relative to the thigh
    :ankle-dorsiflexion-deg   foot toes-up relative to the shank

  See `leg-chain` for what each is measured against, and `posture/support-mode`
  for the thing that actually decides what those angles cost."
  [body posture]
  (let [{:keys [head-flexion-deg trunk-flexion-deg]} posture
        lateral (or (:trunk-lateral-bend-deg posture) 0.0)
        abduct (or (:shoulder-abduction-deg posture) 0.0)
        head-rot (or (:head-rotation-deg posture) 0.0)
        stature-m (:stature-m body)
        pelvis (segment/seg body "pelvis")
        lumbar (segment/seg body "lumbar")
        thorax (segment/seg body "thorax")
        pelvic-tilt (or (:pelvic-tilt-deg posture) 0.0)
        l5s1 [0.0 0.0 0.0]
        ;; The LUMBAR segment is the ROOT of this chain and the pelvis hangs off
        ;; its proximal end. Both start at L5/S1, so either could have been called
        ;; the root; the lumbar spine is, because `spine/levels` states every level
        ;; as a fraction of a segment measured from L5/S1 upward, and rooting the
        ;; chain anywhere else would make that reading depend on a convention
        ;; stated somewhere the levels cannot see.
        ;;
        ;; THE PELVIS ROTATES SINCE 2026-09-08. It used to be placed straight down
        ;; at every posture, which is what made sitting and standing identical
        ;; above L5/S1. An ANTERIOR pelvic tilt tips the top of the sacrum forward
        ;; and therefore carries the femoral heads BACKWARD, which is why the
        ;; segment's flexion is the negative of the input: this segment runs from
        ;; L5/S1 DOWN to the hip axis, so it is the line Duval-Beaupere's pelvic
        ;; tilt is measured along, with the opposite sign convention.
        p-seg (hangs-from (place "pelvis" :midline l5s1
                                 (segment-frame (- pelvic-tilt) 0.0 0.0 false)
                                 (:length-m pelvis) (:com-frac pelvis)
                                 (- pelvic-tilt) false)
                          "lumbar" 0.0)
        lumbar-tilt (lumbar-chord-tilt-deg trunk-flexion-deg pelvic-tilt)
        l-frame (segment-frame lumbar-tilt lateral 0.0 true)
        l-seg (hangs-from (place "lumbar" :midline l5s1 l-frame
                                 (:length-m lumbar) (:com-frac lumbar)
                                 lumbar-tilt true)
                          nil nil)
        t12l1 (:distal l-seg)
        t-frame (segment-frame trunk-flexion-deg lateral 0.0 true)
        t-seg (hangs-from (place "thorax" :midline t12l1 t-frame
                                 (:length-m thorax) (:com-frac thorax)
                                 trunk-flexion-deg true)
                          "lumbar" 1.0)
        c7 (:distal t-seg)
        ;; THREE cervical segments since 2026-09-07, hinged at C2/C3 and at the
        ;; atlanto-occipital joint. `:head-flexion-deg` is unchanged in meaning and
        ;; is divided between them by `cervical-partition`, whose coefficients sum
        ;; to 1.0 — so the SKULL still lands at trunk + head flexion, exactly where
        ;; the single `head_neck` block put the whole complex.
        neck (cervical-chain body cervical-partition c7 trunk-flexion-deg
                             head-flexion-deg lateral head-rot)
        ;; the girdle is carried by the trunk, so its lateral axis is the trunk's —
        ;; leaning sideways carries both shoulders with it
        lat-axis (:lat t-frame)
        left (arm-chain body posture c7 lat-axis abduct :left 1.0 stature-m)
        right (arm-chain body posture c7 lat-axis abduct :right -1.0 stature-m)
        ;; the legs hang from the PELVIS, whose frame this model never rotates, so
        ;; their lateral axis is the world's rather than the trunk's — leaning the
        ;; trunk sideways carries the shoulders with it and does not carry the hips
        leg-left (leg-chain body posture p-seg (:lat (:frame p-seg)) :left 1.0 stature-m)
        leg-right (leg-chain body posture p-seg (:lat (:frame p-seg)) :right -1.0 stature-m)]
    {:frame {:units :metres :origin "L5/S1" :axes {:x :anterior :y :superior :z :left}}
     :sides #{:left :right}
     :joints {:l5s1 l5s1
              ;; the midline landmark at the base of the pelvis segment. It is NOT
              ;; a hip joint and never was: the femoral heads are `:hip/left` and
              ;; `:hip/right`, half of `interhip-frac` to either side of it.
              :pelvis-base (:distal p-seg)
              ;; the joint the trunk split created: the T12/L1 disc, where the
              ;; lumbar spine ends and the thorax begins.
              :t12l1 t12l1
              :c7 c7
              :shoulder/left (:shoulder left)
              :shoulder/right (:shoulder right)
              :elbow/left (:elbow left)
              :elbow/right (:elbow right)
              :wrist/left (:wrist left)
              :wrist/right (:wrist right)
              :hip/left (:hip leg-left)
              :hip/right (:hip leg-right)
              :knee/left (:knee leg-left)
              :knee/right (:knee leg-right)
              :ankle/left (:ankle leg-left)
              :ankle/right (:ankle leg-right)
              ;; the ends of the feet. Not joints — they are the two edges of the
              ;; base of support, and `base-of-support` is the only reason a
              ;; standing posture can be said to be one a body could hold.
              :heel/left (:heel leg-left)
              :heel/right (:heel leg-right)
              :toe/left (:toe leg-left)
              :toe/right (:toe leg-right)
              ;; the two joints the split created. `:c2c3` is the most cranial
              ;; intervertebral disc; `:atlanto-occipital` is the nodding joint,
              ;; and it is the one the suboccipitals act about.
              :c2c3 (:c2c3 neck)
              :atlanto-occipital (:atlanto-occipital neck)
              :vertex (:vertex neck)}
     :segments (vec (concat (into [p-seg l-seg t-seg] (:segments neck))
                            (:segments left) (:segments right)
                            (:segments leg-left) (:segments leg-right)))}))

(defn seg-at
  "The placed segment with this name, or nil."
  [pose name]
  (first (filter #(= name (:name %)) (:segments pose))))

(defn anterior-lever
  "Horizontal (anterior) distance from a joint point to a segment's CoM — the lever
  arm gravity acts through. Positive means the mass is in front of the joint."
  [joint-point placed]
  (- (first (:com placed)) (first joint-point)))

(defn external-moment-vec
  "The static moment the musculature must GENERATE about `joint-point`, given every
  external force acting on the free body DISTAL to it: −Σ (rᵢ − j) × Fᵢ.

  `forces` is a seq of `[point force-vector]` in newtons. Gravity is the case
  where every force is `[0, −w, 0]`, and `gravitational-moment-vec` is exactly
  this function with that substitution — written that way rather than duplicated,
  because the sign convention here is the one thing in this namespace that cannot
  be got slightly wrong and still look right.

  THE GROUND REACTION IS WHY THIS IS GENERAL. Until the lower limb existed, every
  external force on every free body in this model pointed down, so a function that
  could only take weights was a function that could take everything. A standing
  body has one force that points UP, it is the largest force in the problem, and
  it is the ONLY difference between the statics of standing and the statics of
  sitting — see `load/lower-limb-loads`."
  [joint-point forces]
  (math/v* (reduce (fn [m [point f]]
                     (math/v+ m (math/vcross (math/v- point joint-point) f)))
                   [0.0 0.0 0.0]
                   forces)
           -1.0))

(defn gravitational-moment-vec
  "The static moment the musculature must GENERATE about `joint-point`, as a
  3-vector (N·m): −Σ r × W, with W = [0, −w, 0] because gravity acts down the
  world −Y axis.

  SIGN. The negation is not cosmetic. `r × W` is the moment gravity applies; this
  actor's scalar `gravitational-moment` has always reported the moment the muscles
  must produce, which is its opposite. Returning the raw cross product here would
  give a vector whose Z component is the negative of the scalar beside it, and a
  consumer reading one and then the other would get a sign error with no symptom
  other than a wrong answer. `pose-test` pins the two against each other.

  Its Z component is the sagittal (flexion/extension) moment that
  `gravitational-moment` returns; its X component is the FRONTAL-plane moment,
  which is identically zero for a sagittal posture and is not zero as soon as the
  chain is abducted or laterally bent. Computing the whole vector costs nothing
  extra and is the difference between a model that does not resolve the frontal
  plane and one that silently drops it — see `load/solve-posture-loads`, which
  reports the frontal component so that a consumer can see there is a load nobody
  in this model is carrying."
  [joint-point placed-with-weights]
  (external-moment-vec joint-point
                       (for [[placed weight-n] placed-with-weights]
                         [(:com placed) [0.0 (- weight-n) 0.0]])))

(defn gravitational-moment
  "Static gravitational moment (N·m) about `joint-point` in the SAGITTAL plane —
  the Z component of `gravitational-moment-vec`. This IS the RNEA gravity term for
  a chain at rest: no velocity, no acceleration, so every other term vanishes."
  [joint-point placed-with-weights]
  (reduce (fn [m [placed weight-n]]
            (+ m (* weight-n (anterior-lever joint-point placed))))
          0.0
          placed-with-weights))

(defn segment-weights
  "Pair each PLACED segment with its weight in newtons. Keyed by `:name` (unique
  within the pose) and looked up by `:base` (the anthropometric segment), because
  a bilateral model has two `upper_arm`s and each carries the mass of one limb —
  `segment/build-body` already stores paired segments as one side's mass."
  [body pose]
  (into {} (for [{:keys [name base]} (:segments pose)]
             [name (segment/weight-n (segment/seg body base))])))

(defn segments-on
  "Placed segments whose base is one of `bases`, on `side` (or on any side when
  `side` is nil)."
  ([pose bases] (segments-on pose bases nil))
  ([pose bases side]
   (let [bases (set bases)]
     (filter #(and (bases (:base %)) (or (nil? side) (= side (:side %))))
             (:segments pose)))))

(defn bone-lines
  "The chain as drawable line segments [from to] — the minimum a renderer needs to
  show the posture, with no rendering concepts leaking into this namespace."
  [pose]
  (mapv (fn [{:keys [name proximal distal]}] {:name name :from proximal :to distal})
        (:segments pose)))

(defn total-height-m
  "Vertical extent of the placed chain — a cheap invariant: a chain that folds
  forward must get SHORTER, never taller.

  It used to read pelvis-to-vertex because that was the whole chain. With a lower
  limb it reads floor-to-vertex, which is the same invariant over a longer body
  and is closer to a stature than it was."
  [pose]
  (let [ys (mapcat (fn [{:keys [proximal distal]}] [(second proximal) (second distal)])
                   (:segments pose))]
    (- (apply max ys) (apply min ys))))

;; --- standing on something ---------------------------------------------------

(defn whole-body-com
  "`{:point [x y z] :weight-n W}` — the centre of mass of the placed chain and the
  total weight hanging on it.

  W is the whole body's weight and not an approximation of it: the mass fractions
  in `segment` sum to exactly 1.0 across the nine segments once each paired one is
  counted twice, which `the-whole-body-is-accounted-for` asserts. That matters
  here because W is the magnitude of the ground reaction, and a table that summed
  to 1.08 would inflate every standing moment in this model by 8%."
  [body pose-data]
  (let [w (segment-weights body pose-data)
        total (reduce + 0.0 (map #(get w (:name %)) (:segments pose-data)))
        weighted (reduce (fn [acc placed]
                           (math/v+ acc (math/v* (:com placed) (get w (:name placed)))))
                         [0.0 0.0 0.0]
                         (:segments pose-data))]
    {:point (if (pos? total) (math/v* weighted (/ 1.0 total)) [0.0 0.0 0.0])
     :weight-n total}))

(defn ground-y
  "The height of the ground: the lowest point of either foot. nil when the pose has
  no feet, which is how a body model without a lower limb says so rather than
  putting the floor at an arbitrary height."
  [pose-data]
  (let [ys (for [{:keys [base proximal distal]} (:segments pose-data)
                 :when (= "foot" base)
                 pt [proximal distal]]
             (second pt))]
    (when (seq ys) (apply min ys))))

(defn base-of-support
  "`{:back x :front x}` — the anterior extent of the feet on the ground, from the
  most posterior heel to the most anterior toe.

  A standing body is only in static equilibrium while its line of gravity falls
  inside this span. The model does NOT refuse a posture that fails it, because
  such a posture is a real thing a body does — it is the first instant of a step,
  or of a fall — but `load/lower-limb-loads` reports `:cop-inside-base?` so that a
  consumer is never handed the statics of a posture nobody can hold as though it
  were the statics of a posture somebody is holding."
  [pose-data]
  (let [xs (for [{:keys [base proximal distal]} (:segments pose-data)
                 :when (= "foot" base)
                 pt [proximal distal]]
             (first pt))]
    (when (seq xs) {:back (apply min xs) :front (apply max xs)})))

(defn centre-of-pressure
  "Where the ground pushes back: `[x y z]` for one foot, or nil when the pose has
  no feet.

  ITS POSITION IS NOT A PARAMETER — it is the equilibrium condition. A body held
  still has no angular acceleration, so the resultant ground reaction must pass
  through the line of gravity; the centre of pressure is therefore under the
  whole-body centre of mass, and this function computes it there rather than
  taking it as an input. Stating it as `some fraction along the foot` instead
  looks like more anatomy and is less physics: it lets the model report a
  plantarflexor moment for a body that, on its own numbers, is toppling.

  EQUAL SPLIT, stated because it is a limitation and not a derivation. Each foot
  is given half the body weight at the same anterior position, so the model cannot
  represent single-leg stance or a body leaning onto one foot. `z` is the foot's
  own, so the frontal component about a hip is not nonsense; the split that would
  make it correct for an asymmetric posture is not solved here."
  [body pose-data side]
  (let [gy (ground-y pose-data)
        foot (seg-at pose-data (placed-name "foot" side))]
    (when (and gy foot)
      [(first (:point (whole-body-com body pose-data))) gy (nth (:com foot) 2)])))
