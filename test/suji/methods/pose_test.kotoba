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
        {:keys [c7] :as joints} (:joints p)
        elbow (:elbow/left joints)
        wrist (:wrist/left joints)
        shoulder (:shoulder/left joints)]
    (is (= (:t12l1 joints) (:distal (pose/seg-at p "lumbar"))))
    (is (= (:t12l1 joints) (:proximal (pose/seg-at p "thorax"))))
    (is (= c7 (:distal (pose/seg-at p "thorax"))))
    (is (= c7 (:proximal (pose/seg-at p "lower_cervical"))))
    ;; the neck is a chain of three since 2026-09-07, and the two new joints have
    ;; to be shared the same way
    (is (= (:c2c3 joints) (:distal (pose/seg-at p "lower_cervical"))))
    (is (= (:c2c3 joints) (:proximal (pose/seg-at p "upper_cervical"))))
    (is (= (:atlanto-occipital joints) (:distal (pose/seg-at p "upper_cervical"))))
    (is (= (:atlanto-occipital joints) (:proximal (pose/seg-at p "head"))))
    (is (= (:vertex joints) (:distal (pose/seg-at p "head"))))
    (is (= shoulder (:proximal (pose/seg-at p "upper_arm/left"))))
    (is (= elbow (:distal (pose/seg-at p "upper_arm/left"))))
    (is (= elbow (:proximal (pose/seg-at p "forearm/left"))))
    (is (= wrist (:distal (pose/seg-at p "forearm/left"))))
    (is (= wrist (:proximal (pose/seg-at p "hand/left"))))))

(deftest test-endpoints-agree-with-stated-length-and-direction
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 25.0 :shoulder-flexion-deg 35.0
                                         :elbow-flexion-deg 75.0))]
    (doseq [{:keys [name proximal distal length-m dir]} (:segments p)]
      (is (math/nearly= length-m (math/vlen (math/v- distal proximal)) 1e-9)
          (str name ": |distal - proximal| must equal the segment length"))
      (is (math/nearly= 1.0 (math/vlen dir) 1e-9) (str name ": direction must be a unit vector")))))

(deftest midline-stays-in-the-plane-and-the-arms-mirror-each-other
  ;; This used to assert that EVERY segment sits at z = 0, which was true of a
  ;; one-armed model and is exactly what made it unable to represent an asymmetric
  ;; posture. The invariant that survives is: the midline is midline, and the two
  ;; arms are mirror images while the posture is symmetric.
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 30.0 :head-flexion-deg 45.0
                                         :shoulder-flexion-deg 60.0 :elbow-flexion-deg 90.0))]
    (doseq [{:keys [name side proximal distal com]} (:segments p)
            :when (= :midline side)]
      (doseq [pt [proximal distal com]]
        (is (math/nearly= 0.0 (nth pt 2) 1e-12) (str name ": a midline segment has z = 0"))))
    (doseq [base ["upper_arm" "forearm" "hand"]]
      (let [l (pose/seg-at p (str base "/left"))
            r (pose/seg-at p (str base "/right"))]
        (is (some? l)) (is (some? r))
        (doseq [k [:proximal :distal :com]]
          (is (math/nearly= (nth (k l) 0) (nth (k r) 0) 1e-12) (str base " " k ": same x"))
          (is (math/nearly= (nth (k l) 1) (nth (k r) 1) 1e-12) (str base " " k ": same y"))
          (is (math/nearly= (nth (k l) 2) (- (nth (k r) 2)) 1e-12)
              (str base " " k ": mirrored z")))
        (is (> (nth (:com l) 2) 0.05) "the left arm is on the +Z side, off the midline")))))

