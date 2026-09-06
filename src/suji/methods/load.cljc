(ns suji.methods.load
  "suji (筋) — static inverse dynamics: posture → joint moments + spinal load. 1:1 Clojure
  port of `src/suji/methods/load.cljc` (ADR-2606061900). Stdlib only.

  The bones half. Given the sagittal joint angles (posture) and the segment masses
  (segment), it solves the STATIC inverse-dynamics problem: the gravitational moment each
  joint must resist to hold the posture against gravity. This is the gravity term of the
  Featherstone RNEA reduced to statics — the closed-form static special case.

  EMPIRICAL ANCHOR (G7/G10): the cervical (neck) model reproduces Hansraj (2014) forward-
  head-posture loads: neutral ≈ head weight, rising to ~5× head weight at 60° flexion.

  NON-DIAGNOSTIC (G1, 医師法 §17): every output is a mechanical quantity — a moment (N·m),
  a muscle force (N), a compressive load (N / kg-force). None is a diagnosis.

  Numerics: sin/cos resolve on both hosts; degree conversion goes through
  suji.methods.math (Math/toRadians is JVM-only — see that ns).
  Records are kebab-keyword maps; joints kept as an ordered vector (Python list order)."
  (:require [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

;; --- Cervical lever model (Hansraj-calibrated) -------------------------------
(def head-com-lever-m 0.10)     ;; effective horizontal lever of head CoM at full flexion
(def cervical-ext-arm-m 0.02)   ;; cervical extensor moment arm

(defn cervical-load
  "Forward-head-posture cervical load. Reproduces Hansraj (2014) (G7 anchor)."
  ([head-flexion-deg head-weight-n]
   (cervical-load head-flexion-deg head-weight-n head-com-lever-m cervical-ext-arm-m))
  ([head-flexion-deg head-weight-n head-com-lever-m extensor-arm-m]
   (when (<= head-weight-n 0)
     (throw (ex-info "head_weight_n must be positive" {:type :value-error})))
   (when (<= extensor-arm-m 0)
     (throw (ex-info "extensor_arm_m must be positive" {:type :value-error})))
   (let [theta (math/radians head-flexion-deg)
         rho (/ head-com-lever-m extensor-arm-m)
         moment (* head-weight-n head-com-lever-m (Math/sin theta))
         ext-force (/ moment extensor-arm-m)
         compressive (* head-weight-n (+ (* rho (Math/sin theta)) (Math/cos theta)))]
     {:head-flexion-deg head-flexion-deg
      :head-weight-n head-weight-n
      :extensor-moment-nm moment
      :extensor-force-n ext-force
      :compressive-load-n compressive
      :compressive-load-kgf (/ compressive segment/gravity)
      :multiplier-vs-head (/ compressive head-weight-n)})))

(defn cervical-load-sensitivity
  "Marginal cervical compressive load per degree of forward head flexion AT a given posture — the
  local slope d(compressive-load)/d(flexion) of the Hansraj forward-head-posture model, by central
  finite difference. A purely MECHANICAL quantity (N of compressive load per additional degree;
  G1 non-diagnostic — no clinical key, never a prescription) and self-referenced to the SAME posture
  (G3 — the model's local derivative here, never a population rank). It makes a small posture
  change's MODELLED load effect legible: because the load curve is concave, the first degrees off
  neutral cost the most per degree. Returns {:head-flexion-deg :compressive-load-n :d-load-per-deg-n}."
  ([head-flexion-deg head-weight-n] (cervical-load-sensitivity head-flexion-deg head-weight-n 0.5))
  ([head-flexion-deg head-weight-n delta-deg]
   (let [load-at (fn [a] (:compressive-load-n (cervical-load a head-weight-n)))]
     {:head-flexion-deg head-flexion-deg
      :compressive-load-n (load-at head-flexion-deg)
      :d-load-per-deg-n (/ (- (load-at (+ head-flexion-deg delta-deg))
                              (load-at (- head-flexion-deg delta-deg)))
                           (* 2.0 delta-deg))})))

;; --- Generic static joint moment (RNEA gravity term) -------------------------
(defn- ->joint-load
  ([joint moment-nm] (->joint-load joint moment-nm "" nil))
  ([joint moment-nm note] (->joint-load joint moment-nm note nil))
  ([joint moment-nm note per-side]
   (cond-> {:joint joint :moment-nm moment-nm :note note}
     per-side (assoc :per-side per-side))))

(defn- horizontal-lever
  "Horizontal moment arm of a flexed segment's CoM about its proximal joint:
  length * com-frac * sin(flexion). (Pure-vertical segment → zero lever.)"
  [length-m com-frac flexion-deg]
  (* length-m com-frac (Math/sin (math/radians flexion-deg))))

(defn- arm-moment-about
  "Sagittal moment about one shoulder from the arm segments hanging off it."
  [body p side arms-supported]
  (let [w (pose/segment-weights body p)
        carried (if arms-supported
                  ;; forearm + hand rest on the desk; the girdle carries the upper arm only
                  ["upper_arm"]
                  ["upper_arm" "forearm" "hand"])
        joint (get-in p [:joints (keyword "shoulder" (name side))])]
    (pose/gravitational-moment
     joint
     (for [seg (pose/segments-on p carried side)] [seg (get w (:name seg))]))))

(defn shoulder-moment
  "Gravitational moment about the glenohumeral joints from the held-out arm(s).

  BILATERAL since 2026-09-06: the two sides are computed and SUMMED, where this
  used to compute one and multiply by two. Those agree exactly for a symmetric
  posture and disagree for every asymmetric one — and lateral bend is asymmetric
  by definition, so doubling one side could not represent it.

  GEOMETRY CORRECTION (2026-09-06, earlier the same day). This used to place the
  forearm and hand at `(90° − elbow-flexion)` from vertical, which is inverted at
  both ends of the range: a straight arm came out HORIZONTAL and the 90° elbow of
  a typing posture came out VERTICAL. The chain is placed by `pose/solve-pose` and
  the moment read off it as Σ weight × anterior lever, which is the definition of
  the RNEA gravity term rather than a re-derivation of it."
  ([body shoulder-flexion-deg elbow-flexion-deg arms-supported]
   (shoulder-moment body {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                          :shoulder-flexion-deg shoulder-flexion-deg
                          :elbow-flexion-deg elbow-flexion-deg
                          :arms-supported arms-supported}
                    :both))
  ([body posture _mode]
   (let [p (pose/solve-pose body posture)
         sup (:arms-supported posture)
         per-side (into {} (for [side [:left :right]]
                             [side (arm-moment-about body p side sup)]))]
     (->joint-load "shoulder" (+ (:left per-side) (:right per-side))
                   (if sup "forearms supported" "arms unsupported (hanging)")
                   per-side))))

(defn lumbosacral-moment
  "Gravitational moment about L5/S1 from the leaned trunk + head-arm load above it."
  [body trunk-flexion-deg head]
  (let [thorax (segment/seg body "thorax_abdomen")
        m0 (* (segment/weight-n thorax)
              (horizontal-lever (:length-m thorax) (:com-frac thorax) trunk-flexion-deg))
        head-x (* (:length-m thorax) (Math/sin (math/radians trunk-flexion-deg)))
        m (+ m0 (* (:head-weight-n head) head-x))]
    (->joint-load "lumbosacral" m "trunk lean + carried head")))

(defn frontal-moments
  "The FRONTAL-plane (about X) gravitational moments this posture creates.

  Zero for a symmetric posture — the two arms hang at ±half the biacromial breadth
  from the midline and their moments about the spine cancel exactly — and non-zero
  as soon as the trunk bends laterally or the arms abduct unequally. Before the
  model was bilateral it placed ONE arm and doubled it, so there was nothing for
  that arm's frontal moment to cancel against and this number was an artefact of
  the modelling rather than of the posture.

  These are reported, not solved, until a muscle exists that can carry them: see
  `muscle/tension-summary`, which refuses to call an answer complete while any of
  this is unassigned."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        frontal (fn [joint-point segs]
                  (nth (pose/gravitational-moment-vec
                        joint-point (for [seg segs] [seg (get w (:name seg))]))
                       0))
        arm-bases ["upper_arm" "forearm" "hand"]]
    {;; PER SIDE, not summed. The two shoulders are different joints with
     ;; different muscles; a symmetric abduction gives each of them a real frontal
     ;; moment, and adding them cancels to zero and reports that abducting both
     ;; arms asks nothing of either shoulder. The lumbosacral term IS a sum,
     ;; because there is one spine and the two arms load it about the same axis.
     :shoulder-per-side (into {} (for [side [:left :right]]
                                   [side (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                  (pose/segments-on p arm-bases side))]))
     :shoulder-nm (reduce max 0.0
                          (for [side [:left :right]]
                            (math/abs* (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                (pose/segments-on p arm-bases side)))))
     :lumbosacral-nm (frontal (get-in p [:joints :l5s1])
                              (concat (pose/segments-on p ["thorax_abdomen" "head_neck"])
                                      (pose/segments-on p arm-bases)))}))

(defn solve-posture-loads
  "Full static inverse-dynamics solve for a posture (the RNEA gravity term).

  `:frontal` carries the frontal-plane moments, which no muscle in this model
  carries — see `frontal-moments`."
  [body posture]
  (let [head-w (* (segment/head-mass-kg (:total-mass-kg body)) segment/gravity)
        cerv (cervical-load (:head-flexion-deg posture) head-w)
        joints [(->joint-load "cervicothoracic" (:extensor-moment-nm cerv)
                              "cervical extensor moment")
                (shoulder-moment body posture :both)
                (lumbosacral-moment body (:trunk-flexion-deg posture) cerv)]]
    {:cervical cerv :joints joints :frontal (frontal-moments body posture)}))
