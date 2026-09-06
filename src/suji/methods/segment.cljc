(ns suji.methods.segment
  "suji (筋) — anthropometric sagittal-plane segment chain. 1:1 Clojure port of
  `src/suji/methods/segment.cljc` (ADR-2606061900). Stdlib only.

  The skeleton this actor reasons over is a 2-D (sagittal) articulated chain of rigid
  body segments — the `PlanarChain` articulation that kami-genesis solves. Each segment
  carries a mass, a length, and a centre-of-mass (CoM) location along its long axis,
  derived from total body mass M (kg) and stature H (m) using standard regression
  fractions (Winter 4e Table 4.1 / Drillis & Contini via Winter).

  These are population-average values for an adult; they are :representative (G7), not a
  scan of any individual.

  NON-DIAGNOSTIC (G1): this module computes masses and lengths. It says nothing about
  health. It is the mass-distribution input to a statics problem.

  House style: a Segment / BodyModel is a kebab-keyword map; pure fns. The :weight-n and
  :com-m are derived on demand by `weight-n` / `com-m` (mirroring the Python @property)."
  (:require [clojure.string :as str]))

(def gravity 9.80665)                       ;; m/s^2

;; --- the cervical spine, split three ways (2026-09-07) -----------------------
;;
;; WHY. Until today this model had ONE segment above the thorax, `head_neck`,
;; running C7 to the vertex with a single joint at its base. Three things followed
;; from that and none of them was a decision:
;;
;;   * `spine` reported five cervical intervertebral levels whose orientations
;;     were all identical, because there was only one cervical joint to orient
;;     them with. Five samples of one rigid body.
;;   * the suboccipitals could not be written down at all. Rectus capitis
;;     posterior major and minor and obliquus capitis superior and inferior run
;;     from C1/C2 to the occiput, so with one segment BOTH ends of each ride on the
;;     same bone — the shape `attachment-test` names as the error that produces a
;;     constant-looking moment arm. Kamibayashi & Richmond measure them at
;;     3.75 cm² per side, so it is not a rounding error.
;;   * a forward-head posture is LOWER CERVICAL FLEXION WITH UPPER CERVICAL
;;     EXTENSION — the chin tucks under while the head tips back to keep the eyes
;;     level — and a single block can only tilt.
;;
;; WHY THREE AND NOT TWO OR SEVEN.
;;
;;   two (head + one cervical column, hinged at the atlanto-occipital joint) is
;;     the minimum change and buys the same three suboccipitals this split does,
;;     because all three of those cross the atlanto-occipital joint. What it does
;;     NOT buy is the forward-head shape: with one cervical body there is no way
;;     for the bottom of the neck to flex while the top extends, which is the
;;     posture this whole actor exists to describe.
;;
;;   per-vertebra (occiput, C1 … C7) is anatomically ideal and is not honest here.
;;     It needs a mass and a centre of mass for each vertebra, which none of the
;;     three anthropometric tables checked below reports, and an angle for each of
;;     seven joints, where the posture input supplies ONE number. It would be
;;     seven invented numbers dressed as anatomy.
;;
;;   three is the smallest split that represents the forward-head shape. The
;;     boundaries are a real disc (C2/C3, the most cranial intervertebral disc)
;;     and a real joint (atlanto-occipital, the nodding joint), each segment is a
;;     contiguous run of vertebrae, and the two new joints are the two the sagittal
;;     plane actually has: Bogduk & Mercer put atlanto-occipital flexion-extension
;;     at 14–15° and note that the atlanto-axial joint's cardinal motion is axial
;;     rotation, which a sagittal model cannot spend.
;;
;; WHAT THE FOURTH SEGMENT WOULD HAVE BOUGHT, since it is one obliquus away:
;; splitting `upper_cervical` into atlas and axis would make the atlanto-axial
;; joint real and let obliquus capitis inferior (C2 spinous → C1 transverse) be
;; written down — it is the one suboccipital that still cannot be. It would also
;; need a mass for a single vertebra, and the joint it opens is principally a
;; ROTATION joint (40.5° axial against ~10° sagittal), so a sagittal model would
;; carry the cost and not the benefit. See `spine`'s docstring for the list of
;; what this model still cannot say.

