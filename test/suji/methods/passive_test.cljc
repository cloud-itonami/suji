(ns suji.methods.passive-test
  "Passive tension: force the tissue produces without being asked, and the
  correction that measuring it forced."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.attachment :as att]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.recruit :as recruit]
            [suji.methods.segment :as segment]))

(def ^:private body (segment/build-body 70.0 1.70))
(def ^:private optimals
  (att/optimal-lengths (pose/solve-pose body att/reference-posture) 1.70))

(defn- run [posture]
  (let [l (load/solve-posture-loads body posture)]
    {:loads l
     :by (into {} (map (juxt :name identity)) (muscle/solve-muscle-tensions body posture l))
     :lens (att/lengths (pose/solve-pose body posture) 1.70)}))

(def ^:private base
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 15.0
   :elbow-flexion-deg 90.0 :arms-supported false})

(deftest slack-below-the-optimum
  ;; a muscle at or under its optimal length is not stretched and pulls on nothing
  (let [spec (get muscle/specs "erector_spinae")]
    (is (zero? (muscle/passive-force-n spec 0.10 0.10)) "zero AT the optimum")
    (is (zero? (muscle/passive-force-n spec 0.08 0.10)) "and below it")
    (is (pos? (muscle/passive-force-n spec 0.12 0.10)) "and non-zero above")))

