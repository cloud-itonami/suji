(ns suji.methods.pose-test
  "suji (筋) — forward kinematics, and the shoulder-moment defect it was written to
  remove.

  The controls here are the ones the old lever algebra could not answer, stated as
  physics rather than as pinned output: a mass directly under its joint has no lever;
  a chain that folds forward gets shorter; a segment's endpoints and its stated
  direction have to be the same claim."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

(def ^:private body (segment/build-body 70.0 1.70))

(defn- neutral [& {:as over}]
  (merge {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
          :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0}
         over))

;; --- the chain is internally consistent --------------------------------------

(deftest test-segments-are-continuous
  ;; every joint is shared by the segments that meet there — a chain, not a pile
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 20.0 :head-flexion-deg 30.0
                                         :shoulder-flexion-deg 40.0 :elbow-flexion-deg 90.0))
        {:keys [c7 elbow wrist]} (:joints p)]
    (is (= c7 (:distal (pose/seg-at p "thorax_abdomen"))))
    (is (= c7 (:proximal (pose/seg-at p "head_neck"))))
    (is (= c7 (:proximal (pose/seg-at p "upper_arm"))))
    (is (= elbow (:distal (pose/seg-at p "upper_arm"))))
    (is (= elbow (:proximal (pose/seg-at p "forearm"))))
    (is (= wrist (:distal (pose/seg-at p "forearm"))))
    (is (= wrist (:proximal (pose/seg-at p "hand"))))))

(deftest test-endpoints-agree-with-stated-length-and-direction
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 25.0 :shoulder-flexion-deg 35.0
                                         :elbow-flexion-deg 75.0))]
    (doseq [{:keys [name proximal distal length-m dir]} (:segments p)]
      (is (math/nearly= length-m (math/vlen (math/v- distal proximal)) 1e-9)
          (str name ": |distal - proximal| must equal the segment length"))
      (is (math/nearly= 1.0 (math/vlen dir) 1e-9) (str name ": direction must be a unit vector")))))

(deftest test-chain-stays-in-the-sagittal-plane
  ;; this is a 2-D model living in a 3-D frame; z must be identically zero, or a
  ;; renderer will show a body twisted out of the plane the physics assumes
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 30.0 :head-flexion-deg 45.0
                                         :shoulder-flexion-deg 60.0 :elbow-flexion-deg 90.0))]
    (doseq [{:keys [name proximal distal com]} (:segments p)]
      (doseq [pt [proximal distal com]]
        (is (math/nearly= 0.0 (nth pt 2) 1e-12) (str name ": z must be 0"))))))

(deftest test-folding-forward-shortens-the-chain
  (let [hs (mapv #(pose/total-height-m (pose/solve-pose body (neutral :trunk-flexion-deg %)))
                 [0.0 20.0 40.0 60.0])]
    (is (every? (fn [[a b]] (> a b)) (partition 2 1 hs))
        (str "leaning forward must lower the vertex, got " hs))))

;; --- the defect this namespace exists to remove ------------------------------

(deftest test-a-mass-under-its-joint-has-no-lever
  ;; A straight arm hanging at the side: every arm segment's CoM is directly below
  ;; the glenohumeral joint, so the gravitational moment is EXACTLY zero. The lever
  ;; algebra this replaced returned 5.15 N·m here, because it placed a 0° elbow's
  ;; forearm horizontally. This is the control that names its own reason.
  (let [p (pose/solve-pose body (neutral))]
    (doseq [n ["upper_arm" "forearm" "hand"]]
      (is (math/nearly= 0.0 (pose/anterior-lever (get-in p [:joints :shoulder])
                                                 (pose/seg-at p n))
                        1e-12)
          (str n " hangs under the shoulder and can have no anterior lever")))
    (is (math/nearly= 0.0 (:moment-nm (load/shoulder-moment body 0.0 0.0 false)) 1e-12)
        "a straight hanging arm exerts no shoulder moment")))

(deftest test-elbow-flexion-carries-the-forearm-forward
  ;; the sign of the correction: flexing the elbow from a hanging arm must move the
  ;; forearm's mass ANTERIOR, never posterior, and must raise the shoulder moment
  (let [levers (mapv (fn [e]
                       (let [p (pose/solve-pose body (neutral :elbow-flexion-deg e))]
                         (pose/anterior-lever (get-in p [:joints :shoulder])
                                              (pose/seg-at p "forearm"))))
                     [0.0 30.0 60.0 90.0])
        moments (mapv #(:moment-nm (load/shoulder-moment body 0.0 % false)) [0.0 30.0 60.0 90.0])]
    (is (every? (fn [[a b]] (< a b)) (partition 2 1 levers))
        (str "forearm lever must grow with elbow flexion, got " levers))
    (is (every? (fn [[a b]] (< a b)) (partition 2 1 moments))
        (str "shoulder moment must grow with elbow flexion, got " moments))))

(deftest test-supported-forearms-carry-only-the-upper-arm
  ;; with the forearms resting, the elbow angle cannot change the shoulder moment —
  ;; the forearm is not being carried at all
  (let [ms (mapv #(:moment-nm (load/shoulder-moment body 45.0 % true)) [0.0 45.0 90.0 120.0])]
    (is (apply = ms) (str "supported forearms make the elbow angle irrelevant, got " ms))
    (is (< (first ms) (:moment-nm (load/shoulder-moment body 45.0 90.0 false)))
        "supported must stay strictly below unsupported")))

;; --- the pose and the moment solver are the same geometry --------------------

(deftest test-lumbosacral-lever-matches-the-pose
  ;; `lumbosacral-moment` predates the pose layer and computes its own thorax lever.
  ;; They must agree, or the picture and the physics have drifted apart.
  (doseq [deg [0.0 10.0 25.0 45.0]]
    (let [p (pose/solve-pose body (neutral :trunk-flexion-deg deg))
          thorax (pose/seg-at p "thorax_abdomen")
          from-pose (* (segment/weight-n (segment/seg body "thorax_abdomen"))
                       (pose/anterior-lever (get-in p [:joints :l5s1]) thorax))
          legacy (:moment-nm (load/lumbosacral-moment body deg {:head-weight-n 0.0}))]
      (is (math/nearly= from-pose legacy 1e-9)
          (str deg "°: pose-derived " from-pose " vs lumbosacral-moment " legacy)))))

(deftest test-bone-lines-cover-every-segment
  (let [p (pose/solve-pose body (posture/posture-from-workstation posture/laptop-on-lap))
        lines (pose/bone-lines p)]
    (is (= (count (:segments p)) (count lines)))
    (is (= (set (map :name (:segments p))) (set (map :name lines))))
    (doseq [{:keys [from to]} lines]
      (is (> (math/vlen (math/v- to from)) 0.0) "no zero-length bone"))))