(deftest an-asymmetric-posture-stops-the-arms-mirroring
  ;; the point of the bilateral model: lateral bend must make the two sides differ
  (let [p (pose/solve-pose body (neutral :trunk-lateral-bend-deg 30.0))
        l (pose/seg-at p "upper_arm/left")
        r (pose/seg-at p "upper_arm/right")]
    (is (not (math/nearly= (nth (:com l) 1) (nth (:com r) 1) 1e-6))
        "leaning sideways puts one shoulder lower than the other")
    (is (< (nth (:com l) 1) (nth (:com r) 1))
        "leaning toward +Z (the person's left) drops the LEFT shoulder")))

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
    (doseq [n ["upper_arm/left" "forearm/left" "hand/left"]]
      (is (math/nearly= 0.0 (pose/anterior-lever (get-in p [:joints :shoulder/left])
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
                         (pose/anterior-lever (get-in p [:joints :shoulder/left])
                                              (pose/seg-at p "forearm/left"))))
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

(defn- lumbar-moment-from-pose
  "Everything above L5/S1, summed here from a list written out IN THIS TEST rather
  than read from the implementation. That is the whole point of the rewrite: the
  version of this test that shipped until 2026-09-07 asked `lumbosacral-moment`
  for a moment with a synthetic `{:head-weight-n 0.0}` and compared it against a
  THORAX-ONLY pose lever — so it checked the one term that was already right, and
  a model that dropped the head's own lever and both arms passed it every time.

  A test that computes its reference from the same set the implementation uses
  cannot see a missing segment. This one names the five segments out loud."
  [pose-data supported?]
  (let [w (pose/segment-weights body pose-data)
        bases (if supported?
                (into (vec segment/trunk-bases) (conj segment/cervical-bases "upper_arm"))
                (into (vec segment/trunk-bases)
                      (concat segment/cervical-bases ["upper_arm" "forearm" "hand"])))]
    (pose/gravitational-moment
     (get-in pose-data [:joints :l5s1])
     (for [s (pose/segments-on pose-data bases)] [s (get w (:name s))]))))

(deftest test-lumbosacral-moment-is-the-full-pose-derived-moment
  ;; `lumbosacral-moment` used to compute its own lever algebra. It reads the
  ;; placed chain now, and this pins that it reads ALL of it — the trunk, the head
  ;; with its own lever, and both arms.
  (doseq [pst [(neutral)
               (neutral :head-flexion-deg 45.0)
               (neutral :trunk-flexion-deg 60.0)
               (neutral :trunk-flexion-deg 25.0 :head-flexion-deg 30.0
                        :shoulder-flexion-deg 45.0 :elbow-flexion-deg 90.0)
               (posture/posture-from-workstation posture/laptop-on-lap)
               (posture/posture-from-workstation posture/laptop-on-desk)
               (posture/posture-from-workstation posture/external-monitor-eye-level)]]
    (let [p (pose/solve-pose body pst)
          from-pose (lumbar-moment-from-pose p (boolean (:arms-supported pst)))
          got (:moment-nm (load/lumbosacral-moment body pst))]
      (is (math/nearly= from-pose got 1e-9)
          (str pst ": pose-derived " from-pose " vs lumbosacral-moment " got)))))

(deftest test-a-flexed-head-loads-the-lumbar-spine-from-an-upright-trunk
  ;; THE CONTROL THAT NAMES THE DEFECT. Trunk upright, head flexed 45°: the head's
  ;; centre of mass is 12 cm anterior of L5/S1, so the lumbar extensors have a real
  ;; moment to hold. The hand algebra placed the head's weight AT C7 — which is on
  ;; the line of gravity when the trunk is upright — and returned exactly 0.
  (let [pst (neutral :head-flexion-deg 45.0)
        p (pose/solve-pose body pst)
        ;; three segments since 2026-09-07, so the head's moment is a SUM over the
        ;; complex rather than one segment's lever — and the three are no longer
        ;; collinear, which is exactly what the split was for
        necks (pose/segments-on p segment/cervical-bases)
        w (pose/segment-weights body p)
        lever (pose/anterior-lever (get-in p [:joints :l5s1])
                                   (pose/seg-at p "head"))
        got (:moment-nm (load/lumbosacral-moment body pst))]
    (is (> lever 0.10) (str "the premise: the skull's CoM is anterior of L5/S1, got " lever))
    (is (math/nearly= (reduce + 0.0
                              (for [s necks]
                                (* (get w (:name s))
                                   (pose/anterior-lever (get-in p [:joints :l5s1]) s))))
                      got 1e-9)
        (str "and the whole moment is those levers, got " got))
    (is (> got 6.0) (str "which is not zero, and was: " got))))

(deftest test-the-arms-load-the-lumbar-spine-and-resting-them-unloads-it
  ;; The arms hang from the girdle and the girdle is carried by the thorax, so the
  ;; lumbar spine holds up every gram of them. `lumbosacral-moment` had no arm term
  ;; at all and never read `:arms-supported`, while `spine/above-fraction` — the
  ;; same model, one file over — counted the arms at every trunk level.
  (let [at (fn [& {:as over}] (:moment-nm (load/lumbosacral-moment body (merge (neutral) over))))
        hanging (at :trunk-flexion-deg 20.0)
        reaching (at :trunk-flexion-deg 20.0 :shoulder-flexion-deg 60.0 :elbow-flexion-deg 0.0)
        rested (at :trunk-flexion-deg 20.0 :shoulder-flexion-deg 60.0 :elbow-flexion-deg 0.0
                   :arms-supported true)]
    (is (> reaching hanging)
        (str "reaching forward must load the lumbar spine more than hanging: "
             reaching " vs " hanging))
    (is (< rested reaching)
        (str "and resting the forearms must take some of it off: " rested " vs " reaching))))

(deftest test-bone-lines-cover-every-segment
  (let [p (pose/solve-pose body (posture/posture-from-workstation posture/laptop-on-lap))
        lines (pose/bone-lines p)]
    (is (= (count (:segments p)) (count lines)))
    (is (= (set (map :name (:segments p))) (set (map :name lines))))
    (doseq [{:keys [from to]} lines]
      (is (> (math/vlen (math/v- to from)) 0.0) "no zero-length bone"))))

;; --- the moment vector and the scalar beside it must agree -------------------

(deftest the-moment-vector-agrees-with-the-scalar-in-the-sagittal-plane
  ;; `gravitational-moment-vec` negates the raw cross product so that its Z
  ;; component IS `gravitational-moment` — the moment the muscles must generate,
  ;; which is the opposite of the moment gravity applies. Returning the raw
  ;; product would give a sign error with no symptom but a wrong answer.
  (doseq [posture [(neutral)
                   (neutral :trunk-flexion-deg 25.0)
                   (neutral :shoulder-flexion-deg 40.0 :elbow-flexion-deg 90.0)
                   (neutral :head-flexion-deg 45.0 :trunk-flexion-deg 15.0)]]
    (let [p (pose/solve-pose body posture)
          w (pose/segment-weights body p)
          arm (for [n ["upper_arm/left" "forearm/left" "hand/left"]] [(pose/seg-at p n) (get w n)])
          shoulder (get-in p [:joints :shoulder/left])]
      (is (math/nearly= (pose/gravitational-moment shoulder arm)
                        (nth (pose/gravitational-moment-vec shoulder arm) 2)
                        1e-9)
          "Mz must equal the scalar, sign included"))))

(deftest a-sagittal-posture-has-no-frontal-moment
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 30.0 :shoulder-flexion-deg 45.0
                                         :elbow-flexion-deg 90.0))
        w (pose/segment-weights body p)
        arm (for [n ["upper_arm/left" "forearm/left" "hand/left"]] [(pose/seg-at p n) (get w n)])
        v (pose/gravitational-moment-vec (get-in p [:joints :shoulder/left]) arm)]
    (is (math/nearly= 0.0 (nth v 0) 1e-12) "no frontal moment in the sagittal plane")))

