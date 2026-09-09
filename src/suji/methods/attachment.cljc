(ns suji.methods.attachment
  "suji (筋) — muscle paths: where a muscle attaches, and the moment arm that
  follows from it. Pure `.cljc`, stdlib only, no I/O.

  WHY THIS EXISTS. Until 2026-09-06 every moment arm in this actor was a CONSTANT
  in `muscle/specs` — 0.020 m for the cervical extensors, 0.055 m for erector
  spinae, and so on. A constant moment arm says the muscle's leverage does not
  change when the joint moves, which is false for every muscle in the body: the
  arm is the perpendicular distance from the joint centre to the muscle's line of
  action, and moving the joint moves that line. The constants were also the only
  place the anatomy appeared, so nothing in the model knew where a muscle *was*.

  A muscle here is a straight line between two attachment points, each stated ONCE
  in the local frame of the bone it attaches to. `pose` places the bones; the
  attachments ride along; the moment arm is then computed, not tabulated:

      r̂ = p_insertion − joint
      F̂ = unit(p_origin − p_insertion)      ; a muscle pulls its insertion toward its origin
      arm about axis â = ((r̂ × F̂) · â)

  CALIBRATION, stated so it cannot be mistaken for measurement. The offsets below
  are chosen so that at the NEUTRAL posture each derived arm reproduces the
  constant this actor already used — those constants are the anchor, and the
  geometry supplies only the variation away from neutral. `attachment-test` pins
  that agreement, so a change to the anatomy that silently moves the neutral
  leverage fails. This is a schematic line-of-action model (G7 `:representative`):
  it has no via points and no muscle volume, and it is NOT a measurement of
  anybody. It DOES have wrapping surfaces — the first landed on 2026-09-06 and
  this sentence went on saying otherwise until 2026-09-07. A wrapping
  surface is a floor the chord can beat (`moment-arm-detail`), so a radius taken
  from a moment-arm target rather than from the bone binds at every posture and
  quietly restores the constant this file exists to remove; `middle_deltoid` did
  exactly that.

  NON-DIAGNOSTIC (G1): a moment arm is a length."
  (:require [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

;; --- attachment sites --------------------------------------------------------
;; :segment  — the bone it rides on
;; :along    — fraction of that segment's length from its PROXIMAL joint
;; :ant / :lat — offset from the bone's long axis, as a fraction of STATURE,
;;               along that segment's own anterior / lateral basis vectors.
;;               Anthropometric offsets scale with stature, so a fraction of
;;               stature is what stays true across bodies; a fraction of the
;;               segment would make a long-necked model's muscles wander.
;;
;; :lat is stated for the LEFT side. `mirror` negates it for the right, so the
;; anatomy is described once and the asymmetry comes from the posture. Values near
;; half the biacromial breadth (see `pose/biacromial-frac`) put a site on the
;; acromion; smaller ones sit between there and the midline.

(defn- trunk-frac->lumbar
  "Re-express a site stated as a fraction of the OLD single trunk segment
  (L5/S1 -> C7) as a fraction of the `lumbar` segment that now carries its lower
  0.35.

  The world point is unchanged at the neutral posture, where the two trunk
  segments are collinear. The derivation is written out rather than the quotient
  pasted in, so the number the site was actually CHOSEN as — a fraction of the
  whole trunk, which is how every one of these was reasoned about — stays
  readable, and so that moving `segment/lumbar-span` moves the sites with it
  instead of silently leaving them behind."
  [trunk-along]
  (/ trunk-along segment/lumbar-span))

(defn- trunk-frac->thorax
  "The same re-expression for a site on the upper 0.65 — see
  `trunk-frac->lumbar`. A site past 1.0 stays past 1.0: `trapezius`'s origin runs
  onto the nuchal ligament above C7 and is stated that way deliberately."
  [trunk-along]
  (/ (- trunk-along segment/lumbar-span) segment/thorax-span))

(def muscles
  "Line-of-action model for the muscle groups this actor already solved. PCSA
  values are unchanged from `muscle/specs` — this namespace changes where the
  force acts, not how much force is available.

  `:acts-about` is the joint the group's moment is taken about; `:task` says which
  equilibrium it belongs to (see `recruit`), because two muscles can cross the same
  joint and still not be substitutes for one another."
  (array-map
   "cervical_extensors"
   {:name "cervical_extensors" :pcsa-cm2 12.0
    :acts-about :c7 :task :cervical-extension
    ;; posterior to the neck axis, running from the upper thorax to the occiput
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.90) :ant -0.01965 :lat 0.0}
    :insertion {:segment "lower_cervical" :along 0.16666666666666669 :ant -0.00982 :lat 0.0}
    ;; the cervical vertebrae. The extensors lie ON them, so their leverage floors
    ;; at the column's radius instead of thinning toward zero as the head folds —
    ;; without this the straight-line arm falls under the leverage floor at large
    ;; combined trunk+head flexion and `recruit` declines an ordinary posture.
    :wrap {:radius-m 0.012 :sign 1.0}
    ;; ⚠ THIS `:source` SAID `occipital insertion` UNTIL 2026-09-07, AND THAT IS THE
    ;; SENTENCE THAT HID THE GAP. The insertion is at 0.05 of `head_neck`, which on a
    ;; 1.70 m body is 15.5 mm above C7 — BELOW C6/C7, which `spine/levels` puts at
    ;; 18.6 mm. An occipital insertion would have to reach 0.42 (130 mm above C7; see
    ;; the derivation in the block below). So this group crosses exactly ONE
    ;; intervertebral level, C7/T1, and nothing it places touches the skull. Its
    ;; 0.020 m calibration target is not a measured muscle arm either: it is
    ;; `load/cervical-ext-arm-m`, the effective lever the Hansraj model is fitted
    ;; with. What this entry actually places is the deep cervical group that spans
    ;; the cervicothoracic junction — semispinalis cervicis, multifidus, longissimus
    ;; and spinalis cervicis — and THAT is why the capitis muscles below are added
    ;; alongside it rather than carved out of it.
    ;;
    ;; ⚠ THE 12.0 IS STILL UNPROVENANCED, AND I LOOKED. Kamibayashi & Richmond
    ;; 1998 is the source the capitis muscles below are measured from, and I read
    ;; its Table 3–3 directly (`pdftotext -layout` of
    ;; https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf, which reprints
    ;; it with attribution). Its FIFTEEN muscle rows are: sternocleidomastoideus,
    ;; clavotrapezius, acromiotrapezius, rhomboideus, rectus capitis posterior
    ;; major and minor, obliquus capitis superior and inferior, longus capitis,
    ;; splenius (capitis + cervicis as one), semispinalis capitis, scalenus
    ;; anterior / medius / posterior, and levator scapulae.
    ;;
    ;; ⚠ THE COUNT WAS WRONG UNTIL 2026-09-08 AND THE LIST WAS NOT. This said
    ;; "fourteen rows" in three places (here and twice in the README) while the
    ;; enumeration below it names FIFTEEN muscles, and re-extracting the table
    ;; finds fifteen rows carrying a PCSA mean. The likeliest loss is
    ;; acromiotrapezius, whose name and numbers land on different lines in the
    ;; `pdftotext -layout` output. NOTHING THAT RESTS ON THIS MOVES: the four
    ;; muscles this entry lumps are absent from the table either way, and so is
    ;; rectus capitis anterior — both were re-checked against the extracted text,
    ;; not inferred from the count.
    ;;
    ;; Semispinalis CERVICIS, multifidus, longissimus and spinalis cervicis — the
    ;; four this entry places — are NOT in it. So the obvious source cannot
    ;; provenance this number, and that is recorded here as a measured negative
    ;; rather than left for the next reader to rediscover.
    ;;
    ;; What follows from it: the sum 12.00 + 10.80 + 8.52 = 31.32 cm² of posterior
    ;; neck extensor is only defensible while the 12.0 means the deep group alone.
    ;; If it was ever meant as the whole posterior neck, this model overstates
    ;; cervical extensor capacity by 12.0 cm² and every cervical %MVC here is
    ;; correspondingly low. That question needs a source covering the deep
    ;; cervical extensors; it cannot be settled from inside this model, and
    ;; nothing here should be tuned until it is.
    :source "representative; a deep cervical group spanning the cervicothoracic junction. The insertion is 15.5 mm above C7 at reference stature, i.e. below C6/C7 — it is NOT occipital, and the file said it was until 2026-09-07. Kept low and well posterior so the line stays behind the joint through flexion: a straight line from a HIGH insertion crosses in front of C7 around 30 deg and would report the extensors as flexors. The 0.020 m neutral target is load/cervical-ext-arm-m, the Hansraj effective lever, not a measured muscle moment arm. WHAT THIS ENTRY STANDS FOR, moved into the :source on 2026-09-08 because it is the thing that blocks C2/C3 and a blocker stated only in a comment is one no test can hold: it places the deep cervical group that spans the cervicothoracic junction - semispinalis cervicis, multifidus, longissimus and spinalis cervicis. Its 12.0 cm2 is UNPROVENANCED. Kamibayashi & Richmond 1998 Table 3-3, the source every measured PCSA in this file comes from, has fifteen rows and not one of those four is among them, which was checked in the fetched full text rather than assumed. So the muscles that act at C2/C3 in anatomy are already nominally inside this lump, and carving them out would need a number to divide that does not exist. See spine-test/nothing-is-solved-at-c2c3-and-the-reason-is-provenance."}

   ;; --- the muscles that hold the head up ---------------------------------------
   ;; Added 2026-09-07. Until then NOTHING IN THIS MODEL REACHED THE SKULL. The most
   ;; cranial attachment in the whole set was `levator_scapulae` at `head_neck` 0.22
   ;; and `spine/levels` puts C3/C4 at 0.24, so the top cervical level was crossed by
   ;; nothing at all — in the one region a forward-head posture actually loads.
   ;; Measured at `laptop-on-lap`, 70 kg / 1.70 m, before this block existed:
   ;;
   ;;     C7/T1  401.56 N  cervical_extensors, levator_scapulae, upper_trapezius
   ;;     C6/C7   68.66 N  levator_scapulae, upper_trapezius
   ;;     C5/C6   34.51 N  levator_scapulae
   ;;     C4/C5   34.51 N  levator_scapulae
   ;;     C3/C4    0.00 N  nothing
   ;;
   ;; WHERE THE SKULL SITS, derived from the model rather than guessed. This block
   ;; was written on the morning of 2026-09-07, when C7→vertex was ONE segment
   ;; 0.3094 m long: `spine/levels` then spaced its five cervical levels 0.06 of that
   ;; apart — 18.6 mm, a cervical vertebra plus its disc — so continuing the model's
   ;; own spacing upward past C3/C4 (0.24) gave C2/C3 0.30, C1/C2 0.36, and the
   ;; occipito-atlantal joint 0.42, i.e. 130 mm above C7.
   ;;
   ;; LATER THAT DAY THE DERIVATION BECAME THE SEGMENTATION. `segment` cuts the
   ;; neck at exactly those two places, so the fractions above are now segment
   ;; boundaries and every `:along` in this file that used to be measured along
   ;; `head_neck` has been rescaled onto whichever of the three it lands on:
   ;;
   ;;     head_neck 0.05 → lower_cervical 0.1667   (cervical_extensors)
   ;;     head_neck 0.07 → lower_cervical 0.2333   (nuchal_ligament)
   ;;     head_neck 0.10 → lower_cervical 0.3333   (upper_trapezius)
   ;;     head_neck 0.16 → lower_cervical 0.5333   (scalenes)
   ;;     head_neck 0.22 → lower_cervical 0.7333   (levator_scapulae)
   ;;     head_neck 0.44 → head 0.0345             (sternocleidomastoid)
   ;;     head_neck 0.45 → head 0.0517             (splenius_capitis)
   ;;     head_neck 0.48 → head 0.1034             (semispinalis_capitis)
   ;;
   ;; At the NEUTRAL posture the three segments are collinear, so every one of
   ;; those sites lands on the same world point it did before and every neutral
   ;; moment arm this file is calibrated to is unchanged to the bit. Away from
   ;; neutral they do not, which is the whole purpose of the split.
   ;;
   ;; ⚠ WHAT WAS NOT HERE UNTIL THE NECK HAD JOINTS. This paragraph used to say the
   ;; suboccipitals could not be written down at all, because with one rigid
   ;; head-and-neck segment BOTH ENDS of each rode on the same bone —
   ;; `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names
   ;; that shape as the error that produces a constant-looking arm. It ended: "What
   ;; is missing is a JOINT, not an attachment, and no attachment can supply it."
   ;; That was right, and the joint is here now: three of the four cross the
   ;; atlanto-occipital joint and are below.
   ;;
   ;; ⚠ OBLIQUUS CAPITIS INFERIOR IS STILL NOT HERE. It runs from the spinous
   ;; process of C2 to the transverse process of C1, and this model puts the atlas
   ;; and the axis in ONE segment, `upper_cervical` — so both its ends are still on
   ;; one bone and it would still be the error above. Splitting the atlas from the
   ;; axis would fix it and would open the atlanto-axial joint, whose cardinal
   ;; motion is 40.5 deg of AXIAL ROTATION against about 10 deg of flexion; a
   ;; sagittal model would pay for a vertebra-level mass split that no
   ;; anthropometric table publishes and collect almost none of the benefit. See
   ;; `segment/head-share-of-complex` for the three tables that were checked.
   ;;
   ;; PCSA HERE IS MEASURED, and it is the second measured column in this file after
   ;; the lower limb's. Kamibayashi LK, Richmond FJR, "Morphometry of human neck
   ;; muscles", Spine 23(12):1314–1323, 1998 — 14 neck muscles from 10 human cadavers.
   ;; The table was read as Table 3–3 ("Morphometric Parameters of Human Neck
   ;; Muscles", p.65) of Vasavada AN, "Architectural Design and Function of Human Back
   ;; Muscles", chapter 3 of Rothman-Simeone The Spine, which reproduces it with the
   ;; attribution printed under the table; full text obtained 2026-09-07 from
   ;; https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf and read in full,
   ;; not as an abstract. Per-muscle PCSA means (one side, cm²), with the range:
   ;;
   ;;     semispinalis capitis     5.40 (1.30)   3.93–7.32   N=9
   ;;     splenius (cap + cerv)    4.26 (1.04)   2.57–5.48   N=9
   ;;     sternocleidomastoideus   3.72 (0.91)   1.81–5.26   N=9
   ;;
   ;; A midline group in this model carries the BILATERAL sum (see `muscle`'s note on
   ;; `cervical_extensors` and `erector_spinae`), so each is doubled below.
   ;;
   ;; THE DOUBLE-COUNTING DECISION, since a lumped `cervical_extensors` already exists
   ;; and `load/cervical-load` is calibrated: these are ADDED as things the lump
   ;; EXCLUDES, not carved out of it. The evidence is the lump's own geometry, above —
   ;; it inserts below C6/C7 and crosses one level, so no muscle with a cranial
   ;; attachment can be inside it. The arithmetic and what it costs are written out in
   ;; the `:source` of `semispinalis_capitis`, because a decision stated only in a
   ;; commit message is a decision nobody can check later.
   ;;
   ;; THE MOMENT ARMS are representative, calibrated the way the rest of this file's
   ;; are: the offsets put the neutral arm on a stated target and everything away from
   ;; neutral is geometry. The targets are 30 mm for semispinalis capitis and 38 mm
   ;; for splenius capitis, and their ORDER is sourced even though their values are
   ;; not — Vasavada's chapter states that "the semispinalis capitis has shorter
   ;; fascicle lengths, but also a smaller moment arm than the splenius capitis"
   ;; (p.68), and its Figures 3–14/3–15 put neck moment arms on a 0–4 cm scale.

   "semispinalis_capitis"
   {:name "semispinalis_capitis" :pcsa-cm2 10.80
    :acts-about :c7 :task :cervical-extension
    ;; upper thoracic and lower cervical transverse/articular processes → the
    ;; occipital bone between the superior and inferior nuchal lines. The insertion at
    ;; 0.48 of `head_neck` is 148 mm above C7, about 19 mm above the occipito-atlantal
    ;; joint the model's own level spacing puts at 0.42 — the nuchal lines are on the
    ;; occipital squama, above the foramen magnum.
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.85) :ant -0.0168 :lat 0.0}
    :insertion {:segment "head" :along 0.10344827586206896 :ant -0.0191 :lat 0.0}
    ;; THE CERVICAL COLUMN — the same surface `cervical_extensors` declares, at the
    ;; same 0.012 m, because it is the same column and this file's rule is one bone
    ;; one radius (`the-abduction-floor-is-the-humeral-head-and-it-is-a-floor`).
    ;; It is needed, and a straight line alone is not enough: measured 2026-09-07, the
    ;; unwrapped chord from this insertion has a +29.8 mm extension arm at neutral and
    ;; is −9.0 mm at the `laptop-on-lap` head tilt of 63.5°, i.e. the model's principal
    ;; head extensor would be reported as a FLEXOR in the posture it exists to
    ;; describe. That is the same defect the cervical group and the anterior deltoid
    ;; had, and it is worse here because the insertion is 148 mm out along the lever.
    ;; A muscle lying further out from the bone has a larger effective radius and this
    ;; model does not know how much larger, so the column's own radius is used: that
    ;; makes the floor a LOWER bound on the leverage, and therefore an OVERSTATEMENT
    ;; of the force and of the compression that follows from it. Stated rather than
    ;; corrected by a factor nobody measured.
    :wrap {:radius-m 0.012 :sign 1.0}
    ;; TWO-JOINT, and it always was — it runs from the thorax to the OCCIPUT, so it
    ;; crosses the atlanto-occipital joint as well as the cervicothoracic junction
    ;; it is solved at. Until 2026-09-08 that second joint was named only in
    ;; `load/capitis-groups`, a set in another namespace, because there was no
    ;; solver that could use it: `recruit`'s closed form takes one constraint, so
    ;; the fact was a correction term rather than a coupling. It is declared here
    ;; now because `recruit/solve` builds its constraint matrix from `spans?`, and
    ;; a muscle whose second joint is not in its own entry is a muscle the coupled
    ;; solve will not feed.
    :crosses {:joint :atlanto-occipital}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, 5.40 cm2 per side x 2 sides = 10.80 cm2 bilateral (measured; N=9 cadavers, range 3.93-7.32). ADDED ALONGSIDE the lumped cervical_extensors rather than carved out of it, and the arithmetic is this: the lump is 12.00 cm2 and its insertion sits 15.5 mm above C7, below C6/C7, so it crosses one level and contains nothing cranial; total modelled cervical extensor cross-section therefore goes 12.00 -> 12.00 + 10.80 + 8.52 = 31.32 cm2, a factor of 2.61. WHAT THAT COSTS IF THE LUMP WAS MEANT AS THE WHOLE NECK: this model then overstates neck extensor capacity by 12.00 cm2 and every cervical %MVC it reports is correspondingly low. That reading cannot be settled from inside the model, because the 12.00 has no provenance to check - it is :representative with no citation, and Kamibayashi & Richmond do not measure the deep cervical group it places (semispinalis cervicis, multifidus, longissimus and spinalis cervicis are absent from their table). The one-line change that would settle it the other way is muscle/specs \"cervical_extensors\" :pcsa-cm2 12.0 -> 3.356, which is 12.0 x 7.50/26.82, the suboccipital residual's share of the measured bilateral total 10.80 + 8.52 + 7.50 = 26.82 cm2. It is NOT made here: muscle.cljc is landed, and this is reported instead. Neutral extension arm calibrated to a representative 0.030 m."}

   "splenius_capitis"
   {:name "splenius_capitis" :pcsa-cm2 8.52
    :acts-about :c7 :task :cervical-extension
    ;; ligamentum nuchae and the spinous processes of C7–T3 → the mastoid process and
    ;; the lateral third of the superior nuchal line. Superficial to semispinalis
    ;; capitis, so its origin sits further posterior (the spinous process tips rather
    ;; than the transverse processes) and its extension arm is the larger of the two.
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.90) :ant -0.0230 :lat 0.0}
    :insertion {:segment "head" :along 0.05172413793103453 :ant -0.0206 :lat 0.0}
    ;; the same cervical column, the same radius — see the note above.
    :wrap {:radius-m 0.012 :sign 1.0}
    ;; TWO-JOINT for the same reason as semispinalis capitis above — thorax to
    ;; occiput, so it crosses the atlanto-occipital joint too.
    :crosses {:joint :atlanto-occipital}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, splenius 4.26 cm2 per side x 2 = 8.52 cm2 bilateral (measured; N=9, range 2.57-5.48). ⚠ THAT ENTRY IS THE WHOLE SPLENIUS: the table gives one mass and one PCSA for splenius capitis and splenius cervicis together and separates them only by fascicle length (12.3 cm and 14.7 cm). This entry therefore stands for both, which puts a share of splenius cervicis's cross-section on a cranial insertion it does not have - cervicis runs to the C1-C3 transverse processes. It crosses the same six levels either way, so the error is in WHERE the force is applied on the skull and not in which levels carry it. Neutral extension arm calibrated to a representative 0.038 m, larger than semispinalis capitis's because Vasavada (chapter 3, p.68) states the semispinalis capitis has the smaller of the two."}

   "sternocleidomastoid"
   {:name "sternocleidomastoid" :pcsa-cm2 7.44
    :acts-about :c7 :task :cervical-extension
    ;; manubrium and the medial third of the clavicle → the mastoid process and the
    ;; lateral superior nuchal line. It passes WELL ANTERIOR to the cervical column in
    ;; the lower neck, so about C7 it is a FLEXOR and its moment arm is negative.
    ;;
    ;; IT WILL BE REFUSED IN ALMOST EVERY POSTURE THIS ACTOR REPORTS, AND THAT IS THE
    ;; CORRECT ANSWER RATHER THAN A FAILURE. `:cervical-extension` shares an unsigned
    ;; load through `recruit/share`, so a candidate with a negative coefficient comes
    ;; back `:refused :acts-the-wrong-way` — the muscle would add to the load rather
    ;; than resist it, which at a forward-head posture it would. `muscle` then marks it
    ;; `:antagonist? true` because the load WAS placed, so it is not counted as a gap.
    ;; The refusal has to be for that reason and not `:no-line-of-action`, which would
    ;; mean the geometry was degenerate; `the-flexor-is-refused-for-being-a-flexor`
    ;; pins which.
    ;;
    ;; WHAT IT STILL DOES NOT DO. A refused instance carries no `:force-n`, so it
    ;; contributes nothing to `spine`'s compression even though its line crosses every
    ;; cervical level — a real sternocleidomastoid holding a head against a headrest
    ;; compresses the neck, and this model cannot say so. It is also modelled MIDLINE,
    ;; like the other two, so its lateral flexion and axial rotation are absent: those
    ;; would need it paired and in `:cervical-lateral-flexion`, and a muscle belongs to
    ;; one task here.
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.96) :ant 0.0260 :lat 0.0}
    :insertion {:segment "head" :along 0.03448275862068969 :ant -0.0040 :lat 0.0}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, 3.72 cm2 per side x 2 = 7.44 cm2 bilateral (measured; N=9, range 1.81-5.26). No double count to decide: the lumped cervical_extensors is an extensor group and this is the antagonist, which was absent from the model entirely. Neutral arm -0.036 m, a FLEXION arm about C7; Vasavada chapter 3 Figure 3-14 puts the sternocleidomastoid's lower-cervical flexion moment arm between about -2 and -4 cm and has it increasing in flexed postures."}

   ;; --- the suboccipitals -------------------------------------------------------
   ;; Added 2026-09-07, in the same hour the neck got its joints, because until it
   ;; had them these four muscles were the thing this model could not say. Three of
   ;; them are here; obliquus capitis inferior is not, for the reason given above.
   ;;
   ;; THEY ACT ABOUT THE ATLANTO-OCCIPITAL JOINT and nothing else in this model
   ;; does. That is the point of them: they are the only muscles whose equilibrium
   ;; is the one the split created, so if the split had bought nothing they would
   ;; have nowhere to act.
   ;;
   ;; PCSA IS MEASURED. Kamibayashi LK, Richmond FJR, "Morphometry of human neck
   ;; muscles", Spine 23(12):1314-1323, 1998, read as Table 3-3 of Vasavada AN,
   ;; "Architectural Design and Function of Human Back Muscles" (Rothman-Simeone
   ;; The Spine, ch.3, p.65) — the same table and the same fetched PDF the capitis
   ;; muscles above are sourced from, https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf,
   ;; full text read on 2026-09-07. Per side, mean (SD), range:
   ;;
   ;;     rectus capitis posterior major   0.93 (0.33)  0.44-1.45  N=9
   ;;     rectus capitis posterior minor   0.50 (0.19)  0.48-0.83  N=9
   ;;     obliquus capitis superior        1.03 (0.46)  0.29-1.59  N=8
   ;;     obliquus capitis inferior        1.29 (0.54)  0.69-1.73  N=9   ← not modelled
   ;;
   ;; They sum to 3.75 cm² per side, which is the figure this file already quoted
   ;; when it was explaining why they were absent. Each is doubled below, because a
   ;; midline group here carries the bilateral sum. The three that can be modelled
   ;; are 4.92 cm² of the 7.50; the missing obliquus capitis inferior is 2.58 cm²,
   ;; a third of the suboccipital cross-section, and it is a ROTATOR of C1 on C2
   ;; rather than a sagittal extensor, so what its absence costs this sagittal model
   ;; is smaller than its share.
   ;;
   ;; THE GEOMETRY IS CALIBRATED AGAINST MEASURED LENGTHS, NOT AGAINST INVENTED
   ;; MOMENT ARMS, and that is a departure from the rest of this file. Everywhere
   ;; else the offsets are chosen so the neutral arm reproduces a constant this
   ;; actor already used; there is no such constant here, because there was no such
   ;; muscle. Inventing a moment-arm target and then calibrating to it would be a
   ;; number pretending to be an anchor. Kamibayashi & Richmond DO publish a muscle
   ;; length for each of these (same table, `MUSCLE LENGTH (cm)` range), so the
   ;; sites are placed from bony landmarks and the resulting line LENGTH is checked
   ;; against the measurement — `the-suboccipital-lengths-are-checked-against-the-
   ;; measurement` reports the comparison. The moment arms are then whatever the
   ;; geometry gives, and they are reported rather than targeted.
   ;;
   ;; ⚠ THE MODEL'S UPPER CERVICAL IS SHORT. `upper_cervical` spans C2/C3 to the
   ;; occipital condyles and is 37 mm at reference stature, because `segment` cuts
   ;; it at the model's own uniform 18.6 mm level spacing; a real atlas plus axis is
   ;; nearer 50 mm, since C2 with its dens is taller than a typical vertebra and the
   ;; uniform spacing cannot know that. Every muscle spanning this joint therefore
   ;; comes out SHORTER here than the cadaver measurement, which is exactly what the
   ;; length check reports and is not corrected by a factor nobody measured. A short
   ;; muscle also changes length by a larger FRACTION for the same joint rotation, so
   ;; the force-length term falls off faster than it should — biasing these three
   ;; toward reporting less available force, i.e. a higher %MVC, than a correctly
   ;; scaled model would.

   "rectus_capitis_posterior_major"
   {:name "rectus_capitis_posterior_major" :pcsa-cm2 1.86
    :acts-about :atlanto-occipital :task :atlanto-occipital-extension
    ;; spinous process of the axis (C2) → lateral part of the inferior nuchal line.
    ;; The C2 spinous is the most prominent process in the upper neck; 30 mm behind
    ;; the column axis at reference stature.
    :origin {:segment "upper_cervical" :along 0.15 :ant -0.0176 :lat 0.0}
    :insertion {:segment "head" :along 0.055 :ant -0.0159 :lat 0.0}
    ;; the same 0.012 m column radius the other posterior cervical muscles declare
    ;; — one bone one radius. It is a FLOOR and it does not bind at any reference
    ;; posture (the neutral arm is more than twice it, and the atlanto-occipital
    ;; joint EXTENDS as the head flexes on the trunk, which lengthens the arm);
    ;; `the-suboccipital-wrap-floor-never-binds` says so rather than leaving a
    ;; surface in the file whose only effect is to look like diligence.
    :wrap {:radius-m 0.012 :sign 1.0}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, 0.93 cm2 per side x 2 = 1.86 cm2 bilateral (measured; N=9, range 0.44-1.45). Attachment offsets are REPRESENTATIVE landmark distances (C2 spinous 30 mm posterior; inferior nuchal line 27 mm posterior, 10 mm above the condyles) and are NOT calibrated to a moment arm - no measured moment arm about the atlanto-occipital joint was obtained. Modelled midline: the pair's lateral components cancel and what is solved is the sagittal resultant, so this entry says nothing about head rotation."}

   "rectus_capitis_posterior_minor"
   {:name "rectus_capitis_posterior_minor" :pcsa-cm2 1.00
    :acts-about :atlanto-occipital :task :atlanto-occipital-extension
    ;; posterior tubercle of the atlas (C1) → the occiput medial to and below the
    ;; inferior nuchal line. The shortest muscle in this model.
    :origin {:segment "upper_cervical" :along 0.72 :ant -0.0129 :lat 0.0}
    :insertion {:segment "head" :along 0.070 :ant -0.0141 :lat 0.0}
    :wrap {:radius-m 0.012 :sign 1.0}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, 0.50 cm2 per side x 2 = 1.00 cm2 bilateral (measured; N=9, range 0.48-0.83). Offsets representative (C1 posterior tubercle 22 mm posterior; occipital insertion 24 mm posterior, 13 mm above the condyles). Its modelled length falls SHORT of the 2.6-3.1 cm the same table measures, for the reason stated above the block: this model's upper cervical segment is 37 mm where an atlas plus axis is nearer 50."}

   "obliquus_capitis_superior"
   {:name "obliquus_capitis_superior" :pcsa-cm2 2.06
    :acts-about :atlanto-occipital :task :atlanto-occipital-extension
    ;; transverse process of the atlas → the occiput between the nuchal lines,
    ;; lateral. It runs up and BACK, so it has the largest posterior travel of the
    ;; three and the smallest sagittal moment arm — most of its line is vertical.
    :origin {:segment "upper_cervical" :along 0.72 :ant -0.0059 :lat 0.0}
    :insertion {:segment "head" :along 0.150 :ant -0.0135 :lat 0.0}
    :wrap {:radius-m 0.012 :sign 1.0}
    :source "PCSA Kamibayashi & Richmond 1998 Table 3-3, 1.03 cm2 per side x 2 = 2.06 cm2 bilateral (measured; N=8, range 0.29-1.59). Offsets representative. MODELLED MIDLINE AND THEREFORE SAGITTAL ONLY, which costs more here than it does for the other two: the real obliquus capitis superior runs from a TRANSVERSE process, 25 mm lateral, and its lateral flexion and its contribution to steadying the head in rotation are absent. What is modelled is the sagittal component of a bilateral pair."}

   ;; --- the upper cervical FLEXORS ----------------------------------------------
   ;; Added 2026-09-08, and the gap they close was named by the file that created
   ;; it. `load/atlanto-occipital-moment` has said since 2026-09-07:
   ;;
   ;;     "The big superficial extensors, sized by the load at C7, over-extend the
   ;;      joint above them; what a real neck balances that with is its upper
   ;;      cervical FLEXORS — longus capitis, rectus capitis anterior and lateralis
   ;;      — and this model has none of them."
   ;;
   ;; It had none. Measured 2026-09-08 before this block existed: every muscle whose
   ;; `:acts-about` is `:atlanto-occipital` was one of the three suboccipital
   ;; EXTENSORS, every one of them belonged to `:atlanto-occipital-extension`, and
   ;; the model contained no task whose load was a flexion moment about any cervical
   ;; joint. So the joint could not express co-contraction, could not balance the
   ;; capitis surplus, and could not represent a head held back against a headrest —
   ;; at `head-flexion -15 deg` the skull's centre of mass sits BEHIND the condyles,
   ;; gravity extends the head, the moment about the condyles is -0.766 N·m, and
   ;; nothing in the model could resist it.
   ;;
   ;; WHY IT IS A SECOND TASK AND NOT A MIRROR-PAIRED ONE. The elbow, the wrist and
   ;; the knee state their agonist and antagonist in a single `share-signed` task,
   ;; and the sign of the load picks the side. That shape would work here and it was
   ;; not chosen, because it would take something away: `share-signed` REFUSES the
   ;; idle side, and `atlanto-occipital-moment` deliberately hands the extensors a
   ;; load of zero rather than a refusal — "A zero load IS a placed load: the
   ;; suboccipitals come back at 0 N rather than refused, which is the difference
   ;; between 'nothing is asked of them here' and 'this model could not answer'".
   ;; Two tasks with complementary loads (`:residual-nm` and `:over-supplied-nm`,
   ;; exactly one of which is non-zero) give the same forces AND keep that
   ;; distinction on both sides of the joint.
   ;;
   ;; ⚠ WHAT THE FLEXORS ARE ASKED TO CARRY IS MOSTLY NOT GRAVITY. Measured over the
   ;; three reference workstations and a head-flexion sweep, the net moment about the
   ;; condyles is a FLEXION demand at every posture except exact neutral, and at a
   ;; desk posture almost all of it is `:over-supplied-nm` — the surplus the
   ;; uncoupled solve leaves when the two capitis muscles, sized by the load at C7,
   ;; are counted at a joint their own equilibrium was not solved for. The flexors
   ;; therefore report over-MVC in ordinary postures. That is not a claim about a
   ;; person and it is not clamped: it is this model measuring, in newtons of muscle
   ;; the anatomy actually has, how big its own decomposition error is. See
   ;; `muscle/solve-muscle-tensions`, which reports the gravitational and the
   ;; decomposition halves of that load separately on every flexor row so the two
   ;; cannot be read as one.
   ;;
   ;; ⚠ STERNOCLEIDOMASTOID WAS CONSIDERED AND REJECTED ON THE GEOMETRY. It is the
   ;; model's existing neck flexor and the obvious candidate to re-task, and it is
   ;; the wrong muscle: it inserts on the MASTOID PROCESS, which is BEHIND the
   ;; occipital condyles, so about the atlanto-occipital joint it is an EXTENSOR.
   ;; Measured 2026-09-08 on a 70 kg / 1.70 m body, its moment arm about that joint
   ;; is +4.90 mm at head -15 deg, +4.54 mm at neutral and +4.36 mm at the
   ;; `laptop-on-lap` head tilt — positive (extension) at every posture, and BELOW
   ;; `recruit/min-coeff` (5 mm) at all of them. So re-tasking it to
   ;; `:atlanto-occipital-flexion` would give it a negative coefficient and it would
   ;; be refused for acting the wrong way; re-tasking it to the EXTENSION task would
   ;; give it a coefficient below the floor and it would be refused for that. It is
   ;; not a flexor here and not usable as one, which is why the joint needed muscles
   ;; of its own rather than a re-labelling. This is also the mechanism of the
   ;; forward-head posture it is famous for: lower cervical flexion with upper
   ;; cervical EXTENSION, which is exactly the shape `pose/cervical-partition`
   ;; produces.

   "longus_capitis"
   {:name "longus_capitis" :pcsa-cm2 1.84
    :acts-about :atlanto-occipital :task :atlanto-occipital-flexion
    ;; anterior tubercles of the transverse processes of C3-C6 -> the basilar part
    ;; of the occipital bone. Vasavada, chapter 3: "the longus capitis runs from the
    ;; anterior surface of transverse processes to the baso-occiput ... Because it
    ;; lies close to the vertebral bodies, it has only a small flexion moment arm."
    ;; The origin is placed at the MIDPOINT of the C3-C6 span in this model's own
    ;; level spacing (`spine/levels` puts C6/C7 at 0.2 and C3/C4 at 0.8 of
    ;; `lower_cervical`), 10 mm anterior to the column axis, which is about where an
    ;; anterior tubercle sits relative to the centre of a cervical body. The
    ;; insertion is 15 mm anterior to the condyles and 8 mm up the skull's long axis,
    ;; because the clivus rises as it runs forward.
    :origin {:segment "lower_cervical" :along 0.50 :ant 0.0058824 :lat 0.0}
    :insertion {:segment "head" :along 0.045 :ant 0.0088235 :lat 0.0}
    ;; NO WRAPPING SURFACE, and the absence is checked rather than assumed. The
    ;; posterior cervical muscles declare the column's 0.012 m radius because their
    ;; chords swing THROUGH the joint as the head folds; this one runs down the front
    ;; of the same column and its chord moves 0.8 mm across the model's whole range
    ;; (-14.69 mm at head -15 deg to -13.90 mm at 60 deg). A floor here would bind
    ;; at nothing and would only look like diligence.
    :crosses {:joint :c2c3}
    :source "PCSA Kamibayashi LK, Richmond FJR, Morphometry of human neck muscles, Spine 23(12):1314-1323, 1998, Table 3-3 as reprinted in Vasavada AN, Architectural Design and Function of Human Back Muscles, Rothman-Simeone The Spine ch.3 p.65: 0.92 (0.35) cm2 per side, range 0.54-1.63, N=7 -> x2 sides = 1.84 cm2 bilateral (MEASURED). Full text fetched 2026-09-08 from https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf and read with pdftotext -layout, not an abstract; the same row also gives mass 3.7 (1.2) g, muscle length 7.8-11.1 cm mean 9.2 (1.4), NF length 3.8 (1) cm. ATTACHMENT OFFSETS ARE REPRESENTATIVE LANDMARK DISTANCES and are NOT calibrated to a moment arm, for the same reason as the suboccipitals: no measured moment arm about the atlanto-occipital joint was obtained, and inventing a target to calibrate to would be a number pretending to be an anchor. They are checked against the MEASURED LENGTH instead - the modelled line is 91.75 mm at neutral against 78-111 mm measured, mean 92 - and the moment arm is whatever the geometry gives, -14.5 mm at neutral. TWO-JOINT: it also crosses C2/C3, and that moment is reported as :secondary-moment-nm rather than solved, because recruit's closed form takes one constraint. Modelled midline, so its ipsilateral rotation - which Vasavada attributes to the superomedial fascicle orientation - is absent."}

   "rectus_capitis_anterior"
   {:name "rectus_capitis_anterior" :pcsa-cm2 1.00
    :acts-about :atlanto-occipital :task :atlanto-occipital-flexion
    ;; anterior surface of the lateral mass of the atlas -> the basilar occiput just
    ;; behind and below the longus capitis insertion. It is the direct anterior
    ;; counterpart of rectus capitis posterior minor: both connect C1 to the skull
    ;; and neither crosses any intervertebral disc.
    :origin {:segment "upper_cervical" :along 0.72 :ant 0.0052941 :lat 0.0}
    :insertion {:segment "head" :along 0.030 :ant 0.0070588 :lat 0.0}
    :source "PCSA 0.50 cm2 per side x 2 = 1.00 cm2 bilateral - REPRESENTATIVE, NOT MEASURED. Kamibayashi & Richmond do NOT measure this muscle: their Table 3-3 has fifteen rows and rectus capitis anterior is not one of them, which was checked in the fetched full text rather than assumed. Vasavada's chapter names it only qualitatively - \"On the ventral side, the rectus capitis anterior and rectus capitis lateralis are very small muscles that connect the skull to C1, presumably with (small) moment arms for flexion and lateral bending\" (p.66). The 0.50 cm2 per side is taken to EQUAL the measured value of its direct posterior counterpart, rectus capitis posterior minor (0.50 (0.19) cm2 per side, N=9, same table), which is a stated basis rather than a free choice but is still not a measurement of this muscle. WHAT IT COSTS: longus_capitis's PCSA is measured, and once these two share the flexion task by Crowninshield-Brand the measured muscle's force depends on this representative number. The one-line change that removes that dependence is to delete this entry; the joint then has one flexor, which also crosses C2/C3, so the model would be unable to express any purely atlanto-occipital flexion at all. Offsets are representative landmark distances (atlas lateral mass 9 mm anterior to the column axis; basiocciput insertion 12 mm anterior to the condyles and 5 mm up the skull axis, behind and below the longus capitis insertion because the clivus rises as it runs forward). Modelled midline, so its lateral bending is absent."}

   "upper_trapezius"
   {:name "upper_trapezius" :paired? true :pcsa-cm2 9.0
    :acts-about :shoulder :task :scapular-suspension
    ;; TWO-JOINT, and nothing said so until 2026-09-08. It runs from the occiput
    ;; and the nuchal line to the lateral clavicle, so it passes the
    ;; cervicothoracic junction and exerts a moment there — `spine/levels-crossed`
    ;; has always put it across C7/T1, and its force has always been in that
    ;; level's compression. What was missing is the MOMENT: measured at
    ;; `laptop-on-lap`, −3.22 mm of arm about C7 carrying 50.13 N, i.e. −0.161 N·m
    ;; per side that the cervical equilibrium was not told about.
    ;;
    ;; IT IS NOT IN THE `:neck` COUPLED GROUP, and the reason is a limit of the
    ;; solver's inputs rather than of the solver. Its own task is a SUSPENSION
    ;; balance — a force, with a dimensionless direction cosine for a coefficient —
    ;; and the neck group's rows are moments. `recruit/solve` can take rows in
    ;; different units (each multiplier carries the reciprocal of its own row's),
    ;; but `coupled-arms` supplies moment arms and has nothing to say about a
    ;; suspension coefficient. Declaring the crossing makes the size of the gap a
    ;; number at every posture instead of a sentence here.
    :crosses {:joint :c7}
    ;; occiput/nuchal line → lateral clavicle-acromion; suspends the girdle
    :origin {:segment "lower_cervical" :along 0.33333333333333337 :ant -0.0170 :lat 0.0180}
    :insertion {:segment "thorax" :along (trunk-frac->thorax 0.93) :ant -0.0090 :lat 0.1225}
    :source "representative; suspension line. The insertion rides on the THORAX, not on the humerus: the acromion belongs to the shoulder girdle, and a girdle that rotated with the arm would swing its own suspension line horizontal under abduction and report that the trapezius cannot lift"}

   "levator_scapulae"
   {:name "levator_scapulae" :paired? true :pcsa-cm2 5.0
    :acts-about :shoulder :task :scapular-suspension
    ;; the same crossing, for the same reason — C1–C4 transverse processes to the
    ;; superior angle of the scapula, past C7. −1.13 mm of arm carrying 24.81 N at
    ;; `laptop-on-lap`: −0.028 N·m per side. See the note on upper_trapezius.
    :crosses {:joint :c7}
    ;; upper cervical transverse processes → superior medial scapula: shorter,
    ;; more vertical, and closer to the midline than the trapezius
    :origin {:segment "lower_cervical" :along 0.7333333333333334 :ant -0.0120 :lat 0.0125}
    :insertion {:segment "thorax" :along (trunk-frac->thorax 0.93) :ant -0.0120 :lat 0.0750}
    :source "representative; suspension line, on the thorax for the same reason as upper_trapezius — the scapula is not the humerus"}

   "anterior_deltoid"
   {:name "anterior_deltoid" :paired? true :pcsa-cm2 10.0
    :acts-about :shoulder :task :shoulder-flexion
    ;; clavicle → deltoid tuberosity, anterior to the humeral axis
    :origin {:segment "thorax" :along (trunk-frac->thorax 1.0) :ant 0.01811 :lat 0.1225}
    :insertion {:segment "upper_arm" :along 0.42 :ant 0.0 :lat 0.0}
    ;; the humeral head. Without it the straight chord crosses the joint centre at
    ;; 90° of shoulder flexion and the arm goes to zero; with it the arm plateaus
    ;; at the head's radius, which is what a tendon lying on bone actually does.
    :wrap {:radius-m 0.020 :sign 1.0}
    :source "representative; clavicular origin (NOT on the humerus — both ends on one bone rotate rigidly with it and give a moment arm that cannot change with the joint angle), anterior offset calibrated to the 0.030 m neutral arm"}

   ;; --- the girdle's other suspender ------------------------------------------
   ;; Upper trapezius originates on the head, so when the head folds past
   ;; horizontal its origin drops BELOW the acromion and it can no longer lift.
   ;; The middle fibres originate on the thoracic spine, which does not follow the
   ;; head, and they carry the girdle in exactly the postures the upper fibres
   ;; cannot. Adding them is why those postures stop being unanswerable.
   ;;
   ;; BOTH ENDS ON ONE SEGMENT, and it is the only muscle here of which that is
   ;; true. `attachment-test` names that shape as the error that produced a
   ;; constant-looking arm, so it has to be answered rather than left to be
   ;; rediscovered. It is correct here, and the reason is that the shape is wrong
   ;; for a MOMENT and right for a SUSPENSION:
   ;;
   ;;   a moment arm is taken about a joint the segment itself carries, so a line
   ;;   whose endpoints both ride on that segment rotates rigidly with the joint
   ;;   and the arm cannot move — that is the bug;
   ;;
   ;;   a suspension coefficient is a direction cosine against the WORLD vertical
   ;;   (`suspension-effectiveness`), which the segment does not carry. Measured
   ;;   2026-09-07 over 4,608 postures, this muscle's coefficient spans −0.18 to
   ;;   +0.49: it varies, and it changes sign, because leaning the trunk changes
   ;;   how much of its pull is upward.
   ;;
   ;; ⚠ WHAT IT COSTS. Its LENGTH is constant — exactly 1.000 × optimal at all
   ;; 4,608 of those postures, the only muscle in the set for which that is so.
   ;; That is not an attachment error either: the middle trapezius runs from the
   ;; thoracic spinous processes to the acromion, and what changes its length in a
   ;; body is the SCAPULA sliding on the thorax. This model has no scapula and no
   ;; scapulothoracic degree of freedom, so both of its sites are points on the
   ;; thorax and no posture this model can express moves them apart. The
   ;; consequence is arithmetic and one-directional: `force-length-factor` is 1.0
   ;; at ratio 1.0, so this muscle is always credited with its full available
   ;; force, and `passive-slack-frac` is 1.0, so its passive tension is always
   ;; exactly zero. Its reported %MVC is therefore a LOWER bound. The size of the
   ;; understatement is not measured here and cannot be, because measuring it
   ;; needs the degree of freedom that is missing.
   "middle_trapezius"
   {:name "middle_trapezius" :paired? true :pcsa-cm2 8.0
    :acts-about :shoulder :task :scapular-suspension
    :origin {:segment "thorax" :along (trunk-frac->thorax 1.0) :ant -0.0180 :lat 0.0}
    :insertion {:segment "thorax" :along (trunk-frac->thorax 0.93) :ant -0.0090 :lat 0.1225}
    :source "representative; thoracic-spine origin, acromial insertion. Both sites are on the thorax because this model has no scapula, so its length ratio is 1.0 at every posture and its force-length factor and passive tension are constants by construction — see the note above."}

   ;; --- the posterior ligamentous system ---------------------------------------
   ;; NOT MUSCLES. They cannot contract, they have no %MVC, and they engage only
   ;; when the joint has already carried the spine past their slack length. They
   ;; are here because the previous wave measured its own claim and found it
   ;; false: a muscle's own passive tension supplies about 4% of the demand at 60
   ;; degrees of trunk flexion, and flexion-relaxation — the erector spinae going
   ;; electrically silent in deep flexion — is these structures taking over.

   "posterior_lumbar_ligaments"
   {:name "posterior_lumbar_ligaments" :ligament? true
    :acts-about :l5s1 :task :trunk-extension
    ;; supraspinous + interspinous ligaments and the thoracolumbar fascia, as one
    ;; posterior band from the sacrum to the mid-thoracic spinous processes
    :slack-frac 1.055
    :ref-stretch 1.25
    :force-at-ref 4200.0
    :origin {:segment "pelvis" :along 0.15 :ant -0.0330 :lat 0.0}
    :insertion {:segment "lumbar" :along (trunk-frac->lumbar 0.34) :ant -0.0520 :lat 0.0}
    :source "representative; the posterior ligamentous system, slack in neutral and engaging in deep flexion"}

   "nuchal_ligament"
   {:name "nuchal_ligament" :ligament? true
    :acts-about :c7 :task :cervical-extension
    ;; external occipital protuberance → C7 spinous process
    ;; short, and the head turns through a large angle: 15 deg of head flexion
    ;; already stretches it 18%. Calibrated over its own range, not the lumbar one.
    :slack-frac 1.20
    :ref-stretch 1.60
    :force-at-ref 150.0
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.97) :ant -0.0200 :lat 0.0}
    :insertion {:segment "lower_cervical" :along 0.23333333333333336 :ant -0.0130 :lat 0.0}
    :source "representative; the cervical counterpart, engaging in sustained forward head posture"}

   ;; --- the elbow --------------------------------------------------------------
   ;; The chain has placed an elbow since the pose layer existed, and until
   ;; 2026-09-06 no muscle acted about it: the forearm and hand hung off a joint
   ;; whose equilibrium nobody solved. A typing posture holds them out at 90° all
   ;; day, which is exactly the load a desk worker asks about.

   "biceps_brachii"
   {:name "biceps_brachii" :paired? true :pcsa-cm2 9.0
    :acts-about :elbow :task :elbow-flexion
    ;; scapula (supraglenoid / coracoid) → radial tuberosity
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.97) :ant 0.0080 :lat 0.1150}
    :insertion {:segment "forearm" :along 0.11 :ant 0.0135 :lat 0.0}
    ;; the trochlea. Without it the chord crosses the joint near full extension
    ;; and the flexor is reported as an extensor.
    :wrap {:radius-m 0.018 :sign 1.0}
    :source "representative; elbow flexion moment arm ~35-45 mm through mid-range"}

   "brachialis"
   {:name "brachialis" :paired? true :pcsa-cm2 12.0
    :acts-about :elbow :task :elbow-flexion
    ;; distal humerus → ulnar tuberosity: shorter, closer to the joint, and the
    ;; larger cross-section — which is why the criterion gives it the bigger share
    :origin {:segment "upper_arm" :along 0.55 :ant 0.0090 :lat 0.0}
    :insertion {:segment "forearm" :along 0.06 :ant 0.0090 :lat 0.0}
    :wrap {:radius-m 0.013 :sign 1.0}
    :source "representative; the workhorse elbow flexor, arm ~20-25 mm"}

   "triceps_brachii"
   {:name "triceps_brachii" :paired? true :pcsa-cm2 20.0
    :acts-about :elbow :task :elbow-flexion
    ;; humerus + scapula → olecranon, POSTERIOR to the joint, so its moment
    ;; opposes the flexors' by construction — it belongs in the same equilibrium
    :origin {:segment "upper_arm" :along 0.35 :ant -0.0090 :lat 0.0}
    :insertion {:segment "forearm" :along 0.03 :ant -0.0135 :lat 0.0}
    :wrap {:radius-m 0.016 :sign -1.0}
    :source "representative; the elbow extensor, arm ~20-25 mm"}

   ;; --- the wrist --------------------------------------------------------------
   ;; The last joint the kinematics placed and the kinetics did not. The hand is
   ;; small, but a keyboard posture holds it out horizontally for hours and the
   ;; wrist extensors are the muscles a typist actually complains about.

   "wrist_extensors"
   {:name "wrist_extensors" :paired? true :pcsa-cm2 5.0
    :acts-about :wrist :task :wrist-flexion
    ;; lateral epicondyle → dorsal metacarpals. With the forearm horizontal,
    ;; gravity pulls the hand DOWN, so these are the muscles holding it up —
    ;; which is why they are the ones a keyboard posture loads.
    ;; `:ant` is the SEGMENT's own anterior axis, which rotates with it — for a
    ;; forearm tilted past horizontal it no longer points anywhere near world
    ;; anterior. Dorsal and palmar are body-fixed, so the offsets are stated in
    ;; the local frame and the sign that makes the extensors extend was measured,
    ;; not assumed: stating them the other way round put the PALMAR group on the
    ;; lifting side, and only the wrapping surface hid it.
    :origin {:segment "forearm" :along 0.10 :ant 0.0080 :lat 0.0}
    :insertion {:segment "hand" :along 0.25 :ant 0.0060 :lat 0.0}
    :wrap {:radius-m 0.011 :sign 1.0 :retinaculum true}
    :source "representative; wrist extension moment arm ~12-18 mm"}

   "wrist_flexors"
   {:name "wrist_flexors" :paired? true :pcsa-cm2 8.0
    :acts-about :wrist :task :wrist-flexion
    ;; medial epicondyle → palmar metacarpals: the antagonist, larger and with a
    ;; slightly longer arm, and idle in the posture above
    :origin {:segment "forearm" :along 0.10 :ant -0.0090 :lat 0.0}
    :insertion {:segment "hand" :along 0.25 :ant -0.0070 :lat 0.0}
    :wrap {:radius-m 0.014 :sign -1.0 :retinaculum true}
    :source "representative; wrist flexion moment arm ~15-20 mm"}

   ;; --- the frontal plane ------------------------------------------------------
   ;; None of these existed before 2026-09-06, which is why every frontal-plane
   ;; moment this actor computed was reported as carried by nobody.

   "middle_deltoid"
   {:name "middle_deltoid" :paired? true :pcsa-cm2 12.0 :axis :frontal
    :acts-about :shoulder :task :shoulder-abduction
    ;; acromion → deltoid tuberosity. The acromion is LATERAL to the humeral head,
    ;; not coincident with it: an origin at the joint centre gives a line of action
    ;; through the joint and therefore an abduction moment arm of identically zero,
    ;; at every angle. Measured 2026-09-06 — the deltoid could not abduct.
    ;;
    ;; It is SUPERIOR to the head as well, and until 2026-09-07 it was not.
    ;; `:along 1.0` is the distal end of the trunk, which is exactly where `pose`
    ;; puts the shoulder joint — so the acromion sat at the joint's own height
    ;; (measured on this body at neutral: joint y = 0.4896 m, origin y = 0.4896 m).
    ;; An origin level with the joint gives a chord whose abduction leverage is
    ;; LARGEST at 0° of abduction (−20.3 mm), falls through zero at about 78°, and
    ;; is an ADDUCTION arm above that: the principal abductor running backwards
    ;; through the top half of its own range. Nobody saw it, because the wrap
    ;; below was in force at every posture and reported ±22 mm regardless.
    ;;
    ;; The acromion arches OVER the humeral head — the gap between them is the
    ;; subacromial space — so its superior offset is at least one head radius.
    ;; One head radius is what is used: the same 0.020 m the wrap declares rather
    ;; than a second number, and the SMALLEST offset consistent with the acromion
    ;; being above the head rather than one fitted to a target curve. `:along` is
    ;; a fraction of the trunk's length, which is 0.4896 m at reference stature,
    ;; so 0.020 m of it is 0.0408.
    :origin {:segment "thorax" :along (trunk-frac->thorax 1.0408) :ant 0.0 :lat 0.1345}
    :insertion {:segment "upper_arm" :along 0.42 :ant 0.0 :lat 0.0180}
    ;; THE HUMERAL HEAD — the same bone the anterior deltoid wraps, so the same
    ;; radius. It was not: this entry said 0.022 m and the anterior deltoid says
    ;; 0.020 m, for one bone. 0.022 m was never a radius; it is the top of the
    ;; `~20-25 mm` moment-arm range the old `:source` quoted, used as a floor. A
    ;; moment arm and a bone radius are different quantities, and taking the floor
    ;; from the arm's own target guarantees the floor binds everywhere: measured
    ;; 2026-09-07 over 3,072 postures the wrap was in force 3,072 times — against
    ;; 2,432 for the anterior deltoid and 1,560 for the biceps — and the reported
    ;; arm was ±0.022 m at all 3,072. A tabulated moment arm is the one thing this
    ;; namespace exists to remove, and it had grown one back.
    :wrap {:radius-m 0.020 :sign -1.0}
    :source "representative; the wrap radius is the humeral head's, shared with anterior_deltoid — one bone, one radius, so an edit to either is visibly an edit to both. NO ARM IS QUOTED HERE: it is computed. On a 1.70 m body it is 21.7 mm at 0° of abduction, peaks near 28.5 mm around 45°, and reaches the head radius near 86°, where the floor takes over. The sign is stated for the LEFT: a left abductor generates a NEGATIVE moment about +X, because reflecting z reverses the frontal component."}

   "latissimus_dorsi"
   {:name "latissimus_dorsi" :paired? true :pcsa-cm2 14.0 :axis :frontal
    :acts-about :shoulder :task :shoulder-abduction
    ;; thoracolumbar fascia / iliac crest → intertubercular groove of the humerus.
    ;; It pulls the arm DOWN and IN, so its abduction coefficient is the opposite
    ;; sign to the middle deltoid's — which is why it belongs in the same task.
    ;; Without an adductor the abduction equilibrium had exactly one candidate per
    ;; side, and any posture demanding adduction had nobody to demand it of.
    :origin {:segment "pelvis" :along 0.10 :ant -0.0200 :lat 0.0350}
    :insertion {:segment "upper_arm" :along 0.12 :ant -0.0050 :lat -0.0120}
    ;; it wraps the thorax and the humeral head, which is what keeps it an ADDUCTOR
    ;; through the range. Without it the straight chord crosses the joint near the
    ;; neutral arm and the sign flips, so the model briefly has two abductors and
    ;; no adductor — and a posture demanding adduction then has nobody to demand it
    ;; of. Sign stated for the LEFT, opposite to the middle deltoid by construction.
    :wrap {:radius-m 0.015 :sign 1.0}
    :source "representative; the principal adductor/extensor of the shoulder"}

   "quadratus_lumborum"
   {:name "quadratus_lumborum" :paired? true :pcsa-cm2 8.0 :axis :frontal
    :acts-about :l5s1 :task :trunk-lateral-flexion
    ;; iliac crest → 12th rib / upper lumbar transverse processes
    :origin {:segment "pelvis" :along 0.30 :ant -0.0120 :lat 0.0450}
    :insertion {:segment "lumbar" :along (trunk-frac->lumbar 0.30) :ant -0.0120 :lat 0.0330}
    :source "representative; the principal lateral flexor of the lumbar spine"}

   "obliques"
   {:name "obliques" :paired? true :pcsa-cm2 16.0 :axis :frontal
    :acts-about :l5s1 :task :trunk-lateral-flexion
    ;; iliac crest → lower ribs, further from the midline than QL
    :origin {:segment "pelvis" :along 0.20 :ant 0.0060 :lat 0.0800}
    :insertion {:segment "thorax" :along (trunk-frac->thorax 0.42) :ant 0.0060 :lat 0.0700}
    :source "representative; external + internal oblique as one lateral-flexion group"}

   "scalenes"
   {:name "scalenes" :paired? true :pcsa-cm2 5.0 :axis :frontal
    :acts-about :c7 :task :cervical-lateral-flexion
    ;; first and second ribs → cervical transverse processes
    :origin {:segment "thorax" :along (trunk-frac->thorax 0.93) :ant 0.0040 :lat 0.0250}
    :insertion {:segment "lower_cervical" :along 0.5333333333333333 :ant 0.0040 :lat 0.0150}
    :source "representative; lateral flexor of the cervical spine"}


   ;; --- the lower limb ---------------------------------------------------------
   ;; Added 2026-09-07. PCSA is the one number here that is MEASURED rather than
   ;; representative: Ward, Eng, Smallwood & Lieber (2009), "Are current
   ;; measurements of lower extremity muscle architecture accurate?", Clinical
   ;; Orthopaedics and Related Research 467(4), Table 3 — 27 muscles taken from 21
   ;; formaldehyde-fixed human lower extremities, mean age 83 ± 9 years. Where a
   ;; group below is several of their muscles, its PCSA is their sum and the
   ;; arithmetic is written out so it can be checked.
   ;;
   ;; ⚠ THE SPECIMENS WERE 83 YEARS OLD. Ward's own point is that these are the
   ;; best-characterised human values available, not that they are a young adult's;
   ;; a young adult's PCSA is larger. Using them makes every lower-limb %MVC in
   ;; this actor an OVERSTATEMENT of the effort for a young body. That is the
   ;; direction to be wrong in for a model that reports load, and it is stated here
   ;; rather than corrected by a factor nobody measured.
   ;;
   ;; The moment arms are the other way round: representative targets consistent
   ;; with reported ranges, calibrated the way the upper limb's were — the offsets
   ;; are chosen so the neutral arm lands on the target, and everything away from
   ;; neutral is geometry. They are NOT measurements of anybody.

   "gluteus_maximus"
   {:name "gluteus_maximus" :paired? true :pcsa-cm2 33.4
    :acts-about :hip :task :hip-extension
    ;; posterior ilium and sacrum -> gluteal tuberosity of the femur
    :origin {:segment "pelvis" :along 0.35 :ant -0.0360 :lat 0.0350}
    :insertion {:segment "thigh" :along 0.30 :ant -0.0360 :lat 0.0100}
    ;; the ischium and the back of the femoral head, which the tendon lies over.
    ;; Measured 2026-09-07: the straight chord's extension arm falls from 60 mm at
    ;; neutral through ZERO at about 55 degrees of hip flexion and is +32 mm at
    ;; 85 — so without this surface the model reports the principal hip EXTENSOR
    ;; as a flexor in a squat, which is the posture the muscle exists for. Nobody
    ;; was then left to carry the squat's hip moment and `recruit` declined the
    ;; whole equilibrium. It is the same defect the cervical group and the
    ;; anterior deltoid had, in the joint where it costs the most.
    :wrap {:radius-m 0.058 :sign -1.0}
    :source "PCSA Ward et al. 2009 Table 3 (33.4 cm2). Neutral hip-extension arm calibrated to a representative 0.060 m; wrap radius 0.058 m, which is why the arm stays roughly constant through flexion as the reported values do."}

   "iliopsoas"
   {:name "iliopsoas" :paired? true :pcsa-cm2 17.6
    :acts-about :hip :task :hip-extension
    ;; lumbar bodies + iliac fossa -> lesser trochanter. Its insertion is
    ;; posteromedial, so a straight chord from it passes BEHIND the hip and the
    ;; model would report the principal hip flexor as an extensor. It does not,
    ;; because the tendon crosses the pelvic brim and the front of the femoral
    ;; head: that is a wrapping surface, and it is why the psoas moment arm is
    ;; famously flat through flexion where other muscles' are not.
    :origin {:segment "lumbar" :along (trunk-frac->lumbar 0.05) :ant 0.0100 :lat 0.0200}
    :insertion {:segment "thigh" :along 0.10 :ant -0.0050 :lat 0.0050}
    :wrap {:radius-m 0.035 :sign 1.0}
    :source "PCSA Ward et al. 2009 Table 3, psoas 7.7 + iliacus 9.9 = 17.6 cm2. Wrap radius representative (~0.035 m, the reported flexion arm near neutral)."}

   "vasti"
   {:name "vasti" :paired? true :pcsa-cm2 72.4
    :acts-about :knee :task :knee-extension
    ;; femoral shaft -> patella -> tibial tuberosity. One joint: both ends are
    ;; below the hip, which is the whole difference between this and rectus
    ;; femoris and the reason they are separate entries rather than a quadriceps.
    :origin {:segment "thigh" :along 0.55 :ant 0.0150 :lat 0.0}
    :insertion {:segment "shank" :along 0.06 :ant 0.0280 :lat 0.0}
    ;; THE PATELLA. It is a sesamoid the extensor tendon passes OVER, so it holds
    ;; the line of action away from the knee centre and floors the moment arm at
    ;; its own radius — the same machinery as the humeral head, not a second one.
    ;; Without it the chord swings toward the joint as the knee flexes and the
    ;; force required to hold a squat diverges.
    :wrap {:radius-m 0.042 :sign 1.0}
    :source "PCSA Ward et al. 2009 Table 3, vastus lateralis 35.1 + medialis 20.6 + intermedius 16.7 = 72.4 cm2. Patellar radius representative (~0.042 m)."}

   "rectus_femoris"
   {:name "rectus_femoris" :paired? true :pcsa-cm2 13.5
    :acts-about :knee :task :knee-extension
    :crosses {:joint :hip}
    ;; anterior inferior iliac spine -> the same patellar tendon. TWO-JOINT: it
    ;; extends the knee and flexes the hip, and this model solves it at the knee
    ;; and REPORTS what it does at the hip. See `secondary-arm`.
    :origin {:segment "pelvis" :along 0.80 :ant 0.0200 :lat 0.0200}
    :insertion {:segment "shank" :along 0.06 :ant 0.0280 :lat 0.0}
    :wrap {:radius-m 0.042 :sign 1.0}
    :source "PCSA Ward et al. 2009 Table 3 (13.5 cm2). Shares the patellar tendon with the vasti, so the same wrapping surface and the same insertion."}

   "hamstrings"
   {:name "hamstrings" :paired? true :pcsa-cm2 34.5
    :acts-about :knee :task :knee-extension
    :crosses {:joint :hip}
    ;; ischial tuberosity -> posterior proximal tibia and fibula. TWO-JOINT, and
    ;; the reason the knee's flexor side cannot be filled by a one-joint muscle:
    ;; there is no one-joint knee flexor of any size in the body. That is anatomy
    ;; rather than a modelling shortcut.
    :origin {:segment "pelvis" :along 0.90 :ant -0.0250 :lat 0.0200}
    :insertion {:segment "shank" :along 0.08 :ant -0.0200 :lat 0.0100}
    :wrap {:radius-m 0.030 :sign -1.0}
    :source "PCSA Ward et al. 2009 Table 3, semimembranosus 18.4 + biceps femoris long head 11.3 + semitendinosus 4.8 = 34.5 cm2."}

   "gastrocnemius"
   {:name "gastrocnemius" :paired? true :pcsa-cm2 30.8
    :acts-about :ankle :task :ankle-plantarflexion
    :crosses {:joint :knee}
    ;; femoral condyles -> calcaneus by the Achilles tendon. TWO-JOINT: it
    ;; plantarflexes the ankle and flexes the knee, solved at the ankle here.
    :origin {:segment "thigh" :along 0.95 :ant -0.0200 :lat 0.0100}
    :insertion {:segment "foot" :along 0.05 :ant 0.0050 :lat 0.0}
    :wrap {:radius-m 0.030 :sign -1.0}
    :source "PCSA Ward et al. 2009 Table 3, medial head 21.1 + lateral head 9.7 = 30.8 cm2."}

   "soleus"
   {:name "soleus" :paired? true :pcsa-cm2 51.8
    :acts-about :ankle :task :ankle-plantarflexion
    ;; posterior tibia and fibula -> the same Achilles tendon. ONE joint: it does
    ;; not cross the knee, which is why it and not gastrocnemius is the muscle
    ;; that holds a body up in quiet standing whatever the knee is doing.
    ;;
    ;; The largest PCSA in the lower limb, by a distance — 51.8 cm2 against the
    ;; gluteus maximus's 33.4 — which is what a muscle that works all day looks
    ;; like.
    :origin {:segment "shank" :along 0.30 :ant -0.0150 :lat 0.0}
    :insertion {:segment "foot" :along 0.05 :ant 0.0050 :lat 0.0}
    :wrap {:radius-m 0.030 :sign -1.0}
    :source "PCSA Ward et al. 2009 Table 3 (51.8 cm2, the largest in their series)."}

   "tibialis_anterior"
   {:name "tibialis_anterior" :paired? true :pcsa-cm2 10.9
    :acts-about :ankle :task :ankle-plantarflexion
    ;; anterior tibia -> medial cuneiform and first metatarsal base. Its tendon
    ;; runs under the extensor retinacula, which strap it against the front of the
    ;; ankle: a RETINACULUM, not a wrapping surface, so the arm is pinned in both
    ;; directions. A straight chord to a mid-foot insertion would give it 5 cm of
    ;; leverage, half again what a dorsiflexor is measured to have, because the
    ;; retinaculum is exactly what stops the tendon bowstringing forward.
    :origin {:segment "shank" :along 0.25 :ant 0.0120 :lat 0.0050}
    :insertion {:segment "foot" :along 0.45 :ant 0.0120 :lat 0.0}
    :wrap {:radius-m 0.035 :sign 1.0 :retinaculum true}
    :source "PCSA Ward et al. 2009 Table 3 (10.9 cm2). Retinacular radius representative (~0.035 m)."}

   "erector_spinae"
   {:name "erector_spinae" :pcsa-cm2 34.0
    :acts-about :l5s1 :task :trunk-extension
    ;; sacrum/ilium → thoracic spinous processes, posterior to the trunk axis
    :origin {:segment "pelvis" :along 0.20 :ant -0.0291 :lat 0.0}
    :insertion {:segment "lumbar" :along (trunk-frac->lumbar 0.25) :ant -0.0485 :lat 0.0}
    :source "representative; lumbar insertion, kept low and well posterior for the same reason as the cervical group — a straight line to a HIGH thoracic insertion swings in front of L5/S1 near 55 deg of trunk flexion and would report erector spinae as a flexor"}))

;; --- placing an attachment ---------------------------------------------------

(defn site-point
  "World position of one attachment site, given a solved pose and the stature the
  offsets scale with. `side` selects which of a paired segment the site rides on;
  a midline segment ignores it."
  ([pose-data stature-m site] (site-point pose-data stature-m site nil))
  ([pose-data stature-m {:keys [segment along ant lat]} side]
  (let [{:keys [proximal frame length-m]}
        (or (when side (pose/seg-at pose-data (pose/placed-name segment side)))
            (pose/seg-at pose-data segment))]
    (-> proximal
        (math/v+ (math/v* (:long frame) (* along length-m)))
        (math/v+ (math/v* (:ant frame) (* (or ant 0.0) stature-m)))
        (math/v+ (math/v* (:lat frame) (* (or lat 0.0) stature-m)))))))

(defn line-of-action
  "{:origin p :insertion p :dir unit :length-m}. `:dir` points from the insertion
  toward the origin — the direction the muscle pulls the bone it inserts on."
  [pose-data stature-m muscle]
  (let [side (:side muscle)
        o (site-point pose-data stature-m (:origin muscle) side)
        i (site-point pose-data stature-m (:insertion muscle) side)
        d (math/v- o i)]
    {:origin o :insertion i :dir (math/vnorm d) :length-m (math/vlen d)}))

(def flexion-axis
  "The sagittal flexion/extension axis, in the world frame. Flexion is a rotation
  about Z (see `pose`), so a moment about +Z is what an extensor must resist."
  [0.0 0.0 1.0])

(def frontal-axis
  "The frontal-plane (lateral flexion / abduction) axis. Lateral bend and abduction
  are rotations about X, so a muscle resisting them acts about +X."
  [1.0 0.0 0.0])

(defn axis-of
  "The axis a muscle's moment is taken about. Sagittal unless it says otherwise —
  stating it per muscle rather than per call keeps the axis with the anatomy, so a
  frontal muscle cannot be accidentally solved against the sagittal equilibrium."
  [muscle]
  (case (:axis muscle)
    :frontal frontal-axis
    flexion-axis))

(defn straight-moment-arm
  "Signed moment arm (metres) of the straight line from insertion to origin, about
  `joint-point` and `axis`.

  Positive means the muscle's pull opposes flexion — it is an extensor at this
  posture. The SIGN is part of the answer: a muscle whose line of action crosses
  to the other side of the joint stops being an extensor there, and a model that
  returns only a magnitude cannot say so."
  [pose-data stature-m muscle joint-point axis]
  (let [{:keys [insertion dir]} (line-of-action pose-data stature-m muscle)]
    (when dir
      (let [r (math/v- insertion joint-point)]
        (math/vdot (math/vcross r dir) axis)))))

(defn moment-arm-detail
  "The moment arm, and whether it came from the straight line or from a wrapping
  surface: `{:arm :straight :wrapped? :radius-m}`.

  WHY WRAPPING EXISTS. A straight line between two attachment points can pass
  arbitrarily close to — and through — the joint it acts about, at which point the
  computed arm goes to zero and the force required to hold any moment diverges.
  Real muscles do not do this: they lie ON the bone, and at the range where a
  straight chord would cut the corner they wrap over it. This model's anterior
  deltoid crossed zero at 90° of shoulder flexion, which is an ordinary posture,
  and `recruit` had to decline it.

  THE GEOMETRY, which is why this is three lines and not a solver. A muscle
  wrapping over a circular surface of radius R centred on the joint follows a
  tangent–arc–tangent path, and every tangent to that circle is exactly R from the
  centre — so the moment arm while wrapping IS R, independent of the joint angle.
  The straight-line arm therefore does not fall to zero; it falls to R and stays
  there. The two branches agree at the crossing (|straight| = R), so the arm is
  continuous, which `attachment-test` checks rather than assumes.

  `:retinaculum true` on the wrap means the tendon is strapped against the bone
  rather than passing over it, so the arm is pinned at the radius in BOTH
  directions instead of merely floored — see the branch below.

  `:sign` is declared, not derived. It says which side of the joint the muscle
  wraps on — anterior for the deltoid, which is what keeps it a flexor rather than
  letting it swap sides when the chord would have crossed through. Deriving it
  from the straight-line arm would read the sign off exactly the degenerate
  configuration this exists to handle."
  ([pose-data stature-m muscle joint-point]
   (moment-arm-detail pose-data stature-m muscle joint-point flexion-axis))
  ([pose-data stature-m muscle joint-point axis]
   (let [straight (straight-moment-arm pose-data stature-m muscle joint-point axis)
         {:keys [radius-m sign]} (:wrap muscle)]
     (cond
       (nil? straight) {:arm nil :straight nil :wrapped? false}

       ;; Wrap whenever the chord would give LESS leverage on the muscle's own
       ;; side than the surface does — including when the chord has swung past the
       ;; joint entirely and would report the muscle acting the other way. Testing
       ;; `|straight| < R` instead looks right and is not: once the chord passes
       ;; far enough to the wrong side its magnitude exceeds R again, and the model
       ;; hands back a straight-line arm with the sign flipped, so the flexor
       ;; becomes an extensor at 130°. Measured 2026-09-06.
       ;; A RETINACULUM is not a wrapping surface. A surface the tendon passes
       ;; over puts a FLOOR under the moment arm: the chord may give more leverage
       ;; and then it wins. A retinaculum straps the tendon against the bone, so
       ;; the arm is PINNED at the radius in both directions — which is why the
       ;; wrist's moment arms are near-constant through its range where the elbow's
       ;; are not. Measured 2026-09-06: without the distinction the wrist extensor
       ;; arm grew from 11 mm to 29 mm across 45 deg of extension, which no
       ;; retinaculum would allow.
       (and radius-m (:retinaculum (:wrap muscle)))
       {:arm (* (or sign 1.0) radius-m) :straight straight
        :wrapped? true :retinaculum? true :radius-m radius-m}

       (and radius-m (< (* (or sign 1.0) straight) radius-m))
       {:arm (* (or sign 1.0) radius-m) :straight straight
        :wrapped? true :radius-m radius-m}

       :else {:arm straight :straight straight :wrapped? false :radius-m radius-m}))))

(defn moment-arm
  "Signed moment arm (metres), wrapping included. See `moment-arm-detail`."
  ([pose-data stature-m muscle joint-point]
   (moment-arm pose-data stature-m muscle joint-point flexion-axis))
  ([pose-data stature-m muscle joint-point axis]
   (:arm (moment-arm-detail pose-data stature-m muscle joint-point axis))))

(defn mirror
  "The right-side twin of a paired muscle: every lateral offset negated, the joint
  it acts about resolved to that side, and a unique name.

  Mirroring the DATA rather than writing a second table is the point. Two tables
  is two places for an attachment to be edited and one place for it to be
  forgotten, and the asymmetry this model exists to represent has to come from the
  POSTURE, not from the muscles being described differently on the two sides."
  [muscle side]
  (let [sign (if (= :left side) 1.0 -1.0)
        flip (fn [site] (update site :lat #(* sign (or % 0.0))))]
    (-> muscle
        (assoc :side side
               :name (str (:name muscle) "/" (name side))
               :group (:name muscle))
        (update :origin flip)
        (update :insertion flip)
        ;; A wrapping surface's side is stated as a sign, and a moment about the
        ;; FRONTAL axis reverses under the mirror while a sagittal one does not —
        ;; reflecting z flips the x-component of every cross product. Leaving the
        ;; sign alone gave both middle deltoids a positive abduction arm, so one of
        ;; them was pulling the way gravity already was. Measured 2026-09-06.
        (cond-> (and (:wrap muscle) (= :frontal (:axis muscle)) (= :right side))
          (update-in [:wrap :sign] -))
        ;; every paired joint, not only the shoulder: the elbow and the wrist are
        ;; placed per side too, and a muscle acting about `:elbow` on the right has
        ;; to be told about `:elbow/right` or it takes its moment about the left
        ;; one — which is a wrong answer, not an error.
        (cond-> (#{:shoulder :elbow :wrist :hip :knee :ankle} (:acts-about muscle))
          (assoc :acts-about (keyword (name (:acts-about muscle)) (name side))))
        ;; a two-joint muscle's OTHER joint is paired too, and forgetting it here
        ;; would report the right-side rectus femoris's hip moment about the left
        ;; hip — a wrong answer rather than an error, which is the same trap the
        ;; line above exists for.
        ;;
        ;; ONLY IF THAT JOINT IS PAIRED, and the same set decides it as decides
        ;; `:acts-about` above. A paired muscle can cross a MIDLINE joint — upper
        ;; trapezius and levator scapulae both run past C7 — and side-qualifying
        ;; that gives `:c7/left`, a key `pose` has no joint for, so the arm comes
        ;; back nil and the moment is silently not reported. Measured 2026-09-08.
        (cond-> (#{:shoulder :elbow :wrist :hip :knee :ankle}
                 (get-in muscle [:crosses :joint]))
          (assoc-in [:crosses :joint]
                    (keyword (name (get-in muscle [:crosses :joint])) (name side)))))))

(def instances
  "Every muscle the solver works with: midline groups once, paired groups twice.
  Ordered midline-first then left then right, so a consumer's column order is
  stable."
  (vec (concat
        (for [[_ m] muscles :when (not (:paired? m))]
          (assoc m :group (:name m) :side :midline))
        (for [side [:left :right], [_ m] muscles :when (:paired? m)]
          (mirror m side)))))

(defn instance
  "One muscle instance by its unique name."
  [name]
  (first (filter #(= name (:name %)) instances)))

(def vertical
  "The direction a suspension muscle has to pull to hold a hanging girdle up."
  [0.0 1.0 0.0])

(defn suspension-effectiveness
  "How much of a unit of this muscle's force acts vertically — the direction
  cosine of its line of action against `vertical`, in [-1,1]. Negative means it
  would pull the girdle down at this posture; see the note in the body.

  A SUSPENSION TASK IS NOT A MOMENT TASK, and the difference is not cosmetic.
  Upper trapezius and levator scapulae do not flex the glenohumeral joint; they
  hold the shoulder girdle up against the weight hanging from it. Asking for their
  `moment-arm` about the shoulder returns a number — this actor's earlier
  constants were 0.025 m and 0.020 m — but it is a number about the wrong
  equilibrium, and at the neutral posture it comes out NEGATIVE, i.e. it would
  claim these muscles flex the joint they are suspending. Force tasks balance
  Σ (F_i · ĉ_i) against a load; moment tasks balance Σ (F_i · r_i). `recruit`
  solves both with the same criterion, but they are not the same quantity and this
  namespace refuses to hand one out as the other."
  [pose-data stature-m muscle]
  (let [{:keys [dir]} (line-of-action pose-data stature-m muscle)]
    ;; NOT clamped at zero. A non-positive cosine means this muscle's line, at this
    ;; posture, pulls the girdle DOWN rather than up — it is acting the wrong way,
    ;; which is a different fact from having a little leverage, and `recruit`
    ;; refuses the two with different reasons. Clamping to 0 collapsed them into
    ;; one, and the reported reason ("below the leverage floor — a straight-line
    ;; model has no wrapping surface here") was then simply wrong: no wrapping
    ;; surface fixes a muscle that is pulling the other way.
    (when dir (math/vdot dir vertical))))

(def task-sense
  "Which direction a task counts as RESISTING its load, per task.

  `straight-moment-arm` returns a signed arm about the sagittal axis in which
  POSITIVE means extension, and `recruit/share` distributes only across positive
  coefficients — correctly, a muscle cannot push. A task whose load is stated as a
  positive FLEXION moment therefore needs its muscles' arms negated before the
  criterion sees them, or every one of them is refused for acting the wrong way at
  every posture.

  IT IS A PROPERTY OF THE TASK AND NOT OF THE MUSCLE, which is why it is a table
  here rather than a field on each entry in `muscles`. Every muscle in a task shares
  one equilibrium and therefore one sign convention; putting the sign on the muscle
  would let two members of the same task disagree about which way their shared load
  points, and nothing would notice.

  Only tasks that differ from the default appear. `:atlanto-occipital-flexion` is
  the only one today: its load is `load/atlanto-occipital-moment`'s
  `:over-supplied-nm`, a magnitude, so its members' coefficients are the negated
  arm and a muscle that is an EXTENSOR there gets a negative coefficient and is
  refused — which is exactly what happens to the sternocleidomastoid, and why it is
  not in this task.

  This is NOT how the mirror-paired tasks work. `:elbow-flexion`, `:wrist-flexion`,
  `:hip-extension`, `:knee-extension`, `:ankle-plantarflexion` and the two
  lateral-flexion tasks hold BOTH sides of the joint in ONE task and are shared by
  `muscle/share-signed`, which flips the load and every coefficient together when
  the load changes sign. They keep the default +1 here; the flip is per-posture and
  belongs to the solve, not to the anatomy."
  {:atlanto-occipital-flexion -1.0})

(defn effectiveness
  "The coefficient this muscle contributes to its task's equilibrium: a moment arm
  in metres for a moment task, a dimensionless direction cosine for a suspension
  task. Returns nil when the line of action is degenerate.

  Signed by `task-sense`, so a task whose load is a flexion moment gets flexion
  coefficients. `moment-arm` itself is untouched and keeps the one convention —
  positive is extension — because `load/atlanto-occipital-moment` and every test
  that inspects the geometry read it directly and must not have to know which task
  a muscle happens to be in."
  [pose-data stature-m muscle]
  (if (= :scapular-suspension (:task muscle))
    (suspension-effectiveness pose-data stature-m muscle)
    (when-let [a (moment-arm pose-data stature-m muscle
                             (get-in pose-data [:joints (:acts-about muscle)])
                             (axis-of muscle))]
      (* (get task-sense (:task muscle) 1.0) a))))

(defn spans?
  "Does this muscle cross `joint` — that is, does its line of action have a moment
  about it at all?

  THE ANSWER IS DECLARED AND NOT DERIVED, and it has to be. `straight-moment-arm`
  will return a number about ANY point, including a joint the muscle's line does
  not span, and that number is not a moment: a muscle wholly above a joint exerts
  no moment about it, because both of its attachments ride on the same free body.
  Reading a straight-line arm off such a joint would invent a constraint
  coefficient out of geometry that says nothing. So the joints a muscle acts about
  are exactly `:acts-about` plus `:crosses`, and adding a joint to a muscle's
  reach is an edit to its entry in `muscles` rather than a consequence of where
  the joint happens to sit.

  This is the predicate `recruit/solve` builds its constraint matrix from."
  [muscle joint]
  (boolean (or (= joint (:acts-about muscle))
               (= joint (get-in muscle [:crosses :joint])))))

(defn coupled-arms
  "{joint signed-moment-arm} for every joint in `joints` this muscle spans.

  RAW SIGNED ARMS, in `moment-arm`'s one convention (positive opposes flexion) —
  NOT `effectiveness`, which applies `task-sense`. A coupled group is one solve
  over several equilibria, so there is one sign convention across the whole
  constraint matrix and the loads are stated in it; `task-sense` exists to let a
  SINGLE task state its load as a magnitude, and a magnitude is exactly what a
  coupled load cannot be. The atlanto-occipital flexors' arms are negative here
  and the solve reads that as `these muscles flex this joint`, which is the fact;
  `task-sense` negated them so that `share` could distribute an unsigned surplus."
  [pose-data stature-m muscle joints]
  (into (array-map)
        (for [j joints :when (spans? muscle j)]
          [j (moment-arm pose-data stature-m muscle
                         (get-in pose-data [:joints j])
                         (axis-of muscle))])))

(defn secondary-arm
  "The moment arm a TWO-JOINT muscle has at the joint its own task does not solve,
  or nil for a muscle that crosses one joint.

  WHAT IT MEANT UNTIL 2026-09-08, AND WHAT IT MEANS NOW. A muscle that spans two
  joints appears in two equilibria at once, and the two are coupled: the force
  that balances the knee is the same force that appears at the hip. `recruit`'s
  closed form is the solution for ONE equality constraint, so each two-joint
  muscle was solved where it was primary and the moment it simultaneously exerted
  at its other joint was computed here and reported as `:secondary-moment-nm` — a
  real moment, in the model's own numbers, that the other joint's equilibrium had
  NOT been told about. It reached 3.63 N·m at the hip in a deep squat.

  `recruit/solve` now satisfies those constraints simultaneously (see
  `muscle/coupled-groups`), so for the joints inside a coupled group the secondary
  moment IS fed — it is part of the equilibrium that was solved, not a remainder
  from one that was not. This function still computes it, because the number is
  worth showing either way, and `muscle/solve-muscle-tensions` marks the row
  `:secondary-fed?` when the crossing joint was one of the constraints. What
  `:two-joint-unfed-nm` totals is only the joints that were NOT: today that is
  `:c2c3`, which `longus_capitis` crosses and which has no equilibrium in this
  model at all — see `spine-test/nothing-is-solved-at-c2c3-and-the-reason-is-provenance`.

  WHICH JOINT IS PRIMARY. Inside a coupled group this decides nothing about the
  forces — all of the group's constraints are solved at once, so the phrase
  `where it is solved` no longer names anything — but it still names the joint whose task the
  muscle is emitted under, and therefore which joint is called secondary here:

    rectus femoris   knee. It shares the patellar tendon with the vasti and is
                     part of the same extensor mechanism; its hip flexion is
                     reported.
    hamstrings       knee. Not because they matter less at the hip — they matter
                     a great deal there — but because the knee has no one-joint
                     flexor to fill that side of its equilibrium, and the hip has
                     two one-joint muscles that fill both of its. Assigning the
                     hamstrings to the hip would leave the knee with an agonist
                     and no antagonist at all.
    gastrocnemius    ankle, alongside soleus, which is the redundancy the
                     criterion exists to resolve. Its knee flexion is reported."
  [pose-data stature-m muscle]
  (when-let [j (get-in muscle [:crosses :joint])]
    (moment-arm pose-data stature-m muscle
                (get-in pose-data [:joints j])
                (axis-of muscle))))

(def reference-posture
  "Anatomical neutral: every joint at zero. Each muscle's OPTIMAL length is its
  line length here — derived from the same geometry as everything else rather than
  tabulated, so moving an attachment moves the optimal length with it instead of
  leaving a stale constant behind."
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0
   :wrist-extension-deg 0.0 :shoulder-abduction-deg 0.0
   :trunk-lateral-bend-deg 0.0 :head-rotation-deg 0.0
   :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0})

(defn optimal-lengths
  "Every muscle instance's length at the reference posture, keyed by name.

  Pure and body-dependent, so a caller computes it once per body rather than per
  frame. It is not a constant table: a body of a different stature has different
  optimal lengths, and an edited attachment moves them."
  [reference-pose stature-m]
  (into (array-map)
        (for [m instances]
          [(:name m) (:length-m (line-of-action reference-pose stature-m m))])))

(defn lengths
  "Every muscle instance's current length, keyed by name."
  [pose-data stature-m]
  (into (array-map)
        (for [m instances]
          [(:name m) (:length-m (line-of-action pose-data stature-m m))])))

(defn arms
  "Every muscle INSTANCE's task coefficient at this pose, keyed by its unique name
  (a moment arm in metres, or a dimensionless cosine for a suspension muscle — see
  `effectiveness`). A nil is kept as nil rather than coerced to zero: `recruit`
  refuses to distribute a load it cannot place, and 0 would silently mean
  'infinitely strong'."
  [pose-data stature-m]
  (into (array-map)
        (for [m instances]
          [(:name m) (effectiveness pose-data stature-m m)])))
