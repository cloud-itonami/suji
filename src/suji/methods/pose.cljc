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

(defn- place
  "One placed segment: proximal point, frame, length → the record every consumer
  reads. `tilt-deg` is kept so a renderer can build a sagittal transform without
  inverting the direction, and so a test can state the intent it is checking."
  [name proximal frame length-m com-frac tilt-deg up?]
  (let [dir (:long frame)
        distal (math/v+ proximal (math/v* dir length-m))
        com (math/v+ proximal (math/v* dir (* com-frac length-m)))]
    {:name name
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

(defn solve-pose
  "Place the whole sagittal chain in world space for a body + posture.

  Returns {:joints {…point} :segments [placed…] :frame {…}}. Joint keys are the
  anatomical landmarks the moment solver takes moments about; segments are in
  proximal-to-distal order. The arm chain is placed ONCE — in a purely sagittal
  posture both arms are mirror images across the plane and carry the same lever,
  so the loads multiply by two rather than the geometry. The placed arm is the
  person's LEFT (the +Z side); with a non-zero abduction the two arms are no
  longer interchangeable, and `:arm-side` says which one this is so a consumer
  cannot silently read it as both.

  Out-of-plane angles are optional and default to zero:
    :trunk-lateral-bend-deg   trunk away from the midline (about X)
    :shoulder-abduction-deg   arm away from the midline
    :head-rotation-deg        axial rotation of the head on the neck"
  [body posture]
  (let [{:keys [head-flexion-deg trunk-flexion-deg
                shoulder-flexion-deg elbow-flexion-deg]} posture
        lateral (or (:trunk-lateral-bend-deg posture) 0.0)
        abduct (or (:shoulder-abduction-deg posture) 0.0)
        head-rot (or (:head-rotation-deg posture) 0.0)
        pelvis (segment/seg body "pelvis")
        thorax (segment/seg body "thorax_abdomen")
        head (segment/seg body "head_neck")
        ua (segment/seg body "upper_arm")
        fa (segment/seg body "forearm")
        hand (segment/seg body "hand")
        l5s1 [0.0 0.0 0.0]
        ;; pelvis descends from L5/S1; seated, it is the base the chain stands on.
        p-seg (place "pelvis" l5s1 (segment-frame 0.0 0.0 0.0 false)
                     (:length-m pelvis) (:com-frac pelvis) 0.0 false)
        ;; trunk rises from L5/S1, tilted forward by the trunk flexion
        t-seg (place "thorax_abdomen" l5s1
                     (segment-frame trunk-flexion-deg lateral 0.0 true)
                     (:length-m thorax) (:com-frac thorax) trunk-flexion-deg true)
        c7 (:distal t-seg)
        ;; the head's tilt is measured from the trunk it sits on, so world tilt adds
        head-tilt (+ trunk-flexion-deg head-flexion-deg)
        h-seg (place "head_neck" c7 (segment-frame head-tilt lateral head-rot true)
                     (:length-m head) (:com-frac head) head-tilt true)
        ;; glenohumeral ≈ C7 in this chain: there is no scapula segment, and the
        ;; shoulder moment is taken about the joint, so a shared origin with C7 is
        ;; the honest simplification rather than an invented offset.
        shoulder c7
        ua-seg (place "upper_arm" shoulder
                      (segment-frame shoulder-flexion-deg (- abduct) 0.0 false)
                      (:length-m ua) (:com-frac ua) shoulder-flexion-deg false)
        elbow (:distal ua-seg)
        ;; Elbow flexion is the angle BETWEEN the forearm and the upper arm (0° =
        ;; straight arm hanging, 90° = right angle), so the forearm's tilt from
        ;; vertical is the upper arm's tilt PLUS the elbow angle. With a 15°
        ;; shoulder and a 90° elbow that reaches forward and slightly up — a
        ;; keyboard posture. The pre-2026-09-06 `(- 90.0 elbow)` gave 0°, i.e.
        ;; straight down, for the same posture.
        fa-tilt (+ shoulder-flexion-deg elbow-flexion-deg)
        fa-frame (segment-frame fa-tilt (- abduct) 0.0 false)
        fa-seg (place "forearm" elbow fa-frame (:length-m fa) (:com-frac fa) fa-tilt false)
        wrist (:distal fa-seg)
        ;; no wrist flexion in this model: the hand continues the forearm
        hand-seg (place "hand" wrist fa-frame (:length-m hand) (:com-frac hand)
                        fa-tilt false)]
    {:frame {:units :metres :origin "L5/S1" :axes {:x :anterior :y :superior :z :left}}
     :arm-side :left
     :joints {:l5s1 l5s1
              :hip (:distal p-seg)
              :c7 c7
              :shoulder shoulder
              :elbow elbow
              :wrist wrist
              :vertex (:distal h-seg)}
     :segments [p-seg t-seg h-seg ua-seg fa-seg hand-seg]}))

(defn seg-at
  "The placed segment with this name, or nil."
  [pose name]
  (first (filter #(= name (:name %)) (:segments pose))))

(defn anterior-lever
  "Horizontal (anterior) distance from a joint point to a segment's CoM — the lever
  arm gravity acts through. Positive means the mass is in front of the joint."
  [joint-point placed]
  (- (first (:com placed)) (first joint-point)))

(defn gravitational-moment
  "Static gravitational moment (N·m) about `joint-point` from the placed segments,
  each weighted by its own weight in newtons. This IS the RNEA gravity term for a
  chain at rest: no velocity, no acceleration, so every other term vanishes."
  [joint-point placed-with-weights]
  (reduce (fn [m [placed weight-n]]
            (+ m (* weight-n (anterior-lever joint-point placed))))
          0.0
          placed-with-weights))

(defn segment-weights
  "Pair each placed segment with its weight in newtons, from the body it came from."
  [body pose]
  (into {} (for [{:keys [name]} (:segments pose)]
             [name (segment/weight-n (segment/seg body name))])))

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