(deftest abduction-produces-a-frontal-moment-that-grows
  (let [fr (fn [ab]
             (let [p (pose/solve-pose body (neutral :shoulder-flexion-deg 15.0
                                                    :elbow-flexion-deg 90.0
                                                    :shoulder-abduction-deg ab))
                   w (pose/segment-weights body p)
                   arm (for [n ["upper_arm/left" "forearm/left" "hand/left"]] [(pose/seg-at p n) (get w n)])]
               (Math/abs (nth (pose/gravitational-moment-vec (get-in p [:joints :shoulder/left]) arm) 0))))
        xs (mapv fr [0.0 20.0 40.0 60.0])]
    (is (math/nearly= 0.0 (first xs) 1e-12))
    (is (every? (fn [[a b]] (< a b)) (partition 2 1 xs))
        (str "the frontal moment has to grow with abduction: " xs))))

;; --- the cervical spine has joints now (2026-09-07) --------------------------
;;
;; `head_neck` was one rigid segment from C7 to the vertex, so the five cervical
;; levels `spine` reported all shared one orientation, no suboccipital could be
;; written down, and a forward-head posture — lower cervical flexion WITH upper
;; cervical extension — could not be represented at any angle. These pin what the
;; split did and, more carefully, what it must not have changed.

(deftest the-partition-sums-to-one
  ;; THE INVARIANT THE POSTURE INPUT RESTS ON. `:head-flexion-deg` has always meant
  ;; the head's angle relative to the trunk, and three coefficients that summed to
  ;; anything else would silently redefine it — an existing posture map, an existing
  ;; browser control and three reference workstations would all keep their numbers
  ;; and change their meaning.
  (let [{:keys [lower upper head]} pose/cervical-partition]
    (is (math/nearly= 1.0 (+ lower upper head) 1e-12)
        (str "the three shares must be the whole angle: " pose/cervical-partition))
    (is (> lower 1.0)
        (str "the lower cervical column flexes MORE than the head does: " lower))
    (is (pos? upper) (str "C2/C3 flexes with it: " upper))
    (is (neg? head)
        (str "and the skull EXTENDS on the atlas, which is the forward-head "
             "shape and the reason for the split: " head))))

