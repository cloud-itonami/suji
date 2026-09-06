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
            [suji.methods.recruit :as recruit]
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
        ;;
        ;; ⚠ AND HALF OF IT CAME BACK ON 2026-09-08, when the neck became a coupled
        ;; group. Semispinalis and splenius capitis are better levered about the
        ;; atlanto-occipital joint than about C7, so once that joint is a
        ;; constraint the optimum stops using them to hold C7 and puts the work on
        ;; `cervical_extensors` at its shorter arm: 22.08 -> 39.18% MVC, dose
        ;; 18.41 -> 68.58, and back into double-precision saturation. The direction
        ;; assertion below is unchanged; the saturation one is inverted, and the
        ;; number is restated rather than the assertion loosened.
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
    (is (:saturated? c-long)
        (str "cervical_extensors saturates again at 120 min under the coupled neck "
             "solve; its dose is " (:dose c-long) " (was 18.41 uncoupled, 158.35 "
             "before the neck was split at all)"))
    ;; AND THE FLAG STILL DISCRIMINATES AT THIS POSTURE, which is what the old
    ;; assertion was buying and what an inverted one could quietly stop buying:
    ;; the same solve produces muscles that are not saturated.
    (let [unsat (remove :saturated?
                        (map #(strain/muscle-strain % 120.0)
                             (filter :mvc-pct tensions)))]
      (is (seq unsat)
          "some muscle at this posture must NOT saturate, or the flag says nothing"))))

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

