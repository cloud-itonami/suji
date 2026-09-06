(ns suji.methods.posture
  "suji (筋) — laptop-workstation → sagittal posture (joint angles). 1:1 Clojure port of
  `src/suji/methods/posture.cljc` (ADR-2606061900). Stdlib only.

  The kinematic front-end: turns an ergonomic setup (where the screen is, where the
  keyboard is, whether the back is supported) into the joint angles of the sagittal chain.
  A documented, monotonic ergonomic model — NOT a biometric measurement (that is kizashi).
  All angles are degrees.

  House style: a Workstation / Posture is a kebab-keyword map; pure fns.")

(defn- clamp [x lo hi]
  (max lo (min hi x)))

;; --- lumbar lordosis ---------------------------------------------------------

(def lumbar-lordosis
  "Measured lumbar lordosis, by posture, in degrees — Cobb between the cranial
  endplate of L1 and the cranial endplate of S1.

  WHY THIS TABLE IS HERE AT ALL. `pose/lumbar-chord-tilt-deg` gives the lumbar
  spine an orientation of its own, driven by `:pelvic-tilt-deg`, and the lordosis
  that results is numerically equal to that input. So a posture that wants to be
  a SITTING or a STANDING posture rather than an arbitrary one needs a lordosis
  taken from a measurement, and this is where the measurements are.

  THE SOURCE. Cho IY, Park SY, Park JH, Kim TK, Jung TW, Lee HM. `The Effect of
  Standing and Different Sitting Positions on Lumbar Lordosis: Radiographic Study
  of 30 Healthy Volunteers`, Asian Spine Journal 2015;9(5):762-769. Full text read
  2026-09-08 from
  https://www.asianspinejournal.org/journal/view.php?doi=10.4184%2Fasj.2015.9.5.762
  30 healthy male volunteers, mean age 31.1 (SD 1.9), 73.6 kg (SD 9.2), 175 cm
  (SD 6.1) — a cohort close in build to Wilke's single subject (45 years, 70 kg,
  168 cm), which is why this table can be put beside that measurement at all.

  THE ONE THAT MATTERS MOST IS `:stool`, AND IT IS ZERO. Cho measures lordosis on
  a stool at 0.6 deg (SD 3.6) — straight to well inside its own scatter. Wilke's
  only comparable entry is `relaxed sitting on a stool with a normally straight
  back`, and this model's neutral is a straight lumbar spine. So the model's
  neutral IS that posture, MEASURED rather than assumed, and
  `spine/lumbar-cross-check` at Wilke's own posture does not move because the
  pelvis learned to rotate.

  ⚠ THE SCATTER IS LARGE AND THE COHORT IS NOT WILKE'S. Standing is 47.1 with an
  SD of 10.5, so a fifth of Cho's own subjects are more than 10 deg from it, and
  none of them is the man Wilke implanted a transducer in. Any comparison that
  uses a number from here against a pressure from Wilke has imported a parameter
  the pressure's own source does not state — `spine/lumbar-references` marks the
  entries that do with `:parameter-not-in-source` rather than letting a ratio
  imply that one paper supplied both halves."
  {:standing {:deg 47.1 :sd 10.5}
   :chair-with-lumbar-support {:deg 36.2 :sd 8.4}
   :chair-90-deg {:deg 17.7 :sd 4.4}
   :stool {:deg 0.6 :sd 3.6}
   :chair-with-anterior-support {:deg -4.9 :sd 3.3}
   :cross-legged {:deg -7.4 :sd 3.5}
   :citation (str "Cho IY, Park SY, Park JH, Kim TK, Jung TW, Lee HM. "
                  "The Effect of Standing and Different Sitting Positions on Lumbar "
                  "Lordosis: Radiographic Study of 30 Healthy Volunteers. "
                  "Asian Spine J 2015;9(5):762-769.")
   :url "https://www.asianspinejournal.org/journal/view.php?doi=10.4184%2Fasj.2015.9.5.762"
   :obtained :full-text
   :method "Cobb, cranial endplate of L1 to cranial endplate of S1"
   :cohort {:n 30 :sex :male :age-y 31.1 :mass-kg 73.6 :stature-cm 175.0}})

