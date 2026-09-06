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
            [suji.methods.pose :as pose]
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


;; --- what the desk carries, in every plane -----------------------------------
;;
;; THE FLAG REACHED THE SAGITTAL MOMENTS AND NOTHING ELSE, until 2026-09-07.
;; `:arms-supported` was read at five call sites in `load.cljc` with the answer
;; written out by hand at each, and `frontal-moments` did not read it at all.
;; Every test above this line is a sagittal test, which is exactly why none of
;; them noticed.

(def ^:private audit-posture
  "The posture the finding was measured at: an abducted, elbow-flexed reach, so
  that the SAME arm has a sagittal moment and a frontal one at the same time. A
  purely sagittal posture cannot discriminate here — its frontal moment is zero
  in both support states, and zero equals zero."
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
   :shoulder-flexion-deg 20.0 :elbow-flexion-deg 90.0
   :shoulder-abduction-deg 40.0 :wrist-extension-deg 0.0
   :support :seated})

(defn- with-support [posture sup] (assoc posture :arms-supported sup))

(deftest the-desk-takes-the-forearm-and-the-hand-and-nothing-else
  ;; The one decision, asserted where it is made rather than inferred from a
  ;; moment. A desk that took the upper arm would abolish the shoulder moment
  ;; entirely and leave the girdle nothing to suspend, and several tests below
  ;; would still pass.
  (is (= #{"forearm" "hand"} load/desk-borne-bases))
  (doseq [base ["forearm" "hand"]]
    (is (load/body-carries? {:arms-supported false} base)
        (str "an unsupported " base " hangs off the body"))
    (is (not (load/body-carries? {:arms-supported true} base))
        (str "a rested " base " is carried by the desk")))
  (doseq [base ["upper_arm" "thorax_abdomen" "head_neck" "thigh"]]
    (is (load/body-carries? {:arms-supported true} base)
        (str "the desk does not take the " base)))
  ;; and the lumbar list is DERIVED from that answer rather than being a second
  ;; copy of it — it was a map keyed by support state until 2026-09-07
  (is (= ["thorax_abdomen" "head_neck" "upper_arm" "forearm" "hand"]
         (load/lumbar-borne-bases {:arms-supported false})))
  (is (= ["thorax_abdomen" "head_neck" "upper_arm"]
         (load/lumbar-borne-bases {:arms-supported true}))))

(deftest supporting-the-forearms-reduces-the-frontal-shoulder-moment
  ;; THE FINDING. Measured on this body at this posture before the fix: the
  ;; sagittal shoulder moment moved 9.922588 -> 1.812620 N*m when the forearms
  ;; were rested, and the frontal shoulder moment stayed -3.9184149037422804 N*m
  ;; to the last bit. Same arm, same desk, two answers.
  ;;
  ;; PINNED, not merely compared. `supported < unsupported` would pass against a
  ;; model that shaved a gram off, and the value it has to be is not a tolerance
  ;; somebody chose: it is the frontal moment of the upper arm alone.
  (let [fr (fn [sup] (get-in (load/frontal-moments body (with-support audit-posture sup))
                             [:shoulder-per-side :left]))]
    (is (math/nearly= -3.9184149037422804 (fr false) 1e-12)
        (str "an unsupported arm's frontal shoulder moment: " (fr false)))
    (is (math/nearly= -1.600583792781923 (fr true) 1e-12)
        (str "a rested forearm leaves only the upper arm's: " (fr true)))
    (is (> (Math/abs (double (fr false))) (* 2.0 (Math/abs (double (fr true)))))
        (str "which is more than a factor of two, not a rounding difference: "
             (fr false) " vs " (fr true)))))

(deftest the-desk-reaches-the-frontal-lumbar-moment-too
  ;; The other frontal quantity, and it needs a laterally-bent trunk to be
  ;; non-zero at all — an upright posture's two arms cancel about the midline, so
  ;; it reads 2.2e-16 either way and cannot discriminate.
  (let [bent (assoc audit-posture :trunk-lateral-bend-deg 25.0)
        m (fn [sup] (:lumbosacral-nm (load/frontal-moments body (with-support bent sup))))]
    (is (math/nearly= -54.91992161403256 (m false) 1e-9)
        (str "both whole arms bend the spine: " (m false)))
    (is (math/nearly= -48.67019441729147 (m true) 1e-9)
        (str "with the forearms on the desk, less: " (m true)))
    ;; the difference is the forearms and the hands and nothing else
    (is (> (Math/abs (double (m false))) (Math/abs (double (m true))))
        "resting the forearms must lower the frontal lumbar moment")))

(deftest the-desk-takes-the-same-weight-out-of-every-equilibrium
  ;; ONE PLACE DECIDES, checked as a quantity rather than as a call graph. The
  ;; L5/S1 sagittal moment and the frontal one are computed by different code in
  ;; different planes; what they must agree about is WHICH SEGMENTS the body is
  ;; still holding. Reaching for the same segment list from the same function is
  ;; how they do it, and this is the assertion that would fail if one of them grew
  ;; its own copy again.
  (let [forearm+hand-both-sides
        (* 2.0 (+ (* (:mass-kg (segment/seg body "forearm")) segment/gravity)
                  (* (:mass-kg (segment/seg body "hand")) segment/gravity)))
        p (assoc audit-posture :trunk-flexion-deg 30.0)
        placed (fn [sup]
                 (let [pose (pose/solve-pose body (with-support p sup))
                       w (pose/segment-weights body pose)]
                   (reduce + 0.0
                           (map #(get w (:name %))
                                (pose/segments-on
                                 pose (load/lumbar-borne-bases (with-support p sup)))))))]
    (is (math/nearly= 30.204482 forearm+hand-both-sides 1e-6)
        (str "two forearms and two hands weigh " forearm+hand-both-sides " N"))
    (is (math/nearly= forearm+hand-both-sides (- (placed false) (placed true)) 1e-9)
        (str "and that is exactly what the lumbar spine stops carrying: "
             (- (placed false) (placed true)) " N"))))