(deftest the-head-still-tilts-by-trunk-plus-head-flexion
  ;; The same invariant asked of the placed chain rather than of the coefficients,
  ;; and it has to hold to the BIT rather than to a tolerance: `load/cervical-load`
  ;; reads this number and it is the one validated quantity in this library. An
  ;; accumulated sum instead of a set one puts a head asked for 63.5 deg at
  ;; 63.49999999999999, which is why `cervical-chain` sets it.
  (doseq [[h t] [[0.0 0.0] [43.5 20.0] [60.0 0.0] [0.0 60.0] [-15.0 25.0] [30.0 30.0]]]
    (let [p (pose/solve-pose body (neutral :head-flexion-deg h :trunk-flexion-deg t))]
      (is (= (+ t h) (:tilt-deg (pose/seg-at p "head")))
          (str "head " h " trunk " t " must place the skull at " (+ t h)))
      (is (= (+ t h) (load/head-tilt-from-vertical-deg p))
          "and that is what the cervical load is handed"))))

(deftest a-forward-head-posture-flexes-the-column-and-extends-the-skull-on-it
  ;; THE SHAPE THE SPLIT EXISTS FOR, and the one a single block could not make.
  ;; Anatomically a forward head is lower cervical flexion with upper cervical
  ;; extension — the chin tucks under while the head tips back to keep the eyes
  ;; level. Measured at the laptop-on-lap head angle of 43.5 deg on a 20 deg trunk:
  ;; the column reaches 63.95 and 69.97 deg from vertical while the skull sits at
  ;; 63.50, so the occiput is 6.47 deg extended on the atlas.
  (let [p (pose/solve-pose body (neutral :head-flexion-deg 43.5 :trunk-flexion-deg 20.0))
        tilt #(:tilt-deg (pose/seg-at p %))]
    (is (> (tilt "lower_cervical") (tilt "head"))
        (str "the lower cervical column is flexed PAST the skull: "
             (tilt "lower_cervical") " vs " (tilt "head")))
    (is (> (tilt "upper_cervical") (tilt "lower_cervical"))
        "and C2/C3 adds to it")
    (is (> (- (tilt "upper_cervical") (tilt "head")) 5.0)
        (str "so the skull is extended on the atlas by "
             (- (tilt "upper_cervical") (tilt "head")) " deg")))
  ;; and with no head flexion the three are collinear, which is the control: the
  ;; split changes nothing about a posture that does not flex the neck
  (let [p (pose/solve-pose body (neutral :trunk-flexion-deg 45.0))]
    (doseq [b segment/cervical-bases]
      (is (math/nearly= 45.0 (:tilt-deg (pose/seg-at p b)) 1e-12)
          (str b " must be collinear with the trunk when the head is not flexed")))))

(deftest the-derived-atlanto-occipital-angle-stays-inside-its-published-range
  ;; The partition is a fixed proportion, so it can ask a joint for motion the joint
  ;; does not have. Bogduk & Mercer 2000 put atlanto-occipital flexion-extension at
  ;; 14-15 deg (`pose/atlanto-occipital-rom-deg`); at the model's largest head-flexion
  ;; input, 60 deg, the derived extension is 8.9 deg. This is the check that would
  ;; fail if the reversal share were raised past what the joint can do.
  (doseq [h [0.0 15.0 30.0 45.0 60.0 -30.0 -60.0]]
    (let [p (pose/solve-pose body (neutral :head-flexion-deg h :trunk-flexion-deg 20.0))
          ao (- (:tilt-deg (pose/seg-at p "head"))
                (:tilt-deg (pose/seg-at p "upper_cervical")))]
      (is (<= (Math/abs ao) pose/atlanto-occipital-rom-deg)
          (str "head " h " deg asks the atlanto-occipital joint for " ao
               " deg, and it has " pose/atlanto-occipital-rom-deg))))
  ;; the evidence floor: a partition that asked for NOTHING would pass the above
  (let [p (pose/solve-pose body (neutral :head-flexion-deg 60.0))
        ao (- (:tilt-deg (pose/seg-at p "head"))
              (:tilt-deg (pose/seg-at p "upper_cervical")))]
    (is (> (Math/abs ao) 5.0)
        (str "and it does ask for something: " ao " deg at 60 deg of head flexion"))))

;; --- where the world holds the body ------------------------------------------

(deftest the-standing-chain-is-rooted-at-the-feet-and-the-seated-one-at-the-pelvis
  ;; WHAT THE ROOT IS FOR. Until 2026-09-10 this chain was rooted at L5/S1, a
  ;; point nothing in the world holds, and the visible cost was that tilting the
  ;; pelvis moved the FLOOR: at Cho's standing lordosis the soles travelled 11.7 cm
  ;; posteriorly and 5.0 cm upward, which is the model saying a person who arches
  ;; their back slides their feet backwards and lifts off the ground.
  (let [pst {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
             :elbow-flexion-deg 0.0 :support :standing
             :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0}
        flat (pose/solve-pose body pst)
        tilted (pose/solve-pose body (assoc pst :lumbar-lordosis-deg 46.5))]
    (is (= :mid-ankle (:landmark (:root flat))) "standing roots at the feet")
    ;; the root landmark is AT the origin, which is what `rooted-at` means
    (doseq [[nm p] [["flat" flat] ["tilted" tilted]]]
      (is (math/nearly= 0.0 (first (pose/landmark-point p :mid-ankle)) 1e-15)
          (str nm ": the mid-ankle sits at the origin in x"))
      (is (math/nearly= 0.0 (second (pose/landmark-point p :mid-ankle)) 1e-15)
          (str nm ": and in y")))
    ;; the floor and the base of support are the same in both, to the bit
    (is (= (pose/ground-y flat) (pose/ground-y tilted))
        "the ground does not move when the pelvis rotates")
    ;; ⚠ the base of support is compared to a tolerance rather than with `=`
    ;; since 2026-09-11. It used to be bit-identical, and that was luck: the two
    ;; chains are translated by different amounts before subtraction, so the
    ;; equality is `(a+s) - (b+s)` against `(a+t) - (b+t)`, which is an identity in
    ;; the reals and not in a double. When the pelvis stopped rotating by the whole
    ;; lordosis the two rootings landed on different bits and the back of the foot
    ;; came out -0.06459999999999999 against -0.0646. A test that reads a 1-ulp
    ;; difference as a foot that moved is asserting the arithmetic, not the model.
    (doseq [k [:back :front]]
      (is (math/nearly= (k (pose/base-of-support flat))
                        (k (pose/base-of-support tilted))
                        1e-12)
          (str "and neither does the base of support, at " k)))
    ;; the control: something DID move, or this test is asserting a no-op
    (is (> (- (first (get-in tilted [:joints :l5s1]))
              (first (get-in flat [:joints :l5s1])))
           0.05)
        (str "the sacrum travelled anteriorly instead: "
             (- (first (get-in tilted [:joints :l5s1]))
                (first (get-in flat [:joints :l5s1]))) " m"))
    ;; and it travelled LESS than it used to, which is the 2026-09-11 change: the
    ;; pelvis turns 27.25 deg for Cho's 46.5 rather than 46.5
    (is (math/nearly= 0.07394004279599829
                      (- (first (get-in tilted [:joints :l5s1]))
                         (first (get-in flat [:joints :l5s1])))
                      1e-12)
        "7.39 cm, where the whole-lordosis pelvis carried it 11.7 cm"))
  ;; seated, the seat takes the trunk through the ischial tuberosities, and this
  ;; model's stand-in for them is the base of the pelvis
  (let [p (pose/solve-pose body (posture/seated-posture))]
    (is (= :pelvis-base (:landmark (:root p))))
    (is (= [0.0 0.0 0.0] (mapv double (get-in p [:joints :pelvis-base])))
        "the seated chain sits on its own pelvis base"))
  ;; and a support mode with no landmark is REFUSED rather than rooted at L5/S1 by
  ;; default — an unrecognised support and a chain nothing holds must not be the
  ;; same value
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (pose/support-landmark {:support :hanging-from-a-bar}))
      "an unknown support mode is refused"))

