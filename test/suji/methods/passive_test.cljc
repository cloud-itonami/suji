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

(deftest the-passive-term-is-small-here-and-the-model-says-so
  ;; THE CORRECTION THIS TEST EXISTS FOR. The first draft of `passive-force-n`'s
  ;; docstring called this the mechanism behind flexion-relaxation. Measured, it is
  ;; about 4% of the demand at 60° of trunk flexion — real flexion-relaxation is
  ;; the posterior ligamentous system taking over, and those structures are not in
  ;; this model. Pinning the fraction keeps the claim honest: if someone later
  ;; makes the passive term large enough to explain the phenomenon, this fails and
  ;; they have to say why.
  (let [{:keys [by]} (run (assoc base :trunk-flexion-deg 60.0))
        e (by "erector_spinae")
        frac (/ (:passive-n e) (:force-n e))]
    (is (< frac 0.15)
        (str "a muscle's own passive tension is the smaller term here: " frac))
    (is (> frac 0.01) "but not negligible either")))
