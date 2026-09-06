(ns suji.methods.girdle
  "suji (筋) — the scapulothoracic contact: the scapula resting on the ribcage.
  Pure `.cljc`, stdlib only, closed form, no iterative solver, no I/O.

  WHY THIS EXISTS. Every structure this actor had until now could only PULL. A
  muscle pulls, a ligament pulls, and `recruit` shares a load between things that
  pull. The scapula does not articulate with the thorax by a bone-to-bone joint —
  it rests on the ribcage — so the one structure that decides whether the shoulder
  girdle can be held at all was the one kind this model did not contain: a surface
  that can PUSH.

  MEASURED, at suji main 1abb702, over 5,760 postures (trunk 0-60, head 0-60,
  shoulder flexion 0-90, elbow 0-90, abduction 0-90, lateral bend 0-40, arms
  supported and not): 1,920 girdle-suspension side-solves placed NOTHING. In 1,824
  of them all three suspenders — upper trapezius, middle trapezius, levator
  scapulae — came back `:acts-the-wrong-way`; in the other 96 the levator was
  `:coefficient-below-floor` and the two trapezius fibres were still wrong-way.
  `recruit`'s own note says what that refusal means: the posture has left the range
  that line of action represents, and no wrapping surface and no further muscle
  fixes it. It is right about the muscles and it was reasoning about an incomplete
  body. Those postures are ones where the head has folded far enough that every
  suspension line runs DOWNWARD from the acromion, and a girdle hanging from lines
  that all pull down is unholdable — unless something underneath it pushes.

  THE MECHANISM. The thorax narrows toward the top. The scapula sits on that
  narrowing, so the outward normal of the surface it rests on points outward AND
  up the thorax, and a normal force there has a component along the girdle's own
  suspension direction. The ribcage pushes the scapula up the slope it is resting
  on. That is the term this model was missing, and it is not a muscle.

  COMPRESSION ONLY — the defining property, and the thing most worth testing. A
  joint can pull; a surface cannot. `contact-reaction` solves the scapula's
  equilibrium along the surface normal and REFUSES with `:contact-would-pull`
  whenever the answer is negative, rather than handing back a negative normal
  force that reads in every downstream column exactly like a positive one. It is
  the same discipline as `recruit`'s three refusals and for the same reason: a
  wrong answer that is shaped like a right answer is worse than no answer.

  A SURFACE, NOT A POINT — the choice, and what it costs. See `contact-region`.

  WHAT THIS NAMESPACE DOES NOT DO. It reports FORCES (newtons) and a contact
  pressure resultant. It does not compute %MVC: that is `muscle`'s job and its
  denominator is length-aware there. Trapezius appears both here (as a member of
  the couple that holds the scapula against the thorax) and in `muscle`'s
  `:scapular-suspension` task (as a suspender), because the muscle does both jobs;
  the two forces are NOT summed into one number anywhere, and this namespace does
  not claim they are the same force.

  NON-DIAGNOSTIC (G1, 医師法 §17): a contact force is a force and a pressure is a
  pressure. Nothing here is a finding about anybody.
  REPRESENTATIVE (G7): every dimension below is a stated representative value for
  an adult torso scaled on stature. None of them is a measurement of anybody, and
  the ones taken from this repo's own anthropometry say so where they are used."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.recruit :as recruit]
            [suji.methods.segment :as segment]))

;; --- the thoracic surface ----------------------------------------------------
;;
;; A surface of revolution about the thorax's own long axis, with an ELLIPTICAL
;; cross-section (a torso is wider than it is deep) whose semi-axes shrink as the
;; surface rises. Stated in the thorax segment's own frame {:long :ant :lat}, so
;; it rides with the trunk exactly as the muscle attachments do.

(def thorax-semi-depth-frac
  "Anterior-posterior SEMI-axis of the thoracic cross-section at the reference
  level, as a fraction of stature. 0.065 x 1.70 m = 0.111 m, i.e. a chest depth of
  about 22 cm. Representative, not measured."
  0.065)

(def thorax-semi-breadth-frac
  "Lateral SEMI-axis at the reference level, as a fraction of stature.
  0.082 x 1.70 m = 0.139 m, i.e. a chest breadth of about 28 cm. Representative,
  not measured. It is deliberately smaller than half the biacromial breadth
  (`pose/biacromial-frac`/2 = 0.208 m at the same stature): the acromion OVERHANGS
  the ribcage, which is why the girdle can be suspended from above it at all."
  0.082)

(def thorax-taper-breadth
  "How fast the lateral semi-axis shrinks per metre of rise up the thorax
  (dimensionless slope). The upper thorax is markedly narrower than the mid
  thorax; over the scapula's own span the breadth falls by roughly 4 cm in 15 cm.
  Representative, not measured.

  THIS IS THE PARAMETER THE WHOLE ELEMENT TURNS ON. With no taper the surface is a
  right cylinder, every outward normal is perpendicular to the thorax axis, and
  the contact can supply no suspension component at all — it could press the
  scapula inward and never hold it up. `girdle-test` states that as a control."
  0.30)

