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
     :arms-supported (:arms-supported ws)}))

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