(defn pelvic-tilt-for
  "The `:pelvic-tilt-deg` that gives a posture the lordosis Cho measured for it,
  measured from this model's straight-lumbar neutral.

  IT IS A DIFFERENCE, and it has to be. This model has no pelvic incidence and no
  sacral endplate, so it cannot state an absolute sacral slope; what it can state
  is how far a posture's lumbar spine is from straight. The neutral is the stool,
  which Cho measures at 0.6 deg, so every other posture's tilt is its lordosis
  minus that — standing comes out at 46.5.

  ⚠ AND THAT NUMBER IS TOO BIG FOR A PELVIS, which is the honest cost of having
  one input. A real lumbar spine gains lordosis partly by rotating its sacrum and
  partly by wedging its own discs and vertebrae; this model has no wedging, so the
  pelvis has to supply all of it. Cho reports a strong correlation between the
  loss of lordosis and the loss of sacral slope (r = 0.731) and between it and the
  gain in pelvic tilt (r = -0.842), but does not report the PARTITION, so the
  share cannot be sourced and is not invented here. The direction of the error is
  stated instead: in a lordotic posture this model swings the hip joints, and both
  legs with them, further posterior than a real pelvis would."
  [posture-key]
  (- (:deg (get lumbar-lordosis posture-key))
     (:deg (:stool lumbar-lordosis))))