(def thorax-taper-depth
  "The same slope for the anterior-posterior semi-axis. Smaller than the breadth
  taper: the thorax narrows more from side to side than front to back as it rises.
  Representative, not measured.

  Keeping the two DIFFERENT is not decoration. When both semi-axes shrink in the
  same proportion the surface is an elliptic cone, every cross-section is a scaled
  copy of every other, and the outward normal's direction does not depend on
  height at all — so raising the scapula could not change how the contact acts.
  It does."
  0.15)

(def contact-level-frac
  "Height of the contact patch's centre, as a fraction of the thorax segment's
  length measured from L5/S1. 0.75 x 0.49 m = 0.367 m up the thorax, which puts
  the patch on the upper thorax where the scapula lies. Representative."
  0.75)

(def contact-theta-deg
  "Circumferential position of the patch centre for the LEFT scapula, in degrees,
  measured in the thorax's transverse plane from +anterior toward +lateral. 140
  deg is posterolateral: at the reference semi-axes it puts the patch centre 8.5 cm
  behind the thorax axis and 8.9 cm to the left of it. The right side is the
  mirror (see `contact-region`). Representative."
  140.0)

(def contact-arc-half-deg
  "Half-width of the patch in that circumferential angle. 20 deg puts the medial
  edge about 4.8 cm from the midline and the lateral edge about 12.0 cm from it,
  which is the span a scapula's medial and lateral borders occupy. Representative."
  20.0)

(def contact-axial-half-frac
  "Half-height of the patch along the thorax axis, as a fraction of stature.
  0.044 x 1.70 m = 0.075 m, i.e. a patch 15 cm tall — the distance from the
  scapula's superior angle to its inferior angle. Representative."
  0.044)

(def protraction-per-shoulder-flexion
  "Degrees the patch centre slides ANTERIORLY around the thorax per degree of
  shoulder flexion. Reaching forward carries the scapula around the ribcage; at
  90 deg of shoulder flexion this is 22.5 deg of protraction. Representative, and
  it is a kinematic coupling this model asserts rather than a measured rhythm."
  0.25)

(def elevation-rise-m-per-deg
  "Metres the patch centre rises up the thorax axis per degree of
  `:shoulder-elevation-deg`. `muscle`'s docstring names that input as a GAP LEFT
  OPEN — it is carried in the posture and is an input to no equilibrium there,
  because the expression that used to consume it had no stated meaning. It has one
  here: a raised scapula sits on a NARROWER part of a differently-sloping surface,
  which changes the contact normal. 0.0012 m/deg gives about 4 cm of rise at the
  45 deg cap `posture` imposes. Representative."
  0.0012)

(defn thoracic-surface
  "The thoracic contact surface for a solved pose, in world coordinates.

  `:origin` is the thorax's proximal point (L5/S1) and `:long`/`:ant`/`:lat` are
  its frame, so the surface rotates with the trunk. `:s-ref` is the axial distance
  at which the semi-axes take their reference values; above it they shrink."
  [pose-data stature-m]
  (let [t (pose/seg-at pose-data "thorax_abdomen")
        {:keys [long ant lat]} (:frame t)
        len (:length-m t)]
    {:origin (:proximal t)
     :long long :ant ant :lat lat
     :length-m len
     :s-ref (* contact-level-frac len)
     :semi-depth-m (* thorax-semi-depth-frac stature-m)
     :semi-breadth-m (* thorax-semi-breadth-frac stature-m)
     :taper-depth thorax-taper-depth
     :taper-breadth thorax-taper-breadth}))

(defn semi-axes
  "The two semi-axes [depth breadth] of the cross-section at axial distance `s`.

  Floored well above zero rather than allowed to pass through it: a semi-axis that
  reaches zero is a surface that has closed to a line, where the normal is not
  defined, and a negative one is a surface turned inside out. Neither is a torso,
  and the floor keeps a caller who asks about an absurd height from getting an
  answer shaped like a real one."
  [{:keys [semi-depth-m semi-breadth-m taper-depth taper-breadth s-ref]} s]
  (let [d (- s s-ref)]
    [(max 0.005 (- semi-depth-m (* taper-depth d)))
     (max 0.005 (- semi-breadth-m (* taper-breadth d)))]))

