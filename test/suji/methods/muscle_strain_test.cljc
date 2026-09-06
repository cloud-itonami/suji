(ns suji.methods.muscle-strain-test
  "suji (筋) — muscle %MVC + Rohmert strain tests. 1:1 Clojure port of
  src/suji/methods/test_muscle_strain.cljc."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.attachment :as attachment]
            [suji.methods.math :as math]
            [suji.methods.analyze :as analyze]
            [suji.methods.load :as load]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]))

(deftest test-muscle-mvc-in-range-and-nonneg
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        loads (load/solve-posture-loads body p)
        tensions (muscle/solve-muscle-tensions body p loads)]
    ;; every instance appears, and every instance belongs to a declared group
    (is (= (set (map :name tensions)) (set (map :name attachment/instances))))
    (is (every? #(or (:ligament? %) (contains? muscle/specs (:group %))) tensions))
    ;; a LIGAMENT has no %MVC — it cannot contract, so there is no maximum
    ;; voluntary contraction to be a fraction of. Asking `:refused` here would
    ;; have missed that; asking whether the NUMBER is present does not.
    (doseq [t (filter muscle/numeric-mvc? tensions)]
      (is (>= (:force-n t) 0))
      (is (and (<= 0 (:mvc-pct t)) (< (:mvc-pct t) 100))))
    (doseq [t (filter :ligament? tensions)]
      (is (nil? (:mvc-pct t)) "a ligament has no %MVC")
      (is (>= (:force-n t) 0) "but it does transmit a force"))
    ;; and a refused entry carries no numbers to mistake for small ones
    (doseq [t (filter :refused tensions)]
      (is (nil? (:force-n t)))
      (is (nil? (:mvc-pct t))))))

(deftest test-endurance-falls-with-load
  (is (< (strain/endurance-minutes 50.0)
         (strain/endurance-minutes 25.0)
         (strain/endurance-minutes 15.0)))
  (is (math/infinite? (strain/endurance-minutes 5.0)))
  (is (< (Math/abs (- (strain/endurance-minutes 50.0) 1.0)) 0.6))
  (is (< (Math/abs (- (strain/endurance-minutes 25.0) 5.0)) 2.5)))

(deftest test-stiffness-grows-with-load-and-time
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        tensions (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p))
        by (fn [n] (first (filter #(= (:name %) n) tensions)))
        cerv (by "cervical_extensors")
        ;; ⚠ THE SATURATION HALF MOVED TO `erector_spinae` ON 2026-09-07, and which
        ;; muscle it is asked of is now stated rather than assumed. It used to be
        ;; `cervical_extensors`, which at this posture carried the WHOLE cervical
        ;; extensor moment at 56.2% MVC on a 12.0 cm² lump. Adding the muscles that
        ;; hold the head up (semispinalis capitis, splenius capitis) splits that
        ;; same moment three ways over 31.32 cm², so cervical_extensors fell to
        ;; 22.2% MVC and its 120-minute dose fell 158.35 -> 18.41 — still an index
        ;; of 1.00 to two decimals, but no longer 1.0 in double precision. That is
        ;; reported below rather than hidden by moving the assertion quietly.
        ;; erector_spinae is unaffected by the neck (57.4% MVC, dose 165.95) and is
        ;; what "a load this high" means at this posture now.
        high (by "erector_spinae")
        s-short (strain/muscle-strain high 10.0)
        s-long (strain/muscle-strain high 120.0)
        c-long (strain/muscle-strain cerv 120.0)]
    (is (<= 0 (:stiffness-index s-short)))
    (is (<= (:stiffness-index s-short) (:stiffness-index s-long)))
    ;; The index is bounded above by 1 and mathematically never reaches it, but in
    ;; double precision it rounds to exactly 1.0 once the dose passes ~37 — which
    ;; this posture does. So the bound is <=, and the saturation must be REPORTED:
    ;; a ceiling presented as a measurement cannot be told apart from a value.
    (is (<= (:stiffness-index s-long) 1.0))
    (is (:saturated? s-long)
        "a load this high for two hours saturates the index and has to say so")
    ;; and the muscle that used to be asked this: it still grows with time and is
    ;; still 1.00 to display precision, and it is no longer at the double-precision
    ;; ceiling. Asserting the direction as well as the flag means a future change
    ;; that pushed it back over would be visible here rather than silently absorbed.
    (is (< (:stiffness-index (strain/muscle-strain cerv 10.0))
           (:stiffness-index c-long))
        "the cervical extensors' index still grows with time")
    (is (not (:saturated? c-long))
        (str "cervical_extensors no longer saturates at 120 min; its dose fell "
             "158.35 -> " (:dose c-long) " when the cervical extensor moment was "
             "split with the muscles that reach the skull"))))

(deftest test-saturation-is-flagged-only-when-it-happens
  ;; the flag has to discriminate, or it is decoration
  (let [low (strain/muscle-strain {:name "x" :mvc-pct 9.0} 30.0)
        high (strain/muscle-strain {:name "x" :mvc-pct 60.0} 240.0)]
    (is (< (:stiffness-index low) 1.0))
    (is (not (:saturated? low)) "a light load must not be reported as saturated")
    (is (:saturated? high))))