(def lordosis-provenance
  "Where every named posture in this library gets its `:pelvic-tilt-deg` from —
  one entry per posture, and no posture may be absent.

  WHY THIS TABLE EXISTS, AND IT IS NOT DOCUMENTATION. Until 2026-09-09 every
  posture in this file carried a lordosis of zero, and every one of them carried
  it the same way: by not mentioning `:pelvic-tilt-deg` at all and letting
  `pose/lumbar-chord-tilt-deg`'s `(or … 0.0)` supply it. Zero is the RIGHT answer
  for exactly one posture — Cho measures a stool at 0.6 deg (SD 3.6), straight to
  well inside its own scatter — and it was the answer given to standing, to a deep
  squat and to three seated workstations, none of which is a stool. A default that
  is correct once and silent five times is a measurement-shaped hole: it produces
  a number a reader cannot tell from a measured one.

  So every posture is now stated, and `:basis` says which KIND of number it is:

    :measured                  read off a published radiograph of the same named
                               posture. Carries `:from`, `:measured-lordosis-deg`
                               and `:sd-deg`, and its `:pelvic-tilt-deg` must equal
                               `(pelvic-tilt-for :from)`.
    :by-construction           zero because the posture IS a zero — an anatomical
                               reference with every joint at neutral, not a posture
                               anybody holds.
    :parameter-not-in-source   nobody measured this posture's lordosis, so the
                               model holds its neutral and SAYS SO. Carries the
                               direction of the resulting error, because a posture
                               whose lordosis is unknown still has one.

  `every-named-posture-declares-where-its-lordosis-came-from` is the gate: add a
  posture without an entry here and the suite goes red, which is the only way a
  silent zero cannot come back."
  {"standing-neutral"
   {:posture "standing-neutral"
    :pelvic-tilt-deg 0.0
    :basis :by-construction
    :note (str "the anatomical reference, standing up: every joint at neutral by "
               "definition, and the lumbar spine with them. It is not a claim "
               "about a body — it is the zero the other standing postures are "
               "measured from, and `quiet-standing` is what a person standing "
               "still actually does.")}

   "quiet-standing"
   {:posture "quiet-standing"
    :pelvic-tilt-deg 46.5
    :basis :measured
    :from :standing
    :measured-lordosis-deg 47.1
    :sd-deg 10.5
    :note (str "Cho et al. 2015 radiograph standing at 47.1 deg (SD 10.5) and a "
               "stool at 0.6, so the tilt from this model's straight-lumbar "
               "neutral is 46.5. The SD is a fifth of the mean: a fifth of Cho's "
               "own 30 volunteers are more than 10 deg from this number, and "
               "nobody in that cohort is the reader's body.")}

   "quiet-standing-lumbar-neutral"
   {:posture "quiet-standing-lumbar-neutral"
    :pelvic-tilt-deg 0.0
    :basis :by-construction
    :note (str "the CONTROL for `quiet-standing`, and its lordosis is zero for the "
               "same reason `standing-neutral`'s is: it is the definition of the "
               "control rather than a claim about a standing body. It exists "
               "because this model cannot hold Cho's standing lordosis and the "
               "measured line of gravity at once, and the leg's claims are about "
               "the second of those.")}

   "deep-squat"
   {:posture "deep-squat"
    :pelvic-tilt-deg 0.0
    :basis :parameter-not-in-source
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 0.0
     :searched :cho-2015
     :note (str "Cho measures standing and five SITTING postures. A deep squat is "
                "not among them and no radiographic lordosis for one was found, "
                "so the model holds its straight-lumbar neutral here.")
     :direction (str "a deep squat is the posture lumbar lordosis is most often "
                     "reported to REVERSE in, as the pelvis rotates posteriorly at "
                     "the bottom of the descent. If that is so, this model "
                     "OVER-STATES the squat's lordosis by holding it at zero — but "
                     "the size is not sourced, and by how much is not stated here.")}}

   "laptop-on-lap"
   {:posture "laptop-on-lap"
    :pelvic-tilt-deg 0.0
    :basis :parameter-not-in-source
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 0.0
     :searched :cho-2015
     :note (str "a slumped, unsupported sit with a laptop on the thighs. Cho's "
                "nearest rows are a STOOL (0.6) and a 90-deg chair (17.7), and "
                "this is neither; the model holds the stool's zero because that is "
                "its neutral, not because this posture was measured at it.")
     :direction (str "Cho's five sitting rows span -7.4 to 36.2 deg, so a seated "
                     "posture's true lordosis is somewhere in that band and this "
                     "one is at its lower end. The sign of the error is therefore "
                     "not stated — and what a lordosis does to L4/L5 in this model "
                     "runs through the anterior translation named in "
                     "`pose/lumbar-chord-tilt-deg`, so the SIZE of any correction "
                     "would not be trustworthy either.")}}

   "laptop-on-desk"
   {:posture "laptop-on-desk"
    :pelvic-tilt-deg 0.0
    :basis :parameter-not-in-source
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 0.0
     :searched :cho-2015
     :note (str "a desk chair with the back supported. Cho's `chair with lumbar "
                "support` (36.2) is a chair with a lumbar roll and her `90-deg "
                "chair` (17.7) is one without; `:back-supported` does not say "
                "which, so neither row can be read onto this posture without "
                "choosing, and choosing is fitting.")
     :direction (str "both candidate rows are POSITIVE, so a real backrested sit "
                     "is more lordotic than the zero held here — this model "
                     "under-states this posture's lordosis, by somewhere between "
                     "17 and 36 deg depending on the chair.")}}

   "external-monitor+keyboard"
   {:posture "external-monitor+keyboard"
    :pelvic-tilt-deg 0.0
    :basis :parameter-not-in-source
    :parameter-not-in-source
    {:parameter :pelvic-tilt-deg
     :value 0.0
     :searched :cho-2015
     :note "as `laptop-on-desk`: a backrested desk chair, which Cho does not have."
     :direction (str "same as `laptop-on-desk` — under-stated by 17 to 36 deg if "
                     "the backrest supports the lumbar spine at all.")}}

   "seated-posture"
   {:posture "seated-posture"
    :pelvic-tilt-deg 0.0
    :basis :measured
    :from :stool
    :measured-lordosis-deg 0.6
    :sd-deg 3.6
    :note (str "the constructor's DEFAULT, and the one zero in this table that is "
               "a measurement. `seated-posture` with nothing passed is an upright, "
               "unsupported, hands-in-lap sit — which is what Cho radiographed on "
               "a stool at 0.6 deg (SD 3.6), straight to well inside its own "
               "scatter. It is derived as `(pelvic-tilt-for :stool)` rather than "
               "written as 0.0 so that the zero carries its source; a caller who "
               "passes `:pelvic-tilt-deg` is stating a different posture and this "
               "entry no longer describes it.")}})

