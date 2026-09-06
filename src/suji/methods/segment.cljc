(ns suji.methods.segment
  "suji (筋) — anthropometric sagittal-plane segment chain. 1:1 Clojure port of
  `src/suji/methods/segment.cljc` (ADR-2606061900). Stdlib only.

  The skeleton this actor reasons over is a 2-D (sagittal) articulated chain of rigid
  body segments — the `PlanarChain` articulation that kami-genesis solves. Each segment
  carries a mass, a length, and a centre-of-mass (CoM) location along its long axis,
  derived from total body mass M (kg) and stature H (m) using standard regression
  fractions (Winter 4e Table 4.1 / Drillis & Contini via Winter).

  These are population-average values for an adult; they are :representative (G7), not a
  scan of any individual.

  NON-DIAGNOSTIC (G1): this module computes masses and lengths. It says nothing about
  health. It is the mass-distribution input to a statics problem.

  House style: a Segment / BodyModel is a kebab-keyword map; pure fns. The :weight-n and
  :com-m are derived on demand by `weight-n` / `com-m` (mirroring the Python @property)."
  (:require [clojure.string :as str]))

(def gravity 9.80665)                       ;; m/s^2

;; Winter (4e) Table 4.1 — segment mass as a fraction of total body mass M.
(def ^:private mass-frac
  {"head_neck" 0.081
   "thorax_abdomen" 0.355
   "pelvis" 0.142
   "upper_arm" 0.028
   "forearm" 0.016
   "hand" 0.006
   ;; --- the lower limb, added 2026-09-07 -------------------------------------
   ;; The same table, the rows this one was already quoting from. They are here
   ;; now because a posture model with no leg cannot answer where a STANDING body
   ;; is loaded, and the hip, knee and ankle carry the largest loads in the body.
   ;;
   ;; WHY WINTER AND NOT DE LEVA, since the README names both. de Leva (1996)
   ;; re-cuts Zatsiorsky-Seluyanov to joint-centre endpoints and gives the thigh
   ;; 14.16% of body mass against Winter's 10.0%; the two are not
   ;; interchangeable, because they divide the body in different places (de Leva
   ;; puts more of the buttock mass in the thigh). Mixing them would break the
   ;; one property of this table that can actually be checked: with these rows
   ;; the whole body is accounted for EXACTLY once,
   ;;   0.081 + 0.355 + 0.142 + 2(0.028 + 0.016 + 0.006) + 2(0.100 + 0.0465 + 0.0145) = 1.000
   ;; and `the-whole-body-is-accounted-for` asserts it. A mixed table sums to
   ;; 1.08, and every ground reaction this model computes would be 8% too large.
   "thigh" 0.100
   "shank" 0.0465
   "foot" 0.0145})

;; Segment length as a fraction of stature H (Drillis & Contini via Winter).
(def ^:private len-frac
  {"head_neck" 0.182
   "thorax_abdomen" 0.288
   "pelvis" 0.095
   "upper_arm" 0.186
   "forearm" 0.146
   "hand" 0.108
   ;; thigh = greater trochanter to femoral condyle; shank = condyle to medial
   ;; malleolus; foot = HEEL TO TOE, not ankle to toe. The ankle joint sits about
   ;; a quarter of the way along the foot and `pose/heel-frac` places it there,
   ;; because the heel behind the ankle is half of why a person can stand still.
   "thigh" 0.245
   "shank" 0.246
   "foot" 0.152})

;; CoM location as a fraction of segment length, from the PROXIMAL joint (Winter Table 4.1).
(def ^:private com-frac
  {"head_neck" 0.55
   "thorax_abdomen" 0.50
   "pelvis" 0.50
   "upper_arm" 0.436
   "forearm" 0.430
   "hand" 0.506
   "thigh" 0.433
   "shank" 0.433
   ;; from the HEEL, which is where this model's foot segment starts
   "foot" 0.50})

;; The Python _MASS_FRAC dict iteration order (insertion order) drives build_body's loop;
;; preserve it so the segments map matches Python exactly.
(def segment-order
  ["head_neck" "thorax_abdomen" "pelvis" "upper_arm" "forearm" "hand"
   "thigh" "shank" "foot"])

(defn weight-n
  "Gravitational force on this segment (a single segment; not the pair)."
  [seg]
  (* (:mass-kg seg) gravity))

(defn com-m
  "Distance of the CoM from the proximal joint, along the long axis."
  [seg]
  (* (:com-frac seg) (:length-m seg)))

(defn seg
  "BodyModel.seg(name) — segment by name."
  [body name]
  (get (:segments body) name))

(def ^:private paired-set #{"upper_arm" "forearm" "hand" "thigh" "shank" "foot"})

(def below-l5s1
  "Segments that hang BELOW the origin of this model's frame, so that nothing
  above the lumbar spine is ever standing on them.

  It exists because `spine/above-fraction` answers how much of a segment sits
  above a level from a rank table of the two SPINAL segments, and treats anything
  it does not recognise as sitting above every trunk level. That was right while
  the only unrecognised segments were the arms — they hang from the girdle and do
  load every trunk level — and it became wrong the moment a leg existed. Without
  this set the thighs, shanks and feet, a third of body mass, would have been
  added to the compression at L5/S1 in every posture, silently."
  #{"pelvis" "thigh" "shank" "foot"})

(defn build-body
  "Construct the sagittal segment chain for a member of mass M and stature H.

  Paired limb segments (upper_arm/forearm/hand and thigh/shank/foot) store the mass of
  ONE limb; callers that load both arms onto a single midline joint multiply by 2. The
  lower limb is never loaded onto a midline joint — `pose` places both legs and `load`
  sums them, because a body stands on two feet and the whole point of the support model
  is which of them the ground is pushing on."
  ([] (build-body 70.0 1.70))
  ([total-mass-kg] (build-body total-mass-kg 1.70))
  ([total-mass-kg stature-m]
   (when (or (<= total-mass-kg 0) (<= stature-m 0))
     (throw (ex-info "total_mass_kg and stature_m must be positive"
                     {:type :value-error})))
   ;; ONE `array-map` call rather than `assoc` into one: a PersistentArrayMap
   ;; promotes itself to a hash map on the ninth `assoc`, on BOTH hosts, and this
   ;; table now has nine rows. The promotion is silent and it loses the insertion
   ;; order that `kami-biomech-bridge/to-articulation` reads through `vals`.
   (let [segments (apply
                   array-map
                   (mapcat
                    (fn [name]
                      [name {:name name
                             :mass-kg (* (mass-frac name) total-mass-kg)
                             :length-m (* (len-frac name) stature-m)
                             :com-frac (com-frac name)
                             :paired (contains? paired-set name)}])
                    segment-order))]
     {:total-mass-kg total-mass-kg :stature-m stature-m :segments segments})))

(defn head-mass-kg
  "Head+neck mass. ~5.4 kg at 67 kg matches Hansraj's 12-lb head (G7 anchor)."
  ([] (head-mass-kg 70.0))
  ([total-mass-kg] (* (mass-frac "head_neck") total-mass-kg)))
