(ns suji.methods.spine
  "suji (筋) — compression, level by level. Pure `.cljc`, stdlib only, no I/O.

  WHY. This actor reported ONE spinal number: the cervical compressive load, a
  lumped value calibrated against Hansraj (2014). It is the right number and it is
  the only one — it says nothing about where along the neck the load peaks, and
  nothing at all about the lumbar spine, where the same posture puts far more force
  through a joint that is also far larger. Neither does a force alone: a disc's
  tolerance is a STRESS, and 500 N through a cervical disc and 500 N through a
  lumbar one are not the same event.

  WHAT IT COMPUTES. For each intervertebral level, the axial force is

      Σ (weight of everything above it, projected on the spine's local axis)
    + Σ (force of every muscle whose line crosses it, projected on the same axis)

  and the stress is that force divided by the level's disc area. The tissue term is
  reported split into `:muscle-n` and `:ligament-n`, because past flexion-relaxation
  the second one IS the answer and calling it muscle would be a different claim.
  The muscle term usually dominates — an extensor works at a short moment arm, so holding a small
  external moment costs a large force, and all of that force presses the joint
  together. That is why a flexed posture loads a spine so much more than the
  weight it carries would suggest, and a model that omitted the muscle term would
  understate every posture by a factor.

  WHAT IT IS NOT. Disc areas are representative adult values scaled with stature,
  not measurements of anybody (G7). Mass above a level is taken as uniform along
  the segment; the segment's own centre of mass is NOT uniform, and this is stated
  rather than hidden — it biases levels near a segment's ends. There is no
  curvature: the model's spine is two straight segments, so it has no lordosis and
  no shear component. NON-DIAGNOSTIC (G1): a stress is a stress."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

(def reference-stature-m 1.70)

(def levels
  "Intervertebral levels, proximal to distal along each segment.

  `:along` is the fraction of that segment's length from its PROXIMAL joint, so
  L5/S1 is at 0.0 of the trunk and C7/T1 at 0.0 of the neck. `:disc-area-cm2` is a
  representative adult cross-section at reference stature; it scales with
  stature² because an area does."
  [{:name "L5/S1" :region :lumbar :segment "thorax_abdomen" :along 0.00 :disc-area-cm2 18.0}
   {:name "L4/L5" :region :lumbar :segment "thorax_abdomen" :along 0.07 :disc-area-cm2 17.0}
   {:name "L3/L4" :region :lumbar :segment "thorax_abdomen" :along 0.14 :disc-area-cm2 16.0}
   {:name "L2/L3" :region :lumbar :segment "thorax_abdomen" :along 0.21 :disc-area-cm2 15.0}
   {:name "L1/L2" :region :lumbar :segment "thorax_abdomen" :along 0.28 :disc-area-cm2 14.0}
   {:name "C7/T1" :region :cervical :segment "head_neck" :along 0.00 :disc-area-cm2 5.0}
   {:name "C6/C7" :region :cervical :segment "head_neck" :along 0.06 :disc-area-cm2 4.6}
   {:name "C5/C6" :region :cervical :segment "head_neck" :along 0.12 :disc-area-cm2 4.3}
   {:name "C4/C5" :region :cervical :segment "head_neck" :along 0.18 :disc-area-cm2 4.0}
   {:name "C3/C4" :region :cervical :segment "head_neck" :along 0.24 :disc-area-cm2 3.8}])

(defn disc-area-m2
  "Disc area in m², scaled with stature² from the reference."
  [level stature-m]
  (let [k (/ stature-m reference-stature-m)]
    (* (:disc-area-cm2 level) 1e-4 k k)))

(defn level-point
  "World position of a level, and the spine's local axis there (pointing up the
  chain, i.e. the direction compression acts along)."
  [pose-data level]
  (let [{:keys [proximal frame length-m]} (pose/seg-at pose-data (:segment level))]
    {:point (math/v+ proximal (math/v* (:long frame) (* (:along level) length-m)))
     :axis (:long frame)}))

;; --- what sits above a level -------------------------------------------------

(def ^:private chain-order
  "Segments in ascending order along the spine. A level on the trunk has the whole
  neck above it; a level on the neck has only the neck above it. The arms hang
  from the girdle, which is on the trunk, so they load every trunk level and no
  cervical one."
  {"thorax_abdomen" 0 "head_neck" 1})

(defn- above-fraction
  "How much of `seg` sits above `level` — 1.0 for a segment higher up the chain,
  0.0 for one lower, and the remaining fraction for the segment the level is on.

  UNIFORM along the segment, which is not how mass is actually distributed (see
  `segment`'s `:com-frac`). Stated here because it biases levels near a segment's
  ends, and a reader comparing two adjacent levels should know the bias is in the
  model rather than in the body."
  [level seg]
  (let [lvl-rank (chain-order (:segment level))
        seg-rank (chain-order (:base seg))]
    (cond
      ;; A segment the spine's rank table does not know is either an ARM, which
      ;; hangs from the girdle and therefore loads every trunk level, or a LEG,
      ;; which hangs below the pelvis and loads none of them. Before the lower limb
      ;; existed the first case was the only case and this branch could simply say
      ;; 1.0; `segment/below-l5s1` is what makes it stay right — without it a third
      ;; of body mass was added to the lumbar spine, in every posture, and the only
      ;; symptom was a number that was 300 N too large.
      (segment/below-l5s1 (:base seg)) 0.0
      (nil? seg-rank) (if (= 0 lvl-rank) 1.0 0.0)  ;; arms: above trunk levels only
      (> seg-rank lvl-rank) 1.0
      (< seg-rank lvl-rank) 0.0
      :else (max 0.0 (- 1.0 (:along level))))))

(defn- weight-above-n
  "Axial component of the weight sitting above a level."
  [body pose-data level]
  (let [w (pose/segment-weights body pose-data)
        {:keys [axis]} (level-point pose-data level)]
    (reduce + 0.0
            (for [seg (:segments pose-data)
                  :let [f (above-fraction level seg)]
                  :when (pos? f)]
              ;; gravity is [0,-w,0]; its compressive component along the spine
              ;; axis is w × (axis · up)
              (* f (get w (:name seg)) (nth axis 1))))))

(defn- crosses?
  "Does this muscle's line cross the level? True when its two attachment points
  sit on opposite sides of the level along the spine axis."
  [pose-data stature-m level muscle]
  (let [{:keys [point axis]} (level-point pose-data level)
        {:keys [origin insertion]} (attachment/line-of-action pose-data stature-m muscle)
        h (fn [p] (math/vdot (math/v- p point) axis))]
    (and origin insertion (neg? (* (h origin) (h insertion))))))

(defn- tissue-compression-n
  "Axial component of the forces crossing the level, split by what produced them.

  `{:muscle-n … :ligament-n …}`. It used to be one number called `:muscle-n`, and
  once the posterior ligamentous system landed that name stopped being true:
  measured 2026-09-06 at 60 degrees of trunk flexion, the term was 3,904 N of
  which 3,989 N came from the ligament — the muscles were contributing a NEGATIVE
  net component and the ligament was pressing the joint together on its own. A
  reader taking `:muscle-n` literally would have read a spine compressed by muscle
  where it is compressed by tissue, which is a different statement about the
  posture and about what would change it."
  [pose-data stature-m level tensions]
  (let [{:keys [axis]} (level-point pose-data level)
        by-name (into {} (map (juxt :name identity)) tensions)
        contribution
        (fn [m]
          (let [t (by-name (:name m))
                f (:force-n t)]
            (when (and f (pos? f) (crosses? pose-data stature-m level m))
              (let [{:keys [dir]} (attachment/line-of-action pose-data stature-m m)]
                (when dir
                  ;; only the component ALONG the spine compresses it; the
                  ;; transverse component is shear, which this model does not carry
                  (* f (math/abs* (math/vdot dir axis))))))))]
    {:muscle-n (reduce + 0.0 (keep #(when-not (:ligament? %) (contribution %))
                                   attachment/instances))
     :ligament-n (reduce + 0.0 (keep #(when (:ligament? %) (contribution %))
                                     attachment/instances))}))

(defn level-compression
  "Compression at one level: {:name :region :force-n :stress-mpa :weight-n :muscle-n}."
  [body pose-data tensions level]
  (let [stature-m (:stature-m body)
        weight (weight-above-n body pose-data level)
        {:keys [muscle-n ligament-n]} (tissue-compression-n pose-data stature-m level tensions)
        force (+ weight muscle-n ligament-n)
        area (disc-area-m2 level stature-m)]
    {:name (:name level)
     :region (:region level)
     :weight-n weight
     :muscle-n muscle-n
     :ligament-n ligament-n
     :force-n force
     :disc-area-cm2 (* 1e4 area)
     ;; N / m² = Pa; ÷1e6 = MPa, which is the unit disc tolerances are quoted in
     :stress-mpa (/ force area 1e6)}))

(defn profile
  "Compression at every level, in the order `levels` declares."
  [body posture tensions]
  (let [p (pose/solve-pose body posture)]
    (mapv #(level-compression body p tensions %) levels)))

(defn cervical-cross-check
  "Compare this namespace's C7/T1 force against `load/cervical-load`'s lumped,
  Hansraj-calibrated one, and report the ratio.

  THEY DISAGREE, and which one is trustworthy is not symmetric. The lumped model
  is the leg this actor VALIDATED — it reproduces the published forward-head table
  to within 10% — and it gets there with an effective lever fitted to that table.
  This namespace instead adds the muscle force computed from the muscle's actual
  geometric moment arm, which at large flexion is much shorter and therefore
  demands much more force, all of which presses the joint together. Measured
  2026-09-06 at the laptop-on-lap posture: 232 N lumped, 519 N by level, a ratio
  of 2.2.

  Neither number is offered as the right one here. What is offered is the ratio,
  so a consumer cannot read the level profile as though it inherited the lumped
  model's validation — it did not. The cervical leg remains the validated one; the
  level profile is mechanically constructed and UNVALIDATED, and this function
  exists so that saying so is a computation rather than a sentence somebody has to
  remember to keep true."
  [body posture tensions cervical-load]
  (let [rows (profile body posture tensions)
        c7 (first (filter #(= "C7/T1" (:name %)) rows))
        lumped (:compressive-load-n cervical-load)]
    {:level-force-n (:force-n c7)
     :lumped-force-n lumped
     :ratio (when (and lumped (pos? lumped)) (/ (:force-n c7) lumped))
     :validated :lumped
     :note "the lumped cervical model is the validated one (Hansraj 2014); the level profile is unvalidated"}))

(defn attachment-steps
  "Levels whose muscle term is zero while a neighbour's is not — the artefact of
  point attachments.

  A real muscle attaches over a RANGE of vertebrae, so its contribution tapers
  along the spine. This model attaches it at a point, so a level just past that
  point loses the whole force at once. Reporting which levels those are is the
  difference between a reader seeing a step and a reader believing a spine."
  [profile-rows]
  (vec (for [[a b] (partition 2 1 profile-rows)
             :when (and (pos? (:muscle-n a)) (zero? (:muscle-n b)))]
         {:after (:name a) :at (:name b)})))

(defn peak
  "The level carrying the highest stress."
  [profile-rows]
  (when (seq profile-rows)
    (apply max-key :stress-mpa profile-rows)))