(defn lordosis-provenance-for
  "The `lordosis-provenance` entry for a posture map or a posture name.

  REFUSES a posture this library names but has no entry for, rather than
  returning nil — an unanswered question and a posture with no lordosis look
  identical once nil has been returned, which is the exact shape of the hole this
  table was added to close."
  [posture-or-name]
  (let [nm (if (map? posture-or-name) (:name posture-or-name) posture-or-name)]
    (or (get lordosis-provenance nm)
        (throw (ex-info (str "no lordosis provenance for " (pr-str nm)
                             "; a posture may not carry a lordosis this library "
                             "cannot say the origin of")
                        {:type :value-error :posture nm})))))

;; --- support mode ------------------------------------------------------------

(defn support-mode
  "`:standing` or `:seated`. Defaults to `:seated`, which is what this actor has
  modelled since it existed — a desk worker at a laptop — so a posture written
  before the lower limb landed keeps meaning what it meant.

  WHY THIS IS THE CRUX OF THE WHOLE LOWER LIMB, and not a flag.

  The hip, knee and ankle are the only joints in this model whose load does not
  follow from the posture alone. Every joint above them carries whatever hangs off
  it, and gravity settles the question: a forearm held out weighs what it weighs.
  The lower limb is the part of the body that is BETWEEN the mass and the thing
  holding the mass up, so what it carries depends on where the body is resting —
  and where the body is resting is not an angle.

  Seated, the chair takes the trunk through the ischial tuberosities. That load
  reaches the seat WITHOUT passing through the hip, the knee or the ankle, so each
  of those joints carries only what is distal to it: a few kilograms of limb.

  Standing, there is nothing under the pelvis. The whole body's weight has to
  reach the floor through both legs, so every lower-limb joint carries everything
  above it — an order of magnitude more.

  In the statics the difference is exactly ONE FORCE. The free body distal to each
  joint contains the same segments either way; standing adds the ground reaction
  under the foot, and that force is larger than everything else in the problem put
  together. `load/lower-limb-loads` is written to make that visible rather than to
  branch twice.

  Getting this wrong is not a wrong number that looks wrong. A model that put body
  weight through the knee of a seated person would report a plausible 300 N at
  every desk posture it has ever been asked about, and the only way to notice
  would be to already know the answer. The test that would fail is
  `seated-does-not-put-body-weight-through-the-knee`."
  [posture]
  (or (:support posture) :seated))

(defn standing?
  "Is the ground pushing up under this posture's feet?"
  [posture]
  (= :standing (support-mode posture)))

(defn thigh-supported?
  "Does the seat carry the thigh?

  Default: yes when seated, no when standing. A seat that carries the thigh
  carries everything distal to it as well, so the hip of a seated person is
  empty — which is what makes a chair restful, and is why the seated hip moment
  this model reports is zero rather than the 36 N·m a cantilevered thigh costs.

  IDEALISED, and the idealisation is in one direction. A real seat ends before the
  knee does, so a short length of thigh overhangs it; the true seated hip moment
  is small and positive rather than zero. Setting `:thigh-supported false` gives
  the other bound — the thigh held out with nothing under it — and the answer is
  between them."
  [posture]
  (if (contains? posture :thigh-supported)
    (boolean (:thigh-supported posture))
    (not (standing? posture))))