(deftest re-rooting-the-chain-moves-no-moment
  ;; THE FINDING OF 2026-09-10, and it is a negative one. Every joint angle in
  ;; this model is measured from the WORLD vertical, so the chain's shape is
  ;; complete before anything is anchored; changing the anchor is a rigid
  ;; translation. A moment is a sum of `weight x (x_com - x_joint)` and both x's
  ;; move together, so no moment, force, lever or line of gravity can change.
  ;;
  ;; WHY IT IS WORTH A TEST RATHER THAN A SENTENCE. `spine/standing-sitting-
  ;; decomposition` attributed its dominant term — 384 N of a 334 N difference —
  ;; to the chain being rooted at L5/S1, and `pose/lumbar-chord-tilt-deg` said the
  ;; same. Both were wrong, and this is the measurement that says so: root the
  ;; same standing posture three different ways and the lumbosacral moment does
  ;; not move.
  (let [p (pose/solve-pose body posture/quiet-standing)
        rootings (into {} (for [lm [:l5s1 :pelvis-base :mid-ankle]]
                            [lm (pose/rooted-at p lm)]))
        w (pose/segment-weights body p)
        moment-about (fn [pd joint bases]
                       (pose/gravitational-moment
                        (get-in pd [:joints joint])
                        (for [s (pose/segments-on pd bases)] [s (get w (:name s))])))
        trunk ["lumbar" "thorax" "head" "upper_cervical" "lower_cervical"
               "upper_arm" "forearm" "hand"]
        reference (moment-about (:l5s1 rootings) :l5s1 trunk)]
    ;; the control FIRST: the three rootings really are different chains in space,
    ;; or the invariance below is asserting nothing
    (is (> (math/abs* (- (first (get-in (:l5s1 rootings) [:joints :l5s1]))
                         (first (get-in (:mid-ankle rootings) [:joints :l5s1]))))
           0.10)
        "the three rootings put L5/S1 in visibly different places")
    (doseq [[lm pd] rootings]
      ;; the translation is rigid: every point moved by the same vector
      (let [d (:translation (:root pd))]
        (doseq [k [:l5s1 :c7 :ankle/left :toe/right]]
          (is (math/nearly= 0.0
                            (math/vlen (math/v- (math/v- (get-in pd [:joints k])
                                                         (get-in p [:joints k]))
                                                d))
                            1e-15)
              (str lm ": " k " moved by the root translation and by nothing else"))))
      ;; and the moment it was blamed on did not move
      (is (math/nearly= reference (moment-about pd :l5s1 trunk) 1e-12)
          (str lm ": the lumbosacral moment is " (moment-about pd :l5s1 trunk)
               " N·m, and rooting cannot change it"))
      ;; nor did the line of gravity, which is a difference of two x's
      (is (math/nearly= (:ahead-of-ankle-m (pose/line-of-gravity body p))
                        (:ahead-of-ankle-m (pose/line-of-gravity body pd))
                        1e-12)
          (str lm ": the line of gravity ahead of the ankle is unchanged")))
    ;; the size of the thing rooting cannot touch, pinned so a reader does not
    ;; have to take `it is not the root` on trust. 22.51 N·m on a 70 kg / 1.70 m
    ;; body; `spine`'s 21.41 N·m is the same quantity on Wilke's smaller subject
    ;; ⚠ 22.512908211478976 until 2026-09-11. The moment is what the lumbar chord's
    ;; tilt puts on L5/S1, and the chord stopped following the pelvis when the
    ;; pelvis stopped taking the whole lordosis: 0.90 N·m on a 70 kg / 1.70 m body,
    ;; against 22.51. The invariance being asserted here is unaffected by the size
    ;; — re-rooting is a rigid translation whatever the moment is — but pinning it
    ;; means the day it moves, this says so.
    (is (math/nearly= 0.901314694482678 reference 1e-9)
        (str "the standing lumbosacral moment is " reference
             " N·m under every rooting"))
    ;; and it is the SHIPPED quantity, not a hand-assembled lookalike: the bases
    ;; above are the ones `load` carries at L5/S1, checked rather than trusted
    (is (math/nearly= reference
                      (:moment-nm (load/lumbosacral-moment body posture/quiet-standing))
                      1e-12)
        "the segments summed here are the ones load/lumbosacral-moment sums")))