(deftest a-suboccipital-reporting-zero-carries-the-reason-with-it
  ;; A suboccipital reporting 0 N at a desk posture is indistinguishable, in the
  ;; output alone, from a muscle nobody thought about. The reason has to be
  ;; reachable from the same data.
  ;;
  ;; WHAT THE REASON USED TO BE, AND WHAT IT IS NOW. Until 2026-09-08 it was
  ;; `:task-over-supplied-nm`: the muscles solved at C7 were already exerting more
  ;; at this joint than it needed, so its own extensors were handed a load of zero
  ;; and the surplus was reported beside them. `recruit/solve` chooses the C7
  ;; forces with this joint in the problem, so there is no surplus — and the
  ;; zero has a different and better reason, which the row now states: the joint
  ;; HAS a load, it is satisfied, and the coupled optimum satisfies it with the
  ;; muscles that were going to cross it anyway rather than with these three.
  (let [b (segment/build-body 70.0 1.70)
        pst (posture/posture-from-workstation posture/laptop-on-lap)
        loads (load/solve-posture-loads b pst)
        tens (muscle/solve-muscle-tensions b pst loads)
        sub (filterv #(= :atlanto-occipital-extension (:task %)) tens)
        summary (muscle/tension-summary tens loads)]
    (is (= 3 (count sub)) (str "three suboccipitals: " (mapv :group sub)))
    (doseq [t sub]
      (is (zero? (:active-n t)) (str (:group t) " produces nothing here: " t))
      (is (nil? (:refused t))
          (str (:group t) " is not refused — the force was computed and it is "
               "zero: " t))
      (is (pos? (:task-load-nm t))
          (str (:group t) " states the joint's load, which is NOT zero — that is "
               "the difference from the uncoupled model: " t))
      (is (math/nearly= 0.0 (get-in t [:coupled-residual-nm :atlanto-occipital]) 1e-9)
          (str (:group t) " states that the load is nevertheless met: " t)))
    (is (math/nearly= 0.0 (get-in summary [:coupled-residual-nm :atlanto-occipital]) 1e-9)
        (str "and the summary states it once: "
             (select-keys summary [:coupled-residual-nm])))
    ;; the discriminating half: these three DO carry where the coupled optimum
    ;; wants them, so `:active-n 0.0` is not a constant of the model
    (let [p2 {:head-flexion-deg -55.0 :trunk-flexion-deg 75.0
              :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0 :arms-supported false}
          l2 (load/solve-posture-loads b p2)
          t2 (filterv #(= :atlanto-occipital-extension (:task %))
                      (muscle/solve-muscle-tensions b p2 l2))]
      (doseq [t t2]
        (is (pos? (:active-n t))
            (str (:group t) " must take active force where the optimum wants it: " t))))))


(deftest the-girdle-suspension-muscles-load-c7-and-are-not-in-its-group
  ;; THE GAP THE COUPLED SOLVE DID NOT CLOSE, as a number rather than a sentence.
  ;;
  ;; Upper trapezius runs from the occiput and the nuchal line to the lateral
  ;; clavicle and levator scapulae from the upper cervical transverse processes to
  ;; the scapula, so both pass the cervicothoracic junction. `spine/levels-crossed`
  ;; has always put them across C7/T1 and their force has always been in that
  ;; level's compression; what was never reported is the MOMENT they exert there,
  ;; which the cervical equilibrium is not told about.
  ;;
  ;; They are not in the `:neck` coupled group and the reason is the shape of the
  ;; solver's inputs, not the solver: their own equilibrium is a SUSPENSION balance
  ;; — a force, with a dimensionless direction cosine for a coefficient — and the
  ;; neck group's rows are moments. `recruit/solve` can take rows in different
  ;; units, because each multiplier carries the reciprocal of its own row's;
  ;; `attachment/coupled-arms` cannot supply a suspension coefficient.
  ;;
  ;; And even if it could, this model would decline: both arms about C7 are BELOW
  ;; `recruit/min-coeff`, which is the floor that says a straight line has no
  ;; business claiming leverage this close to the joint.
  ;;
  ;; ⚠ HOW THIS TEST FAILS, MEASURED, because two different breaks give two
  ;; different kinds of red. Deleting `:crosses {:joint :c7}` from either entry
  ;; fails it HERE, by its own label — `must declare that it crosses C7`, `must
  ;; report the moment`, and the summary total off by that muscle's share
  ;; (−0.3786 → −0.3228 N·m with levator scapulae removed). Restoring the `mirror`
  ;; bug instead — side-qualifying every crossed joint, so these two get
  ;; `:c7/left` — does NOT fail here: it throws a NullPointerException out of
  ;; `attachment/straight-moment-arm`, because `(get-in pose-data [:joints
  ;; :c7/left])` is nil and the vector subtraction destructures it. That is a loud
  ;; failure and not a silent one, and it is deliberately left loud: a joint key
  ;; that does not exist is a wiring mistake, and returning nil for it would make
  ;; it indistinguishable from a degenerate line of action.
  (let [b (segment/build-body 70.0 1.70)
        pst (posture/posture-from-workstation posture/laptop-on-lap)
        loads (load/solve-posture-loads b pst)
        tens (muscle/solve-muscle-tensions b pst loads)
        by (into {} (map (juxt :name identity)) tens)
        summary (muscle/tension-summary tens loads)]
    (doseq [n ["upper_trapezius/left" "upper_trapezius/right"
               "levator_scapulae/left" "levator_scapulae/right"]]
      (let [t (by n)]
        ;; the crossing is DECLARED, so the moment is reported at every posture
        (is (= :c7 (:crosses-joint t))
            (str n " must declare that it crosses C7 — and NOT `:c7/left`, which "
                 "`pose` has no joint for: " (pr-str (select-keys t [:crosses-joint
                                                                     :secondary-arm-m]))))
        (is (number? (:secondary-moment-nm t))
            (str n " must report the moment it exerts there: " t))
        (is (false? (boolean (:secondary-fed? t)))
            (str n " is NOT in a coupled group, and must say so rather than look "
                 "solved: " t))
        ;; below the leverage floor, which is why coupling them would refuse them.
        ;; Guarded on the arm being a NUMBER rather than letting `abs*` throw: a
        ;; nil arm here is the failure the assertion above names, and an uncaught
        ;; NPE would report it as `Uncaught exception, not in assertion` instead of
        ;; as the thing that is wrong. Verified 2026-09-08 by restoring the mirror
        ;; bug this test was written against.
        (is (and (number? (:secondary-arm-m t))
                 (< (math/abs* (:secondary-arm-m t)) recruit/min-coeff))
            (str n "'s arm about C7 must be a number below the floor "
                 recruit/min-coeff ", got " (pr-str (:secondary-arm-m t))))))
    ;; and the total is in the summary, where a consumer will find it without
    ;; knowing which four rows to look at
    (is (contains? (:two-joint-unfed-nm summary) :c7)
        (str "the summary must total it: " (:two-joint-unfed-nm summary)))
    (is (math/nearly= -0.3786202544993853 (get-in summary [:two-joint-unfed-nm :c7]) 1e-12)
        (str "measured 2026-09-08 at laptop-on-lap, 70 kg / 1.70 m: "
             (get-in summary [:two-joint-unfed-nm :c7]) " N·m against a C7 demand of "
             (get-in loads [:cervical :extensor-moment-nm])))
    ;; THE SIZE IS THE POINT: 7.6% of the demand at this posture. Small, real, and
    ;; not zero — a reported approximation that is always zero would mean nothing.
    (is (< 0.05 (/ (math/abs* (get-in summary [:two-joint-unfed-nm :c7]))
                   (get-in loads [:cervical :extensor-moment-nm]))
           0.15)
        (str "it is a few percent of the C7 demand, not a rounding artefact and "
             "not a headline: " (get-in summary [:two-joint-unfed-nm :c7]) " of "
             (get-in loads [:cervical :extensor-moment-nm])))))
