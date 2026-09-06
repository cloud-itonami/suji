;; suji 筋 — validation of the gravitational-moment formulas (shoulder + lumbosacral).
;; Run:  bb test
(ns suji.methods.moment-balance-test
  "Validation of the gravitational joint-moment formulas in load.cljc — `shoulder-moment` and
  `lumbosacral-moment`, which `solve-posture-loads` composes but which (unlike `cervical-load`,
  pinned against the Hansraj 2014 table) had NO direct test coverage. A static gravitational joint
  moment is Σ(segment weight × horizontal lever-arm); this pins the analytical behaviour that the
  physics implies and a regression would break:
    - the moment grows monotonically as the limb/trunk flexes toward horizontal (the lever-arm
      grows as sin of the flexion angle);
    - the upright/neutral trunk carries EXACTLY zero L5/S1 moment (no horizontal lever);
    - supporting the forearms removes their lever and strictly lowers the shoulder moment;
    - a heavier carried head raises the L5/S1 moment at the same lean."
  (:require [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.segment :as segment]
            #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])))

(def ^:private body (segment/build-body 70.0 1.70))
(defn- monotone-increasing? [xs] (every? (fn [[a b]] (< a b)) (partition 2 1 xs)))

(deftest shoulder-moment-grows-with-flexion-toward-horizontal
  ;; holding the arm further out (more shoulder flexion, toward 90° horizontal) raises the
  ;; gravitational moment about the glenohumeral joint
  (let [ms (mapv #(:moment-nm (load/shoulder-moment body % 0.0 false)) [0.0 30.0 60.0 90.0])]
    (is (monotone-increasing? ms) (str "shoulder moment must grow with flexion: " ms))
    (is (every? #(and (>= % 0.0) (math/finite? %)) ms) "moments are non-negative + finite")))

(deftest supporting-the-forearms-reduces-shoulder-moment
  ;; resting the forearms (arms-supported) removes the forearm+hand lever → strictly less moment
  ;; than holding them unsupported at the same flexion
  (doseq [deg [30.0 60.0 90.0]]
    (is (< (:moment-nm (load/shoulder-moment body deg 0.0 true))
           (:moment-nm (load/shoulder-moment body deg 0.0 false)))
        (str "supported forearms must lower the shoulder moment at " deg "°"))))

(deftest lumbosacral-moment-is-zero-upright-and-grows-with-lean
  (let [head {:head-weight-n (* 0.081 70.0 9.81)}
        ms (mapv #(:moment-nm (load/lumbosacral-moment body % head)) [0.0 20.0 40.0 60.0])]
    (is (< (Math/abs (double (first ms))) 1e-9)
        "an upright trunk (0° flexion) carries no gravitational L5/S1 moment")
    (is (monotone-increasing? ms) (str "L5/S1 moment must grow with trunk flexion: " ms))
    (is (every? #(math/finite? (double %)) ms) "moments are finite")))

(deftest lumbosacral-moment-grows-with-carried-head-weight
  ;; a heavier head carried above L5/S1 raises the moment at the same lean
  (doseq [deg [20.0 40.0 60.0]]
    (is (< (:moment-nm (load/lumbosacral-moment body deg {:head-weight-n 20.0}))
           (:moment-nm (load/lumbosacral-moment body deg {:head-weight-n 200.0})))
        (str "a heavier carried head must raise the L5/S1 moment at " deg "°"))))

