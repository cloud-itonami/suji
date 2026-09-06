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
        ua-seg (place "upper_arm" side shoulder ua-frame (:length-m ua) (:com-frac ua)
                      shoulder-flexion-deg false)
        elbow (:distal ua-seg)
        ;; Elbow flexion is the angle BETWEEN the forearm and the upper arm (0° =
        ;; straight arm hanging, 90° = right angle), so the forearm's tilt from
        ;; vertical is the upper arm's tilt PLUS the elbow angle.
        fa-tilt (+ shoulder-flexion-deg elbow-flexion-deg)
        fa-frame (segment-frame fa-tilt (* (- side-sign) abduct) 0.0 false)
        fa-seg (place "forearm" side elbow fa-frame (:length-m fa) (:com-frac fa) fa-tilt false)
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
        hand-seg (place "hand" side wrist hand-frame (:length-m hand) (:com-frac hand)
                        hand-tilt false)]
    {:shoulder shoulder :elbow elbow :wrist wrist
     :segments [ua-seg fa-seg hand-seg]}))

(defn solve-pose
  "Place the whole chain in world space for a body + posture.

  Returns {:joints {…point} :segments [placed…] :sides #{…} :frame {…}}. Joint keys
  are the anatomical landmarks the moment solver takes moments about; segments are
  in proximal-to-distal order, midline first and then each arm.

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
                              usual 15-25 deg)"
  [body posture]
  (let [{:keys [head-flexion-deg trunk-flexion-deg]} posture
        lateral (or (:trunk-lateral-bend-deg posture) 0.0)
        abduct (or (:shoulder-abduction-deg posture) 0.0)
        head-rot (or (:head-rotation-deg posture) 0.0)
        stature-m (:stature-m body)
        pelvis (segment/seg body "pelvis")
        thorax (segment/seg body "thorax_abdomen")
        head (segment/seg body "head_neck")
        l5s1 [0.0 0.0 0.0]
        p-seg (place "pelvis" :midline l5s1 (segment-frame 0.0 0.0 0.0 false)
                     (:length-m pelvis) (:com-frac pelvis) 0.0 false)
        t-frame (segment-frame trunk-flexion-deg lateral 0.0 true)
        t-seg (place "thorax_abdomen" :midline l5s1 t-frame
                     (:length-m thorax) (:com-frac thorax) trunk-flexion-deg true)
        c7 (:distal t-seg)
        head-tilt (+ trunk-flexion-deg head-flexion-deg)
        h-seg (place "head_neck" :midline c7 (segment-frame head-tilt lateral head-rot true)
                     (:length-m head) (:com-frac head) head-tilt true)
        ;; the girdle is carried by the trunk, so its lateral axis is the trunk's —
        ;; leaning sideways carries both shoulders with it
        lat-axis (:lat t-frame)
        left (arm-chain body posture c7 lat-axis abduct :left 1.0 stature-m)
        right (arm-chain body posture c7 lat-axis abduct :right -1.0 stature-m)]
    {:frame {:units :metres :origin "L5/S1" :axes {:x :anterior :y :superior :z :left}}
     :sides #{:left :right}
     :joints {:l5s1 l5s1
              :hip (:distal p-seg)
              :c7 c7
              :shoulder/left (:shoulder left)
              :shoulder/right (:shoulder right)
              :elbow/left (:elbow left)
              :elbow/right (:elbow right)
              :wrist/left (:wrist left)
              :wrist/right (:wrist right)
              :vertex (:distal h-seg)}
     :segments (vec (concat [p-seg t-seg h-seg] (:segments left) (:segments right)))}))

(defn seg-at
  "The placed segment with this name, or nil."
  [pose name]
  (first (filter #(= name (:name %)) (:segments pose))))

(defn anterior-lever
  "Horizontal (anterior) distance from a joint point to a segment's CoM — the lever
  arm gravity acts through. Positive means the mass is in front of the joint."
  [joint-point placed]
  (- (first (:com placed)) (first joint-point)))

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
  (math/v* (reduce (fn [m [placed weight-n]]
                     (let [r (math/v- (:com placed) joint-point)
                           w [0.0 (- weight-n) 0.0]]
                       (math/v+ m (math/vcross r w))))
                   [0.0 0.0 0.0]
                   placed-with-weights)
           -1.0))

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
  "Vertical extent of the placed chain (hip to vertex) — a cheap invariant: a chain
  that folds forward must get SHORTER, never taller."
  [pose]
  (let [ys (mapcat (fn [{:keys [proximal distal]}] [(second proximal) (second distal)])
                   (:segments pose))]
    (- (apply max ys) (apply min ys))))
