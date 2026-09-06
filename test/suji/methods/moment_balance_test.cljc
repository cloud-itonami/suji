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
    - the head and the arms are CARRIED by the lumbar spine, each with its own lever.

  MONOTONICITY CANNOT SEE A MISSING SEGMENT, which is why the last line reads as it
  does since 2026-09-07. Every test here was monotone-in-an-angle, and a model that
  dropped the head's own lever and both arms is still monotone in trunk flexion —
  it just answers 75.2 N·m where the chain says 112.5. The `lumbosacral-moment`
  tests now check WHAT IS BEING CARRIED as well as how it varies."
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

(defn- upright [& {:as over}]
  (merge {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
          :elbow-flexion-deg 0.0 :arms-supported false}
         over))

(deftest lumbosacral-moment-is-zero-upright-and-grows-with-lean
  ;; The claim is unchanged; only the way the posture is stated is. Zero here now
  ;; means the WHOLE chain above L5/S1 is stacked on the line of gravity — trunk,
  ;; head and both arms — where before it meant only that the thorax was, which is
  ;; a much weaker thing to have checked.
  (let [ms (mapv #(:moment-nm (load/lumbosacral-moment body (upright :trunk-flexion-deg %)))
                 [0.0 20.0 40.0 60.0])]
    (is (< (Math/abs (double (first ms))) 1e-9)
        "an upright trunk (0° flexion) carries no gravitational L5/S1 moment")
    (is (monotone-increasing? ms) (str "L5/S1 moment must grow with trunk flexion: " ms))
    (is (every? #(math/finite? (double %)) ms) "moments are finite")))

(deftest the-head-loads-the-lumbar-spine-with-the-trunk-upright
  ;; REPLACES `lumbosacral-moment-grows-with-carried-head-weight`, whose stated
  ;; reason this change made unaskable: the head's weight was an INJECTED argument
  ;; and is now read from the body, so there is no longer a heavier head to pass.
  ;; What that test was reaching for — that the head is carried at all — is checked
  ;; here instead, and sharply: with the trunk upright the head is the ONLY thing
  ;; above L5/S1 with a lever, so flexing it must move the moment off zero. The old
  ;; formula placed the head's weight at C7, which is on the line of gravity in
  ;; this posture, and returned 0 at every head angle.
  (let [ms (mapv #(:moment-nm (load/lumbosacral-moment body (upright :head-flexion-deg %)))
                 [0.0 15.0 30.0 45.0 60.0])]
    (is (< (Math/abs (double (first ms))) 1e-9) "a head straight up has no lever")
    (is (monotone-increasing? (rest ms))
        (str "and flexing it must raise the L5/S1 moment, got " ms))
    (is (> (last ms) 8.0) (str "by a real amount, not a rounding one: " (last ms)))))

(deftest the-arms-load-the-lumbar-spine
  ;; the other half of the same omission: `lumbosacral-moment` had no arm term and
  ;; never read `:arms-supported`, so the two arms — a tenth of body mass, hanging
  ;; from a girdle the lumbar spine holds up — were free.
  (doseq [trunk [0.0 20.0 40.0]]
    (let [hanging (:moment-nm (load/lumbosacral-moment
                               body (upright :trunk-flexion-deg trunk)))
          held-out (:moment-nm (load/lumbosacral-moment
                                body (upright :trunk-flexion-deg trunk
                                              :shoulder-flexion-deg 60.0)))]
      (is (> held-out hanging)
          (str "holding the arms out must load L5/S1 more than letting them hang, at "
               trunk "°: " held-out " vs " hanging)))))

