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
     :ankle-dorsiflexion-deg 0.0}))

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
           ;; (0.6 deg, SD 3.6) — see `lumbar-lordosis`.
           pelvic-tilt-deg 0.0}}]
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
  added to make the muscle come out non-zero."
  {:name "standing-neutral"
   :support :standing
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
  added to the ankle to make soleus fire; the lean does it."
  {:name "quiet-standing"
   :support :standing
   :head-flexion-deg 5.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :wrist-extension-deg 0.0
   :hip-flexion-deg 0.0 :knee-flexion-deg 5.0 :ankle-dorsiflexion-deg 5.0
   :arms-supported false})

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
  gravity-over-its-feet` asserts the constraint rather than trusting the choice."
  {:name "deep-squat"
   :support :standing
   :head-flexion-deg 0.0 :trunk-flexion-deg 55.0
   :shoulder-flexion-deg 60.0 :elbow-flexion-deg 20.0 :wrist-extension-deg 0.0
   :hip-flexion-deg 85.0 :knee-flexion-deg 110.0 :ankle-dorsiflexion-deg 25.0
   :arms-supported false})

(def reference-standing-postures [standing-neutral quiet-standing deep-squat])