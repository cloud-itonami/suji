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
    :insertion {:segment "thorax_abdomen" :along 0.985 :ant -0.0090 :lat 0.1225}
    :source "representative; suspension line. The insertion rides on the THORAX, not on the humerus: the acromion belongs to the shoulder girdle, and a girdle that rotated with the arm would swing its own suspension line horizontal under abduction and report that the trapezius cannot lift"}

   "levator_scapulae"
   {:name "levator_scapulae" :paired? true :pcsa-cm2 5.0
    :acts-about :shoulder :task :scapular-suspension
    ;; upper cervical transverse processes → superior medial scapula: shorter,
    ;; more vertical, and closer to the midline than the trapezius
    :origin {:segment "head_neck" :along 0.22 :ant -0.0120 :lat 0.0125}
    :insertion {:segment "thorax_abdomen" :along 0.985 :ant -0.0120 :lat 0.0750}
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
        (cond-> (= :shoulder (:acts-about muscle))
          (assoc :acts-about (keyword "shoulder" (name side)))))))

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
                (get-in pose-data [:joints (:acts-about muscle)]))))

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
