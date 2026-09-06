(ns suji.methods.spine-test
  "Level-by-level spinal compression: that the muscle term is there and dominant,
  that the profile is ordered the way the physics implies, and that the model says
  where it disagrees with the leg this actor actually validated."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.spine :as spine]))

(def ^:private body (segment/build-body 70.0 1.70))

(defn- run [posture]
  (let [l (load/solve-posture-loads body posture)
        t (muscle/solve-muscle-tensions body posture l)]
    {:loads l :tensions t :rows (spine/profile body posture t)}))

(def ^:private lap (posture/posture-from-workstation posture/laptop-on-lap))
(def ^:private monitor (posture/posture-from-workstation posture/external-monitor-eye-level))

(deftest every-level-is-reported-once-with-a-stress
  (let [rows (:rows (run lap))]
    (is (= (count spine/levels) (count rows)))
    (is (= (mapv :name spine/levels) (mapv :name rows)))
    (doseq [r rows]
      (is (number? (:stress-mpa r)))
      (is (pos? (:disc-area-cm2 r)))
      (is (math/nearly= (:force-n r) (+ (:weight-n r) (:muscle-n r)) 1e-9)
          (str (:name r) ": force must be weight + muscle, with nothing else hidden in it")))))

(deftest the-muscle-term-dominates-a-flexed-posture
  ;; THE POINT of computing this rather than quoting the weight carried. An
  ;; extensor works at a short moment arm, so holding a small external moment costs
  ;; a large force, and all of that force presses the joint together. A model with
  ;; only the weight term understates a flexed spine by a factor.
  (let [rows (:rows (run lap))
        l5s1 (first (filter #(= "L5/S1" (:name %)) rows))]
    (is (> (:muscle-n l5s1) (:weight-n l5s1))
        (str "at L5/S1 the muscle term must exceed the weight term: " l5s1))))

(deftest a-worse-posture-loads-the-spine-more
  (let [peak-of #(:stress-mpa (spine/peak (:rows (run %))))]
    (is (> (peak-of lap) (peak-of monitor))
        "the laptop on the lap must put more stress through the spine than the monitor at eye level")))

(deftest lumbar-carries-more-force-and-less-stress-than-cervical
  ;; the reason a stress is reported at all: the lumbar spine takes far more force
  ;; through a far larger disc, and a force alone cannot say which matters
  (let [rows (:rows (run lap))
        l5s1 (first (filter #(= "L5/S1" (:name %)) rows))
        c7 (first (filter #(= "C7/T1" (:name %)) rows))]
    (is (> (:force-n l5s1) (:force-n c7)) "more force through the lumbar spine")
    (is (> (:disc-area-cm2 l5s1) (* 2.0 (:disc-area-cm2 c7))) "through a much larger disc")))

(deftest discs-scale-with-stature-squared
  (let [tall (segment/build-body 70.0 2.00)
        a-s (spine/disc-area-m2 (first spine/levels) 1.70)
        a-t (spine/disc-area-m2 (first spine/levels) 2.00)]
    (is (math/nearly= (Math/pow (/ 2.00 1.70) 2) (/ a-t a-s) 1e-9)
        "an area scales with the square")))

(deftest the-model-reports-where-it-disagrees-with-the-validated-leg
  ;; The lumped cervical model is the one validated against Hansraj (2014); this
  ;; profile is not, and it disagrees with it by about a factor of two because it
  ;; uses the muscle's geometric moment arm rather than a fitted effective lever.
  ;; A consumer must not be able to read the profile as if it inherited the
  ;; validation, so the disagreement is computed rather than remembered.
  (let [{:keys [loads tensions]} (run lap)
        x (spine/cervical-cross-check body lap tensions (:cervical loads))]
    (is (= :lumped (:validated x)))
    (is (pos? (:ratio x)))
    (is (> (:ratio x) 1.5)
        (str "the two paths really do disagree, and by how much is the point: " x))
    (is (< (:ratio x) 4.0) "but not by an order of magnitude")))

(deftest the-step-detector-reports-steps-and-only-steps
  ;; A real muscle attaches over a range of vertebrae; this one attaches at a
  ;; point, so a level just past it can lose the whole force at once. Reporting
  ;; those is the difference between a reader seeing an artefact and a reader
  ;; believing a spine.
  ;;
  ;; This used to assert that the CURRENT model has such steps, and it did until
  ;; passive tension landed: a stretched muscle now contributes across levels
  ;; where it previously contributed exactly zero, and the steps filled in. That
  ;; is a real improvement and not a reason to keep asserting the artefact — so
  ;; what is tested is the DETECTOR, on inputs that do and do not contain one.
  (let [with-step [{:name "A" :muscle-n 100.0} {:name "B" :muscle-n 0.0}
                   {:name "C" :muscle-n 50.0} {:name "D" :muscle-n 0.0}]
        without [{:name "A" :muscle-n 100.0} {:name "B" :muscle-n 60.0}
                 {:name "C" :muscle-n 20.0} {:name "D" :muscle-n 5.0}]]
    (is (= [{:after "A" :at "B"} {:after "C" :at "D"}] (spine/attachment-steps with-step)))
    (is (empty? (spine/attachment-steps without)))
    (is (empty? (spine/attachment-steps [])))
    (is (empty? (spine/attachment-steps [{:name "A" :muscle-n 0.0}])))))

(deftest passive-tension-smoothed-the-profile
  ;; the measured consequence, kept so that losing it would be visible
  (let [rows (:rows (run lap))]
    (is (empty? (spine/attachment-steps rows))
        (str "no level loses its whole muscle term any more: "
             (mapv (juxt :name :muscle-n) rows)))
    (is (every? #(pos? (:muscle-n %)) (remove #(= "C3/C4" (:name %)) rows))
        "every level below the top of the neck carries some muscle force")))