(deftest test-stiffness-band-thresholds
  (is (= (strain/stiffness-band 0.1) "low"))
  (is (= (strain/stiffness-band 0.3) "moderate"))
  (is (= (strain/stiffness-band 0.6) "high"))
  (is (= (strain/stiffness-band 0.9) "very-high")))

(deftest test-strain-rejects-negative-session
  (let [body (segment/build-body)
        t (first (muscle/solve-muscle-tensions
                  body (posture/posture-from-workstation posture/laptop-on-lap)
                  (load/solve-posture-loads body (posture/posture-from-workstation posture/laptop-on-lap))))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (strain/muscle-strain t -5.0)))))

(deftest test-end-to-end-lap-worse-than-monitor
  (let [results (analyze/analyze-all 70.0 1.70 120.0)
        lap (first (filter #(= (:workstation %) "laptop-on-lap") results))
        mon (first (filter #(= (:workstation %) "external-monitor+keyboard") results))]
    (is (> (:stiffness-index (analyze/worst-stiffness (:strains lap)))
           (:stiffness-index (analyze/worst-stiffness (:strains mon)))))
    (let [reduction (- 1 (/ (get-in mon [:loads :cervical :compressive-load-kgf])
                            (get-in lap [:loads :cervical :compressive-load-kgf])))]
      (is (> reduction 0.4)))))

(deftest a-refusal-passes-through-the-strain-model
  ;; `muscle` can decline to compute a force; there is no dose to accumulate from
  ;; a load nobody computed. Before 2026-09-06 this threw a NullPointerException
  ;; two lines into the arithmetic — the caller found out from a crash rather than
  ;; from an answer, and only if it happened to exercise the posture.
  (let [refused {:name "anterior_deltoid" :refused :coefficient-below-floor :mvc-pct nil}
        s (strain/muscle-strain refused 120.0)]
    (is (nil? (:stiffness-index s)))
    (is (= :coefficient-below-floor (:refused s)))
    (is (not (:saturated? s)) "a refusal is not a saturation")
    (is (= "not-computed" (strain/stiffness-band (:stiffness-index s)))
        "and it must not fall into the `low` band, which reads as the best case"))
  ;; a whole session of tensions, some refused
  (let [xs (strain/session-strain [{:name "a" :mvc-pct 12.0}
                                   {:name "b" :refused :no-line-of-action :mvc-pct nil}]
                                  60.0)]
    (is (= 2 (count xs)))
    (is (number? (:stiffness-index (first xs))))
    (is (nil? (:stiffness-index (second xs))))))

;; --- the desk reaches the muscles that carry the frontal plane ---------------

(def ^:private abducted-reach
  "Shoulder 20 deg flexion, elbow 90 deg, 40 deg of abduction. The abduction is
  what makes this posture able to answer the question: `middle_deltoid` carries
  the FRONTAL shoulder moment, and until 2026-09-07 that moment was the one
  quantity in this model that `:arms-supported` did not reach."
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 20.0 :elbow-flexion-deg 90.0
   :shoulder-abduction-deg 40.0 :wrist-extension-deg 0.0
   :support :seated})

(defn- mvc-at [posture sup muscle-name]
  (let [body (segment/build-body 70.0 1.70)
        p (assoc posture :arms-supported sup)
        loads (load/solve-posture-loads body p)
        tensions (muscle/solve-muscle-tensions body p loads)]
    (:mvc-pct (first (filter #(= muscle-name (:name %)) tensions)))))

(deftest resting-the-forearms-unloads-the-middle-deltoid
  ;; Measured on this body at this posture BEFORE the fix: 21.56177777831644 %MVC
  ;; supported and 21.56177777831644 %MVC unsupported — byte-identical, because
  ;; `load/frontal-moments` did not read the flag and the abduction equilibrium is
  ;; fed entirely by `:frontal :shoulder-per-side`. A forearm resting on a desk
  ;; was still hanging in mid-air, and the deltoid was still holding it out.
  (let [unsup (mvc-at abducted-reach false "middle_deltoid/left")
        sup (mvc-at abducted-reach true "middle_deltoid/left")]
    (is (math/nearly= 21.56177777831644 unsup 1e-9)
        (str "an unsupported abducted arm: " unsup " %MVC"))
    (is (math/nearly= 8.843874638150753 sup 1e-9)
        (str "with the forearm rested: " sup " %MVC"))
    (is (< sup (* 0.5 unsup))
        (str "which is less than half, not a rounding difference: " sup " vs " unsup))))

(deftest resting-the-forearms-unloads-the-quadratus-lumborum
  ;; The same defect one joint down: the frontal LUMBAR moment fed
  ;; `:trunk-lateral-flexion`, and it too counted a rested forearm as hanging.
  ;; Needs a laterally-bent trunk, because an upright posture's two arms cancel
  ;; about the midline and the load is 2.2e-16 in both support states.
  (let [bent (assoc abducted-reach :trunk-lateral-bend-deg 25.0)
        unsup (mvc-at bent false "quadratus_lumborum/right")
        sup (mvc-at bent true "quadratus_lumborum/right")]
    (is (math/nearly= 27.882735608784447 unsup 1e-9)
        (str "bent, arms hanging: " unsup " %MVC"))
    (is (math/nearly= 24.577900226844537 sup 1e-9)
        (str "bent, forearms rested: " sup " %MVC"))
    (is (< sup unsup) "resting the forearms must lower the lateral-flexion demand")))