(defn posture-from-workstation
  "Map an ergonomic setup to sagittal joint angles (documented monotonic model).

    - head flexion ≈ 1.1° per cm the screen sits below eye level, capped at 60°;
      0 cm below → ~5° resting flexion.
    - trunk flexion: 5° if supported, else 20° self-supported slump.
    - shoulder flexion ≈ 0.8° per cm keyboard-above-elbow + a 15° forward-reach base.
    - scapular elevation ≈ 1.2° per cm keyboard-above-elbow.
    - elbow ~ 90° neutral typing (kept fixed; forearm horizontal).
    - wrist extension ≈ 12° + 0.9° per cm the keyboard sits above elbow height,
      capped at 35°. A keyboard the hands must reach UP to is the classic cause of
      sustained wrist extension, and it is the input this model was missing until
      the wrist had an equilibrium to spend it on."
  [ws]
  (let [head (clamp (+ 5.0 (* 1.1 (:screen-below-eye-cm ws))) 0.0 60.0)
        trunk (if (:back-supported ws) 5.0 20.0)
        shoulder (clamp (+ 15.0 (* 0.8 (max 0.0 (:keyboard-above-elbow-cm ws)))) 0.0 90.0)
        elevation (clamp (* 1.2 (max 0.0 (:keyboard-above-elbow-cm ws))) 0.0 45.0)
        wrist-ext (clamp (+ 12.0 (* 0.9 (max 0.0 (:keyboard-above-elbow-cm ws)))) 0.0 35.0)]
    {:head-flexion-deg head
     :trunk-flexion-deg trunk
     :shoulder-flexion-deg shoulder
     :elbow-flexion-deg 90.0
     :shoulder-elevation-deg elevation
     :wrist-extension-deg wrist-ext
     :arms-supported (:arms-supported ws)
     ;; A workstation is a CHAIR. Saying so is the difference between a lower limb
     ;; that reports what a seated leg carries and one that reports what a standing
     ;; leg carries, and the two differ by a factor of about thirty.
     :support :seated
     ;; hip and knee at right angles with the feet flat: shank tilt is
     ;; (hip − knee) = 0, so the shank hangs vertically and the foot lies flat at
     ;; 90° to it. These are not derived from the workstation, because nothing in
     ;; a workstation description says how high the seat is.
     :hip-flexion-deg 90.0
     :knee-flexion-deg 90.0
     :ankle-dorsiflexion-deg 0.0
     ;; STATED SINCE 2026-09-09, and it is still zero. A workstation is a CHAIR
     ;; and Cho does not radiograph the chair this describes — her nearest rows
     ;; are a stool at 0.6 and a 90-deg chair at 17.7, and `:back-supported` does
     ;; not say which. So the model holds its neutral and `lordosis-provenance`
     ;; records that this posture's lordosis is `:parameter-not-in-source` with
     ;; the direction of the error, rather than the value arriving here silently
     ;; through `(or (:pelvic-tilt-deg posture) 0.0)` and reading like one that
     ;; was measured.
     :pelvic-tilt-deg 0.0}))

(defn seated-posture
  "A seated posture stated directly, for the cases the workstation model cannot
  reach.

  `posture-from-workstation` derives the trunk angle from ONE bit — whether the
  back is supported — and gives 5° supported, 20° unsupported. There is no way
  through it to say `sitting upright with no backrest`, which is an ordinary
  posture and the one a lumbar-pressure measurement is usually taken in; a caller
  who wanted it had to write the whole map out by hand and re-state the seated
  lower limb from memory. This is the constructor that was missing.

  Everything defaults to an upright, unsupported, hands-in-lap sit; pass what
  differs."
  [& {:keys [trunk-flexion-deg head-flexion-deg shoulder-flexion-deg
             elbow-flexion-deg wrist-extension-deg arms-supported
             hip-flexion-deg knee-flexion-deg ankle-dorsiflexion-deg thigh-supported
             pelvic-tilt-deg]
      :or {trunk-flexion-deg 0.0 head-flexion-deg 0.0 shoulder-flexion-deg 0.0
           elbow-flexion-deg 0.0 wrist-extension-deg 0.0 arms-supported false
           hip-flexion-deg 90.0 knee-flexion-deg 90.0 ankle-dorsiflexion-deg 0.0
           thigh-supported true
           ;; a straight lumbar spine, which is what Cho measures on a stool
           ;; (0.6 deg, SD 3.6) — see `lumbar-lordosis`. DERIVED rather than
           ;; written as 0.0 since 2026-09-09: the number is the same to the bit,
           ;; and it now carries its source instead of looking like an unset
           ;; default. `lordosis-provenance` has the entry.
           pelvic-tilt-deg (pelvic-tilt-for :stool)}}]
  {:support :seated
   :pelvic-tilt-deg pelvic-tilt-deg
   :trunk-flexion-deg trunk-flexion-deg
   :head-flexion-deg head-flexion-deg
   :shoulder-flexion-deg shoulder-flexion-deg
   :elbow-flexion-deg elbow-flexion-deg
   :wrist-extension-deg wrist-extension-deg
   :shoulder-elevation-deg 0.0
   :arms-supported arms-supported
   :hip-flexion-deg hip-flexion-deg
   :knee-flexion-deg knee-flexion-deg
   :ankle-dorsiflexion-deg ankle-dorsiflexion-deg
   :thigh-supported thigh-supported})

