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
  it has no wrapping surfaces, no via points, no muscle volume, and it is NOT a
  measurement of anybody.

  NON-DIAGNOSTIC (G1): a moment arm is a length."
  (:require [suji.methods.math :as math]
            [suji.methods.pose :as pose]))

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
    :origin {:segment "thorax_abdomen" :along 0.90 :ant -0.01965 :lat 0.0}
    :insertion {:segment "head_neck" :along 0.05 :ant -0.00982 :lat 0.0}
    ;; the cervical vertebrae. The extensors lie ON them, so their leverage floors
    ;; at the column's radius instead of thinning toward zero as the head folds —
    ;; without this the straight-line arm falls under the leverage floor at large
    ;; combined trunk+head flexion and `recruit` declines an ordinary posture.
    :wrap {:radius-m 0.012 :sign 1.0}
    :source "representative; occipital insertion near C7 height and well posterior, so the line stays behind the joint through flexion — a straight line from a HIGH insertion crosses in front of C7 around 30 deg and would report the extensors as flexors"}

   "upper_trapezius"
   {:name "upper_trapezius" :paired? true :pcsa-cm2 9.0
    :acts-about :shoulder :task :scapular-suspension
    ;; occiput/nuchal line → lateral clavicle-acromion; suspends the girdle
    :origin {:segment "head_neck" :along 0.10 :ant -0.0170 :lat 0.0180}
    :insertion {:segment "thorax_abdomen" :along 0.93 :ant -0.0090 :lat 0.1225}
    :source "representative; suspension line. The insertion rides on the THORAX, not on the humerus: the acromion belongs to the shoulder girdle, and a girdle that rotated with the arm would swing its own suspension line horizontal under abduction and report that the trapezius cannot lift"}

   "levator_scapulae"
   {:name "levator_scapulae" :paired? true :pcsa-cm2 5.0
    :acts-about :shoulder :task :scapular-suspension
    ;; upper cervical transverse processes → superior medial scapula: shorter,
    ;; more vertical, and closer to the midline than the trapezius
    :origin {:segment "head_neck" :along 0.22 :ant -0.0120 :lat 0.0125}
    :insertion {:segment "thorax_abdomen" :along 0.93 :ant -0.0120 :lat 0.0750}
    :source "representative; suspension line, on the thorax for the same reason as upper_trapezius — the scapula is not the humerus"}

   "anterior_deltoid"
   {:name "anterior_deltoid" :paired? true :pcsa-cm2 10.0
    :acts-about :shoulder :task :shoulder-flexion
    ;; clavicle → deltoid tuberosity, anterior to the humeral axis
    :origin {:segment "thorax_abdomen" :along 1.0 :ant 0.01811 :lat 0.1225}
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
    :origin {:segment "thorax_abdomen" :along 1.0 :ant -0.0180 :lat 0.0}
    :insertion {:segment "thorax_abdomen" :along 0.93 :ant -0.0090 :lat 0.1225}
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
    :insertion {:segment "thorax_abdomen" :along 0.34 :ant -0.0520 :lat 0.0}
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
    :origin {:segment "thorax_abdomen" :along 0.97 :ant -0.0200 :lat 0.0}
    :insertion {:segment "head_neck" :along 0.07 :ant -0.0130 :lat 0.0}
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
    :origin {:segment "thorax_abdomen" :along 0.97 :ant 0.0080 :lat 0.1150}
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
    :origin {:segment "thorax_abdomen" :along 1.0408 :ant 0.0 :lat 0.1345}
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
    :insertion {:segment "thorax_abdomen" :along 0.30 :ant -0.0120 :lat 0.0330}
    :source "representative; the principal lateral flexor of the lumbar spine"}

   "obliques"
   {:name "obliques" :paired? true :pcsa-cm2 16.0 :axis :frontal
    :acts-about :l5s1 :task :trunk-lateral-flexion
    ;; iliac crest → lower ribs, further from the midline than QL
    :origin {:segment "pelvis" :along 0.20 :ant 0.0060 :lat 0.0800}
    :insertion {:segment "thorax_abdomen" :along 0.42 :ant 0.0060 :lat 0.0700}
    :source "representative; external + internal oblique as one lateral-flexion group"}

   "scalenes"
   {:name "scalenes" :paired? true :pcsa-cm2 5.0 :axis :frontal
    :acts-about :c7 :task :cervical-lateral-flexion
    ;; first and second ribs → cervical transverse processes
    :origin {:segment "thorax_abdomen" :along 0.93 :ant 0.0040 :lat 0.0250}
    :insertion {:segment "head_neck" :along 0.16 :ant 0.0040 :lat 0.0150}
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
    :origin {:segment "thorax_abdomen" :along 0.05 :ant 0.0100 :lat 0.0200}
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
    :insertion {:segment "thorax_abdomen" :along 0.25 :ant -0.0485 :lat 0.0}
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
        ;; line above exists for
        (cond-> (:crosses muscle)
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

(defn effectiveness
  "The coefficient this muscle contributes to its task's equilibrium: a moment arm
  in metres for a moment task, a dimensionless direction cosine for a suspension
  task. Returns nil when the line of action is degenerate."
  [pose-data stature-m muscle]
  (if (= :scapular-suspension (:task muscle))
    (suspension-effectiveness pose-data stature-m muscle)
    (moment-arm pose-data stature-m muscle
                (get-in pose-data [:joints (:acts-about muscle)])
                (axis-of muscle))))

(defn secondary-arm
  "The moment arm a TWO-JOINT muscle has at the joint its own task does not solve,
  or nil for a muscle that crosses one joint.

  WHAT THIS IS HONEST ABOUT. A muscle that spans two joints appears in two
  equilibria at once, and the two are coupled: the force that balances the knee is
  the same force that appears at the hip. Solving that needs an optimisation with
  two equality constraints, and the Crowninshield–Brand form `recruit` uses is the
  closed solution for ONE. There is no closed form of that shape for two, so this
  model does not have one.

  What it does instead is state the consequence rather than hide it. Each
  two-joint muscle is solved in the equilibrium where it is the primary actor, and
  the moment it simultaneously exerts at its other joint is computed here and
  reported as `:secondary-moment-nm` — a real moment, in the model's own numbers,
  that the other joint's equilibrium was NOT told about.
  `muscle/tension-summary` totals it per joint as `:two-joint-unfed-nm` so the
  size of the approximation is visible at every posture instead of being a
  sentence in a docstring.

  WHICH JOINT IS PRIMARY, and why:

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
