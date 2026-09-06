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
  ;; The phenomenon the ligaments were added for, and the reason the previous
  ;; wave's claim about passive muscle tension had to be withdrawn: in deep trunk
  ;; flexion the erector spinae falls silent while the posterior ligamentous system
  ;; takes the load. A model without ligaments must report the muscle working
  ;; hardest exactly where it is measured to be working least.
  (let [active (fn [t] (:active-n (get (:by (run (assoc base :trunk-flexion-deg (double t))))
                                       "erector_spinae")))
        ligament (fn [t] (:force-n (get (:by (run (assoc base :trunk-flexion-deg (double t))))
                                        "posterior_lumbar_ligaments")))
        demand (fn [t] (:moment-nm (first (filter #(= "lumbosacral" (:joint %))
                                                  (:joints (:loads (run (assoc base :trunk-flexion-deg (double t)))))))))]
    (is (> (demand 60) (demand 20)) "the premise: deeper flexion is a bigger demand")
    (is (> (active 20) 0.0) "the muscle works in moderate flexion")
    (is (math/nearly= 0.0 (active 60) 1e-9)
        (str "and falls silent in deep flexion, got " (active 60)))
    (is (> (ligament 60) (ligament 40)) "while the ligament takes it")
    (is (> (ligament 60) (* 5.0 (ligament 20))) "and takes over, not merely helps")))

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