;; Three reference laptop scenarios — the answer to "what does a laptop posture do".
(def laptop-on-lap
  {:name "laptop-on-lap"
   :screen-below-eye-cm 35.0
   :keyboard-above-elbow-cm -5.0
   :back-supported false
   :arms-supported false})

(def laptop-on-desk
  {:name "laptop-on-desk"
   :screen-below-eye-cm 20.0
   :keyboard-above-elbow-cm 6.0
   :back-supported true
   :arms-supported true})

(def external-monitor-eye-level
  {:name "external-monitor+keyboard"
   :screen-below-eye-cm 0.0
   :keyboard-above-elbow-cm 0.0
   :back-supported true
   :arms-supported true})

(def reference-workstations [laptop-on-lap laptop-on-desk external-monitor-eye-level])

;; --- standing reference postures ---------------------------------------------
;; The workstations above are all seated. These are the postures the lower limb
;; exists to answer for, and each is here because a test needs a posture whose
;; angles are stated once rather than inlined at four call sites.

(def standing-neutral
  "Standing with every joint at zero: the anatomical reference, standing up.

  It is a REFERENCE and not a posture anybody holds. With the whole chain stacked
  vertically the line of gravity falls almost exactly over the ankle joints, so
  the model correctly reports that almost nothing is asked of the plantarflexors —
  correctly, because a body balanced perfectly over its ankles would need nothing
  from them. That is the control for `quiet-standing`: the plantarflexor demand
  there comes from the LEAN, which is measured, and not from a constant somebody
  added to make the muscle come out non-zero.

  ITS LORDOSIS IS ZERO BY CONSTRUCTION, which is a different kind of number from
  the zero `seated-posture` carries. That one is Cho's stool, measured. This one
  is the definition of the posture: every joint at neutral, and the lumbar spine
  is a joint. `lordosis-provenance` records the two as `:by-construction` and
  `:measured` so that a reader cannot mistake either for the other."
  {:name "standing-neutral"
   :support :standing
   :pelvic-tilt-deg 0.0
   :head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :wrist-extension-deg 0.0
   :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0
   :arms-supported false})