(def lower-cervical-span
  "Where the C2/C3 disc sits, as a fraction of the C7→vertex length.

  DERIVED FROM THIS MODEL'S OWN LEVEL SPACING rather than imported. `spine/levels`
  spaces the five cervical levels 0.06 of the segment apart (18.6 mm at reference
  stature, which is a cervical vertebra plus its disc) and puts C3/C4 at 0.24;
  continuing that spacing upward gives C2/C3 at 0.30. `attachment` wrote this
  derivation down on 2026-09-07, before there was a segment to use it."
  0.30)

(def atlanto-occipital-along
  "Where the occipital condyles sit, as a fraction of the C7→vertex length —
  0.42, i.e. 130 mm above C7 at reference stature, by the same continuation
  (C2/C3 0.30, C1/C2 0.36, occipito-atlantal 0.42).

  It is the cut Plagenhoef, Evans & Abdelnour describe as their head segment:
  \"For the head: (1) decapitate the skull from the atlas\" (1983, p.170)."
  0.42)

(def upper-cervical-span (- atlanto-occipital-along lower-cervical-span))
(def head-span (- 1.0 atlanto-occipital-along))

(def head-neck-len-frac
  "C7 to the vertex, as a fraction of stature — Drillis & Contini via Winter, the
  value `head_neck` carried. The three segments partition it, so nothing above or
  below the neck moved."
  0.182)

(def head-neck-mass-frac
  "Winter (4e) Table 4.1, `Head and neck` — 8.1% of body mass. The three cervical
  segments sum to exactly this."
  0.081)

(def head-neck-com-frac
  "Where the WHOLE complex's centre of mass sits, as a fraction of C7→vertex from
  C7 — the 0.55 `head_neck` carried, consistent with Winter's head-and-neck centre
  of mass at the ear canal.

  The split preserves it exactly (`the-split-keeps-the-complex-centre-of-mass`),
  which is what keeps the gravitational moment about C7 unchanged at the neutral
  posture where the three segments are collinear. Away from neutral they are not
  collinear and the moment DOES change — that is the point of the split, not a
  side effect of it."
  0.55)