(deftest passive-rises-monotonically-with-stretch
  (let [spec (get muscle/specs "erector_spinae")
        fs (mapv #(muscle/passive-force-n spec (* % 0.10) 0.10) [1.0 1.1 1.2 1.3 1.4 1.5])]
    (is (every? (fn [[a b]] (<= a b)) (partition 2 1 fs)) (str "monotone: " fs))
    (is (math/nearly= (* muscle/passive-at-stretch (muscle/peak-force-n spec)) (last fs) 1e-9)
        "and reaches the stated fraction of peak at 1.5x")))

(deftest a-missing-length-produces-no-passive-force
  ;; the opposite fallback from `force-length-factor`, and for the opposite reason:
  ;; inventing a force the model cannot place would put load on a muscle it never
  ;; measured, where inventing a CAPACITY only mis-scales one it did
  (let [spec (get muscle/specs "erector_spinae")]
    (is (zero? (muscle/passive-force-n spec nil 0.10)))
    (is (zero? (muscle/passive-force-n spec 0.10 nil)))
    (is (zero? (muscle/passive-force-n spec 0.10 0.0)))))

(deftest a-stretched-muscle-carries-some-of-the-load-without-being-asked
  (let [{:keys [by lens]} (run (assoc base :trunk-flexion-deg 40.0))
        e (by "erector_spinae")]
    (is (> (/ (get lens "erector_spinae") (get optimals "erector_spinae")) 1.05)
        "the premise: this posture stretches the erector spinae")
    (is (pos? (:passive-n e)) "so its tissue pulls")
    (is (pos? (:active-n e)) "and the rest is still asked for")
    (is (math/nearly= (:force-n e) (+ (:active-n e) (:passive-n e)) 1e-9)
        "what it transmits is what it chose plus what it could not help")))

(deftest passive-tension-is-not-distributed
  ;; it is determined by length, so the criterion must not get to choose it. The
  ;; check: a candidate with a passive term takes it whether or not the criterion
  ;; would have given it anything.
  (let [r (recruit/share [{:name "a" :f-max-n 600.0 :coeff 0.05 :passive-n 0.0}
                          {:name "b" :f-max-n 200.0 :coeff 0.05 :passive-n 40.0}]
                         30.0)
        by (into {} (map (juxt :name identity)) r)]
    (is (math/nearly= 40.0 (:passive-n (by "b")) 1e-9))
    (is (math/nearly= 0.0 (:passive-n (by "a")) 1e-9))
    ;; and the equilibrium still balances, counting both
    (is (math/nearly= 0.0 (recruit/residual r 30.0) 1e-9))))

(deftest passive-tissue-can-carry-the-whole-load
  ;; then nothing is asked of the contractile machinery at all — not a negative
  ;; activation and not an antagonist firing
  (let [r (recruit/share [{:name "a" :f-max-n 600.0 :coeff 0.05 :passive-n 900.0}] 30.0)
        a (first r)]
    (is (math/nearly= 0.0 (:active-n a) 1e-9) "no activation is needed")
    (is (math/nearly= 900.0 (:force-n a) 1e-9) "and the tissue still transmits its force")))

(deftest a-muscles-own-passive-tension-is-the-smaller-term
  ;; THE CORRECTION THIS TEST EXISTS FOR, restated against the right denominator.
  ;; The first draft of `passive-force-n`'s docstring called a muscle's own passive
  ;; tension the mechanism behind flexion-relaxation. It is not: measured against
  ;; the MOMENT the joint has to carry, it supplies a small share. (An earlier
  ;; version of this test divided by the muscle's own force instead, which reads
  ;; 1.0 the moment the muscle stops activating — that is the phenomenon, not the
  ;; share.)
  (let [posture (assoc base :trunk-flexion-deg 40.0)
        {:keys [by loads]} (run posture)
        e (by "erector_spinae")
        demand (:moment-nm (first (filter #(= "lumbosacral" (:joint %)) (:joints loads))))
        share (/ (* (:coeff e) (:passive-n e)) demand)]
    (is (< share 0.10)
        (str "a muscle's own passive tension carries a small share of the demand: " share))
    (is (> share 0.005) "but not nothing")))

(deftest flexion-relaxation
  ;; The phenomenon the ligaments were added for: in deep trunk flexion the erector
  ;; spinae quietens while the posterior ligamentous system takes the load. A model
  ;; without ligaments reports the muscle working hardest exactly where it is
  ;; measured to be working least.
  ;;
  ;; ⚠ THIS TEST ASSERTED SILENCE UNTIL 2026-09-07, AND THE SILENCE WAS AN
  ;; ARTEFACT. `lumbosacral-moment` omitted the head's own lever and both arms, so
  ;; the demand at 60° of trunk flexion came out 75.24 N·m where the placed chain
  ;; says 121.36. The ligament's moment there is 108.18 N·m — MORE than the
  ;; understated demand — so the remainder went negative and `recruit` clamped the
  ;; erector spinae at exactly 0.0. It was not the model reproducing
  ;; flexion-relaxation; it was the model running out of load.
  ;;
  ;; Measured with the corrected demand, erector spinae ACTIVE force (N) and %MVC:
  ;;
  ;;      trunk    was      now     %MVC now
  ;;      20°      473.5    977.0    51.7
  ;;      40°      266.9   1295.8    80.6
  ;;      60°        0.0    441.2    40.8
  ;;      61°        —      392.7    38.0   ← the minimum, then the ligament clamps
  ;;
  ;; So the phenomenon SURVIVES in the shape that can be checked — the muscle peaks
  ;; near 40° and falls by about 70% by 61° while the ligament force triples — and
  ;; does NOT survive as silence. That is what this test now asserts, because that
  ;; is what the model does. It is a real limitation and not a tuning target: the
  ;; ligament's `:force-at-ref` was chosen against the understated demand, and the
  ;; only honest way to decide whether the corrected model should reach silence is
  ;; EMG from a real trunk, which this repository does not have. Re-tuning the
  ;; ligament to restore a 0 would be fitting the tissue to a bug.
  (let [active (fn [t] (:active-n (get (:by (run (assoc base :trunk-flexion-deg (double t))))
                                       "erector_spinae")))
        mvc (fn [t] (:mvc-pct (get (:by (run (assoc base :trunk-flexion-deg (double t))))
                                   "erector_spinae")))
        ligament (fn [t] (:force-n (get (:by (run (assoc base :trunk-flexion-deg (double t))))
                                        "posterior_lumbar_ligaments")))
        demand (fn [t] (:moment-nm (first (filter #(= "lumbosacral" (:joint %))
                                                  (:joints (:loads (run (assoc base :trunk-flexion-deg (double t)))))))))]
    (is (> (demand 60) (demand 20)) "the premise: deeper flexion is a bigger demand")
    (is (> (active 20) 0.0) "the muscle works in moderate flexion")
    ;; THE RELAXATION. The demand keeps growing and the muscle's share of it falls:
    ;; that is the whole content of the phenomenon, and it does not need a zero.
    (is (> (active 40) (active 20)) "the muscle is still taking up load at 40°")
    (is (< (active 61) (* 0.35 (active 40)))
        (str "and by 61° it has given up most of it: " (active 61) " vs " (active 40)))
    (is (< (mvc 61) (* 0.55 (mvc 40)))
        (str "which is a fall in effort, not just in force: " (mvc 61) "% vs " (mvc 40) "%"))
    ;; AND IT IS NOT SILENT, stated as an assertion so that a future change which
    ;; restores the zero has to come here and say why.
    (is (> (active 60) 100.0)
        (str "the corrected model does NOT silence the erector spinae at 60°, and the "
             "0 N this test used to assert was `recruit` clamping a demand that was "
             "understated by the missing head and arm terms. Got " (active 60)))
    (is (> (ligament 60) (ligament 40)) "while the ligament takes it")
    (is (> (ligament 60) (* 5.0 (ligament 20))) "and takes over, not merely helps")))

(deftest the-ligament-carries-more-moment-than-the-old-lumbosacral-term-asked-for
  ;; The mechanism of the artefact above, isolated so it cannot be argued about.
  ;; The posterior ligamentous system's moment at 60° of trunk flexion exceeds what
  ;; `lumbosacral-moment` used to report as the WHOLE demand there. A load-sharing
  ;; model handed less load than one of its passive elements already supplies has
  ;; nothing left to share, and clamps.
  (let [{:keys [by loads]} (run (assoc base :trunk-flexion-deg 60.0))
        lig (by "posterior_lumbar_ligaments")
        lig-moment (* (:coeff lig) (:force-n lig))
        ;; the pre-2026-09-07 formula, written out: thorax lever + head weight
        ;; placed AT C7, no arms.
        ;; the trunk was ONE segment when that formula was written; it is two
        ;; since 2026-09-08, so the old formula is restated over the pair —
        ;; the same trunk mass at the same centre of mass and the same length.
        lumbar (segment/seg body "lumbar")
        thorax (segment/seg body "thorax")
        trunk-len (+ (:length-m lumbar) (:length-m thorax))
        trunk-w (+ (segment/weight-n lumbar) (segment/weight-n thorax))
        head-w (* (segment/head-mass-kg 70.0) segment/gravity)
        s (Math/sin (math/radians 60.0))
        old-demand (+ (* trunk-w trunk-len segment/trunk-com-frac s)
                      (* head-w trunk-len s))
        new-demand (:moment-nm (first (filter #(= "lumbosacral" (:joint %)) (:joints loads))))]
    (is (> lig-moment old-demand)
        (str "the ligament alone (" lig-moment " N·m) exceeded the old demand ("
             old-demand " N·m), which is why the muscle came out at exactly zero"))
    (is (< lig-moment new-demand)
        (str "and does not exceed the real one (" new-demand " N·m), which is why "
             "the muscle is still working"))))

(deftest a-ligament-has-no-percent-of-a-maximum-it-cannot-contract-to
  (let [{:keys [by]} (run (assoc base :trunk-flexion-deg 40.0))
        lig (by "posterior_lumbar_ligaments")]
    (is (:ligament? lig))
    (is (nil? (:mvc-pct lig)))
    (is (math/nearly= 0.0 (:active-n lig) 1e-12) "and no active force")
    (is (pos? (:force-n lig)) "but it transmits one")))

(deftest a-ligament-says-when-the-posture-has-left-its-calibrated-range
  ;; the exponential is calibrated between slack and `:ref-stretch` and says
  ;; nothing beyond. Extrapolating gave the nuchal ligament 52,312 N at an
  ;; ordinary forward-head posture. It is clamped, and the clamp is announced —
  ;; a real ligament stiffens further and then FAILS, and this model has no
  ;; failure law.
  (let [spec (att/instance "nuchal_ligament")
        neutral 0.10
        ref (:ref-stretch spec)]
    (is (not (muscle/ligament-at-limit? spec (* 1.1 neutral) neutral)))
    (is (muscle/ligament-at-limit? spec (* (+ ref 0.05) neutral) neutral))
    ;; and the force does not run away past the limit
    (let [at-ref (muscle/ligament-force-n spec (* ref neutral) neutral)
          far (muscle/ligament-force-n spec (* 3.0 neutral) neutral)]
      (is (math/nearly= at-ref far 1e-9) "clamped, not extrapolated")
      (is (math/nearly= (:force-at-ref spec) at-ref 1e-9)))))