(deftest the-line-of-gravity-splits-into-three-travels-that-sum-to-it
  ;; WHAT THE PREVIOUS AGENT HAD TO BREAK THE MODEL TO MEASURE. On 2026-09-09 the
  ;; split between `the pelvis path` and `the chord path` was obtained by editing
  ;; `lumbar-chord-tilt-deg` to ignore the pelvic tilt, running the model and
  ;; reverting — a counterfactual that could not ship and could not be tested.
  ;; It is an identity over the placed chain, so it needs no counterfactual: the
  ;; line of gravity is the sacrum's travel over the feet, plus the trunk's lean
  ;; over the sacrum, plus what is below L5/S1 about its own ankles.
  (doseq [p [posture/standing-neutral posture/quiet-standing-lumbar-neutral
             posture/quiet-standing posture/deep-squat]]
    (let [g (pose/line-of-gravity body (pose/solve-pose body p))]
      (is (< (math/abs* (:residual-m g)) 1e-12)
          (str (:name p) ": the three travels must sum to the line of gravity, "
               "residual " (:residual-m g) " m"))))
  ;; and the sizes, pinned per term rather than only as a sum — a residual cannot
  ;; catch a term being mis-priced, because the terms telescope
  (let [neutral (pose/line-of-gravity body (pose/solve-pose body posture/quiet-standing-lumbar-neutral))
        cho (pose/line-of-gravity body (pose/solve-pose body posture/quiet-standing))
        d (fn [k] (- (k cho) (k neutral)))]
    (is (math/nearly= 0.03703697189273054 (:ahead-of-ankle-m neutral) 1e-9)
        "lumbar-neutral quiet standing: 3.70 cm, inside the measured 2-6 cm")
    (is (math/nearly= 0.08199176312474336 (:ahead-of-ankle-m cho) 1e-9)
        (str "Cho's standing lordosis: 8.20 cm, still outside it. It was 13.97 cm "
             "until 2026-09-11, when the pelvis stopped spending the whole "
             "lordosis"))
    ;; EACH TERM PINNED ABSOLUTELY, IN BOTH POSTURES, AND NOT ONLY AS A DIFFERENCE.
    ;; Measured while writing this: moving a constant 1 cm from the trunk term into
    ;; the sacrum term produced NO FAILURE at all. The residual could not see it —
    ;; the sum is unchanged by construction — and the difference pins below could
    ;; not either, because a constant offset appears in both postures and cancels
    ;; when they are subtracted. Two checks that both look like per-term checks and
    ;; are both blind to the same defect. These are the ones that see it.
    (doseq [[nm g expected]
            [["lumbar-neutral" neutral {:sacrum-over-the-feet-m 0.019536412946749866
                                        :trunk-over-the-sacrum-m 0.0012397936122427468
                                        :everything-below-l5s1-m 0.016260765333737916}]
             ["Cho's lordosis" cho {:sacrum-over-the-feet-m 0.05916827588540495
                                    :trunk-over-the-sacrum-m 0.0013129788670846216
                                    :everything-below-l5s1-m 0.02151050837225379}]]]
      (doseq [[k v] expected]
        (is (math/nearly= v (k g) 1e-9)
            (str nm ": " k " is " (k g) " m, pinned at " v))))
    ;; THE 4.50 cm, SPLIT THREE WAYS AND EACH ONE PINNED. It was 10.27 cm until
    ;; 2026-09-11, and the three terms did not shrink in the same proportion — the
    ;; trunk term collapsed by a factor of 431 and the other two by about 1.6.
    ;; That asymmetry is the whole shape of the change: the pelvis still rotates
    ;; and still carries the sacrum forward, and the lumbar chord has stopped
    ;; carrying the trunk out in front of it.
    (is (math/nearly= 0.044954791232012827 (d :ahead-of-ankle-m) 1e-9))
    (is (math/nearly= 0.03963186293865509 (d :sacrum-over-the-feet-m) 1e-9)
        "3.96 cm of it is the sacrum travelling forward over the feet, was 6.28")
    (is (math/nearly= 7.318525484187473e-5 (d :trunk-over-the-sacrum-m) 1e-12)
        (str "0.007 cm is the trunk leaning forward over the sacrum, was 3.16 cm "
             "— the chord tilts 0.052 deg now where it tilted 23.25"))
    (is (math/nearly= 0.005249743038515876 (d :everything-below-l5s1-m) 1e-9)
        "and 0.52 cm is the pelvis and legs moving about their own ankles, was 0.83")
    ;; the term that collapsed, stated as the ratio so the claim above is checked
    (is (> (/ 0.031555602822798755 (d :trunk-over-the-sacrum-m)) 400.0)
        (str "the trunk-over-the-sacrum travel fell by a factor of "
             (/ 0.031555602822798755 (d :trunk-over-the-sacrum-m))
             ", which is the chord no longer following the pelvis"))
    ;; the mechanism, derived rather than pinned: the sacrum's own travel is
    ;; L_pelvis x sin(tilt), and the term is that discounted by the fraction of
    ;; body weight above L5/S1
    ;; ⚠ IT IS `sin(pelvic rotation)`, NOT `sin(lordosis)`. This read the lordosis
    ;; until 2026-09-11, which was right only while the two were the same number.
    (let [travel (* (:length-m (segment/seg body "pelvis"))
                    (Math/sin (math/radians
                               (pose/pelvic-rotation-deg
                                (:lumbar-lordosis-deg posture/quiet-standing)))))]
      (is (< (d :sacrum-over-the-feet-m) travel)
          (str "the sacrum travels " travel " m and the term is less, because the "
               "legs are below L5/S1 and do not follow it"))
      (is (> (d :sacrum-over-the-feet-m) (* 0.5 travel))
          "but most of the body is above L5/S1, so most of the travel counts"))))

