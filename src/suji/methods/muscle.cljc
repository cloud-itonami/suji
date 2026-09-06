(ns suji.methods.muscle
  "suji (筋) — muscle tension: share each equilibrium's load across the muscles
  that can carry it, and report the result as %MVC. Stdlib only, no I/O.

  Force available is F_max = PCSA × specific tension; %MVC is force ÷ F_max.

  WHAT CHANGED 2026-09-06. Three things, all of which used to be stated as
  constants or as expressions with no stated intent:

  1. **Moment arms are computed, not tabulated.** They come from
     `suji.methods.attachment`, which places each muscle's attachments on the
     bones `suji.methods.pose` placed and derives the perpendicular distance. A
     constant arm asserts that leverage does not change when the joint moves,
     which is false for every muscle in the body. The constants this actor used
     are still the anchor — the attachment offsets are calibrated so the derived
     arm reproduces them at the neutral posture — but the variation away from
     neutral is now geometry rather than absence.

  2. **Redundancy is resolved by a stated criterion.** Upper trapezius and levator
     scapulae both suspend the shoulder girdle, so the equilibrium does not
     determine their forces on its own. They used to be assigned by two unrelated
     hand-written expressions. They are now shared by `suji.methods.recruit`
     (Crowninshield–Brand minimum cubed stress), which is a closed form, is exact,
     and moves when the anatomy moves.

  3. **The model refuses postures it cannot answer for.** A straight-line muscle
     has no wrapping surface, so its line of action can pass through the joint it
     acts about; near there the required force diverges. Such a muscle comes back
     with `:refused` and no `:mvc-pct`, and consumers must show that rather than a
     number. See `recruit/min-coeff`.

  A CORRECTION MADE ALONG THE WAY. The old upper-trapezius expression added a term
  proportional to head weight × sin(head flexion). The head's extension load is
  already carried by `cervical_extensors` in the cervical-extension equilibrium,
  so that term charged the same load twice. It is gone.

  A GAP LEFT OPEN. `:shoulder-elevation-deg` (scapular elevation) is carried in
  the posture and is NOT an input to any equilibrium here. The old expressions
  used it as a multiplier with no stated meaning; rather than keep an unaccountable
  term, this model states that it does not yet resolve the elevation demand.

  NON-DIAGNOSTIC (G1): %MVC is a force ratio, not a diagnosis.
  G10 anti-pseudoscience: Hill-model muscles only; NO 経絡/気/波動."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.recruit :as recruit]
            [suji.methods.segment :as segment]))

(def specific-tension-n-cm2 60.0)

(defn f-max-n [spec] (* (:pcsa-cm2 spec) specific-tension-n-cm2))

(def specs
  "Kept as the public description of each group. `:moment-arm-m` is the NEUTRAL
  arm the attachment geometry is calibrated against — it is documentation of the
  calibration target, not the value used in the solve. `attachment-test` pins the
  agreement, so this table cannot drift away from the anatomy."
  (array-map
   "cervical_extensors" {:name "cervical_extensors" :pcsa-cm2 12.0 :moment-arm-m 0.020}
   "upper_trapezius"    {:name "upper_trapezius"    :pcsa-cm2 9.0  :moment-arm-m 0.025}
   "levator_scapulae"   {:name "levator_scapulae"   :pcsa-cm2 5.0  :moment-arm-m 0.020}
   "anterior_deltoid"   {:name "anterior_deltoid"   :pcsa-cm2 10.0 :moment-arm-m 0.030}
   "erector_spinae"     {:name "erector_spinae"     :pcsa-cm2 34.0 :moment-arm-m 0.055}))

;; emission order — midline groups, then each side, matching `attachment/instances`
(def emit-order (mapv :name attachment/instances))

(defn f-max-of
  "F_max for one muscle instance, from its group's PCSA."
  [inst]
  (f-max-n (get specs (:group inst))))