(def head-share-of-complex
  "How much of the head-and-neck mass is the skull. 0.80 — REPRESENTATIVE, NOT
  MEASURED, and this is the one number in the split that is not derived from
  something already in the model.

  THREE STANDARD TABLES WERE CHECKED AND NONE OF THEM SPLITS IT. Recorded as a
  measured negative so the next reader does not repeat the search:

    Winter, Biomechanics and Motor Control of Human Movement 4e, Table 4.1 — one
      row, `Head and neck`, 0.081. No head row and no neck row.
    de Leva P (1996), `Adjustments to Zatsiorsky-Seluyanov's segment inertia
      parameters`, J Biomech 29(9):1223-1230, Table 4 — full text read 2026-09-07
      from https://ebm.ufabc.edu.br/wp-content/uploads/2013/12/Leva-1996.pdf. His
      `Head` segment is vertex→cervicale (C7), i.e. THIS SAME SPAN, at 6.94% of
      body mass for males. He also lists it vertex→mid-gonion with the SAME 6.94%
      and a different length: the endpoints move and the mass does not, because he
      never divided it either.
    Plagenhoef, Evans & Abdelnour (1983), `Anatomical Data for Analyzing Human
      Motion`, Res Q Exerc Sport 54(2):169-178 — full text read 2026-09-07 from
      https://courses.grainger.illinois.edu/me481/sp2021/Anthro1.pdf. Their
      dissection protocol DOES cut here (p.170: \"For the head: (1) decapitate the
      skull from the atlas\"), and their tables still report only `Head and neck`,
      8.26% for men.

  WHAT 0.80 IS ANCHORED ON, since it is not anchored on a table: at 70 kg it puts
  4.54 kg in the skull and 1.13 kg in the neck. The neck it leaves is a cylinder
  about 130 mm long and about 110 mm across, which at the density of soft tissue
  is roughly a kilogram — so the split is consistent with the volume the model's
  own geometry gives the neck. That is a plausibility check and not a measurement,
  and a reader who has a measured head mass should change THIS line: the two
  cervical fractions and the head's centre of mass are computed from it, so
  nothing else has to move.

  WHICH DIRECTION IT BIASES. A heavier skull puts more mass further up the lever,
  so it raises the moment about C7 and about the atlanto-occipital joint in a
  flexed posture. 0.80 is at the high end of the plausible range, so the
  suboccipital and cervical demands this model reports are more likely over- than
  under-stated."
  0.80)

(def cervical-mass-frac
  "The three-way split of Winter's 0.081, as fractions of BODY mass.

  The skull takes `head-share-of-complex`; the neck's remainder is divided between
  the two cervical segments BY LENGTH — a uniform cylinder, which is the same
  idealisation `spine/above-fraction` already makes about mass along a segment,
  stated here so the two agree."
  (let [neck (* head-neck-mass-frac (- 1.0 head-share-of-complex))]
    {:lower (* neck (/ lower-cervical-span atlanto-occipital-along))
     :upper (* neck (/ upper-cervical-span atlanto-occipital-along))
     :head (* head-neck-mass-frac head-share-of-complex)}))

(def head-com-frac
  "Where the skull's centre of mass sits along the atlanto-occipital→vertex axis.

  DERIVED, not chosen: it is the value that makes the three segments' combined
  centre of mass land exactly where `head_neck`'s did (`head-neck-com-frac`), given
  the masses above and uniform cylinders for the two neck segments. Solving

      Σ mᵢ xᵢ = 0.55                (xᵢ measured from C7, in units of C7→vertex)

  for the head's own fraction gives about 0.371 — the skull's centre of mass about
  67 mm above the occipital condyles, which is roughly where an anatomy text puts
  it (near the sella turcica, a little above and in front of the ear canal). That
  it lands somewhere sensible is a check on `head-share-of-complex` rather than an
  independent measurement of anything."
  (let [{:keys [lower upper head]} cervical-mass-frac
        total (+ lower upper head)
        ;; centre of mass of each neck segment, from C7, in units of C7→vertex
        x-lower (* 0.5 lower-cervical-span)
        x-upper (+ lower-cervical-span (* 0.5 upper-cervical-span))
        ;; what the head's centre of mass has to be, from C7, to hit the target
        x-head (/ (- (* head-neck-com-frac total) (* lower x-lower) (* upper x-upper))
                  head)]
    (/ (- x-head atlanto-occipital-along) head-span)))

;; --- the trunk, split in two at T12/L1 (2026-09-08) --------------------------
;;
;; WHY. `thorax_abdomen` ran L5/S1 to C7 as ONE rigid body. The five lumbar
;; intervertebral levels `spine` reports all sat on it, so all five took the
;; THORAX's orientation, and the only thing that could change that orientation was
;; `:trunk-flexion-deg`. Two consequences followed and neither was a decision:
;;
;;   * the pelvis could not rotate. `pose` placed it straight down from L5/S1 at
;;     every posture, so sitting and standing differed ONLY below L5/S1 and the
;;     lumbar spine could not tell them apart. `spine/lumbar-cross-check` measured
;;     exactly that: the same 350.887 N at L4/L5 for both, where Wilke measures
;;     0.50 MPa standing against 0.46 MPa sitting.
;;   * lordosis was not representable at all. `spine`'s own docstring said so
;;     ("There is no curvature"), and lordosis is precisely what separates a
;;     seated lumbar spine from a standing one.
;;
;; A missing segment was a missing degree of freedom, exactly as it was in the
;; neck. This is the same repair.
;;
;; WHY TWO AND NOT FIVE. Five lumbar segments would give each level its own
;; orientation, which is anatomically better and is not honest here: it needs a
;; mass and a centre of mass PER VERTEBRA, which no table checked for the cervical
;; split publishes and none publishes here either, and an angle for each of five
;; joints where the posture supplies one. That is the argument `cervical-split`
;; made against per-vertebra necks, and it is not weaker one region down. Two
;; segments buy the degree of freedom the cross-check needs — a lumbar spine whose
;; orientation is NOT the thorax's — and buy nothing else, which is the point.
;;
;; WHAT IT DOES NOT BUY, said plainly: the five lumbar levels STILL share one
;; orientation. It is now the lumbar's own rather than the thorax's, and that is
;; the whole change.

(def lumbar-span
  "Where the T12/L1 disc sits, as a fraction of the L5/S1 -> C7 length — 0.35.

  DERIVED FROM THIS MODEL'S OWN LEVEL SPACING rather than imported, the same way
  `lower-cervical-span` is. `spine/levels` spaces the five lumbar levels 0.07 of
  the trunk apart (20.2 mm at reference stature, which is a lumbar vertebra plus
  its disc) and puts L1/L2 at 0.28; continuing that spacing upward gives T12/L1
  at 0.35.

  A PLAUSIBILITY CHECK, not a measurement: 0.35 x 0.288 H is 0.171 m at 1.70 m
  stature, and an adult lumbar spine from the S1 endplate to the top of L1 is
  about 0.17 m. That it lands there is a check on the 0.07 spacing, which was
  itself representative."
  0.35)

(def thorax-span (- 1.0 lumbar-span))

(def trunk-len-frac
  "L5/S1 to C7 as a fraction of stature — Drillis & Contini via Winter, the value
  `thorax_abdomen` carried. The two segments partition it."
  0.288)

(def trunk-mass-frac
  "Winter (4e) Table 4.1, `Thorax and abdomen` — 35.5% of body mass. The two
  segments sum to exactly this."
  0.355)

(def trunk-com-frac
  "Where the WHOLE trunk's centre of mass sits, as a fraction of L5/S1 -> C7 from
  L5/S1 — the 0.50 `thorax_abdomen` carried. The split preserves it exactly
  (`the-trunk-split-keeps-the-trunk-centre-of-mass`), which is what keeps every
  moment in this model unchanged at the neutral posture where the two segments are
  collinear. Away from neutral they are not collinear and the moments DO change —
  that is the point of the split, not a side effect of it.

  ⚠ IT IS NOT WINTER'S. Winter's own `Thorax and abdomen` row puts the centre of
  mass at 0.63 of C7-T1 -> L4-L5 from C7, i.e. 0.37 from the bottom, and his two
  component rows agree with that (0.361 recomputed over this model's span). This
  model has used 0.50 since it existed. The split SURFACES that discrepancy and
  deliberately does not correct it here: correcting it would move every moment in
  the library at once, which would make the lordosis result below unattributable.
  It is recorded so the next reader can take it, and the direction is stated — a
  trunk centre of mass 0.13 of the trunk too high OVER-states every trunk moment
  this model reports."
  0.50)

(def trunk-mass-split
  "The two-way split of Winter's 0.355, as fractions of BODY mass.

  MEASURED, AND FROM THE TABLE THIS MODEL ALREADY QUOTES — which is where this
  split differs from the cervical one. `head-share-of-complex` is 0.80 because
  three standard tables were checked and NONE of them divides head from neck.
  Winter divides the trunk at exactly the place this split needs it:

    Thorax               C7-T1/T12-L1 and diaphragm    0.216
    Abdomen              T12-L1/L4-L5                  0.139
    Thorax and abdomen   C7-T1/L4-L5                   0.355

  and 0.216 + 0.139 = 0.355 exactly, against the row this model was already using.
  Full text read 2026-09-08 from
  https://courses.grainger.illinois.edu/me481/sp2021/Anthro-Winter.pdf (Table 4.1).

  ⚠ ONE LEVEL OF MISMATCH, WHICH THE SPLIT INHERITS AND DID NOT CREATE. Winter's
  abdomen ends at L4-L5 and his pelvis begins there; this model's trunk ends at
  L5/S1 and its pelvis begins there. So the model has always applied Winter's
  0.355 to a span one level longer than his, and now applies his 0.139 to the same
  one-level-longer span. Correcting it would need a mass for the L5 vertebra and
  its share of the abdominal wall and viscera, which Winter does not publish.

  THE TRUNK IS NOT UNIFORM, AND THAT MOVES A NUMBER. 0.139 over 0.35 of the length
  is 0.397 of body mass per unit trunk length; 0.216 over 0.65 is 0.332. The lower
  trunk is 20% denser than the upper one, and until this split the model spread
  0.355 evenly along the whole trunk. `spine/weight-above-n` therefore changes at
  every lumbar level except L5/S1 — see the README. It moves for this reason and
  for no other, and `the-lumbar-weight-above-is-the-two-masses` derives it rather
  than pinning it."
  {:lumbar 0.139
   :thorax 0.216})

(def thorax-com-frac
  "Where the thorax's centre of mass sits along T12/L1 -> C7, from T12/L1.

  DERIVED, not chosen — the same construction as `head-com-frac`. The lumbar
  segment is given the uniform-cylinder 0.50 that `spine/above-fraction` already
  assumes about mass along a segment, and this is the value that makes the two
  together land the trunk's centre of mass exactly at `trunk-com-frac`:

      m_l x_l + m_t x_t = (m_l + m_t) x_trunk     (x measured from L5/S1, in
                                                   units of L5/S1 -> C7)

  It comes out about 0.553, i.e. the thorax's centre of mass a little above the
  middle of the thorax.

  ⚠ AND IT IS NOT WINTER'S EITHER, for the reason `trunk-com-frac` gives. Winter
  puts the thorax's centre of mass at 0.82 of C7-T1 -> T12-L1 from C7, i.e. 0.18
  from T12-L1 — LOW in the thorax, near the diaphragm, which is where the liver
  and the heart are. 0.553 is far from that. The discrepancy is entirely inherited
  from `trunk-com-frac`'s 0.50: preserving a trunk centre of mass that is too high
  forces the thorax's to be too high as well, and the split puts all of the error
  in the thorax because the lumbar took the uniform value. Anything this model
  says about a LORDOTIC posture is sensitive to it, because that is the posture in
  which the two segments stop being collinear. Stated rather than hidden."
  (let [{:keys [lumbar thorax]} trunk-mass-split
        total (+ lumbar thorax)
        x-lumbar (* 0.5 lumbar-span)
        x-thorax (/ (- (* trunk-com-frac total) (* lumbar x-lumbar)) thorax)]
    (/ (- x-thorax lumbar-span) thorax-span)))

;; Winter (4e) Table 4.1 — segment mass as a fraction of total body mass M.
(def ^:private mass-frac
  {;; --- the cervical spine, split three ways 2026-09-07 ------------------------
   ;; Winter gives ONE row here — "head and neck", 0.081 — and this model needed
   ;; three, because a neck with no joint in it cannot bend and cannot carry a
   ;; suboccipital muscle. See `cervical-split` below for where the three numbers
   ;; come from and which of them is measured (none of them is: the split is
   ;; REPRESENTATIVE, and the sources that were checked are named there).
   ;;
   ;; The three sum to 0.081 exactly, so `the-whole-body-is-accounted-for` holds
   ;; unchanged and every ground reaction this model computes is untouched.
   "lower_cervical" (:lower cervical-mass-frac)
   "upper_cervical" (:upper cervical-mass-frac)
   "head" (:head cervical-mass-frac)
   ;; --- the trunk, split in two at T12/L1 on 2026-09-08 -----------------------
   ;; Winter's OWN two rows, which sum to the 0.355 this model was using. See
   ;; `trunk-mass-split` for the citation and for the one level of mismatch at the
   ;; bottom that the split inherits.
   "thorax" (:thorax trunk-mass-split)
   "lumbar" (:lumbar trunk-mass-split)
   "pelvis" 0.142
   "upper_arm" 0.028
   "forearm" 0.016
   "hand" 0.006
   ;; --- the lower limb, added 2026-09-07 -------------------------------------
   ;; The same table, the rows this one was already quoting from. They are here
   ;; now because a posture model with no leg cannot answer where a STANDING body
   ;; is loaded, and the hip, knee and ankle carry the largest loads in the body.
   ;;
   ;; WHY WINTER AND NOT DE LEVA, since the README names both. de Leva (1996)
   ;; re-cuts Zatsiorsky-Seluyanov to joint-centre endpoints and gives the thigh
   ;; 14.16% of body mass against Winter's 10.0%; the two are not
   ;; interchangeable, because they divide the body in different places (de Leva
   ;; puts more of the buttock mass in the thigh). Mixing them would break the
   ;; one property of this table that can actually be checked: with these rows
   ;; the whole body is accounted for EXACTLY once,
   ;;   0.081 + 0.355 + 0.142 + 2(0.028 + 0.016 + 0.006) + 2(0.100 + 0.0465 + 0.0145) = 1.000
   ;; where the 0.081 is now the three cervical rows added together.
   ;; and `the-whole-body-is-accounted-for` asserts it. A mixed table sums to
   ;; 1.08, and every ground reaction this model computes would be 8% too large.
   "thigh" 0.100
   "shank" 0.0465
   "foot" 0.0145})

;; Segment length as a fraction of stature H (Drillis & Contini via Winter).
(def ^:private len-frac
  {;; The three cervical segments partition the SAME 0.182 H that `head_neck`
   ;; occupied — C7 to the vertex — at the two boundaries `cervical-split` states.
   ;; Their lengths therefore sum to 0.182 exactly and nothing above or below the
   ;; neck moved.
   "lower_cervical" (* head-neck-len-frac lower-cervical-span)
   "upper_cervical" (* head-neck-len-frac upper-cervical-span)
   "head" (* head-neck-len-frac head-span)
   ;; The two trunk segments partition the SAME 0.288 H that `thorax_abdomen`
   ;; occupied — L5/S1 to C7 — at the boundary `lumbar-span` states. Their lengths
   ;; therefore sum to 0.288 and nothing above or below the trunk moved.
   "thorax" (* trunk-len-frac thorax-span)
   "lumbar" (* trunk-len-frac lumbar-span)
   "pelvis" 0.095
   "upper_arm" 0.186
   "forearm" 0.146
   "hand" 0.108
   ;; thigh = greater trochanter to femoral condyle; shank = condyle to medial
   ;; malleolus; foot = HEEL TO TOE, not ankle to toe. The ankle joint sits about
   ;; a quarter of the way along the foot and `pose/heel-frac` places it there,
   ;; because the heel behind the ankle is half of why a person can stand still.
   "thigh" 0.245
   "shank" 0.246
   "foot" 0.152})

;; CoM location as a fraction of segment length, from the PROXIMAL joint (Winter Table 4.1).
(def ^:private com-frac
  {;; A neck segment is treated as a uniform cylinder (0.50); the head's is DERIVED
   ;; so that the three together keep the centre of mass Winter's single segment
   ;; had — see `head-com-frac`.
   "lower_cervical" 0.50
   "upper_cervical" 0.50
   "head" head-com-frac
   ;; The lumbar segment is a uniform cylinder (0.50); the thorax's is DERIVED so
   ;; that the two together keep the centre of mass the single trunk segment had —
   ;; see `thorax-com-frac`.
   "thorax" thorax-com-frac
   "lumbar" 0.50
   "pelvis" 0.50
   "upper_arm" 0.436
   "forearm" 0.430
   "hand" 0.506
   "thigh" 0.433
   "shank" 0.433
   ;; from the HEEL, which is where this model's foot segment starts
   "foot" 0.50})

;; The Python _MASS_FRAC dict iteration order (insertion order) drives build_body's loop;
;; preserve it so the segments map matches Python exactly.
(def segment-order
  ["lower_cervical" "upper_cervical" "head" "thorax" "lumbar" "pelvis"
   "upper_arm" "forearm" "hand" "thigh" "shank" "foot"])

(defn weight-n
  "Gravitational force on this segment (a single segment; not the pair)."
  [seg]
  (* (:mass-kg seg) gravity))

(defn com-m
  "Distance of the CoM from the proximal joint, along the long axis."
  [seg]
  (* (:com-frac seg) (:length-m seg)))

(defn seg
  "BodyModel.seg(name) — segment by name."
  [body name]
  (get (:segments body) name))

(def ^:private paired-set #{"upper_arm" "forearm" "hand" "thigh" "shank" "foot"})

(def below-l5s1
  "Segments that hang BELOW the origin of this model's frame, so that nothing
  above the lumbar spine is ever standing on them.

  It exists because `spine/above-fraction` answers how much of a segment sits
  above a level from a rank table of the two SPINAL segments, and treats anything
  it does not recognise as sitting above every trunk level. That was right while
  the only unrecognised segments were the arms — they hang from the girdle and do
  load every trunk level — and it became wrong the moment a leg existed. Without
  this set the thighs, shanks and feet, a third of body mass, would have been
  added to the compression at L5/S1 in every posture, silently."
  #{"pelvis" "thigh" "shank" "foot"})

(defn build-body
  "Construct the sagittal segment chain for a member of mass M and stature H.

  Paired limb segments (upper_arm/forearm/hand and thigh/shank/foot) store the mass of
  ONE limb; callers that load both arms onto a single midline joint multiply by 2. The
  lower limb is never loaded onto a midline joint — `pose` places both legs and `load`
  sums them, because a body stands on two feet and the whole point of the support model
  is which of them the ground is pushing on."
  ([] (build-body 70.0 1.70))
  ([total-mass-kg] (build-body total-mass-kg 1.70))
  ([total-mass-kg stature-m]
   (when (or (<= total-mass-kg 0) (<= stature-m 0))
     (throw (ex-info "total_mass_kg and stature_m must be positive"
                     {:type :value-error})))
   ;; ONE `array-map` call rather than `assoc` into one: a PersistentArrayMap
   ;; promotes itself to a hash map on the ninth `assoc`, on BOTH hosts, and this
   ;; table has ELEVEN rows since the neck was split. The promotion is silent and
   ;; it loses the insertion order that `kami-biomech-bridge/to-articulation` reads
   ;; through `vals`. `array-map` itself never promotes, at any size.
   (let [segments (apply
                   array-map
                   (mapcat
                    (fn [name]
                      [name {:name name
                             :mass-kg (* (mass-frac name) total-mass-kg)
                             :length-m (* (len-frac name) stature-m)
                             :com-frac (com-frac name)
                             :paired (contains? paired-set name)}])
                    segment-order))]
     {:total-mass-kg total-mass-kg :stature-m stature-m :segments segments})))

(def trunk-bases
  "The two segments that used to be `thorax_abdomen`, distal to proximal.

  Anything that asked for `thorax_abdomen` wanted one of two different things, and
  the split makes it say which: the WHOLE trunk between L5/S1 and C7 (this
  vector), or the part of it a shoulder girdle rides on (`\"thorax\"`).
  `load/trunk-borne-bases` wants the first; `girdle/thoracic-surface` wants the
  second — and the surface still spans both, because a scapula slides on a rib
  cage whose height this model measures from L5/S1."
  ["thorax" "lumbar"])

(def cervical-bases
  "The three segments that used to be `head_neck`, proximal to distal.

  Anything that asked for `head_neck` wanted one of two different things, and the
  split makes it say which: the WHOLE complex above C7 (this vector), or the SKULL
  (`\"head\"`). `load/trunk-borne-bases` and `load/frontal-moments` want the first;
  `load/head-tilt-from-vertical-deg` wants the second."
  ["lower_cervical" "upper_cervical" "head"])

(defn head-mass-kg
  "Mass of everything above C7 — the whole head-and-neck complex, which is what
  the C7/T1 disc carries and what `load/cervical-load` is handed as `head_weight_n`.

  ITS VALUE DID NOT MOVE WHEN THE NECK WAS SPLIT, and that is deliberate rather
  than incidental. Hansraj's forward-head model is fitted with a ~12 lb (5.44 kg)
  head, Winter's head-and-neck row is 5.43 kg at 67 kg, and this actor has always
  identified the two (`test-head-mass-matches-hansraj-head`). Handing
  `cervical-load` the SKULL alone after the split would have quietly cut the one
  validated quantity in this library by a fifth. The name says `head` because
  Hansraj's does; the docstring says which mass it is because the name is now
  ambiguous and was not before.

  ~5.4 kg at 67 kg matches Hansraj's 12-lb head (G7 anchor)."
  ([] (head-mass-kg 70.0))
  ([total-mass-kg] (* (reduce + 0.0 (map mass-frac cervical-bases)) total-mass-kg)))
