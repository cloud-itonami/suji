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
  ([joint moment-nm] (->joint-load joint moment-nm ""))
  ([joint moment-nm note] {:joint joint :moment-nm moment-nm :note note}))

(defn- horizontal-lever
  "Horizontal moment arm of a flexed segment's CoM about its proximal joint:
  length * com-frac * sin(flexion). (Pure-vertical segment → zero lever.)"
  [length-m com-frac flexion-deg]
  (* length-m com-frac (Math/sin (math/radians flexion-deg))))

(defn shoulder-moment
  "Gravitational moment about the glenohumeral joint from the held-out arm(s). Both arms
  load the shoulder girdle → ×2.

  GEOMETRY CORRECTION (2026-09-06). This used to place the forearm and hand with
  `(- 90.0 elbow-flexion-deg)` degrees of tilt from vertical. That is backwards at
  both ends of the range: a straight arm (0°) came out HORIZONTAL, and the 90° elbow
  of a typing posture came out VERTICAL — hanging straight down from the elbow, with
  no lever of its own, so the forearm and hand contributed only the elbow's own
  offset and the shoulder moment of every keyboard posture was under-stated. Elbow
  flexion is the angle between forearm and upper arm, so the forearm's tilt from
  vertical is the upper arm's tilt PLUS the elbow angle. The chain is now placed once
  by `pose/solve-pose` and the moment read off it as Σ weight × anterior lever, which
  is the definition of the RNEA gravity term rather than a re-derivation of it."
  [body shoulder-flexion-deg elbow-flexion-deg arms-supported]
  (let [;; only the arm chain matters about the shoulder, and in this model the arm's
        ;; tilt is measured from vertical (gravity's frame), not from the thorax — so
        ;; the trunk and head angles cannot change this moment and are left at zero.
        p (pose/solve-pose body {:head-flexion-deg 0.0
                                 :trunk-flexion-deg 0.0
                                 :shoulder-flexion-deg shoulder-flexion-deg
                                 :elbow-flexion-deg elbow-flexion-deg})
        shoulder (get-in p [:joints :shoulder])
        w (pose/segment-weights body p)
        carried (if arms-supported
                  ;; forearm + hand rest on the desk; the girdle carries the upper arm only
                  ["upper_arm"]
                  ["upper_arm" "forearm" "hand"])
        m (pose/gravitational-moment
           shoulder
           (for [n carried] [(pose/seg-at p n) (get w n)]))]
    (->joint-load "shoulder" (* m 2.0)
                  (if arms-supported "forearms supported" "arms unsupported (hanging)"))))

(defn lumbosacral-moment
  "Gravitational moment about L5/S1 from the leaned trunk + head-arm load above it."
  [body trunk-flexion-deg head]
  (let [thorax (segment/seg body "thorax_abdomen")
        m0 (* (segment/weight-n thorax)
              (horizontal-lever (:length-m thorax) (:com-frac thorax) trunk-flexion-deg))
        head-x (* (:length-m thorax) (Math/sin (math/radians trunk-flexion-deg)))
        m (+ m0 (* (:head-weight-n head) head-x))]
    (->joint-load "lumbosacral" m "trunk lean + carried head")))

(defn solve-posture-loads
  "Full static inverse-dynamics solve for a posture (the RNEA gravity term)."
  [body posture]
  (let [head-w (* (segment/head-mass-kg (:total-mass-kg body)) segment/gravity)
        cerv (cervical-load (:head-flexion-deg posture) head-w)
        joints [(->joint-load "cervicothoracic" (:extensor-moment-nm cerv)
                              "cervical extensor moment")
                (shoulder-moment body (:shoulder-flexion-deg posture)
                                 (:elbow-flexion-deg posture) (:arms-supported posture))
                (lumbosacral-moment body (:trunk-flexion-deg posture) cerv)]]
    {:cervical cerv :joints joints}))