(deftest a-posture-carrying-the-retired-pelvic-tilt-key-is-refused
  ;; THE RENAME'S SAFETY NET. `:pelvic-tilt-deg` became `:lumbar-lordosis-deg` on
  ;; 2026-09-11, because the key holds a Cobb angle and the pelvis stopped turning
  ;; by all of it. `solve-pose` reads the new key through an `(or … 0.0)`, so a
  ;; posture written before the rename would otherwise arrive with NO lordosis and
  ;; be solved as a straight lumbar spine — a measured 46.5 deg turning into a
  ;; silent zero, which is the exact failure `posture/lordosis-provenance` exists
  ;; to prevent and which this repo has now recorded three times.
  (let [pst {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
             :elbow-flexion-deg 0.0 :support :standing
             :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0}]
    (is (some? (pose/solve-pose body (assoc pst :lumbar-lordosis-deg 46.5)))
        "the new key solves")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (pose/solve-pose body (assoc pst :pelvic-tilt-deg 46.5)))
        "and the retired one is refused rather than ignored")
    ;; the control that the refusal is about the KEY and not about the value: a
    ;; posture carrying the old key set to zero is refused too, because a reader
    ;; using the old name has the wrong model of what the number does
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (pose/solve-pose body (assoc pst :pelvic-tilt-deg 0.0)))
        "including when its value would have made no difference")))