(def quiet-standing
  "Standing still: the posture a person is actually in when they are standing.

  Five degrees of ankle dorsiflexion and five of knee flexion, which together
  incline the shank forward over a flat foot and leave the thigh vertical. The
  numbers are REPRESENTATIVE of the quiet-stance literature rather than measured
  here; what they are chosen to reproduce is the one thing about quiet standing
  everybody agrees on — the line of gravity passes a few centimetres ANTERIOR to
  the ankle joint, so the plantarflexors work continuously and are never silent.
  This model puts it at about 3.6 cm.

  The mechanism is geometric and can be checked by hand: a shank inclined 5°
  forward puts the ankle 0.42 m × sin 5° ≈ 3.6 cm behind the knee, and the mass
  above the knee is what the ankle then has to hold up over that lever. Nothing is
  added to the ankle to make soleus fire; the lean does it.

  IT HAS A LORDOSIS SINCE 2026-09-09, AND IT IS MEASURED. Cho et al. 2015
  radiograph standing at 47.1 deg (SD 10.5) against a stool at 0.6, so the tilt
  from this model's straight-lumbar neutral is 46.5 — `(pelvic-tilt-for
  :standing)`, not a literal. Until then this posture carried zero, which is a
  STOOL's lordosis, and it carried it by not mentioning the key.

  ⚠ AND INSTALLING IT BREAKS THE OTHER MEASURED FACT ABOUT THIS POSTURE, which is
  the most useful thing on this branch. A real quiet stance puts the line of
  gravity 2-6 cm anterior to the ankle; this model put it at 3.70 cm with the
  lordosis unset and puts it at 13.97 cm with Cho's lordosis installed, because
  L5/S1 is the root of the chain and tilting the lumbar chord TRANSLATES the whole
  trunk forward instead of rotating the pelvis under it. The two measurements
  cannot both be reproduced here, and `cho-s-standing-lordosis-and-the-measured-
  line-of-gravity-cannot-both-hold` pins the 10.27 cm rather than choosing between
  them. It is the same defect that makes `spine/sitting-standing-comparison`
  overshoot Wilke sevenfold, arriving from a completely independent measurement."
  {:name "quiet-standing"
   :support :standing
   :head-flexion-deg 5.0 :trunk-flexion-deg 0.0
   :pelvic-tilt-deg (pelvic-tilt-for :standing)
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :wrist-extension-deg 0.0
   :hip-flexion-deg 0.0 :knee-flexion-deg 5.0 :ankle-dorsiflexion-deg 5.0
   :arms-supported false})

(def quiet-standing-lumbar-neutral
  "`quiet-standing` with the lumbar spine held straight — the control, and not a
  posture anybody is in.

  It is what `quiet-standing` WAS until 2026-09-09, when the standing lordosis
  Cho measured was installed. It is kept because two of this library's claims
  about quiet standing are claims about the LEG — the line of gravity a few
  centimetres in front of the ankle, and the plantarflexor demand that follows —
  and this model cannot hold those and Cho's lordosis at the same time (see
  `quiet-standing`). Separating them says which of the two a given test is about
  instead of quietly weakening one to fit the other."
  (assoc quiet-standing :name "quiet-standing-lumbar-neutral" :pelvic-tilt-deg 0.0))

(def deep-squat
  "A deep squat, held: thighs near horizontal, heels down, trunk leaning forward to
  keep the line of gravity over the feet.

  The knee travels forward past the ankle, so the ground reaction passes well
  BEHIND the knee and the quadriceps carry it — which is the difference between
  this and standing, where the same force passes in front of the knee and the
  quadriceps do nothing. Representative angles for a squat held with the heels
  down; a squat is a range of postures and this is one of them.

  THE TRUNK LEAN IS NOT FREE. A squat is only a posture somebody is holding while
  the line of gravity stays over the feet, and with the thighs near horizontal the
  trunk is most of what decides where that line falls: at 45 degrees it lands a
  quarter of the way along the base of support and at 55 it lands a third of the
  way, just anterior to the ankle. 55 is used here because that is a squat balanced
  on the whole foot rather than on the heel. `a-standing-posture-keeps-its-line-of-
  gravity-over-its-feet` asserts the constraint rather than trusting the choice.

  ITS LORDOSIS IS NOT SOURCED. Cho radiographs standing and five SITTING
  postures; a squat is not among them and no radiographic lumbar lordosis for one
  was found. The model therefore holds its straight-lumbar neutral here and
  `lordosis-provenance` marks it `:parameter-not-in-source` with the direction —
  a deep squat is the posture lordosis is most often reported to REVERSE in, so a
  zero here is likely to OVER-state it. By how much is not stated, because it is
  not known."
  {:name "deep-squat"
   :support :standing
   :pelvic-tilt-deg 0.0
   :head-flexion-deg 0.0 :trunk-flexion-deg 55.0
   :shoulder-flexion-deg 60.0 :elbow-flexion-deg 20.0 :wrist-extension-deg 0.0
   :hip-flexion-deg 85.0 :knee-flexion-deg 110.0 :ankle-dorsiflexion-deg 25.0
   :arms-supported false})

(def reference-standing-postures [standing-neutral quiet-standing deep-squat])
(def named-postures
  "Every posture this namespace names, keyed by the name it is known under —
  the enumeration `lordosis-provenance` is checked against.

  IT EXISTS SO THE GATE CANNOT BE FORGOTTEN. A provenance table checked against a
  list written out inside a test is checked against a copy, and a copy is where a
  new posture goes missing: add one, forget the list, and the test keeps passing
  because it is still asking about the postures it already knew. This is the list,
  it lives beside the postures, and `every-named-posture-declares-where-its-
  lordosis-came-from` walks it.

  The three WORKSTATIONS are here as the postures they produce, not as the
  ergonomic descriptions they are — a workstation has no lordosis, the sit it
  implies does. `seated-posture` is here as its default, which is the only
  configuration of it this namespace can speak for; a caller who passes
  `:pelvic-tilt-deg` has stated a posture of their own and owns its provenance."
  (into (into {} (for [ws reference-workstations]
                   [(:name ws) (posture-from-workstation ws)]))
        (into {"seated-posture" (seated-posture)
               "quiet-standing-lumbar-neutral" quiet-standing-lumbar-neutral}
              (for [p reference-standing-postures] [(:name p) p]))))