(defn suspended-weight-n
  "Weight ONE shoulder girdle has to suspend: the arm segments hanging from it.
  Resting the forearms on a desk transfers those two segments to the desk, so the
  girdle carries the upper arm only — that is the whole of the `arms-supported`
  effect, stated as which segments are hanging rather than as a multiplier.

  Per side since the model became bilateral: this used to be `2 ×` one arm, which
  is the same number for a symmetric posture and cannot represent any other."
  [body p side]
  (let [w (pose/segment-weights body p)
        hanging (if (:arms-supported (meta p)) ["upper_arm"] ["upper_arm" "forearm" "hand"])]
    (reduce + 0.0 (map #(get w (:name %)) (pose/segments-on p hanging side)))))

(defn- girdle-load-n [body p side arms-supported]
  (let [w (pose/segment-weights body p)
        hanging (if arms-supported ["upper_arm"] ["upper_arm" "forearm" "hand"])]
    (reduce + 0.0 (map #(get w (:name %)) (pose/segments-on p hanging side)))))

(defn- candidates
  "Muscle instances for one task, optionally restricted to one side."
  [task side coeffs]
  (for [m attachment/instances
        :when (and (= task (:task m))
                   (or (nil? side) (= side (:side m))))]
    {:name (:name m) :f-max-n (f-max-of m) :coeff (get coeffs (:name m))}))

(defn- ->tension
  "Attach %MVC to one shared result. A refusal stays a refusal — there is no
  %MVC for a force this model declined to compute."
  [shared]
  (if (:refused shared)
    (assoc shared :mvc-pct nil)
    (assoc shared :mvc-pct (/ (* 100.0 (max 0.0 (:force-n shared))) (:f-max-n shared)))))

(defn solve-muscle-tensions
  "Map a posture's joint loads onto per-muscle force and %MVC.

  BILATERAL. Each side's shoulder and girdle equilibrium is solved on its own, so
  an asymmetric posture — which is any posture with lateral bend, and any posture
  where the two arms differ — loads the two sides differently. The midline groups
  (cervical extensors, erector spinae) are solved once; their PCSA is already the
  bilateral sum.

  Returns a vector in `emit-order`. An entry is either
  {:name :group :side :force-n :f-max-n :mvc-pct :coeff} or
  {:name :refused :note :mvc-pct nil}."
  [body posture loads]
  (let [p (pose/solve-pose body posture)
        coeffs (attachment/arms p (:stature-m body))
        sup (:arms-supported posture)
        joint (fn [n] (first (filter #(= n (:joint %)) (:joints loads))))
        by-name (fn [xs] (into {} (map (juxt :name identity)) xs))
        shoulder-per-side (:per-side (joint "shoulder"))
        results
        (apply merge
               (by-name (recruit/share (candidates :cervical-extension nil coeffs)
                                       (get-in loads [:cervical :extensor-moment-nm])))
               (by-name (recruit/share (candidates :trunk-extension nil coeffs)
                                       (:moment-nm (joint "lumbosacral"))))
               (for [side [:left :right]]
                 (merge
                  (by-name (recruit/share (candidates :shoulder-flexion side coeffs)
                                          (get shoulder-per-side side 0.0)))
                  (by-name (recruit/share (candidates :scapular-suspension side coeffs)
                                          (girdle-load-n body p side sup))))))
        instance-by (into {} (map (juxt :name identity)) attachment/instances)]
    (mapv (fn [n]
            (let [inst (instance-by n)]
              (merge (select-keys inst [:group :side])
                     (->tension (get results n)))))
          emit-order)))

(defn tension-summary
  "Which parts of the load this solve could place, for a consumer that must not
  present an incomplete answer as a complete one.

  Two independent ways an answer can be incomplete, and they are reported
  separately because they have different fixes:

    :refused         a muscle exists but its leverage is unresolvable HERE — a
                     straight-line model with no wrapping surface. Fixed by
                     wrapping surfaces, not by more muscles.
    :unassigned-*    a load exists and no muscle in this model can carry it at
                     all. This actor has no frontal-plane musculature, so any
                     frontal moment is unassigned by construction. Fixed by adding
                     muscles, not by better geometry."
  ([tensions] (tension-summary tensions nil))
  ([tensions loads]
   (let [frontal (:frontal loads)
         unassigned (when frontal
                      (+ (math/abs* (:shoulder-nm frontal 0.0))
                         (math/abs* (:lumbosacral-nm frontal 0.0))))]
     (cond-> {:total (count tensions)
              :refused (count (filter :refused tensions))
              :complete? (not-any? :refused tensions)
              :max-mvc-pct (let [xs (keep :mvc-pct tensions)] (when (seq xs) (apply max xs)))}
       frontal (assoc :unassigned-frontal-nm unassigned
                      :frontal frontal
                      :complete? (and (not-any? :refused tensions)
                                      (< unassigned 1e-9)))))))

(defn fmt-mvc
  "Display helper: a %MVC, or the reason there is not one."
  [t]
  (if (:refused t) "—" (str (math/fmt-fixed (:mvc-pct t) 1) " %")))
