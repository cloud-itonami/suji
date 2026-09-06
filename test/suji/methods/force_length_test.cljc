(ns suji.methods.force-length-test
  "The force–length relation, and the thing it fixes: a %MVC whose denominator was
  a constant.

  A muscle cannot produce its maximum at every length — at half or one and a half
  times its optimal it produces nothing at all — and the posture decides its
  length. Dividing by the peak force therefore understates the effort of any
  posture that stretches or shortens a muscle away from its optimum, which is most
  of the interesting ones."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.attachment :as att]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

(def ^:private body (segment/build-body 70.0 1.70))
(def ^:private ref-pose (pose/solve-pose body att/reference-posture))
(def ^:private optimals (att/optimal-lengths ref-pose 1.70))

(deftest the-curve-has-the-shape-it-claims
  ;; Pinned values, so this can be compared by hand against
  ;; `kotoba.biomech.muscle/force-length-factor`, which is the same closed form in
  ;; a repo suji deliberately does not depend on (see that function's docstring).
  ;; This test CANNOT notice biomech changing; that is the price of the duplication
  ;; and is stated rather than implied.
  (is (math/nearly= 1.00 (muscle/force-length-factor 0.10 0.10) 1e-12) "peak at optimal")
  (is (math/nearly= 0.96 (muscle/force-length-factor 0.09 0.10) 1e-12))
  (is (math/nearly= 0.96 (muscle/force-length-factor 0.11 0.10) 1e-12) "symmetric")
  (is (math/nearly= 0.00 (muscle/force-length-factor 0.05 0.10) 1e-12) "zero at half")
  (is (math/nearly= 0.00 (muscle/force-length-factor 0.15 0.10) 1e-12) "zero at 1.5x")
  (is (zero? (muscle/force-length-factor 0.02 0.10)) "and clamped, never negative")
  (is (zero? (muscle/force-length-factor 0.30 0.10))))

(deftest a-missing-length-falls-back-to-the-peak
  ;; a muscle whose length this model cannot state must not be silently scaled to
  ;; zero — that would report it as infinitely strained
  (is (math/nearly= 1.0 (muscle/force-length-factor nil 0.10) 1e-12))
  (is (math/nearly= 1.0 (muscle/force-length-factor 0.10 nil) 1e-12))
  (is (math/nearly= 1.0 (muscle/force-length-factor 0.10 0.0) 1e-12)))

(deftest every-muscle-is-at-its-optimum-in-the-reference-posture
  ;; the optimal length IS the length there, by construction — so this pins that
  ;; the derivation and the consumer agree, and that no muscle degenerates
  (let [lens (att/lengths ref-pose 1.70)]
    (doseq [m att/instances]
      (let [nm (:name m)]
        (is (pos? (get optimals nm)) (str nm ": a muscle has a length"))
        (is (math/nearly= 1.0 (muscle/force-length-factor (get lens nm) (get optimals nm)) 1e-9)
            (str nm ": at the reference posture every muscle is at its optimum"))))))

(deftest the-optimum-scales-with-the-body
  (let [tall (segment/build-body 70.0 2.00)
        o-t (att/optimal-lengths (pose/solve-pose tall att/reference-posture) 2.00)]
    (doseq [m att/instances]
      (is (> (get o-t (:name m)) (get optimals (:name m)))
          (str (:name m) ": a taller body has longer muscles")))))

(deftest the-denominator-is-no-longer-a-constant
  ;; THE DEFECT THIS REMOVES, at the posture where it is worst. At 90° of shoulder
  ;; flexion with a straight elbow the anterior deltoid sits at about 0.75 of its
  ;; optimal length and can produce about three quarters of its peak. Dividing by
  ;; the peak reported 83% of maximum voluntary contraction; dividing by what the
  ;; muscle can actually produce there reports above 100% — which is a different
  ;; statement about the posture, and the true one.
  (let [posture {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                 :shoulder-flexion-deg 90.0 :elbow-flexion-deg 0.0 :arms-supported false}
        p (pose/solve-pose body posture)
        lens (att/lengths p 1.70)
        l (load/solve-posture-loads body posture)
        by (into {} (map (juxt :name identity)) (muscle/solve-muscle-tensions body posture l))
        d (by "anterior_deltoid/left")
        ratio (/ (get lens "anterior_deltoid/left") (get optimals "anterior_deltoid/left"))
        peak-pct (* 100.0 (/ (:force-n d) (muscle/peak-force-n (get muscle/specs "anterior_deltoid"))))]
    (is (< ratio 0.85) (str "the premise: the deltoid is short here, at " ratio))
    (is (> (:mvc-pct d) peak-pct)
        "a length-aware denominator must report MORE effort, not less")
    (is (> (- (:mvc-pct d) peak-pct) 10.0)
        (str "and by a margin that matters: " peak-pct " -> " (:mvc-pct d)))
    (is (:over-mvc? d) "here it crosses maximum voluntary contraction")))

(deftest the-criterion-weighs-available-force-not-peak
  ;; a muscle too short to contribute must not be handed load on the strength of a
  ;; cross-section it cannot use
  (let [shares (fn [f-max-a] (muscle/f-max-of {:group "biceps_brachii" :name "x"}
                                              {"x" f-max-a} {"x" 0.10}))]
    (is (> (shares 0.10) (shares 0.13)) "a stretched muscle offers less")
    (is (> (shares 0.10) (shares 0.075)) "and so does a shortened one")))
