(ns suji.methods.muscle
  "suji (筋) — muscle tension: distribute joint moments to muscles (%MVC). 1:1 Clojure port
  of `src/suji/methods/muscle.cljc` (ADR-2606061900). Stdlib only.

  Each static joint moment from load is balanced by a prime-mover muscle group acting at
  its anatomical moment arm (a Hill-type force = moment / arm). Dividing by the muscle's
  maximum force F_max = PCSA × specific-tension gives the %MVC.

  NON-DIAGNOSTIC (G1): %MVC is a force ratio, not a diagnosis.
  G10 anti-pseudoscience: Hill-model muscles only; NO 経絡/気/波動.

  House style: SPECS is an array-map (insertion order = Python dict order) of kebab-keyword
  maps keyed on the underscore muscle names (matching the Python str keys); tensions are a
  vector emitted in the Python append order."
  (:require [suji.methods.math :as math]
            [suji.methods.segment :as segment]
            [suji.methods.load :as load]))

(def specific-tension-n-cm2 60.0)

(defn- f-max-n [spec]
  (* (:pcsa-cm2 spec) specific-tension-n-cm2))

;; Representative bilateral group PCSAs (sum of left+right where paired).
;; Insertion order matches the Python SPECS dict.
(def specs
  (array-map
   "cervical_extensors" {:name "cervical_extensors" :pcsa-cm2 12.0 :moment-arm-m 0.020}
   "upper_trapezius"    {:name "upper_trapezius"    :pcsa-cm2 9.0  :moment-arm-m 0.025}
   "levator_scapulae"   {:name "levator_scapulae"   :pcsa-cm2 5.0  :moment-arm-m 0.020}
   "anterior_deltoid"   {:name "anterior_deltoid"   :pcsa-cm2 10.0 :moment-arm-m 0.030}
   "erector_spinae"     {:name "erector_spinae"     :pcsa-cm2 34.0 :moment-arm-m 0.055}))

(defn- tension
  [name force-n]
  (let [spec (get specs name)
        force-n (max 0.0 force-n)
        fmax (f-max-n spec)]
    {:name name :force-n force-n :f-max-n fmax :mvc-pct (/ (* 100.0 force-n) fmax)}))

(defn solve-muscle-tensions
  "Map the posture's joint loads to per-muscle force and %MVC."
  [body posture loads]
  (let [cerv-arm (:moment-arm-m (get specs "cervical_extensors"))
        cerv-ext (tension "cervical_extensors"
                          (/ (get-in loads [:cervical :extensor-moment-nm]) cerv-arm))
        sh (first (filter #(= (:joint %) "shoulder") (:joints loads)))
        ant-delt (tension "anterior_deltoid"
                          (/ (:moment-nm sh) (:moment-arm-m (get specs "anterior_deltoid"))))
        ls (first (filter #(= (:joint %) "lumbosacral") (:joints loads)))
        erector (tension "erector_spinae"
                         (/ (:moment-nm ls) (:moment-arm-m (get specs "erector_spinae"))))
        ;; upper trapezius + levator scapulae composite
        arm-each (+ (:mass-kg (segment/seg body "upper_arm"))
                    (:mass-kg (segment/seg body "forearm"))
                    (:mass-kg (segment/seg body "hand")))
        arm-w-pair (* arm-each segment/gravity 2.0)
        elev (Math/sin (math/radians (:shoulder-elevation-deg posture)))
        support-factor (if-not (:arms-supported posture) 1.0 0.4)
        head-co (* 0.15 (get-in loads [:cervical :head-weight-n])
                   (Math/sin (math/radians (:head-flexion-deg posture))))
        trap-force (+ (* arm-w-pair (+ 0.3 (* 0.7 elev)) support-factor) head-co)
        trap (tension "upper_trapezius" trap-force)
        lev-force (+ (* 0.5 (* arm-w-pair (+ 0.2 (* 0.6 elev)) support-factor))
                     (* 0.6 head-co))
        lev (tension "levator_scapulae" lev-force)]
    [cerv-ext ant-delt erector trap lev]))