(defn surface-point
  "World point on the thoracic surface at axial distance `s` and circumferential
  angle `theta-deg` (0 = anterior, 90 = toward the person's left)."
  [{:keys [origin long ant lat] :as surface} s theta-deg]
  (let [[a b] (semi-axes surface s)
        th (math/radians theta-deg)]
    (-> origin
        (math/v+ (math/v* long s))
        (math/v+ (math/v* ant (* a (Math/cos th))))
        (math/v+ (math/v* lat (* b (Math/sin th)))))))

(defn surface-normal
  "Outward unit normal of the thoracic surface at (`s`, `theta-deg`).

  DERIVED, not asserted. For X(s,th) = C + s*u + a(s)cos(th)*p + b(s)sin(th)*q with
  a(s) = a0 - sa*(s - s0) and b(s) = b0 - sb*(s - s0), the outward normal is
  proportional to

      b*cos(th) * p  +  a*sin(th) * q  +  (a*sb*sin^2(th) + b*sa*cos^2(th)) * u

  The third term is the whole mechanism of this namespace: it is zero when the
  taper is zero, so a cylinder's normals are all perpendicular to its axis and the
  contact can hold nothing up. With the thorax narrowing upward the term is
  positive, and the surface pushes the scapula UP the slope as well as outward.

  `girdle-test` does not take the algebra on trust — it checks the result against
  finite differences of `surface-point` along both surface directions, which is
  what a normal IS."
  [{:keys [long ant lat taper-depth taper-breadth] :as surface} s theta-deg]
  (let [[a b] (semi-axes surface s)
        th (math/radians theta-deg)
        c (Math/cos th)
        sn (Math/sin th)]
    (math/vnorm
     (math/v+ (math/v+ (math/v* ant (* b c))
                       (math/v* lat (* a sn)))
              (math/v* long (+ (* a taper-breadth sn sn)
                               (* b taper-depth c c)))))))

;; --- the contact region ------------------------------------------------------

(defn contact-region
  "The patch of thoracic surface the scapula rests on, for one side.

  A SURFACE, NOT A POINT — the choice this element makes, and what it costs.

  A point contact is a force at one place with one normal and NO moment: a point
  cannot resist being tipped about itself. A surface contact is a pressure spread
  over a patch, and its resultant acts at a CENTRE OF PRESSURE that migrates
  within the patch as the load changes. That migration is a moment — bounded by
  the force times the patch's half-extent — supplied for free, by the surface,
  without any muscle doing anything. It is why you can rest an object on a table
  and it does not tip.

  Modelling it as a point would get a moment arm that is wrong in a way that looks
  entirely reasonable, in two distinct ways, and this is why the arithmetic here
  is a patch and not a single (s, theta):

    1. The moment capacity would be zero, so the ENTIRE tipping moment of the arm
       hanging off the glenoid would be charged to the muscles. At the reference
       posture that moment is about 5 N.m; the patch can supply it several times
       over. The muscle forces would come out large, plausible, and wrong.

    2. The normal at a single point is a single direction, so the contact would
       either help or not help, uniformly. Across this patch the outward normal
       swings through 40 degrees of circumference — the medial edge faces almost
       straight back and the lateral edge faces well out to the side — and which
       part of the patch is loaded therefore decides how much of the reaction is
       vertical. A point model cannot represent that and cannot say it cannot.

  What the patch costs: two extents that are representative rather than measured
  (`contact-arc-half-deg`, `contact-axial-half-frac`), a centre of pressure that
  has to be tracked and bounded, and an `:at-patch-edge?` state to report when the
  bound is reached. A point would need none of those.

  It costs one more thing, stated because it is a real limit and not a detail:
  the patch is treated as a piece of the surface of revolution, so its edges are
  points ON that surface. A scapula is a plate with a rim that lifts off. This
  model has no rim and no lift-off short of the CoP reaching the patch edge, and
  `:at-patch-edge?` is the only warning it can give.

  MIGRATION. The patch is not nailed down. It slides anteriorly with shoulder
  flexion (`protraction-per-shoulder-flexion`) and rises with
  `:shoulder-elevation-deg` (`elevation-rise-m-per-deg`), because a scapula
  slides on the ribcage — that is the whole reason there is no joint here. Both
  couplings are representative.

  MIRRORING. `theta` is stated for the LEFT, exactly as `attachment`'s `:lat`
  offsets are, and the right side is 360 - theta. Negating the angle negates
  sin(theta) and leaves cos(theta) alone, which negates the lateral component of
  every point and every normal and leaves the anterior and axial ones untouched —
  which is what a reflection in the sagittal plane does."
  [pose-data stature-m posture side]
  (let [surface (thoracic-surface pose-data stature-m)
        flex (or (:shoulder-flexion-deg posture) 0.0)
        elev (or (:shoulder-elevation-deg posture) 0.0)
        theta-left (- contact-theta-deg (* protraction-per-shoulder-flexion flex))
        theta (if (= :left side) theta-left (- 360.0 theta-left))
        s (+ (:s-ref surface) (* elevation-rise-m-per-deg elev))
        axial-half (* contact-axial-half-frac stature-m)
        ;; the two circumferential edges, medial (toward the spine) and lateral
        medial-theta (if (= :left side)
                       (+ theta contact-arc-half-deg)
                       (- theta contact-arc-half-deg))
        lateral-theta (if (= :left side)
                        (- theta contact-arc-half-deg)
                        (+ theta contact-arc-half-deg))
        centre (surface-point surface s theta)
        medial (surface-point surface s medial-theta)
        lateral (surface-point surface s lateral-theta)
        superior (surface-point surface (+ s axial-half) theta)
        inferior (surface-point surface (- s axial-half) theta)]
    {:side side
     :surface surface
     :s-m s
     :theta-deg theta
     :centre centre
     :normal (surface-normal surface s theta)
     :medial medial :lateral lateral
     :superior superior :inferior inferior
     :medial-normal (surface-normal surface s medial-theta)
     :lateral-normal (surface-normal surface s lateral-theta)
     ;; How far the centre of pressure may travel from the patch centre before it
     ;; leaves the patch. Measured as straight distances to the edge points rather
     ;; than arc lengths: the bound is on where the resultant may ACT, which is a
     ;; position, and the chord is the honest under-statement of the arc.
     :circumferential-half-m (math/vlen (math/v- lateral centre))
     :axial-half-m (math/vlen (math/v- superior centre))}))

;; --- the contact reaction ----------------------------------------------------

(def contact-refusal
  "The reason keyword this element refuses with, in the style of `recruit`'s.

  It is its OWN reason and not one of `recruit`'s three, because it has its own
  fix and they have theirs. `:coefficient-below-floor` asks for a wrapping
  surface; `:acts-the-wrong-way` says the posture has left the range a line of
  action represents; `:no-line-of-action` asks for better attachment data. This
  one says the scapula would have to be HELD ONTO the ribcage by the ribcage, and
  nothing about muscles or attachments repairs that — the posture has asked a
  surface to be a joint."
  :contact-would-pull)

(defn contact-reaction
  "The normal force the thoracic surface must supply to keep the scapula in
  equilibrium along the surface normal, or a refusal.

  `normal` is the outward unit normal; `applied` is every OTHER force on the
  scapula as a world 3-vector in newtons — the arm load coming through the
  glenohumeral joint, and every muscle pull. Equilibrium along the normal is

      N + sum(F_i . n) = 0    =>    N = -sum(F_i . n)

  A CONTACT CARRIES COMPRESSION ONLY. N < 0 is the surface pulling the scapula
  onto itself, which a surface cannot do — so this returns
  {:refused :contact-would-pull :required-n N} and NO `:normal-n`, on the same
  principle as `recruit`: a refused element carries no force, rather than a
  negative one that every downstream sum would happily add up.

  N = 0 is not a refusal. A scapula resting on a surface with nothing pressing it
  is in contact and transmitting nothing, which is a real state and a common one.

  This is deliberately a function of a normal and a list of forces rather than of
  a body and a posture. It is the whole physical content of the element, it is
  exactly testable on constructed input, and `girdle-test` tests it that way —
  both directions, because an element that has only ever been shown to refuse has
  not been shown to discriminate."
  [normal applied]
  (if (nil? normal)
    {:refused :no-line-of-action
     :note "the thoracic surface has no normal here — the cross-section is degenerate"}
    (let [required (- (reduce + 0.0 (map #(math/vdot % normal) applied)))]
      (if (neg? required)
        {:refused contact-refusal
         :required-n required
         :direction normal
         :note (str "equilibrium along the surface normal needs "
                    (math/fmt-fixed required 2)
                    " N, i.e. the ribcage pulling the scapula onto itself. A joint "
                    "can pull and a surface cannot; at this posture the scapula is "
                    "being lifted off the thorax rather than pressed onto it.")}
        {:normal-n required
         :direction normal
         :force (math/v* normal required)}))))

(defn centre-of-pressure
  "Where the contact resultant acts, and whether that is still on the patch.

  A distributed pressure is statically equivalent to its resultant acting at one
  point, and where that point sits is set by the moment the contact has to supply.
  Given the tangential moment `m-tan` (a world 3-vector, N.m) the contact must
  supply about the patch centre, the offset that produces it is

      d = (n x m-tan) / N

  because for d perpendicular to n, d x (N n) = N (d x n) = m-tan exactly.

  BOUNDED BY THE PATCH, which is the whole difference from a point contact — and
  clamped rather than refused when the bound is reached, following this repo's
  ligament: beyond its calibrated range the force is held at the last value it can
  defend and `at-limit?` says so. The CoP reaching the patch edge is a real
  mechanical event (the scapula is on the verge of tipping onto that edge) and not
  a modelling failure, so it is a reported state, not a refusal. What the model
  cannot then supply is handed to the muscles, which is exactly where it belongs.

  The component of the demanded moment ALONG the normal is a twist about the
  contact's own normal. A frictionless pressure distribution cannot supply any of
  it, at any centre of pressure, so it is not silently dropped — it comes back as
  `:unbalanced-twist-nm` and stays the model's to answer for."
  [{:keys [centre normal circumferential-half-m axial-half-m]} normal-n m-demand]
  (let [twist (math/vdot m-demand normal)
        m-tan (math/v- m-demand (math/v* normal twist))
        raw (if (> normal-n 1e-9)
              (math/v* (math/vcross normal m-tan) (/ 1.0 normal-n))
              [0.0 0.0 0.0])
        reach (math/vlen raw)
        ;; the patch is not round; the tightest bound in the direction the offset
        ;; actually points is the honest one, and the smaller half-extent is the
        ;; conservative stand-in for it
        limit (min circumferential-half-m axial-half-m)
        at-edge? (> reach limit)
        offset (if (and at-edge? (> reach 1e-12))
                 (math/v* raw (/ limit reach))
                 raw)]
    {:offset offset
     :point (math/v+ centre offset)
     :reach-m reach
     :limit-m limit
     :at-patch-edge? at-edge?
     :supplied-nm (math/vcross offset (math/v* normal normal-n))
     :unbalanced-twist-nm twist}))

;; --- the muscles that hold the scapula against the thorax --------------------
;;
;; Defined HERE rather than in `attachment` on purpose. These three act on a body
;; — the scapula — that `pose` does not place as a segment, and their line of
;; action runs to a point on the contact patch rather than to a point on a bone
;; the kinematics carries. Putting them in `attachment` would mean either giving
;; `pose` a scapula or stating an attachment against a segment the muscle does not
;; actually pull on.

(def stabiliser-specific-tension
  "Newtons per square centimetre of physiological cross-section. The same value
  `muscle/specific-tension-n-cm2` uses, restated rather than required, because
  `muscle` requires `attachment` and `recruit` and this namespace has no other
  reason to depend on it. `girdle-test` pins the two together, so the copy cannot
  drift silently — the same bargain `muscle/force-length-factor` strikes with
  kotoba-lang/biomech and for the same kind of reason."
  60.0)

(def stabilisers
  "Serratus anterior, the rhomboids and trapezius, as the couple that holds the
  scapula on the thorax.

  THEY ARE A COUPLE, which is why they are one task. Serratus anterior runs from
  the lateral ribs, forward and around the thorax, to the medial border of the
  scapula; the rhomboids run from the thoracic spinous processes, downward and
  laterally, to the same medial border. Their pulls very nearly oppose one
  another, so their forces largely cancel as a net force and what survives is a
  MOMENT that presses the scapula flat against the ribcage. Trapezius takes the
  third corner, at the acromion. A model with only one of them would have a muscle
  that translates the scapula rather than a couple that holds it.

  `:origin` is a point on the thoracic surface, stated as (axial fraction of the
  thorax length, circumferential angle for the LEFT). `:insertion` names which
  scapular landmark it pulls, resolved against the contact patch.

  PCSA values are representative and are the same order as the groups already in
  `muscle/specs`. They are used only to weight the criterion's share between the
  three; this namespace reports forces, not %MVC."
  [{:name "serratus_anterior" :pcsa-cm2 15.0
    :origin {:along 0.68 :theta-deg 55.0}
    :insertion :medial
    :source "representative; lateral ribs, anterolateral on the thorax, running around it to the medial border — the muscle whose whole job is holding the scapula against the ribcage"}
   {:name "rhomboids" :pcsa-cm2 12.0
    :origin {:along 0.92 :theta-deg 180.0}
    :insertion :medial
    :source "representative; thoracic spinous processes, running inferolaterally to the medial border"}
   {:name "trapezius_scapular" :pcsa-cm2 10.0
    :origin {:along 0.98 :theta-deg 180.0}
    :insertion :acromial
    :source "representative; the fibres reaching the acromion. The SAME group `muscle` solves in :scapular-suspension — one muscle doing two jobs, reported as two forces and never summed"}])

(defn acromion-point
  "Where the acromial end of the scapula sits, from the glenohumeral joint `pose`
  already places: up the thorax axis and slightly further from the midline.

  Taken off the joint rather than off the surface because the acromion is NOT on
  the ribcage — it overhangs it, which is the geometric fact that lets the girdle
  be suspended from above at all."
  [pose-data stature-m side]
  (let [surface (thoracic-surface pose-data stature-m)
        g (get-in pose-data [:joints (keyword "shoulder" (name side))])
        side-sign (if (= :left side) 1.0 -1.0)]
    (-> g
        (math/v+ (math/v* (:long surface) (* 0.012 stature-m)))
        (math/v+ (math/v* (:lat surface) (* side-sign 0.008 stature-m))))))

(defn stabiliser-lines
  "Each stabiliser's insertion point, unit pull direction and moment arm about the
  patch's tipping axis, for one side.

  `axis` is the axis the tipping moment is taken about, so the coefficients come
  back in metres and can go straight to `recruit` — the same contract
  `attachment/effectiveness` honours for a moment task."
  [pose-data stature-m region side axis]
  (let [surface (:surface region)
        len (:length-m surface)
        mirror-theta (fn [th] (if (= :left side) th (- 360.0 th)))]
    (for [{:keys [name pcsa-cm2 origin insertion]} stabilisers]
      (let [o (surface-point surface
                             (* (:along origin) len)
                             (mirror-theta (:theta-deg origin)))
            i (case insertion
                :medial (:medial region)
                :acromial (acromion-point pose-data stature-m side))
            dir (math/vnorm (math/v- o i))
            r (math/v- i (:centre region))]
        {:name name
         :pcsa-cm2 pcsa-cm2
         :insertion i
         :origin o
         :dir dir
         :coeff (when dir (math/vdot (math/vcross r dir) axis))}))))

;; --- the girdle load ---------------------------------------------------------

(defn girdle-load-n
  "Weight ONE shoulder girdle carries: the arm segments hanging from it.

  Computed here rather than borrowed. `muscle/suspended-weight-n` is the public
  function for this and it reads `:arms-supported` off `(meta p)`, which
  `pose/solve-pose` never attaches — so it silently takes the unsupported branch
  whatever it is asked. `muscle`'s own solve does not use it (it has a private
  copy that takes the flag as an argument), so nothing is wrong downstream today;
  this namespace does not use it either, and says why rather than passing a flag
  into a function that cannot read it."
  [body pose-data side arms-supported]
  (let [w (pose/segment-weights body pose-data)
        hanging (if arms-supported ["upper_arm"] ["upper_arm" "forearm" "hand"])]
    (reduce + 0.0 (map #(get w (:name %)) (pose/segments-on pose-data hanging side)))))

(def vertical
  "The direction the girdle has to be held against. Same as
  `attachment/vertical` and stated again here so this namespace's equilibria can
  be read without following a require."
  [0.0 1.0 0.0])


;; --- the solve ---------------------------------------------------------------

(defn lift-reaction
  "The normal force the contact must supply to hold `lift-n` newtons of the girdle
  up, or a refusal.

  THERE ARE TWO EQUATIONS AND THIS IS THE SECOND. `contact-reaction` answers how
  hard the surface is PRESSED, from equilibrium along the surface normal. This one
  answers how much of the girdle the surface HOLDS UP, from equilibrium along the
  vertical: a reaction of N along the outward normal lifts N*(n.y), so holding
  `lift-n` needs N = lift-n / (n.y).

  Both must give a non-negative N, and both refuse with the same
  `:contact-would-pull`, because the keyword names the physics rather than the
  equation: a surface cannot pull, whichever way you ask it to.

  This is where the surface's slope decides everything. When the outward normal
  has no upward component the denominator is zero and no finite reaction lifts
  anything; when it points DOWNWARD the reaction that would lift the girdle is
  negative, i.e. the ribcage hauling the scapula onto itself. Both are refusals
  and neither is an error — they are this element saying that at this posture the
  scapula is not resting on anything that can hold it."
  [normal lift-n]
  (let [ny (when normal (math/vdot normal vertical))]
    (cond
      (nil? normal)
      {:refused :no-line-of-action
       :note "the thoracic surface has no normal here — the cross-section is degenerate"}

      (<= lift-n 0.0)
      {:normal-n 0.0 :direction normal :lifted-n 0.0}

      (<= ny 1e-9)
      {:refused contact-refusal
       :required-n (if (< ny (- 1e-9)) (/ lift-n ny) math/inf)
       :direction normal
       :note (str "the outward normal has a vertical component of "
                  (math/fmt-fixed ny 4)
                  " — the surface the scapula rests on does not slope upward here, "
                  "so no compressive reaction on it holds the girdle up. Lifting "
                  "would need the ribcage to pull the scapula onto itself.")}

      :else
      {:normal-n (/ lift-n ny) :direction normal :lifted-n lift-n})))

(defn- tipping-moment
  "Moment the hanging arm exerts about the patch centre — what would tip the
  scapula off the ribcage if nothing resisted it."
  [region glenoid load-n]
  (math/vcross (math/v- glenoid (:centre region))
               [0.0 (- load-n) 0.0]))

(defn solve-contact
  "The scapulothoracic contact for one side of one posture, at its BASELINE — the
  reaction the hanging arm's own weight generates, before any muscle presses
  harder. `solve-suspension` is what asks it to carry more.

  THE REDUCTION, stated because an unstated one is an unstated model. The scapula
  has six degrees of freedom and this element resolves three: the translation
  along the surface normal, and the two tipping rotations in the tangent plane.
  The two tangential translations are NOT resolved — this model has no friction
  and no scapulothoracic ligament, so it cannot say what stops the scapula
  sliding, and it does not pretend to. The twist about the normal is resolved only
  to the extent of saying that a frictionless contact supplies none of it: it is
  charged to the muscles and also reported as `:unbalanced-twist-nm`.

  THE ORDER, which is what makes this a closed form and not a solver:

    1. The hanging arm's tipping moment about the patch is computed.
    2. The CONTACT supplies what its centre of pressure can, bounded by the patch
       — the surface behaving like a surface, costing no muscle anything.
    3. The MUSCLES supply the rest — the tangential remainder AND the whole twist
       — shared between the three stabilisers by `recruit`, on the same
       cubed-stress criterion as every other task in this actor.
    4. The normal force follows from equilibrium along the normal, with those
       muscle pulls in it. That is where the compression-only refusal lives.

  Step 2 before step 3 is `the contact supplies a normal force and a moment about
  the contact region; the muscles supply the rest` read literally, and it is also
  the right physics: a body resting on a surface uses the surface first, and only
  starts to need holding when its centre of pressure reaches the edge.

  The capacity in step 2 is computed from the load's own press rather than from
  the final normal force, which UNDER-states it — the muscles end up pressing
  harder, so the true capacity is larger. Under-stating leaves the couple carrying
  a little more than it strictly must, which is the direction an incomplete model
  should err in.

  A REFUSED STABILISER IS USUALLY AN ANTAGONIST. Three lines pulling on one plate
  will not all resist the same tipping direction, and a static minimum-stress
  optimum does not co-contract — so `recruit` refuses the ones on the other side
  with `:acts-the-wrong-way`, correctly. They are marked `:antagonist?` here for
  the same reason `muscle` marks them: an unmarked refusal reads as an unanswered
  load, and this one is not."
  [body posture side]
  (let [p (pose/solve-pose body posture)
        stature (:stature-m body)
        region (contact-region p stature posture side)
        glenoid (get-in p [:joints (keyword "shoulder" (name side))])
        load-n (girdle-load-n body p side (:arms-supported posture))
        load-vec [0.0 (- load-n) 0.0]
        n-hat (:normal region)
        m-load (tipping-moment region glenoid load-n)
        ;; (1)+(2) what the patch can take, at the press the load alone generates
        n-from-load (if n-hat (max 0.0 (- (math/vdot load-vec n-hat))) 0.0)
        cop (when n-hat (centre-of-pressure region n-from-load (math/v* m-load -1.0)))
        supplied (if cop (:supplied-nm cop) [0.0 0.0 0.0])
        ;; (3) everything the contact could not supply: the tangential remainder
        ;; beyond the patch bound, plus the twist a frictionless surface never
        ;; touches. Computed as one vector so neither can be counted twice.
        m-residual (math/v- (math/v* m-load -1.0) supplied)
        couple-nm (math/vlen m-residual)
        axis (or (math/vnorm m-residual) [0.0 0.0 1.0])
        lines (vec (stabiliser-lines p stature region side axis))
        couple (recruit/share
                (mapv (fn [{:keys [name pcsa-cm2 coeff]}]
                        {:name name :f-max-n (* pcsa-cm2 stabiliser-specific-tension)
                         :coeff coeff})
                      lines)
                couple-nm)
        couple-carried? (recruit/carried? couple)
        couple (mapv (fn [c] (if (and (:refused c) couple-carried?)
                               (assoc c :antagonist? true)
                               c))
                     couple)
        force-of (into {} (map (juxt :name :force-n)) couple)
        ;; (4) equilibrium along the normal: the arm load plus every muscle pull
        applied (into [load-vec]
                      (keep (fn [{:keys [name dir]}]
                              (when-let [f (get force-of name)]
                                (when dir (math/v* dir f))))
                            lines))
        contact (contact-reaction n-hat applied)]
    {:side side
     :region region
     :load-n load-n
     :applied applied
     :tipping-moment-nm (math/vlen m-load)
     :contact-moment-capacity-nm (* n-from-load (min (:circumferential-half-m region)
                                                     (:axial-half-m region)))
     :contact-moment-supplied-nm (math/vlen supplied)
     :couple-demand-nm couple-nm
     :lines lines
     :couple couple
     :couple-placed? couple-carried?
     :contact contact
     :cop cop
     ;; how much of the girdle's weight the ribcage is holding up at this baseline
     :vertical-n (if-let [n (:normal-n contact)]
                   (* n (math/vdot n-hat vertical))
                   0.0)}))

(def suspension-task
  "The task keyword `muscle` solves the girdle's vertical equilibrium under. Named
  once here so the two places this namespace reaches into `attachment/instances`
  cannot disagree about it."
  :scapular-suspension)

(def suspension-groups
  "The groups already solving that task, READ from `attachment/instances` rather
  than listed. A suspender added there is included here without this namespace
  being edited, and one removed there does not leave a name behind that resolves
  to nothing — which is the failure mode a hand-written list has and this does
  not. `girdle-test` pins it to the source it is read from."
  (into #{} (comp (filter #(= suspension-task (:task %))) (map :group))
        attachment/instances))

(defn solve-suspension
  "The girdle's vertical equilibrium for one side, WITH the contact in it.

  This is the equilibrium `muscle` already solves — the girdle load shared across
  the suspenders by the cubed-stress criterion — with the contact in front of it
  and behind it.

  THE CONTACT IS NOT A CANDIDATE THE CRITERION CHOOSES BETWEEN, and entering it as
  one would be a category error. A muscle's force is CHOSEN, out of a redundant
  set, by something with a cost to minimise. A contact reaction is DETERMINED: it
  is whatever equilibrium says it is, it minimises nothing, and it has no stress
  for a cubed-stress criterion to weigh. So it is handled outside the share, in
  exactly the way `recruit` handles passive tension outside the share and for
  exactly the same reason — the criterion must not get to choose a force that is
  not chosen.

  THREE STEPS:

    1. The contact's BASELINE lift (`solve-contact`) comes off the girdle load
       first. That is the part the ribcage holds up merely because the arm's
       weight presses the scapula onto an upward-sloping surface, and it costs
       nothing.
    2. The SUSPENDERS share what is left, by the criterion, exactly as now.
    3. If they cannot — if every suspension line at this posture pulls the girdle
       DOWN, which is the measured 1,920-posture gap this element exists for — the
       contact carries the remainder instead, and the stabilisers press the
       scapula onto the ribcage hard enough to generate it. `lift-reaction`
       computes that reaction and refuses it if it would be a pull.

  Step 3 is the whole point and it is worth being plain about what it says: a
  girdle hanging from lines that all pull downward is held up by the ribcage it is
  resting on, and by the muscles pressing it there. That is not a workaround for
  the suspenders having refused — it is what actually holds a shoulder in that
  posture, and this actor had no way to say it.

  Returns {:load-n :contact :contact-n :residual-n :muscle :placed? :carried-by}."
  [body posture side]
  (let [p (pose/solve-pose body posture)
        stature (:stature-m body)
        coeffs (attachment/arms p stature)
        base (solve-contact body posture side)
        load-n (:load-n base)
        n-hat (get-in base [:region :normal])
        ;; (1) the baseline lift, which cannot be more than the load nor negative
        baseline (math/clamp (:vertical-n base) 0.0 load-n)
        residual (- load-n baseline)
        ;; (2) the suspenders
        cands (vec (for [m attachment/instances
                         :when (and (= suspension-task (:task m)) (= side (:side m)))]
                     {:name (:name m)
                      :f-max-n (* (get m :pcsa-cm2 1.0) stabiliser-specific-tension)
                      :coeff (get coeffs (:name m))}))
        shared (recruit/share cands residual)
        muscles-carried? (recruit/carried? shared)
        ;; (3) the fallback: the contact carries the remainder
        lift (when-not muscles-carried? (lift-reaction n-hat residual))
        lifted (if (and lift (:normal-n lift)) residual 0.0)
        placed? (or (< residual 1e-9) muscles-carried? (some? (:normal-n lift)))]
    {:side side
     :load-n load-n
     :contact base
     :baseline-n baseline
     :contact-n (+ baseline lifted)
     :residual-n residual
     :lift lift
     :muscle shared
     :placed? placed?
     :carried-by (cond
                   (< residual 1e-9) :contact
                   (and muscles-carried? (> baseline 1e-9)) :both
                   muscles-carried? :muscle
                   (some? (:normal-n lift)) :contact
                   :else :nobody)}))

(defn solve-girdle
  "Both sides. `{:left {...} :right {...}}` of `solve-suspension`."
  [body posture]
  (into {} (for [side [:left :right]] [side (solve-suspension body posture side)])))

(defn contact-pressure-kpa
  "Contact normal force spread over the patch, in kilopascals — a pressure, which
  is the quantity a contact is actually described by.

  The patch area is taken as an ellipse of the two half-extents, which over-states
  it (a scapula is not an ellipse) and therefore UNDER-states the pressure.
  Representative, and reported only where the contact was not refused."
  [{:keys [region contact]}]
  (when-let [n (:normal-n contact)]
    (let [area (* math/pi
                  (:circumferential-half-m region)
                  (:axial-half-m region))]
      (when (pos? area) (/ (/ n area) 1000.0)))))

(defn girdle-summary
  "What this element could and could not answer for a posture — the shape
  `muscle/tension-summary` uses, so a consumer can read the two the same way.

  `:contact-refused` counts sides where the surface would have had to pull. That
  is NOT the same kind of incompleteness as a muscle refusal and it has a
  different fix: a muscle refusal asks for a wrapping surface or better attachment
  data, and this one says the posture has asked a surface to behave like a joint."
  [body posture]
  (let [g (solve-girdle body posture)
        sides (vals g)]
    {:sides (count sides)
     :placed (count (filter :placed? sides))
     :contact-refused (count (filter #(or (get-in % [:contact :contact :refused])
                                          (get-in % [:lift :refused]))
                                     sides))
     :at-patch-edge (count (filter #(get-in % [:contact :cop :at-patch-edge?]) sides))
     :carried-by (frequencies (map :carried-by sides))
     :complete? (every? :placed? sides)}))

(defn body-with-defaults
  "A body at the reference stature, so a caller can exercise this namespace
  without reaching into `segment`."
  []
  (segment/build-body 70.0 1.70))
