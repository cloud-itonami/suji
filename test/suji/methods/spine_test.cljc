(ns suji.methods.spine-test
  "Level-by-level spinal compression: that the muscle term is there and dominant,
  that the profile is ordered the way the physics implies, and that the model says
  where it disagrees with the leg this actor actually validated."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [kotoba.lang.text :as str]
            [suji.methods.attachment :as attachment]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.spine :as spine]))

(def ^:private body (segment/build-body 70.0 1.70))

(defn- run [posture]
  (let [l (load/solve-posture-loads body posture)
        t (muscle/solve-muscle-tensions body posture l)]
    {:loads l :tensions t :rows (spine/profile body posture t)}))

(defn- refusal-data
  "The ex-data of whatever `f` threw, or nil if it returned."
  [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) e (ex-data e))))

(def ^:private lap (posture/posture-from-workstation posture/laptop-on-lap))
(def ^:private monitor (posture/posture-from-workstation posture/external-monitor-eye-level))

(deftest every-level-is-reported-once-with-a-stress
  (let [rows (:rows (run lap))]
    (is (= (count spine/levels) (count rows)))
    (is (= (mapv :name spine/levels) (mapv :name rows)))
    (doseq [r rows]
      (is (number? (:stress-mpa r)))
      (is (pos? (:disc-area-cm2 r)))
      (is (number? (:ligament-n r)) (str (:name r) ": the ligament term is reported"))
      (is (math/nearly= (:force-n r) (+ (:weight-n r) (:muscle-n r) (:ligament-n r)) 1e-9)
          (str (:name r)
               ": force must be weight + muscle + ligament, with nothing else hidden in it")))))

(deftest the-tissue-term-is-split-because-the-name-stopped-being-true
  ;; It was one number called `:muscle-n`. Once the posterior ligamentous system
  ;; landed that name was false: measured at 60 degrees of trunk flexion the term
  ;; was 3,904 N of which 3,989 N came from the LIGAMENT — the muscles' net axial
  ;; contribution had gone negative and the ligament was pressing the joint
  ;; together on its own. A reader taking `:muscle-n` literally would read a spine
  ;; compressed by muscle where it is compressed by tissue, which is a different
  ;; statement about the posture and about what would change it.
  (let [deep (:rows (run (merge lap {:trunk-flexion-deg 60.0 :arms-supported false})))
        l5s1 (first (filter #(= "L5/S1" (:name %)) deep))]
    (is (pos? (:ligament-n l5s1)) "the ligament presses the lumbar spine in deep flexion")
    (is (> (:ligament-n l5s1) (math/abs* (:muscle-n l5s1)))
        (str "and does more of it than the muscles: " (select-keys l5s1 [:muscle-n :ligament-n])))
    ;; and in a moderate posture the muscles are still the answer
    (let [mild (first (filter #(= "L5/S1" (:name %)) (:rows (run lap))))]
      (is (> (:muscle-n mild) (:ligament-n mild))
          (str "in an ordinary posture the muscle term dominates: "
               (select-keys mild [:muscle-n :ligament-n]))))))

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
  ;; profile is not, and it disagrees with it. A consumer must not be able to read
  ;; the profile as if it inherited the validation, so the disagreement is computed
  ;; rather than remembered.
  ;;
  ;; ⚠ THE REASON WAS REWRITTEN ON 2026-09-07 AND THE BOUND WAS TIGHTENED, because
  ;; the old ones were not measuring this. The comment said the two disagree `by
  ;; about a factor of two ... because it uses the muscle's geometric moment arm
  ;; rather than a fitted effective lever`, and the assertion was `1.5 < ratio <
  ;; 4.0`. But C7/T1 was being computed by a half-space test on HEIGHT, so its
  ;; muscle term included 62.4 N per side of anterior deltoid and 30.7 N per side
  ;; of wrist extensor — 186 N of the 588 N — none of which is transmitted through
  ;; a neck. The moment arm was not the whole of the difference and the ratio was
  ;; not measuring the quantity the sentence named.
  ;;
  ;; Measured at laptop-on-lap, 70 kg / 1.70 m: 651.12 N by level before the
  ;; repair, 464.92 N after, against an unchanged 273.62 N lumped — the ratio fell
  ;; 2.380 -> 1.699. The old 1.5-4.0 band admitted BOTH, which is why it never
  ;; reported the defect. The bound is now tight enough to have to be re-measured
  ;; if the muscle set moves again, and that is the point of it.
  ;;
  ;; The model moving CLOSER to the validated leg is not evidence that it got
  ;; better at what the validated leg measures. It is the arithmetic consequence of
  ;; removing forces that were never in the neck; agreement bought by deleting a
  ;; defect elsewhere is not a validation, and `:validated :lumped` still says
  ;; which side carries one.
  (let [{:keys [loads tensions]} (run lap)
        x (spine/cervical-cross-check body lap tensions (:cervical loads))]
    (is (= :lumped (:validated x)))
    (is (pos? (:ratio x)))
    (is (> (:ratio x) 1.0)
        (str "the two paths really do still disagree, and by how much is the point: " x))
    ;; ⚠ RE-MEASURED AGAIN ON 2026-09-07, when the muscles that reach the SKULL were
    ;; added (semispinalis capitis, splenius capitis, sternocleidomastoid). The
    ;; level side moved 464.92 -> 470.74 N against an unchanged 273.62 N lumped, so
    ;; the ratio went 1.699 -> 1.720. IT MOVED AWAY FROM 1, not toward it, and
    ;; neither direction would have been evidence: the lumped side is the one
    ;; Hansraj anchors, and a profile that agreed with it exactly would still be
    ;; unvalidated. What moved is the C7/T1 muscle term, 401.56 -> 407.38 N, and it
    ;; moved only a little because the new muscles took a share of the SAME cervical
    ;; extensor moment rather than adding a second one. The upper levels are where
    ;; the change is; see `the-top-cervical-level-is-crossed-by-what-reaches-the-skull`.
    ;; ⚠ RE-MEASURED A THIRD TIME ON 2026-09-07, when `head_neck` was split into
    ;; `lower_cervical` + `upper_cervical` + `head`. The level side moved
    ;; 470.740 -> 470.299 N against an UNCHANGED 273.619 N lumped, so the ratio
    ;; went 1.7204 -> 1.7188 — a move of 0.09%, and it is small for a reason worth
    ;; stating: the split preserved the head's tilt from vertical exactly, so the
    ;; Hansraj-calibrated moment the cervical extensors are sharing did not move,
    ;; and the muscle forces at C7 moved only by however much their moment arms
    ;; did. What DID move is the weight term (24.810 -> 24.420 N), because the neck
    ;; now bends inside itself and the mass above C7 sits at a slightly different
    ;; angle to the level's axis.
    ;;
    ;; IT MOVED TOWARD 1 BY A HAIR AND THAT IS NOT A VALIDATION. The lumped side is
    ;; the one Hansraj anchors; a profile that agreed with it exactly would still be
    ;; unvalidated, and this repo has recorded twice already that agreement bought
    ;; by changing something else is not evidence.
    ;; ⚠ RE-MEASURED A FOURTH TIME ON 2026-09-08, when the neck became a COUPLED
    ;; group — C7 and the atlanto-occipital joint solved together by
    ;; `recruit/solve` instead of C7 first and the joint above it handed the
    ;; leftover. The level side moved 470.299 -> 466.143 N against an UNCHANGED
    ;; 273.619 N lumped, so the ratio went 1.7188 -> 1.7036, a move of 0.9%.
    ;;
    ;; WHY IT MOVED AT ALL, since the cervical extensor moment at C7 is the same
    ;; number: the SHARE of it changed. Semispinalis and splenius capitis have a
    ;; larger moment arm about the atlanto-occipital joint than about C7, so once
    ;; that joint is a constraint the optimum stops loading them to hold C7 —
    ;; splenius fell 87.29 -> 3.81 N and semispinalis 125.25 -> 101.56 N — and
    ;; puts the work on `cervical_extensors`, whose 138.32 -> 245.48 N is applied
    ;; at a SHORTER arm and therefore costs more force. The compression at C7/T1
    ;; is the sum of those forces, and it came out slightly lower.
    ;;
    ;; IT MOVED TOWARD 1 AGAIN, BY 0.9%, AND THAT IS STILL NOT A VALIDATION. The
    ;; lumped side is the one Hansraj anchors; a profile that agreed with it
    ;; exactly would still be unvalidated. This repo has now recorded that four
    ;; times, and the fourth is no more evidence than the first three.
    (is (math/nearly= 1.7036 (:ratio x) 0.001)
        (str "today's disagreement, measured rather than banded: " x))
    (is (math/nearly= 466.143 (:level-force-n x) 0.01)
        (str "the C7/T1 force after the coupled neck solve: " x))
    (is (math/nearly= 273.62 (:lumped-force-n x) 0.01)
        (str "the lumped side did not move — it does not go through `recruit` at "
             "all, and that is checked here rather than assumed: " x))))

;; --- which muscles load a level ----------------------------------------------

(def ^:private lap-pose (pose/solve-pose body lap))

(defn- level-named [n] (first (filter #(= n (:name %)) spine/levels)))

(defn- height-either-side?
  "The OLD predicate, kept as a control: are this muscle's two attachment points on
  opposite sides of the level's height, measured along the spine's local axis
  there? That is exactly what `crosses?` used to ask, so a muscle for which this is
  TRUE and `levels-crossed` is empty is a muscle the repair actually changed."
  [pose-data level muscle]
  (let [{:keys [point axis]} (spine/level-point pose-data level)
        {:keys [origin insertion]} (attachment/line-of-action pose-data 1.70 muscle)
        h #(math/vdot (math/v- % point) axis)]
    (neg? (* (h origin) (h insertion)))))

(deftest a-muscle-loads-a-level-by-its-path-not-by-its-height
  ;; THE FINDING. `crosses?` projected both attachment points onto the spine's
  ;; axis at the level and asked whether they straddled it, so anything whose two
  ;; ends sat at different heights counted — whether or not its line of force went
  ;; anywhere near a spine.
  ;;
  ;; Measured 2026-09-07 at laptop-on-lap, 70 kg / 1.70 m, before the repair: the
  ;; C3/C4 muscle term was 61.44 N and ALL of it was `wrist_extensors/left` and
  ;; `wrist_extensors/right` at 30.72 N each; C5/C6 and C4/C5 were 64% wrist
  ;; extensor. At 60 degrees of trunk flexion `vasti` and `tibialis_anterior`
  ;; appeared at L1/L2, and `vasti` carried 117.7 N of the 186.0 N at C3/C4.
  ;;
  ;; The control is the point: the wrist extensor's two ends really ARE on opposite
  ;; sides of C3/C4's height (−0.110 m and +0.152 m along the local axis), so the
  ;; old test's condition still holds and the new answer is nonetheless empty. A
  ;; test that only asserted `crosses nothing` could be passed by a rule that had
  ;; simply stopped answering.
  (let [c34 (level-named "C3/C4")
        we (attachment/instance "wrist_extensors/left")]
    (is (some? we))
    (is (height-either-side? lap-pose c34 we)
        "the wrist extensor's ends do straddle C3/C4's height, which is what the old rule asked")
    (is (= [] (spine/levels-crossed lap-pose we))
        "and it crosses no level at all: its force goes to the forearm")
    ;; the same muscle, asked about every level, so this is not one lucky level
    (doseq [m [(attachment/instance "wrist_extensors/left")
               (attachment/instance "wrist_extensors/right")
               (attachment/instance "vasti/left")
               (attachment/instance "tibialis_anterior/left")]]
      (is (= [] (spine/levels-crossed lap-pose m))
          (str (:name m) " loads no intervertebral level"))))
  ;; and at 60 degrees, where the leg muscles were reaching the lumbar spine
  (let [deep (pose/solve-pose body (merge lap {:trunk-flexion-deg 60.0 :arms-supported false}))
        l12 (level-named "L1/L2")
        vasti (attachment/instance "vasti/left")]
    (is (height-either-side? deep l12 vasti)
        "at 60 degrees the vastus's ends straddle L1/L2's height too")
    (is (= [] (spine/levels-crossed deep vasti))
        "a vastus transmits its force to the tibia, not through a lumbar disc")))

(deftest a-cervical-level-can-only-be-crossed-by-something-attached-to-the-neck
  ;; The general form of the finding, swept over the whole muscle set and every
  ;; reference posture rather than over the four instances the defect happened to
  ;; produce. A cervical level cuts one of the three cervical segments, so the only
  ;; way for a muscle's two ends to fall in different pieces is for one of them to
  ;; be on the neck or the skull, above the cut. Everything else in the body — both
  ;; arms, both legs, the pelvis, the whole trunk — reaches the root without ever
  ;; entering the neck.
  ;;
  ;; This is the claim the old rule violated, and it is derived: nothing here names
  ;; a muscle or a group.
  (doseq [p [lap monitor (merge lap {:trunk-flexion-deg 60.0 :arms-supported false})
             (assoc lap :shoulder-abduction-deg 45.0 :wrist-extension-deg 25.0)]]
    (let [pd (pose/solve-pose body p)]
      (doseq [m attachment/instances
              :let [cervical (filter #(= :cervical (:region %)) (spine/levels-crossed pd m))]
              :when (seq cervical)]
        (is (some #(contains? (set segment/cervical-bases) (:segment %))
                  [(:origin m) (:insertion m)])
            (str (:name m) " crosses " (mapv :name cervical)
                 " without attaching to the neck: " (select-keys m [:origin :insertion]))))))
  ;; the evidence floor: a sweep that admitted nothing would pass the above by
  ;; having nothing to check
  (is (<= 4 (count (filter #(seq (filter (fn [l] (= :cervical (:region l)))
                                         (spine/levels-crossed lap-pose %)))
                           attachment/instances)))
      "and some muscles do cross cervical levels, so the sweep is not empty"))

(deftest the-erector-spinae-still-crosses-the-lumbosacral-junction
  ;; THE CONTROL FOR THE OTHER DIRECTION. It is easy to write a crossing rule that
  ;; deletes everything, and L5/S1 is where such a rule shows: the erector spinae
  ;; originates on the PELVIS, at 0.15 of it, and inserts on the trunk at 0.25, and
  ;; L5/S1 is the joint between the two. The disc is between the muscle's ends and
  ;; the force presses it together.
  ;;
  ;; The model puts L5/S1 at exactly 0.0 of the trunk and hangs the pelvis at
  ;; exactly 0.0 of the trunk, so this is decided by a strict comparison rather than
  ;; by anatomy: a rule that placed the pelvis on the trunk's own side of that point
  ;; would lose the single largest muscle contribution in the whole profile —
  ;; 1,070.5 N of the 1,074.7 N at L5/S1, measured at laptop-on-lap — and would
  ;; still look like a working crossing rule everywhere else.
  (let [es (attachment/instance "erector_spinae")
        crossed (mapv :name (spine/levels-crossed lap-pose es))]
    (is (= "pelvis" (:segment (:origin es))) "it originates below the junction")
    (is (= "lumbar" (:segment (:insertion es))) "and inserts above it")
    (is (= ["L5/S1" "L4/L5" "L3/L4" "L2/L3"] crossed)
        (str "so it crosses every lumbar level below its insertion at 0.25: " crossed))
    (let [l5s1 (first (filter #(= "L5/S1" (:name %)) (:rows (run lap))))]
      (is (some #(= "erector_spinae" (first %)) (:muscle-crossing l5s1))
          (str "and it is in the row: " (:muscle-crossing l5s1)))))
  ;; the same shape for the arm chain, which hangs at the OTHER end of the trunk:
  ;; latissimus dorsi runs pelvis -> humerus and so crosses every lumbar level and
  ;; no cervical one, because the arm reaches the spine at the top of the thorax
  (let [ld (attachment/instance "latissimus_dorsi/left")]
    (is (= ["L5/S1" "L4/L5" "L3/L4" "L2/L3" "L1/L2"]
           (mapv :name (spine/levels-crossed lap-pose ld)))
        "the whole lumbar spine, and nothing cervical")))

(deftest the-crossing-rule-reads-the-attachments-and-not-the-name
  ;; DERIVED RATHER THAN TABULATED, demonstrated by giving the rule a muscle it has
  ;; never heard of. The same anonymous map crosses nothing when its two sites are
  ;; on the forearm and the hand, and four cervical levels when the same map's
  ;; sites are moved onto the trunk and the neck. No name is consulted, so there is
  ;; no list of spinal muscles to fall out of date, and adding a muscle or moving an
  ;; attachment changes the answer without this rule being edited.
  (let [arm {:origin {:segment "forearm" :along 0.10}
             :insertion {:segment "hand" :along 0.25}
             :side :left}
        ;; 0.20 of the old `head_neck` is 0.667 of `lower_cervical`, which spans
        ;; 0.00-0.30 of it — the same place on the same body, restated on the
        ;; segment the split put there
        spinal (assoc arm
                      :origin {:segment "thorax" :along 0.9538461538461539}
                      :insertion {:segment "lower_cervical" :along 0.667})]
    (is (= [] (mapv :name (spine/levels-crossed lap-pose arm))))
    (is (= ["C7/T1" "C6/C7" "C5/C6" "C4/C5"]
           (mapv :name (spine/levels-crossed lap-pose spinal)))
        "every level below the insertion at 0.667 of the lower cervical column")
    ;; and moving ONE number moves the answer: an insertion one level lower crosses
    ;; one level fewer
    (is (= ["C7/T1" "C6/C7" "C5/C6"]
           (mapv :name (spine/levels-crossed
                        lap-pose
                        (assoc spinal :insertion {:segment "lower_cervical" :along 0.5}))))
        "the answer follows the attachment, not the identity of the muscle")))

(deftest a-pose-that-does-not-say-what-it-connects-is-refused
  ;; The failure mode this whole repair is about, one layer down. The crossing rule
  ;; reads the skeleton's shape from `pose`'s `:attaches-to`. A pose that does not
  ;; carry it — an older `place`, a hand-built fixture — would make every walk reach
  ;; the root immediately, every site land on the proximal side, and every muscle
  ;; cross nothing. That is the same value a correct profile of a muscle-free body
  ;; would produce, so it has to be refused instead, and the refusal has to name the
  ;; reason rather than merely throw.
  (let [stripped (update lap-pose :segments #(mapv (fn [seg] (dissoc seg :attaches-to)) %))
        d (refusal-data #(spine/levels-crossed
                          stripped (attachment/instance "erector_spinae")))]
    (is (= :value-error (:type d))
        (str "a pose with no :attaches-to is refused as a value error: " (pr-str d)))
    (is (string? (:segment d)) "and the refusal names a segment it could not read")
    ;; the control: the SAME call on the same pose with the key present answers
    (is (seq (spine/levels-crossed lap-pose (attachment/instance "erector_spinae")))
        "while the unstripped pose answers normally"))
  ;; and a site on a bone this pose does not have is refused too, rather than being
  ;; quietly treated as a root
  (let [d (refusal-data #(spine/levels-crossed
                          lap-pose {:origin {:segment "tail" :along 0.5}
                                    :insertion {:segment "thorax" :along 0.5}}))]
    (is (= :value-error (:type d)) (str "an unknown bone is refused: " (pr-str d)))
    (is (= "tail" (:segment d)) "and the refusal names it")))

(deftest the-desk-takes-the-forearms-off-the-lumbar-spine
  ;; `:arms-supported` reached every other equilibrium in this model and did not
  ;; reach this one. `above-fraction` gave an arm 1.0 at every trunk level because
  ;; it hangs from the girdle, which is right *unless the forearm is lying on a
  ;; desk* — and `weight-above-n` was byte-identical in both support states, so a
  ;; forearm resting on a desk was still hanging in mid-air as far as the lumbar
  ;; spine was concerned. `load/body-carries?` is the one place that question is
  ;; answered and this was the last caller that did not ask it.
  ;;
  ;; Measured 2026-09-07 on a 70 kg / 1.70 m body at `laptop-on-desk`: L5/S1 weight
  ;; above falls 366.5454 N -> 336.4558 N, and `:force-n` with it, 660.4416 ->
  ;; 630.3521.
  (let [supported (posture/posture-from-workstation posture/laptop-on-desk)
        unsupported (assoc supported :arms-supported false)
        rows-of (fn [p] (:rows (run p)))
        w-at (fn [rows n] (:weight-n (first (filter #(= n (:name %)) rows))))
        sup (rows-of supported) uns (rows-of unsupported)]
    (is (true? (:arms-supported supported)) "this workstation does rest the arms")
    (is (math/nearly= 336.4558 (w-at sup "L5/S1") 0.001)
        (str "the desk holds the forearms: " (w-at sup "L5/S1")))
    (is (math/nearly= 366.5454 (w-at uns "L5/S1") 0.001)
        (str "and without it the body does: " (w-at uns "L5/S1")))
    ;; the drop is DERIVED, not typed: two forearms and two hands, projected on the
    ;; level axis. If it were anything else the flag would be moving the wrong mass.
    (let [pd (pose/solve-pose body supported)
          l5s1 (level-named "L5/S1")
          axis (:axis (spine/level-point pd l5s1))
          arms (* 2.0 (+ (segment/weight-n (segment/seg body "forearm"))
                         (segment/weight-n (segment/seg body "hand"))))]
      (is (math/nearly= (* arms (nth axis 1))
                        (- (w-at uns "L5/S1") (w-at sup "L5/S1"))
                        1e-9)
          "the mass the desk took is exactly two forearms and two hands"))
    ;; the same at every lumbar level, because the cut gives an arm 1.0 at all of
    ;; them, and NOT at any cervical level, because an arm never counted there
    (let [drop-at #(- (w-at uns %) (w-at sup %))
          lumbar (mapv drop-at ["L5/S1" "L4/L5" "L3/L4" "L2/L3" "L1/L2"])]
      (is (every? #(math/nearly= (first lumbar) % 1e-9) lumbar)
          (str "one drop at every lumbar level: " lumbar))
      (is (pos? (first lumbar)) "and it is a drop, not a nothing")
      (doseq [n ["C7/T1" "C6/C7" "C5/C6" "C4/C5" "C3/C4"]]
        (is (math/nearly= 0.0 (drop-at n) 1e-12)
            (str n " does not move: an arm hangs below every cervical level"))))
    ;; and an unsupported workstation is not touched by any of this
    (let [lap-sup (:rows (run (assoc lap :arms-supported true)))]
      (is (not (math/nearly= (w-at (:rows (run lap)) "L5/S1")
                             (w-at lap-sup "L5/S1") 1e-9))
          "the flag moves laptop-on-lap too when it is set; it is the flag and not the workstation"))))

(defn- row
  "A profile row as `attachment-steps` needs it: named, in a region, and carrying
  the muscles that cross it with the force each contributes."
  [name region crossing]
  {:name name :region region :muscle-crossing crossing
   :muscle-n (reduce + 0.0 (map second crossing))})

(deftest the-step-detector-names-the-muscle-that-stopped-crossing
  ;; A real muscle attaches over a range of vertebrae; this one attaches at a
  ;; point, so a level just past it loses the whole force at once.
  ;;
  ;; The detector asked whether `:muscle-n` was EXACTLY 0.0, which passive tension
  ;; made unreachable — see `the-profile-steps-and-the-detector-says-where`. What
  ;; replaced it is not a smaller threshold: `crosses?` is all-or-nothing, so the
  ;; model already knows WHICH muscles stop crossing, and the detector reports that
  ;; fact with the newtons attached. This exercises it on both answers.
  (let [with-step [(row "A" :lumbar [["m1" 100.0] ["m2" 20.0]])
                   (row "B" :lumbar [["m2" 20.0]])]
        ;; the discriminating control, and the reason this is not a magnitude test:
        ;; the same muscle set, and a 99% fall in the force it contributes. That is
        ;; the line of action swinging, not an attachment artefact, and a detector
        ;; tuned to a percentage would call it one.
        big-fall-same-muscles [(row "A" :lumbar [["m1" 100.0]])
                               (row "B" :lumbar [["m1" 1.0]])]
        ;; and the mirror: a whole muscle lost, but a small one. Still a step —
        ;; the artefact is losing the muscle, not losing a lot of newtons.
        small-loss [(row "A" :lumbar [["m1" 100.0] ["m2" 0.05]])
                    (row "B" :lumbar [["m1" 100.0]])]]
    (is (= [{:after "A" :at "B" :lost ["m1"] :lost-n 100.0
             :muscle-n-before 120.0 :muscle-n-after 20.0}]
           (spine/attachment-steps with-step)))
    (is (empty? (spine/attachment-steps big-fall-same-muscles))
        "a fall with no muscle lost is the line of action moving, not a step")
    (is (= ["m2"] (:lost (first (spine/attachment-steps small-loss))))
        "and a small whole muscle lost is still a step")
    (is (empty? (spine/attachment-steps [])))
    (is (empty? (spine/attachment-steps [(row "A" :lumbar [])])))
    ;; L1/L2 and C7/T1 are adjacent in the vector and are not neighbours in a
    ;; spine: this model has no thoracic levels. Pairing them would report the
    ;; missing region as an attachment artefact.
    (is (empty? (spine/attachment-steps [(row "L1/L2" :lumbar [["erector" 400.0]])
                                         (row "C7/T1" :cervical [["cervical" 100.0]])]))
        "the lumbar and cervical regions are not neighbours in this model")))

(deftest the-step-detector-refuses-rows-it-cannot-read
  ;; THE FAILURE MODE THIS CLASS OF DETECTOR HAS. A detector handed input it
  ;; cannot see must not return the same value as one that looked and found
  ;; nothing — `[]` would read as `no steps` and be indistinguishable from
  ;; `no data`. The reason is pinned, not merely the throw: without the guard a
  ;; missing `:muscle-crossing` would simply produce an empty `lost` and the
  ;; predicate would go quiet, which is the shape of the bug being removed.
  (let [without-crossing [{:name "A" :region :lumbar :muscle-n 100.0}
                          {:name "B" :region :lumbar :muscle-n 0.0}]
        without-region [(dissoc (row "A" :lumbar [["m1" 100.0]]) :region)
                        (dissoc (row "B" :lumbar []) :region)]
        d (fn [rows] (refusal-data #(spine/attachment-steps rows)))]
    (is (= :value-error (:type (d without-crossing)))
        (str "rows with no :muscle-crossing are refused as a value error: "
             (pr-str (d without-crossing))))
    (is (= "A" (:name (:row (d without-crossing))))
        "and the refusal carries the row it could not read")
    (is (= :value-error (:type (d without-region)))
        "so are rows that do not say which region they are in")))

;; --- the lumbar spine against the literature ---------------------------------

(deftest the-pressure-index-converts-a-pressure-into-a-force-and-nothing-else
  ;; A pressure is not a force, and the number that turns one into the other is a
  ;; modelling assumption rather than a measurement. It is pinned here by hand so
  ;; that changing it is a visible act: 1 MPa is 1 N/mm², so 0.46 MPa through
  ;; 1800 mm² is 828 N of applied pressure-times-area, and Nachemson's index of
  ;; 1.5 divides that to 552 N of compressive force.
  (is (math/nearly= 552.0 (spine/pressure->compressive-force-n 0.46 1800.0 1.5) 1e-9)
      "0.46 MPa x 1800 mm2 / 1.5 = 552 N")
  (is (math/nearly= 828.0 (spine/pressure->compressive-force-n 0.46 1800.0 1.0) 1e-9)
      "an index of 1.0 is pressure x area, i.e. no conversion at all")
  ;; and the index this namespace uses is the one Nachemson measured for a NORMAL
  ;; lumbar disc, with the spread he tabulated, not a single number chosen here
  (is (= 1.5 (:mean spine/nachemson-pressure-index)))
  (is (= [1.5 1.7] (:range spine/nachemson-pressure-index)))
  (is (= :full-text (:obtained spine/nachemson-pressure-index))))

(deftest a-conversion-without-an-index-refuses-for-the-reason-it-names
  ;; This asserted only `thrown?` at first, and it could not fail: with the guard
  ;; deleted, a zero index divides and a nil index dereferences, and BOTH throw —
  ;; so the test went green while measuring nothing about the guard. Pinning the
  ;; reason is what makes it a check. An index of zero must be refused because it
  ;; is not a usable index, not because arithmetic happened to object.
  (let [zero (refusal-data #(spine/pressure->compressive-force-n 0.46 1800.0 0.0))
        missing (refusal-data #(spine/pressure->compressive-force-n 0.46 1800.0 nil))]
    (is (= :value-error (:type zero))
        (str "a zero index is refused as a value error, not as an incidental "
             "divide-by-zero: " (pr-str zero)))
    (is (= 0.0 (:index zero)) "and the refusal carries the offending value")
    (is (= :value-error (:type missing)) (str "so is a missing one: " (pr-str missing)))
    (is (contains? missing :index))))

(deftest every-lumbar-reference-carries-the-provenance-of-its-number
  ;; The whole deliverable here IS reference values, so a value without a source,
  ;; a URL and a statement of whether the full text or only an abstract was read
  ;; is indistinguishable from an invented one. An evidence floor as well: a
  ;; reference list that quietly emptied must not pass this by having nothing to
  ;; check.
  (is (<= 4 (count spine/lumbar-references)) "the reference set is not empty")
  (doseq [r spine/lumbar-references]
    (is (keyword? (:id r)) (str r " needs an id"))
    (is (pos? (:pressure-mpa r)) (str (:id r) " needs a published pressure"))
    ;; either it is comparable and states the posture it was measured at, or it
    ;; says why it is not — never neither
    (is (or (and (:posture r) (:posture-basis r))
            (and (:not-comparable r) (:not-comparable-note r)))
        (str (:id r) " must either pin a posture or say why it cannot")))
  (let [x (spine/lumbar-cross-check)]
    (is (string? (:citation x)))
    (is (string? (:url x)))
    (is (= :full-text (:obtained x)) "the source was read, not summarised")))

(deftest the-lumbar-check-scales-the-model-to-the-reference-subject
  ;; A reference value is stated for a particular body. Scaling the reference to
  ;; the model would silently rewrite the measurement; scaling the model to the
  ;; reference is the only direction that keeps the published number intact. The
  ;; check therefore takes no body at all — it builds Wilke's subject, 70 kg and
  ;; 1.68 m — and this asserts that it really is that body and not this file's
  ;; 70 kg / 1.70 m default.
  (let [x (spine/lumbar-cross-check)]
    (is (= {:mass-kg 70.0 :stature-m 1.68} (:subject x)))
    (let [wilke-body (segment/build-body 70.0 1.68)
          posture (:posture x)
          l (load/solve-posture-loads wilke-body posture)
          t (muscle/solve-muscle-tensions wilke-body posture l)
          row (first (filter #(= "L4/L5" (:name %))
                             (spine/profile wilke-body posture t)))]
      (is (math/nearly= (:force-n row) (:model-force-n x) 1e-9)
          "the reported model force is the one this body produces"))
    ;; The two dimensions of the subject discriminate DIFFERENT outputs here, and
    ;; a control that used the wrong one would pass without testing anything.
    ;; MASS moves the force: at this posture the force is the weight stacked
    ;; above, so a heavier body must give a bigger one.
    (let [heavy (segment/build-body 90.0 1.68)
          posture (:posture x)
          l (load/solve-posture-loads heavy posture)
          t (muscle/solve-muscle-tensions heavy posture l)
          row (first (filter #(= "L4/L5" (:name %))
                             (spine/profile heavy posture t)))]
      (is (> (:force-n row) (* 1.2 (:model-force-n x)))
          (str "a 90 kg body must not report a 70 kg body's force: "
               (:force-n row) " vs " (:model-force-n x))))
    ;; STATURE does NOT move the force at this posture — segment masses are
    ;; fractions of total mass, and with no tissue term there is no lever for a
    ;; length to act through. It moves the DISC AREA, which scales with stature²,
    ;; and that is what discriminates 1.68 m from this file's 1.70 m default.
    (is (math/nearly= (* 1e6 (spine/disc-area-m2
                              (first (filter #(= "L4/L5" (:name %)) spine/levels))
                              1.68))
                      (:model-disc-area-mm2 x) 1e-6)
        "the reported disc area is the one a 1.68 m body has")
    (is (not (math/nearly= (* 1e6 (spine/disc-area-m2
                                   (first (filter #(= "L4/L5" (:name %)) spine/levels))
                                   1.70))
                           (:model-disc-area-mm2 x) 1e-6))
        "and not the one this file's 1.70 m default has")))

(deftest the-model-disagrees-with-the-in-vivo-measurement-and-the-check-says-so
  ;; THE FINDING, and the reason this file exists. At relaxed unsupported sitting
  ;; Wilke telemetered 0.46 MPa from a living L4/L5 disc of 1800 mm²; through
  ;; Nachemson's index that is 552 N, and 476-600 N once both sources' own
  ;; spreads are carried through. This model returns about 351 N — BELOW the
  ;; reference's own spread, by about a third.
  ;;
  ;; It is below it for a reason this namespace's docstring already predicts: at
  ;; exactly zero trunk flexion the extensor moment is zero, so the tissue term
  ;; is exactly zero and the force is nothing but the weight stacked above. A
  ;; real spine at rest is not unloaded — it has lordosis, resting muscle tone
  ;; and abdominal pressure, and this model has none of the three.
  ;;
  ;; This test asserts TODAY'S DISAGREEMENT. If someone improves the model until
  ;; it agrees, this fails, and that is correct: the finding recorded in the
  ;; README would then be false and has to be rewritten. What must not happen is
  ;; the model being tuned to the reference with the disagreement quietly
  ;; disappearing from the output.
  (let [x (spine/lumbar-cross-check)
        [lo hi] (:reference-force-range-n x)]
    (is (= :reference (:validated x))
        "the in-vivo measurement is the validated side, not the profile")
    (is (false? (:model-validated? x)))
    (is (math/nearly= 552.0 (:reference-force-n x) 1e-9)
        "0.46 MPa through 1800 mm2 at index 1.5")
    (is (math/nearly= 476.470588 lo 1e-5) "0.45 MPa at index 1.7")
    (is (math/nearly= 600.0 hi 1e-9) "0.50 MPa at index 1.5")
    (is (false? (:within-reference-spread? x))
        (str "the model is outside the reference's own spread: " (:model-force-n x)
             " N against " lo "-" hi " N"))
    (is (= :model-below-reference (:direction x))
        (str "and it is below it, not above: " (select-keys x [:model-force-n :ratio])))
    (is (< (:model-force-n x) lo))
    (is (< 0.5 (:ratio x) 0.8)
        (str "the model reads about two thirds of the measurement: " (:ratio x)))))

(deftest the-disagreement-is-the-absent-tissue-term-not-the-weight
  ;; Naming WHICH part of the model is short. The weight above L4/L5 is ordinary
  ;; anthropometry and there is no reason to doubt it; what is missing is
  ;; everything else, and at zero trunk flexion this model has exactly none of it.
  (let [x (spine/lumbar-cross-check)]
    (is (math/nearly= 0.0 (:model-muscle-n x) 1e-9)
        "at zero trunk flexion the muscle term is exactly zero")
    (is (math/nearly= 0.0 (:model-ligament-n x) 1e-9)
        "and so is the ligament term")
    (is (math/nearly= (:model-force-n x) (:model-weight-n x) 1e-9)
        "so the whole modelled force is the weight stacked above, and nothing else")))

(deftest a-reference-whose-posture-the-source-does-not-state-refuses-a-ratio
  ;; Wilke reports `sitting with maximum flexion, 0.83 MPa` and does not report the
  ;; trunk angle. Comparing against it would mean CHOOSING an angle, and choosing
  ;; it is the move that turns a validation into a fit. So the entry keeps the
  ;; published pressure and refuses the ratio, and the refusal names its reason —
  ;; which is a different output from agreement and from disagreement both.
  (let [x (spine/lumbar-cross-check
           (spine/reference-by-id :wilke-1999-sitting-maximum-flexion))]
    (is (= :posture-angle-not-stated-in-source (:could-not-obtain x)))
    (is (nil? (:model-force-n x)) "no force is produced for a posture nobody stated")
    (is (nil? (:ratio x)))
    (is (= 0.83 (:reference-pressure-mpa x))
        "the published pressure is still carried; it is the comparison that is refused"))
  ;; `standing, bent forward` is refused for the same reason: Table 1 p.757 gives
  ;; 1.10 MPa and no trunk angle.
  (let [x (spine/lumbar-cross-check
           (spine/reference-by-id :wilke-1999-standing-bent-forward))]
    (is (= :posture-angle-not-stated-in-source (:could-not-obtain x)))
    (is (nil? (:ratio x))))
  ;; ⚠ `relaxed standing` USED TO BE HERE and is not any more, and the reason it
  ;; left matters more than the fact that it did. It was refused twice: first as
  ;; `this model cannot stand` (no thigh segment), then as `it returns the same
  ;; 351 N for standing and for sitting` (sitting and standing differed only below
  ;; L5/S1). The pelvis rotates since 2026-09-08 and the lumbar spine tilts with
  ;; it, so the model can hold the two postures apart and the entry produces a
  ;; ratio. What it does NOT have is a lordosis stated by Wilke — that number is
  ;; imported from Cho et al. 2015 — so the entry carries `:parameter-not-in-source`
  ;; instead, and so does the sitting entry, whose imported lordosis is zero.
  (let [x (spine/lumbar-cross-check
           (spine/reference-by-id :wilke-1999-relaxed-standing))]
    (is (nil? (:could-not-obtain x)) "standing is comparable now")
    (is (some? (:ratio x)))
    (is (= :lumbar-lordosis-deg (:parameter (:parameter-not-in-source x)))
        "and it declares the input its own source does not state")
    (is (= :cho-2015-standing (:from (:parameter-not-in-source x)))
        "naming where the input came from instead")))

(deftest the-niosh-scale-is-the-published-kilogram-force-figures-times-g
  ;; NIOSH's 1981 guide states these as kilogram-force — 350 kg and 650 kg — and
  ;; the familiar 3400 N and 6400 N are those two times g. Deriving them rather
  ;; than typing the round numbers keeps the published figure as the source of
  ;; truth and the newtons as the derived value.
  (let [c spine/niosh-1981-compression-criteria]
    (is (= 350.0 (:design-upper-limit-kgf c)))
    (is (= 650.0 (:hazardous-above-kgf c)))
    (is (math/nearly= 3432.33 (:design-upper-limit-n c) 0.01))
    (is (math/nearly= 6374.32 (:hazardous-above-n c) 0.01))
    (is (= 2 (count (:citations c))) "both the 1981 guide and the 1993 revision")
    (is (every? #(= :full-text (:obtained %)) (:citations c)))))

(deftest the-niosh-comparison-discriminates-in-both-directions
  ;; A threshold check that only ever answers one way is not a check. Both sides
  ;; are exercised on this model's own numbers: an ordinary seated posture sits
  ;; an order of magnitude under the design figure, and 60 degrees of unsupported
  ;; trunk flexion passes it.
  (let [seated (:model-force-n (spine/lumbar-cross-check))
        deep (:force-n (first (filter #(= "L5/S1" (:name %))
                                      (:rows (run (merge lap {:trunk-flexion-deg 60.0
                                                              :arms-supported false}))))))
        a (spine/niosh-compression-comparison seated)
        b (spine/niosh-compression-comparison deep)]
    (is (false? (:above-design-upper-limit? a))
        (str "relaxed sitting is well under the design figure: " seated " N"))
    (is (< (:ratio-to-design-upper-limit a) 0.2))
    (is (true? (:above-design-upper-limit? b))
        (str "60 degrees of unsupported trunk flexion passes it: " deep " N"))
    (is (false? (:above-hazardous? b))
        "but not the higher one, which this model reaches only past 650 kgf")))

(deftest the-top-cervical-level-is-crossed-by-what-reaches-the-skull
  ;; ⚠ THIS TEST HAS BEEN WRONG TWICE AND IS NOW ASSERTING THE OPPOSITE OF WHAT IT
  ;; FIRST DID. The history is the point, because each version was pinning the
  ;; state of a defect as though it were a property of a neck.
  ;;
  ;; (1) It was called `no-level-is-left-with-exactly-no-muscle-force` and asserted
  ;;     `(every? #(pos? (:muscle-n %)) rows)` under the sentence `passive tension
  ;;     keeps every level carrying SOME muscle force`. It passed, and what was
  ;;     holding C3/C4 above zero was `wrist_extensors/left` and
  ;;     `wrist_extensors/right` — 61.4 N of a 61.4 N muscle term, i.e. all of it —
  ;;     admitted by a `crosses?` that compared HEIGHTS. A defect was the reason.
  ;; (2) `crosses?` became a path test, C3/C4 emptied, and this test was renamed
  ;;     `the-top-cervical-level-has-no-muscle-crossing-it-at-all` and asserted the
  ;;     emptiness. That was honest about the model and it named the gap: the most
  ;;     cranial attachment in the whole set was `levator_scapulae` at 0.22 of
  ;;     `head_neck` and C3/C4 sits at 0.24, so nothing spanned it.
  ;; (3) 2026-09-07: the muscles that hold the head up were added, so the gap is
  ;;     closed and the assertion inverts rather than disappearing — the same move
  ;;     `a-frontal-load-is-now-carried` made when the frontal muscles landed.
  ;;
  ;; Measured at laptop-on-lap, 70 kg / 1.70 m, before -> after:
  ;;
  ;;     C7/T1  401.56 -> 407.38 N   +   semispinalis, splenius
  ;;     C6/C7   68.66 -> 276.11 N   +   semispinalis, splenius
  ;;     C5/C6   34.51 -> 241.96 N   +   semispinalis, splenius
  ;;     C4/C5   34.51 -> 241.96 N   +   semispinalis, splenius
  ;;     C3/C4    0.00 -> 207.44 N   +   semispinalis, splenius
  ;; (4) 2026-09-08: the upper cervical FLEXORS were added, and `longus_capitis`
  ;;     runs from the C3-C6 anterior tubercles to the basiocciput, so it reaches
  ;;     the skull too and joins this set. It is the first ANTERIOR line to cross a
  ;;     cervical level in this model. Its force at this posture is near zero — the
  ;;     flexion side of the atlanto-occipital joint is loaded by gravity only when
  ;;     the head is tipped BACK, and at `laptop-on-lap` it is tipped forward — so
  ;;     the newtons at C3/C4 barely move (207.44 -> 207.44 N) while the set that
  ;;     spans it grows by one. Membership and magnitude are different questions and
  ;;     this test asks the first.
  (let [rows (:rows (run lap))
        by-name #(first (filter (fn [r] (= % (:name r))) rows))
        top (by-name "C3/C4")
        crossing (set (map first (:muscle-crossing top)))]
    (is (= #{"semispinalis_capitis" "splenius_capitis" "longus_capitis"} crossing)
        (str "C3/C4 is spanned by the muscles that reach the skull, and only those: "
             top))
    (is (pos? (:muscle-n top)) "so its muscle term is no longer zero")
    (is (> (:force-n top) (* 5.0 (:weight-n top)))
        (str "and the level is now dominated by muscle rather than by the weight "
             "above it — 226.30 N of which 18.86 N is weight: " top))
    ;; the discriminating half, unchanged in shape: every level carries muscle
    ;; force, so this is a statement about the whole cervical profile and not about
    ;; one row having been made loud
    (doseq [r rows]
      (is (pos? (:muscle-n r))
          (str (:name r) " carries muscle force: " (:muscle-n r))))
    ;; and the REASON, read off the SKELETON rather than asserted about it. It used
    ;; to compare two `:along` numbers on the one `head_neck` segment; since
    ;; 2026-09-07 the neck is three segments and an `:along` on one of them is not
    ;; comparable with an `:along` on another, so the question is asked of
    ;; `levels-crossed`, which walks the attachment tree and answers it properly.
    (let [pd (pose/solve-pose body lap)
          crosses-c34? (fn [m] (some #(= "C3/C4" (:name %)) (spine/levels-crossed pd m)))
          ;; the gap version (2) of this test pinned, kept as a live measurement
          ;; rather than as prose: WITHOUT the three cranial muscles nothing in the
          ;; set still reaches, so the C3/C4 row would empty again. That is what
          ;; makes this a test of the new anatomy and not of the profile in general.
          cranial #{"semispinalis_capitis" "splenius_capitis" "sternocleidomastoid"
                    "longus_capitis"}
          suboccipital #{"rectus_capitis_posterior_major" "rectus_capitis_posterior_minor"
                         "obliquus_capitis_superior"}
          reaching (filter crosses-c34? attachment/instances)
          without (filter crosses-c34?
                          (remove #(cranial (:group %)) attachment/instances))]
      (is (seq reaching) "something in the muscle set does span C3/C4")
      (is (every? #(cranial (:group %)) reaching)
          (str "and it is exactly the four that reach the skull: "
               (mapv :name reaching)))
      (is (empty? without)
          (str "remove them and nothing spans C3/C4 at all, which is exactly the "
               "gap this test used to assert: " (mapv :name without)))
      ;; THE SUBOCCIPITALS DO NOT APPEAR HERE, AND THAT IS CORRECT. They run from
      ;; C1/C2 to the occiput, so both their ends are ABOVE every level this model
      ;; has — there is no disc between C1 and C2 or between C1 and the occiput, so
      ;; there is nothing for them to cross. Splitting the neck gave them a joint,
      ;; not a level.
      (doseq [m attachment/instances :when (suboccipital (:group m))]
        (is (empty? (spine/levels-crossed pd m))
            (str (:name m) " sits entirely above every intervertebral level"))))))

(deftest the-profile-steps-and-the-detector-says-where
  ;; THE FINDING. `attachment-steps` required `:muscle-n` to be exactly 0.0, which
  ;; passive tension made unreachable, so it returned `[]` on data full of steps —
  ;; and its unit test went on exercising it against constructed rows that do
  ;; reach zero, which the model no longer produces. The README published the
  ;; silence as `the attachment steps are GONE`.
  ;;
  ;; Measured at laptop-on-lap: the erector spinae inserts at 0.25 of the trunk,
  ;; which lies between L2/L3 (0.21) and L1/L2 (0.28), so it crosses every level
  ;; below and none above.
  ;;
  ;; THE STEP IS STRUCTURAL AND THE MAGNITUDE IS NOT, and this wave watched the
  ;; difference happen. At `1abb7028` the term fell 482.86 N → 4.19 N; after the
  ;; lumbar joint moment was fixed on main it falls 1074.73 N → 4.19 N. The two
  ;; levels, the muscle and the fact of the step did not move at all. So the
  ;; structure is asserted as identity and the newtons are asserted as today's
  ;; measurement — if the load layer moves them again this fails and somebody
  ;; re-measures, which is the point of pinning them.
  (let [rows (:rows (run lap))
        steps (spine/attachment-steps rows)
        lumbar (filterv #(= "L1/L2" (:at %)) steps)
        s (first lumbar)]
    (is (seq steps) "this profile steps; a detector that says otherwise is not looking")
    (is (= 1 (count lumbar)))
    (is (= "L2/L3" (:after s)))
    (is (= ["erector_spinae"] (:lost s))
        (str "and it is the erector spinae's point insertion: " s))
    ;; exactly one muscle is lost and none is gained, so the newtons that left are
    ;; the whole of the change — the detector is not estimating anything
    (is (math/nearly= (:lost-n s) (- (:muscle-n-before s) (:muscle-n-after s)) 1e-9)
        (str "the force lost IS the change, because one muscle left and none "
             "arrived: " s))
    ;; re-measured 2026-09-07 when the neck was split: 1074.734 -> 1076.647 and
    ;; 1070.547 -> 1072.460. The erector spinae works a little harder because the
    ;; cervical column now flexes MORE than the head does (the forward-head shape),
    ;; which carries the mass above C7 a little further anterior of L5/S1.
    (is (math/nearly= 1076.647 (:muscle-n-before s) 0.01))
    (is (math/nearly= 4.187 (:muscle-n-after s) 0.01))
    (is (math/nearly= 1072.460 (:lost-n s) 0.01)
        "the newtons that went with it, so the size is reported rather than judged")
    ;; and the old predicate saw none of it, because 4.19 N is not 0.0 N
    (is (pos? (:muscle-n-after s))
        (str "the level below is not empty, which is exactly why the exact-zero "
             "test could not fire: " s))))

;; --- C2/C3: what blocks it, measured rather than asserted ---------------------
;;
;; `attachment-test`'s `awaiting-muscles` names `:c2c3` as a joint the kinematics
;; places and the kinetics does not solve, and until 2026-09-08 it explained the
;; blockage as a shortage of MUSCLES — "Filling it needs the muscles that act on the
;; upper cervical spine specifically (rectus capitis anterior and lateralis, longus
;; capitis, the semispinalis and multifidus cervicis fascicles that end on C2), none
;; of which this model has."
;;
;; Two of those were added the same day and they did not fill it, because they act
;; about the atlanto-occipital joint. That prompted the question this test answers:
;; is the segmentation the blocker at all? The answer is no — and it is measured
;; here rather than argued, because the reason a gap is open decides what would
;; close it, and getting that wrong sends the next wave at the wrong thing.
;;
;; `spine/levels-crossed` and `attachment/moment-arm` both take a muscle map rather
;; than a name, so a candidate that is NOT in `attachment/instances` can be handed
;; to them and the model will answer about it. That is what makes the claim
;; checkable without adding an unprovenanced muscle to the solve.

(def ^:private c2c3-candidates
  "Three real muscles that act on the C2/C3 segment, written in this model's own
  attachment language and deliberately NOT added to `attachment/muscles`.

  Their anatomy is Vasavada AN, `Architectural Design and Function of Human Back
  Muscles`, Rothman-Simeone The Spine ch.3, full text fetched 2026-09-08 from
  https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf and read with
  `pdftotext -layout`, not an abstract:

    semispinalis cervicis  \"originates on thoracic transverse processes and inserts
                            on cervical spinous processes from C2 to C5, with the
                            bulk of its mass inserting on C2\" (p.66)
    multifidus (cervical)  \"smaller muscles that span one or two vertebral
                            segments\" (p.66), caudal transverse/articular processes
                            to rostral spinous processes
    longus colli, superior \"fibers run superomedially from transverse processes to
      oblique part          the anterior vertebral bodies\" (p.66); the superior
                            oblique part reaches the anterior tubercle of the atlas

  THE OFFSETS ARE ILLUSTRATIVE AND NOTHING IS SOLVED WITH THEM. They exist to answer
  one question — can a line between two of this model's bones cross C2/C3 and have a
  moment arm about it that moves — and the answer does not depend on their values,
  only on which segments the ends ride."
  [{:name "semispinalis_cervicis"
    :origin {:segment "thorax" :along 0.8153846153846154 :ant -0.0141 :lat 0.0}
    :insertion {:segment "upper_cervical" :along 0.20 :ant -0.0106 :lat 0.0}}
   {:name "multifidus_cervicis"
    :origin {:segment "lower_cervical" :along 0.88 :ant -0.0088 :lat 0.0}
    :insertion {:segment "upper_cervical" :along 0.20 :ant -0.0106 :lat 0.0}}
   {:name "longus_colli_superior_oblique"
    :origin {:segment "lower_cervical" :along 0.45 :ant 0.0053 :lat 0.0}
    :insertion {:segment "upper_cervical" :along 0.80 :ant 0.0047 :lat 0.0}}])

(deftest the-segmentation-can-express-a-c2c3-muscle
  ;; THE FIRST HALF OF THE VERDICT: the three muscles that act at C2/C3 in anatomy
  ;; all have their two ends on DIFFERENT segments of this model, so none of them is
  ;; the shape `a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm`
  ;; names as the known error, and each has a moment arm about `:c2c3` that moves
  ;; when the joint moves.
  (let [flex #(pose/solve-pose body (merge posture/standing-neutral
                                           {:head-flexion-deg (double %)}))]
    (doseq [m c2c3-candidates]
      (is (not= (get-in m [:origin :segment]) (get-in m [:insertion :segment]))
          (str (:name m) " spans two of this model's bones"))
      (is (some #(= "C2/C3" (:name %)) (spine/levels-crossed (flex 0) m))
          (str (:name m) " crosses the C2/C3 level"))
      (let [arms (mapv #(attachment/moment-arm (flex %) 1.70 m
                                               (get-in (flex %) [:joints :c2c3]))
                       [-15 0 15 30 45 60])
            lo (apply min arms) hi (apply max arms)
            scale (/ (+ (math/abs* lo) (math/abs* hi)) 2.0)]
        ;; A SPREAD AND NOT A `distinct` COUNT, and the difference is the whole
        ;; point of the test. A muscle with both ends on one bone gives an arm that
        ;; is constant in exact arithmetic and differs in the last bits in a double,
        ;; so `(count (distinct arms))` returns 6 for exactly the shape this is
        ;; supposed to reject — verified 2026-09-08 by moving this candidate's origin
        ;; onto `upper_cervical`, where the distinct count still passed. 1% of the
        ;; mean arm is far above float noise and far below what any of these three
        ;; actually moves (12% for the multifidus, 45% and 60% for the other two).
        (is (> (- hi lo) (* 0.01 scale))
            (str (:name m) ": its arm about :c2c3 must MOVE with the joint, and "
                 "not merely differ in the last bits, got " arms))
        (is (every? (fn [[a b]] (not= a b)) (partition 2 1 arms))
            (str (:name m) ": monotone in the joint angle, got " arms)))))
  ;; and the one-level multifidus crosses C2/C3 and NOTHING ELSE, which is the
  ;; sharpest form of the claim: a muscle can be placed that acts at this joint and
  ;; at no other level in the model
  (let [mf (second c2c3-candidates)
        crossed (mapv :name (spine/levels-crossed
                             (pose/solve-pose body posture/standing-neutral) mf))]
    (is (= ["C2/C3"] crossed)
        (str "a C3-to-C2 multifidus crosses exactly the one level, got " crossed))))

(deftest nothing-is-solved-at-c2c3-and-the-reason-is-provenance
  ;; THE SECOND HALF: the joint is still unsolved, and now the reason can be stated
  ;; without hedging. It is not the segmentation — the test above measures that. It
  ;; is that no PCSA is published for any of the three in the source this model uses,
  ;; and that the lumped `cervical_extensors` is already documented as standing for
  ;; two of them, so adding them would double-count against a number that has no
  ;; provenance to divide.
  (let [pd (pose/solve-pose body lap)]
    ;; nothing acts AT it
    (is (empty? (filter #(= :c2c3 (:acts-about %)) attachment/instances))
        "no muscle in this model is solved at :c2c3")
    (is (contains? (:joints pd) :c2c3) "and the joint is placed, so this is a gap")
    ;; muscles DO cross the level, and every one of them belongs to another joint's
    ;; equilibrium — which is the shape `awaiting-muscles` calls a lower bound
    (let [crossing (filter #(some (fn [l] (= "C2/C3" (:name l)))
                                  (spine/levels-crossed pd %))
                           attachment/instances)]
      (is (seq crossing) "muscles do cross the C2/C3 level")
      (is (every? #(not= :c2c3 (:acts-about %)) crossing)
          (str "and not one of them is solved at it: "
               (mapv (juxt :name :acts-about) crossing)))
      ;; the anterior half of that set is new on 2026-09-08. Before it, every line
      ;; crossing C2/C3 that carried force was a posterior extensor; `longus_capitis`
      ;; is the first anterior one, so the level's lower bound is less short than it
      ;; was even though nothing is solved there yet.
      (is (some #(= "longus_capitis" (:group %)) crossing)
          (str "an anterior line now crosses C2/C3: " (mapv :name crossing))))
    ;; the lumped group is where the double-count would land, and its own :source
    ;; names the two muscles it stands for. Pinning the SENTENCE rather than a
    ;; number, because the blocker is a provenance claim and a provenance claim
    ;; that quietly disappears from the file is exactly how a gap gets refilled by
    ;; accident.
    (let [src (:source (attachment/instance "cervical_extensors"))]
      (is (re-find #"semispinalis cervicis" src)
          "the lumped cervical group still declares that it stands for semispinalis cervicis")
      (is (re-find #"multifidus" src)
          "and for multifidus, which is the other muscle acting at C2/C3")
      (is (re-find #"UNPROVENANCED" src)
          "and it still declares that its own 12.0 cm2 has no source to carve from"))))

;; --- the pelvis rotates, and what that did to Wilke (2026-09-08) -------------

(def ^:private wilke-body (segment/build-body 70.0 1.68))

(defn- l4l5-at
  "L4/L5 compression on Wilke's body at the standing reference posture with this
  pelvic tilt substituted, everything else held."
  [tilt]
  (let [pst (assoc (:posture (spine/reference-by-id :wilke-1999-relaxed-standing))
                   :lumbar-lordosis-deg tilt)
        l (load/solve-posture-loads wilke-body pst)
        t (muscle/solve-muscle-tensions wilke-body pst l)]
    (:force-n (first (filter #(= "L4/L5" (:name %))
                             (spine/profile wilke-body pst t))))))

(deftest zero-pelvic-tilt-is-a-straight-lumbar-spine
  ;; THE PROPERTY THE WHOLE CHANGE RESTS ON. `pose/lumbar-chord-tilt-deg` is
  ;; `trunk + pelvic-tilt/2`, so at zero tilt the lumbar segment is collinear with
  ;; the thorax and every number this actor produced before the pelvis could rotate
  ;; is unchanged. It is also not merely convenient: Cho et al. measure a stool at
  ;; 0.6 deg (SD 3.6), and Wilke's one comparable posture is a stool.
  (doseq [trunk [0.0 20.0 60.0]]
    (is (math/nearly= trunk (pose/lumbar-chord-tilt-deg trunk 0.0) 1e-12)
        (str "at zero pelvic tilt the lumbar takes the trunk's own tilt: " trunk)))
  (let [p (pose/solve-pose body {:head-flexion-deg 0.0 :trunk-flexion-deg 25.0
                                 :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0})]
    (is (= (:dir (pose/seg-at p "lumbar")) (:dir (pose/seg-at p "thorax")))
        "and the two trunk segments point the same way")))

(deftest the-lumbar-chord-is-the-mean-tangent-of-the-measured-arc
  ;; ⚠ THIS TEST WAS `the-lumbar-chord-bisects-its-two-ends` UNTIL 2026-09-11 AND
  ;; BOTH HALVES OF ITS CLAIM WERE UNSOURCED. It said the sacral end is turned by
  ;; the whole lordosis and the T12/L1 end is held by the thorax, so the chord — a
  ;; circular arc's chord bisecting its two end tangents — sits at `trunk + p/2`.
  ;;
  ;; Mills et al. 2026 measure both halves in 50 asymptomatic adults, standing and
  ;; seated. The pelvis supplies 0.586 of a lordosis change and not all of it, and
  ;; the arc is not circular: the five motion segments carry 25.7 / 27.3 / 21.5 /
  ;; 14.8 / 10.7 per cent of the turn from the sacrum up, so its mean tangent lies
  ;; at 0.5848 of the turn rather than at 0.5.
  ;;
  ;; The two measured numbers very nearly cancel, and that is the result of the
  ;; day: `share - chord-turn-fraction` is 0.00113, so between two upright postures
  ;; the lumbar spine's two ends stay one above the other.
  (doseq [[trunk lordosis] [[0.0 46.5] [20.0 10.0] [0.0 -20.0] [15.0 0.0]]]
    (let [share posture/sacral-slope-share-of-lordosis
          lower (+ trunk (* share lordosis))       ; the sacral end, turned by the pelvis
          upper (+ trunk (* (- share 1.0) lordosis)) ; the T12/L1 end, behind the thorax
          got (pose/lumbar-chord-tilt-deg trunk lordosis)]
      ;; the two ends are what `lumbar-tangent-tilt-deg` puts at 0.0 and 1.0
      (is (math/nearly= lower (pose/lumbar-tangent-tilt-deg trunk lordosis 0.0) 1e-12)
          "the tangent at the bottom of the lumbar spine is the sacral endplate")
      (is (math/nearly= upper (pose/lumbar-tangent-tilt-deg trunk lordosis 1.0) 1e-12)
          "and the tangent at the top is the L1 endplate")
      ;; the lordosis IS the angle between them, whatever the share
      (is (math/nearly= lordosis (- lower upper) 1e-12)
          (str "trunk " trunk " lordosis " lordosis ": the two ends differ by the "
               "lordosis by construction, whatever the pelvis takes of it"))
      (is (math/nearly= lordosis
                        (pose/lumbar-lordosis-deg {:lumbar-lordosis-deg lordosis})
                        1e-12)
          "and that is the number the posture states")
      ;; the chord is the arc-length mean of the tangent, not the mean of the ends
      (is (math/nearly= (+ trunk (* (- share posture/lumbar-chord-turn-fraction)
                                    lordosis))
                        got 1e-12)
          (str "trunk " trunk " lordosis " lordosis ": chord is trunk + (share - "
               "chord-turn-fraction) x lordosis, got " got))
      (is (math/nearly= got (pose/lumbar-tangent-tilt-deg
                             trunk lordosis
                             ;; the length fraction whose turn fraction IS the
                             ;; chord's — solved rather than written, and the
                             ;; identity is that the chord is a tangent of the arc
                             (loop [lo 0.0 hi 1.0 n 0]
                               (if (> n 60)
                                 (* 0.5 (+ lo hi))
                                 (let [mid (* 0.5 (+ lo hi))]
                                   (if (< (posture/lumbar-turn-fraction mid)
                                          posture/lumbar-chord-turn-fraction)
                                     (recur mid hi (inc n))
                                     (recur lo mid (inc n)))))))
                        1e-9)
          "and it is a tangent of the arc, at the point whose turn fraction it is")))
  ;; THE CONTROL ON THE SIZE, at Cho's standing lordosis. A circular arc under a
  ;; pelvis that took the whole lordosis put the chord at 23.25 deg; the measured
  ;; share and the measured shape put it at 0.052.
  (is (math/nearly= 23.25 (+ 0.0 (* (- 1.0 0.5) 46.5)) 1e-12)
      "what the old rule computed, restated so the comparison is in this file")
  (is (math/nearly= 0.052454793413479195 (pose/lumbar-chord-tilt-deg 0.0 46.5) 1e-12)
      (str "what the measured one computes: "
           (pose/lumbar-chord-tilt-deg 0.0 46.5) " deg"))
  ;; and the same cancellation checked against Mills' ABSOLUTE columns, which is a
  ;; different route to it: the chord is `SS - ∫c x LL` in each posture, and the
  ;; two postures land within a tenth of a degree of each other
  (let [chord-of (fn [k]
                   (let [m (get posture/spinopelvic-motion k)]
                     (- (get-in m [:sacral-slope :deg])
                        (* posture/lumbar-chord-turn-fraction
                           (get-in m [:lumbar-lordosis :deg])))))]
    (is (< (math/abs* (- (chord-of :standing) (chord-of :relaxed-seated))) 0.1)
        (str "Mills' standing chord is " (chord-of :standing) " deg and his seated "
             "chord is " (chord-of :relaxed-seated) " deg — measured absolutely, "
             "with no share in the arithmetic, and they differ by "
             (math/abs* (- (chord-of :standing) (chord-of :relaxed-seated)))))
    (is (< 4.0 (chord-of :standing) 6.0)
        (str "both sit about 5 deg anterior of vertical, which this model cannot "
             "state: it has no pelvic incidence, so it carries the DIFFERENCE and "
             "not either absolute value"))))

(deftest the-lower-limb-does-not-reach-l4l5
  ;; WHAT MAKES THE STANDING COMPARISON A COMPARISON OF LORDOSIS. The two Wilke
  ;; reference postures differ below L5/S1 as well as above it — one has the hip
  ;; and knee at 90 deg on a stool, the other stands on a 5 deg lean. If any of
  ;; that reached L4/L5 the difference this model reports would be a mixture and
  ;; `sitting-standing-comparison` could not say what it was measuring.
  ;;
  ;; It does not reach it, and the reason is the skeleton's shape rather than a
  ;; judgement: the walk from a thigh goes thigh → pelvis → lumbar at 0.0, and 0.0
  ;; is not past a cut at 0.2, so every leg segment is proximal to every lumbar
  ;; level. Asserted by holding the lordosis fixed and swapping the support mode.
  (let [sit (:posture (spine/reference-by-id :wilke-1999-sitting-relaxed-no-backrest))
        stand (:posture (spine/reference-by-id :wilke-1999-relaxed-standing))
        at (fn [pst tilt]
             (let [p (assoc pst :lumbar-lordosis-deg tilt)
                   l (load/solve-posture-loads wilke-body p)
                   t (muscle/solve-muscle-tensions wilke-body p l)]
               (:force-n (first (filter #(= "L4/L5" (:name %))
                                        (spine/profile wilke-body p t))))))]
    (is (not= (:support sit) (:support stand)) "the two postures really do differ")
    (doseq [tilt [0.0 46.5]]
      (is (math/nearly= (at sit tilt) (at stand tilt) 1e-9)
          (str "at " tilt " deg of lordosis, sitting and standing must give the same "
               "L4/L5 force: " (at sit tilt) " vs " (at stand tilt))))))

(deftest standing-and-sitting-now-differ-and-the-difference-is-the-lordosis
  ;; The gap this change exists to close. Until 2026-09-08 both entries returned
  ;; 350.887 N because sitting and standing differed only BELOW L5/S1.
  ;;
  ;; ⚠ AND THE SIGN REVERSED ON 2026-09-11. This asserted `(pos? …)` — standing
  ;; loads L4/L5 MORE, which is Wilke's direction — from 2026-09-08 until the five
  ;; lumbar levels stopped sharing one orientation. They now take their own tilts
  ;; from Mills' measured segmental shape, L4/L5 stands 15.29 deg from vertical at
  ;; Cho's standing lordosis, and only `cos(15.29) = 0.965` of the weight above it
  ;; acts along its axis. That drops 12.34 N, which is more than the 0.89 N the
  ;; lumbosacral moment adds, so this model now says standing UNLOADS L4/L5 by
  ;; 10.86 N where Wilke measures it loading it by 48.
  ;;
  ;; The 91.98 N of shear that tilt creates is carried by NOTHING here — no facet,
  ;; no shear-resisting muscle recruitment — which is the limitation
  ;; `a-tilted-level-drops-its-shear-and-nothing-carries-it` has named since
  ;; 2026-09-08 and which this change made material.
  (let [x (spine/sitting-standing-comparison)]
    (is (neg? (:model-difference-n x))
        (str "standing now UNLOADS L4/L5 relative to sitting: "
             (:model-difference-n x) " N, against Wilke's +48"))
    (is (math/nearly= (:model-difference-n x) (- (l4l5-at 46.5) (l4l5-at 0.0)) 1e-9)
        "and the whole of that difference must come from the lordosis")
    (is (math/nearly= -10.85937763960402 (:model-difference-n x) 1e-9)
        "pinned")
    (is (= 0.0 (:lumbar-lordosis-deg (:sitting x))) "sitting is straight")
    (is (= 46.5 (:lumbar-lordosis-deg (:standing x))) "standing is lordotic")
    (is (:parameter-not-in-source (:standing x))
        "and the entry declares that Wilke did not state that lordosis")
    (is (:parameter-not-in-source (:sitting x))
        "as does the sitting entry, whose imported lordosis happens to be zero")
    (is (false? (:model-validated? x)) "neither side of this is validated")))

(deftest the-direction-agreeing-with-wilke-was-not-evidence-and-it-has-stopped-agreeing
  ;; ⚠ THIS TEST WAS `the-direction-agreeing-with-wilke-is-not-evidence` UNTIL
  ;; 2026-09-11, AND ITS WARNING TURNED OUT TO BE RIGHT IN THE STRONGEST WAY.
  ;;
  ;; It said: `sitting-standing-comparison` reports `:same-direction? true`, and
  ;; that agreement is worth nothing, because reversing the SIGN of the lordosis
  ;; left the model still saying the tilted posture loads L4/L5 more. A model that
  ;; cannot produce the opposite answer has not predicted this one.
  ;;
  ;; With the pelvis's share and the segmental distribution both taken from a
  ;; measurement, the model CAN produce the opposite answer, and it does:
  ;;
  ;;   lordosis  0.0    348.862 N
  ;;   lordosis +46.5   338.002 N   ← an anterior lordosis now UNLOADS the level
  ;;   lordosis -46.5   696.352 N   ← a posterior one still loads it, hugely
  ;;
  ;; `:same-direction?` is FALSE. The model and Wilke now disagree about which of
  ;; his two postures loads L4/L5 more. That is a worse agreement and a better
  ;; model: the old agreement was produced by an unsourced identity, and the two
  ;; measurements that replaced it point the other way.
  ;;
  ;; WHY IT UNLOADS. At Cho's standing lordosis the L4/L5 level stands 15.29 deg
  ;; from vertical, so only `cos(15.29)` of the weight above it acts along its
  ;; axis. The other component is 91.98 N of SHEAR, and nothing in this model
  ;; carries shear — no facet joint, no shear-driven muscle recruitment. The
  ;; disagreement with Wilke is therefore an underestimate of a known size in a
  ;; known direction, which is what
  ;; `a-tilted-level-drops-its-shear-and-nothing-carries-it` reports.
  ;;
  ;; THE ASYMMETRY IS STILL THERE and is still not lordosis. A posterior tilt
  ;; swings the eight pelvis-origin muscle groups under L5/S1 and collapses their
  ;; moment arms, which is why -46.5 deg reports 696 N — twice the flat posture —
  ;; while +46.5 reports 338. `pelvic-tilt-reaches-l4l5-by-a-second-path-that-is-
  ;; not-lordosis` names that path.
  (let [flat (l4l5-at 0.0)]
    (is (< (l4l5-at 46.5) flat)
        (str "an anterior lordosis UNLOADS the level axially: " (l4l5-at 46.5)
             " vs " flat))
    (is (> (l4l5-at -46.5) flat)
        (str "and a posterior one loads it, by the moment-arm path rather than by "
             "lordosis: " (l4l5-at -46.5) " vs " flat))
    ;; the two are no longer the same answer, which is the property the old test
    ;; could not find
    (is (not (math/nearly= (l4l5-at 46.5) (l4l5-at -46.5) 1.0))
        "the model is no longer sign-blind")
    (is (false? (:same-direction? (spine/sitting-standing-comparison)))
        (str "and it no longer agrees with Wilke's direction at all, which is the "
             "honest end of an agreement that was never evidence"))))

(deftest pelvic-tilt-reaches-l4l5-by-a-second-path-that-is-not-lordosis
  ;; The half of the previous test that a docstring cannot assert. Eight of this
  ;; model's muscle groups hang their ORIGIN on the pelvis, so the new degree of
  ;; freedom moves them directly — a path from `:lumbar-lordosis-deg` to a lumbar
  ;; compression that does not pass through the lumbar spine's orientation at all.
  ;; Naming it is the difference between reading `standing loads L4/L5 more` as a
  ;; statement about lordosis and reading it as what it is.
  (let [on-pelvis (filterv #(= "pelvis" (:segment (:origin %)))
                           (vals attachment/muscles))]
    (is (<= 5 (count on-pelvis))
        (str "several muscle groups originate on the pelvis: "
             (mapv :name on-pelvis)))
    (is (some #(= "erector_spinae" (:name %)) on-pelvis)
        "including the only trunk extensor this model has"))
  ;; and the path is live: the extensor's moment arm about L5/S1 moves with the
  ;; pelvis even at a posture whose lumbar spine is doing the same thing
  (let [arm (fn [tilt]
              (let [pst (assoc (:posture (spine/reference-by-id
                                          :wilke-1999-relaxed-standing))
                               :lumbar-lordosis-deg tilt)
                    p (pose/solve-pose wilke-body pst)]
                (attachment/moment-arm p 1.68 (attachment/instance "erector_spinae")
                                       (get-in p [:joints :l5s1]))))]
    (is (not (math/nearly= (arm 0.0) (arm 20.0) 1e-6))
        (str "the extensor's arm about L5/S1 follows the pelvis: "
             (arm 0.0) " -> " (arm 20.0)))))

(deftest the-lordosis-sensitivity-reversed-and-is-now-far-too-low
  ;; THE FINDING, pinned so that it cannot quietly stop being true — AND IT
  ;; CHANGED SIGN ON 2026-09-11.
  ;;
  ;; Until then the model separated Wilke's two postures by about SEVEN TIMES too
  ;; much: his standing-minus-sitting is 48 N through the same pressure index and
  ;; this model returned 333.6 N. The cause was named in
  ;; `pose/lumbar-chord-tilt-deg`: the chord sat at `trunk + lordosis/2`, so 46.5
  ;; deg of lordosis under a vertical thorax put T12/L1 6.685 cm anterior to L5/S1
  ;; and carried the whole 367.9 N above the level out onto that lever.
  ;;
  ;; That was an unsourced identity — `lordosis == a rigid rotation of the whole
  ;; pelvis` — and Mills et al. 2026 measure both halves of it in 50 asymptomatic
  ;; adults. With the measured share (0.586) and the measured segmental shape the
  ;; chord tilts 0.052 deg rather than 23.25, T12/L1 sits 0.016 cm anterior to
  ;; L5/S1 rather than 6.685, and the model now returns **1.57 N against Wilke's
  ;; 48** — about a THIRTIETH, from a factor of seven the other way.
  ;;
  ;; ⚠ THAT IS NOT A SMALLER DISAGREEMENT. On a log scale it is a larger one, and
  ;; this test says so rather than reading `the ratio moved toward 1` off a number
  ;; that went past it. What it does mean is that essentially the whole of the
  ;; model's old ability to tell standing from sitting at L4/L5 was the artefact:
  ;; remove the artefact with a measurement and the model is back to answering
  ;; nearly the same compression for both postures, which is what it did before
  ;; the pelvis could rotate at all.
  (let [x (spine/sitting-standing-comparison)
        need (spine/lordosis-matching-reference-difference-deg)]
    (is (neg? (:difference-ratio x))
        (str "the model now separates the two postures the WRONG WAY: "
             (:difference-ratio x)))
    (is (math/nearly= -0.2262370341584171 (:difference-ratio x) 1e-9)
        "pinned: -10.859 N against Wilke's +48")
    (is (nil? need)
        (str "and no lordosis inside the measured one reproduces Wilke's "
             "difference any more — the bisection is bracketed on [0, 46.5] and "
             "the model does not reach 48 N anywhere in it, so it returns nil "
             "rather than an endpoint. It answered 5.736 deg until 2026-09-11, "
             "got " need))
    ;; the direction, stated: the model is short at the top of the bracket
    (is (< (:model-difference-n x) (:reference-difference-n x))
        (str "at Cho's own 46.5 deg the model is " (:model-difference-n x)
             " N against the reference's " (:reference-difference-n x) " N"))
    (is (false? (:same-direction? x))
        (str "and the sign no longer agrees with Wilke at all. The agreement was "
             "never evidence — `the-direction-agreeing-with-wilke-was-not-evidence"
             "-and-it-has-stopped-agreeing` showed that the model gave the same "
             "answer for either sign of the lordosis — and with the pelvis's share "
             "and the segmental shape both measured it is gone"))))

(deftest the-model-reads-below-wilke-at-both-postures
  ;; ⚠ THIS TEST WAS `the-model-brackets-wilke-rather-than-matching-either-end`
  ;; UNTIL 2026-09-11 AND THE MODEL NO LONGER BRACKETS. It was 0.632 at the one
  ;; comparable posture and 1.137 at the posture that became comparable — below
  ;; the measurement when the spine was straight and above it when the spine was
  ;; lordotic. With the pelvis taking the measured 0.586 of the lordosis the
  ;; standing ratio falls to 0.584 and the model is BELOW the reference at both.
  ;;
  ;; That is the more coherent picture and it is not an improvement in agreement:
  ;; the model reads about two thirds of Wilke's in-vivo pressure at both postures,
  ;; which is the same systematic shortfall `lumbar-cross-check` has reported for
  ;; the sitting entry since it existed. What it removes is a bracket that came
  ;; from one posture being wrong in the other direction.
  (let [x (spine/sitting-standing-comparison)]
    (is (= :model-below-reference (:direction (:sitting x))))
    (is (= :model-below-reference (:direction (:standing x)))
        "standing is below the reference now, where it was above it")
    (is (false? (:within-reference-spread? (:sitting x))))
    (is (false? (:within-reference-spread? (:standing x))))
    (is (< (:ratio (:sitting x)) 1.0)
        (str "sitting: " (:ratio (:sitting x))))
    (is (< (:ratio (:standing x)) 1.0)
        (str "and standing: " (:ratio (:standing x)) " — no longer a bracket"))
    ;; the two are close to each other, which is the shortfall being systematic
    (is (< (math/abs* (- (:ratio (:sitting x)) (:ratio (:standing x)))) 0.10)
        (str "and the two ratios are within 0.10 of each other — "
             (:ratio (:sitting x)) " and " (:ratio (:standing x))
             " — so what is left is one shortfall rather than two errors of "
             "opposite sign"))))

(deftest the-pelvis-actually-rotates
  ;; The kinematic half, checked where it is visible rather than only where it is
  ;; convenient. An ANTERIOR pelvic tilt tips the top of the sacrum forward, so
  ;; the sacrum and the femoral heads SEPARATE along X by `L_pelvis x sin(tilt)`.
  ;;
  ;; WHICH OF THE TWO MOVES IS THE ROOTING QUESTION, and since 2026-09-10 the
  ;; standing chain is rooted at the feet: the world holds the ankles, so the hip
  ;; stays where the legs put it and L5/S1 travels ANTERIORLY instead. Before that
  ;; the chain was rooted at L5/S1 and this test asserted the mirror image — the
  ;; hip travelling posteriorly — which is the SAME separation seen from the other
  ;; end. The separation is the physics; which end moves is the frame.
  (let [pst {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
             :elbow-flexion-deg 0.0 :support :standing
             :hip-flexion-deg 0.0 :knee-flexion-deg 0.0 :ankle-dorsiflexion-deg 0.0}
        flat (pose/solve-pose body pst)
        tilted (pose/solve-pose body (assoc pst :lumbar-lordosis-deg 30.0))
        x (fn [p k] (first (get-in p [:joints k])))
        sep (fn [p] (- (x p :hip/left) (x p :l5s1)))
        ;; ⚠ IT IS THE PELVIS'S OWN ROTATION, NOT THE LORDOSIS, since 2026-09-11.
        ;; This read `sin(30)` — right only while the two were the same number.
        ;; Mills et al. 2026 measure that the pelvis supplies 0.586 of a lordosis
        ;; change, so 30 deg of lordosis turns the pelvis 17.58 deg and the
        ;; separation is `L_pelvis x sin(17.58)`.
        rot (pose/pelvic-rotation-deg 30.0)
        expected (- (* (:length-m (segment/seg body "pelvis"))
                       (Math/sin (math/radians rot))))]
    (is (math/nearly= 0.0 (sep flat) 1e-12) "the pelvis hangs straight down at zero")
    (is (math/nearly= 17.58 rot 0.01)
        (str "30 deg of lordosis turns the pelvis " rot " deg, not 30"))
    (is (math/nearly= expected (sep tilted) 1e-12)
        (str "an anterior tilt separates hip from sacrum by L_pelvis x sin(" rot
             ") = " expected " m, and got " (sep tilted)))
    (is (< (sep tilted) -0.03)
        (str "which carries the hip posterior RELATIVE TO THE SACRUM: " (sep tilted)))
    (is (math/nearly= -0.048776170588125965 (sep tilted) 1e-12)
        "4.88 cm, where the whole-lordosis pelvis separated them by 8.07 cm")
    ;; and the world holds the feet, so it is the sacrum that travels
    (is (math/nearly= (x flat :ankle/left) (x tilted :ankle/left) 1e-12)
        "the standing chain is rooted at the feet, so the ankle does not move")
    (is (math/nearly= (x flat :hip/left) (x tilted :hip/left) 1e-12)
        "and neither does the hip, because the leg between them did not change")
    (is (math/nearly= (- expected) (- (x tilted :l5s1) (x flat :l5s1)) 1e-12)
        (str "L5/S1 is what travels, and anteriorly: "
             (- (x tilted :l5s1) (x flat :l5s1)) " m"))))

(deftest a-tilted-level-drops-its-shear-and-nothing-carries-it
  ;; THE COST OF THE NEW DEGREE OF FREEDOM, in newtons. `weight-above-n` takes the
  ;; component of the weight above a level that acts ALONG the level's axis. While
  ;; every lumbar level was vertical under a vertical gravity that was the whole
  ;; weight; a lordosis tilts them, and the transverse component becomes shear that
  ;; this model does not carry anywhere. The compression it reports is therefore
  ;; lower than it would be for the same posture with a shear term, and the amount
  ;; is worth having rather than leaving to be inferred.
  (let [w (fn [tilt]
            (let [pst (assoc (:posture (spine/reference-by-id
                                        :wilke-1999-relaxed-standing))
                             :lumbar-lordosis-deg tilt)
                  l (load/solve-posture-loads wilke-body pst)
                  t (muscle/solve-muscle-tensions wilke-body pst l)]
              (:weight-n (first (filter #(= "L4/L5" (:name %))
                                        (spine/profile wilke-body pst t))))))
        flat (w 0.0)
        tilted (w 46.5)]
    (is (< tilted flat)
        (str "a tilted level takes less of the weight axially: " tilted " vs " flat))
    ;; and the amount is the cosine of the CHORD's tilt — derived from
    ;; `lumbar-chord-tilt-deg` rather than written as `lordosis/2`, which is what
    ;; it said until 2026-09-11 and was right only while the chord bisected
    ;; ⚠ IT IS THE LEVEL'S OWN TILT, NOT THE CHORD'S, SINCE 2026-09-11. This read
    ;; `cos(lordosis/2)` until then and `cos(chord)` for part of the same day, and
    ;; both were right only while all five lumbar levels shared the chord's
    ;; orientation. They take their own now (`spine/level-axis-offset-deg`), and
    ;; L4/L5 stands 15.29 deg from vertical at Cho's standing lordosis while the
    ;; chord stands at 0.05. Derived from `lumbar-tangent-tilt-deg` at the level's
    ;; own `:along` rather than written, so a change to the turn table moves it.
    (let [l4l5 (first (filter #(= "L4/L5" (:name %)) spine/levels))
          tilt (pose/lumbar-tangent-tilt-deg 0.0 46.5 (:along l4l5))]
      (is (math/nearly= 15.287675522780077 tilt 1e-9)
          (str "L4/L5's own axis stands " tilt " deg from vertical"))
      (is (math/nearly= (* flat (Math/cos (math/radians tilt))) tilted 1e-9)
          (str "and the part it drops is exactly the cosine of THAT: " tilted
               " vs " (* flat (Math/cos (math/radians tilt)))))
      ;; ⚠ AND IT IS MATERIAL AGAIN, WHICH IS WHY THE MODEL NOW CONTRADICTS WILKE.
      ;; The compression this level drops is 12.34 N and the SHEAR that tilt
      ;; creates is 91.98 N, carried by nothing here — no facet joint, no
      ;; shear-driven recruitment. It was 28.33 N and 137.7 N while the chord took
      ;; the whole lordosis, and 0.000146 N and 0.32 N for the part of 2026-09-11
      ;; when the levels still shared the chord.
      (is (math/nearly= 12.344768085666999 (- flat tilted) 1e-9)
          (str "which is " (- flat tilted) " N of real load arriving nowhere"))
      (is (> (- flat tilted)
             (:newtons (first (filter #(= :lumbosacral-moment-on-the-neutral-geometry
                                          (:name %))
                                      (:contributions
                                       (spine/standing-sitting-decomposition))))))
          (str "and it is LARGER than the lumbosacral moment term, which is why "
               "this model now says an anterior lordosis unloads L4/L5"))
      ;; the shear, which is the bigger of the two and the one nothing carries
      (let [d (spine/standing-sitting-decomposition)]
        (is (math/nearly= 91.98283491240159 (:shear-nothing-carries-n d) 1e-9)
            (str "the shear nothing carries is " (:shear-nothing-carries-n d) " N"))
        (is (> (:shear-nothing-carries-n d) (* 5.0 (- flat tilted)))
            "several times the compression the same tilt drops, as it was before")))))

;; --- the 7x, decomposed ------------------------------------------------------

(deftest the-standing-sitting-decomposition-accounts-for-every-newton
  ;; THE FLOOR UNDER EVERY OTHER CLAIM IN THIS SECTION. A decomposition that does
  ;; not sum to the thing it decomposes is a list of numbers, and a list of numbers
  ;; can be adjusted until it reads well. This asserts closure first and the
  ;; individual sizes second.
  ;;
  ;; AND THE IDENTITY THE SPLIT RESTS ON is asserted separately, because it is an
  ;; assumption about the model rather than about this posture: the erector-spinae
  ;; terms are `moment / arm x projection`, which is the solved force only while
  ;; the erector spinae is the one trunk-extension candidate carrying load. The day
  ;; a second one does, `:chain-identity-residual-nm` grows and this goes red —
  ;; rather than the split quietly attributing that muscle's share to the pelvis.
  (let [d (spine/standing-sitting-decomposition)]
    (is (< (math/abs* (:residual-n d)) 1e-9)
        (str "the contributions must sum to the difference they decompose; "
             "residual " (:residual-n d) " N"))
    (is (< (math/abs* (:chain-identity-residual-nm d)) 1e-9)
        (str "force x arm must equal the lumbosacral moment, or the erector-spinae "
             "split is mis-attributing another muscle's share: residual "
             (:chain-identity-residual-nm d) " N·m"))
    (let [by (into {} (map (juxt :name identity)) (:contributions d))
          n (fn [k] (:newtons (get by k)))]
      ;; every term, pinned. These are five figures rather than a tolerance
      ;; because the point of the exercise is that each one is separately
      ;; checkable — a change that moves any of them should have to say which.
      ;;
      ;; TWO OF THEM MOVED BY ONE UNIT IN THE LAST PLACE ON 2026-09-10, and the
      ;; movement is worth reading rather than rounding away. Rooting the standing
      ;; chain at the feet shifts every x by a constant, and floating-point
      ;; subtraction is not translation-invariant: `(a+t) - (b+t)` is not bit-for-bit
      ;; `a - b`. The relative move is 3e-16, four orders inside this tolerance.
      ;; It is also the EVIDENCE that the translation ran at all — a re-rooting
      ;; that changed literally nothing would be indistinguishable from one that
      ;; was never applied. The sitting cross-check is exempt and stayed byte-
      ;; identical, because a seated pose is re-rooted along y alone.
      ;;
      ;; ⚠ ALL FIVE MOVED ON 2026-09-11 and the previous values are kept in the
      ;; comment because the shape of the change is the finding. The chord stopped
      ;; following the pelvis, so every term that is a consequence of the chord's
      ;; tilt collapsed by two to three orders, and `:other-crossing-muscles` —
      ;; the two obliques, which cross L4/L5 and are not the erector spinae —
      ;; became 47% of what is left rather than 0.4% of it. The difference is now
      ;; small enough that the two smallest terms in the old split dominate it.
      ;;
      ;;   term                                       2026-09-10   pelvis+chord   per-level
      ;;   :lumbar-chord-cosine                       -28.330642     -0.000146   -12.344768
      ;;   :lumbosacral-moment-on-the-neutral-geometry 384.375364      0.891462     0.891462
      ;;   :pelvis-origin-moment-arms                 -25.775144     -0.057240    -0.057240
      ;;   :level-axis-under-the-muscle-line            2.073383      0.004619    -0.065489
      ;;   :other-crossing-muscles                      1.216941      0.729580     0.716658
      ;;   :trunk-mass-split                            0.0           0.0          0.0
      ;;
      ;; The middle column is the pelvis's share and the measured chord; the right
      ;; one adds the five levels taking their own orientations. `:lumbar-chord-
      ;; cosine` is named for the CHORD and is now driven by the LEVEL's own tilt —
      ;; 15.29 deg at L4/L5 against the chord's 0.05 — which is why it went back up
      ;; by four orders and took the sign of the whole difference with it.
      (is (math/nearly= -12.344768085666999 (n :lumbar-chord-cosine) 1e-9))
      (is (math/nearly= 0.8914616249439752
                        (n :lumbosacral-moment-on-the-neutral-geometry) 1e-9))
      (is (math/nearly= -0.05724025764582985 (n :pelvis-origin-moment-arms) 1e-9))
      (is (math/nearly= -0.06548878357073673
                        (n :level-axis-under-the-muscle-line) 1e-9))
      (is (math/nearly= 0.7166578623355458 (n :other-crossing-muscles) 1e-9))
      (is (= 0.0 (n :trunk-mass-split))))))

(deftest the-dominant-term-was-the-lumbar-chord-and-the-chord-is-measured-now
  ;; ⚠ THIS TEST HAS BEEN RENAMED TWICE AND BOTH RENAMES ARE THE POINT. It was
  ;; `…-is-the-chain-being-rooted-at-l5s1` until 2026-09-10, when re-rooting was
  ;; measured to move no moment at all; it was `the-dominant-term-of-the-7x-is-the-
  ;; lumbar-chord-and-not-the-root` until 2026-09-11, when the chord stopped being
  ;; unsourced and the 7x went with it.
  ;;
  ;; WHAT THE OLD TEST ASSERTED, and it was true then: one term was larger than the
  ;; whole standing-minus-sitting difference — 384.4 N of 333.6 — and it was the
  ;; moment the lumbar CHORD's tilt created. At `trunk + lordosis/2` = 23.25 deg on
  ;; a vertical thorax, T12/L1 sat 6.685 cm anterior to L5/S1 and the whole 367.9 N
  ;; above the level rode out there.
  ;;
  ;; WHAT IT ASSERTS NOW. The chord is derived from Mills' measured share and
  ;; measured segmental shape, T12/L1 sits 0.0155 cm anterior to L5/S1, and the
  ;; same term is 0.891 N of a 1.568 N difference — 57% rather than 115%. The
  ;; mechanism is unchanged and it is still the largest single term; what changed
  ;; is that its size is now a consequence of two measurements rather than of an
  ;; identity nobody had checked.
  (let [d (spine/standing-sitting-decomposition)
        by (into {} (map (juxt :name identity)) (:contributions d))
        moment-term (:newtons (get by :lumbosacral-moment-on-the-neutral-geometry))]
    ;; the mechanism, derived rather than pinned: the chord's anterior travel is
    ;; `L_lumbar x sin(chord tilt)`, and the moment is the weight above L5/S1
    ;; times the lever that produces
    (let [stand-pst (:posture (spine/reference-by-id :wilke-1999-relaxed-standing))
          p (pose/solve-pose wilke-body stand-pst)
          travel (- (first (get-in p [:joints :t12l1]))
                    (first (get-in p [:joints :l5s1])))
          chord (pose/lumbar-chord-tilt-deg (:trunk-flexion-deg stand-pst)
                                            (:lumbar-lordosis-deg stand-pst))]
      (is (math/nearly= (* (:length-m (segment/seg wilke-body "lumbar"))
                           (Math/sin (math/radians chord)))
                        travel 1e-12)
          (str "the chord carries T12/L1 " travel " m anterior to L5/S1, which is "
               "L_lumbar x sin(" chord " deg)"))
      (is (math/nearly= 1.5503590963286684e-4 travel 1e-15)
          (str "and it is 0.0155 cm, where the unsourced chord made it 6.685 cm: "
               travel " m"))
      (is (< travel 0.001)
          "the lumbar spine's top end is now essentially above its bottom one")
      ;; and re-rooting the very same pose leaves the moment where it was
      (let [w (pose/segment-weights wilke-body p)
            bases (load/lumbar-borne-bases stand-pst)
            m (fn [pd] (pose/gravitational-moment
                        (get-in pd [:joints :l5s1])
                        (for [s (pose/segments-on pd bases)] [s (get w (:name s))])))]
        (doseq [lm [:l5s1 :pelvis-base :mid-ankle]]
          (is (math/nearly= (m p) (m (pose/rooted-at p lm)) 1e-12)
              (str "rooted at " lm " the standing lumbosacral moment is still "
                   (m (pose/rooted-at p lm)) " N·m")))))
    ;; IT IS NO LONGER THE LARGEST TERM. `:lumbar-chord-cosine` is, at -12.34 N
    ;; against this one's 0.89, and it carries the sign of the whole difference.
    (is (= :lumbar-chord-cosine
           (:name (apply max-key #(math/abs* (:newtons %)) (:contributions d))))
        (str "the largest term is the cosine of the LEVEL's own tilt now, where "
             "it was this moment at 384.4 N of a 333.6 N difference"))
    (is (< (math/abs* moment-term)
           (math/abs* (:newtons (get by :lumbar-chord-cosine))))
        (str "the moment term is " moment-term " N against the cosine term's "
             (:newtons (get by :lumbar-chord-cosine)) " N"))
    (is (neg? (:share (get by :lumbosacral-moment-on-the-neutral-geometry)))
        (str "and its SHARE is negative — "
             (:share (get by :lumbosacral-moment-on-the-neutral-geometry))
             " — because it pushes the difference toward Wilke's sign while the "
             "difference itself has taken the other one"))
    (is (= 0.0 (:moment-nm (:sitting d)))
        "the sitting posture asks nothing of the erector spinae")
    (is (math/nearly= 0.049648103704033664 (:moment-nm (:standing d)) 1e-12)
        (str "and the standing one asks " (:moment-nm (:standing d)) " N·m of it, "
             "where it asked 21.407 N·m — from the same posture, whose trunk "
             "flexion is zero in both"))
    ;; ⚠ THE COUNTERFACTUAL STILL RUNS THE OTHER WAY FROM WILKE, and that has not
    ;; been repaired. Take the moment out — leave the lordosis, remove the moment
    ;; its chord creates — and what is left of standing is its weight term alone,
    ;; which is still below sitting's. The model's agreement with Wilke's
    ;; DIRECTION is still produced by this term; the term is 0.891 N now instead of
    ;; 384 N, so what the agreement rests on is 230 times thinner and has not
    ;; changed in kind.
    (is (< (:weight-n (:standing d)) (:weight-n (:sitting d)))
        (str "with the moment removed the model contradicts Wilke's direction: "
             "standing " (:weight-n (:standing d)) " N against sitting "
             (:weight-n (:sitting d)) " N"))))

(deftest the-trunk-mass-split-contributes-nothing-to-the-standing-sitting-difference
  ;; ONE OF THE FIVE TERMS IS EXACTLY ZERO AND THAT IS A RESULT. The T12/L1 split
  ;; took 2.0251 N off L4/L5 and moved the sitting cross-check AWAY from Wilke;
  ;; it cannot move the DIFFERENCE, because both postures carry the same segments
  ;; above the level and the subtraction cancels.
  ;;
  ;; Asserted on the weight BEFORE the tilt takes its cosine, which is the
  ;; quantity the mass split changes. If a future change made the two postures
  ;; carry different mass above L4/L5 — a desk under one of them, a support mode
  ;; that reached the trunk — this would go red, and the zero above would have
  ;; stopped being true without anybody noticing.
  (let [d (spine/standing-sitting-decomposition)
        {:keys [sitting standing]} (:weight-above-n d)]
    (is (math/nearly= sitting standing 1e-9)
        (str "the same weight sits above L4/L5 in both postures: " sitting
             " vs " standing " N"))
    ;; and the compressive terms differ by exactly the cosine of the chord
    (is (math/nearly= (* (:weight-n (:sitting d)) (:axis-vertical (:standing d)))
                      (:weight-n (:standing d)) 1e-9)
        "so the whole of the weight term's move is the chord's cosine")))

(deftest the-compression-the-tilt-drops-and-the-shear-it-creates-are-different-numbers
  ;; THIS NAMESPACE'S OWN HEADER GOT THIS WRONG, and it is worth the test rather
  ;; than a correction alone. It said the weight term falling 348.862 -> 320.531
  ;; means `28.3 N of real load leaves the model and arrives nowhere`. 28.3 N is
  ;; what leaves the COMPRESSIVE term, `W x (1 - cos)`. What appears transverse to
  ;; the level — the shear nothing here carries — is `W x sin`, and on Wilke's body
  ;; that is 137.7 N. The uncarried load was understated by a factor of about five.
  ;;
  ;; Derived from the level's own axis rather than pinned to an angle, so a change
  ;; to the chord rule moves both together.
  (let [d (spine/standing-sitting-decomposition)
        lost (:compression-lost-to-tilt-n d)
        shear (:shear-nothing-carries-n d)
        {:keys [axis-vertical axis-horizontal]} (:standing d)
        w (:standing (:weight-above-n d))]
    (is (math/nearly= (* w (- 1.0 axis-vertical)) lost 1e-9)
        (str "the compression lost is W x (1 - cos): " lost " N"))
    (is (math/nearly= (* w axis-horizontal) shear 1e-9)
        (str "the shear created is W x sin: " shear " N"))
    (is (> shear (* 4.0 lost))
        (str "and the second is several times the first — " shear " N against "
             lost " N — so a reader told only the smaller one under-reads what "
             "this model fails to carry"))
    ;; nothing carries it: the level reports a compression and no transverse term
    (is (nil? (:shear-n (first (filter #(= "L4/L5" (:name %)) (:rows (run posture/standing-neutral))))))
        "and there is no field for it anywhere in the profile")))

(deftest the-pelvis-origin-arms-unload-this-posture-and-would-load-the-mirror-of-it
  ;; THE SECOND PATH, SIZED. `pelvic-tilt-reaches-l4l5-by-a-second-path-that-is-not-
  ;; lordosis` shows the path exists; this says how much of the 334 N it is worth
  ;; at the posture that matters, and it is -25.8 N — SEVEN PER CENT, and negative.
  ;;
  ;; That is the honest correction to a story this repo has told loosely. An
  ;; anterior pelvic tilt LENGTHENS the erector spinae's moment arm (0.05447 ->
  ;; 0.05838 m), so the same moment costs less force. The 2121.6 N reported for a
  ;; POSTERIOR tilt with the lumbar spine held straight is the same path with the
  ;; arms collapsing instead, and the asymmetry between the two is why the model
  ;; answers `the tilted posture loads more` for either sign.
  (let [d (spine/standing-sitting-decomposition)
        by (into {} (map (juxt :name identity)) (:contributions d))
        arm-term (:newtons (get by :pelvis-origin-moment-arms))]
    (is (neg? arm-term)
        (str "at an ANTERIOR tilt this path unloads the level: " arm-term " N"))
    (is (< (math/abs* (:share (get by :pelvis-origin-moment-arms))) 0.10)
        "and it is under a tenth of the difference, not the story")
    (is (> (:arm-m (:standing d)) (:arm-m (:sitting d)))
        (str "because the arm is longer, not shorter: " (:arm-m (:sitting d))
             " -> " (:arm-m (:standing d)) " m"))
    ;; the eight groups the second path runs through, counted from the skeleton
    ;; rather than listed here — the number is a fact about the attachments
    (let [pelvic (into #{} (comp (filter #(= "pelvis" (:segment (:origin %))))
                                 (map #(first (str/split (:name %) #"/"))))
                       attachment/instances)]
      (is (= 8 (count pelvic))
          (str "eight muscle groups originate on the pelvis: " (pr-str (sort pelvic)))))))

(deftest the-two-wilke-cross-checks-are-pinned-at-full-precision
  ;; THE REPO CLAIMED THESE WERE BYTE-IDENTICAL THROUGH FOUR WAVES OF CHANGE AND
  ;; NOTHING ENFORCED IT. The README says so for 2026-09-08 and again for
  ;; 2026-09-09; the assertions that existed were `ratio < 1 < ratio` and
  ;; `direction-ratio > 5`, which would not have noticed a 10 N move in either
  ;; entry. Pinned here so the claim is checkable by something other than a
  ;; reader's memory.
  ;;
  ;; SITTING IS PINNED WITH `=`, exactly. It survives the 2026-09-10 re-rooting to
  ;; the bit because a seated chain is rooted at the base of its own pelvis, which
  ;; sits at x = 0 under it: the translation is along y alone, every x is
  ;; untouched, and a sagittal moment is a sum over x.
  ;;
  ;; STANDING IS PINNED TO 1e-9, and the gap is 2 units in the last place
  ;; (682.4216680362731 -> …33, relative 3e-16). A standing chain is rooted at the
  ;; ankles, whose x is not zero, so every x moves by a constant and
  ;; `(a+t) - (b+t)` is not bit-for-bit `a - b`. That is the only difference the
  ;; re-rooting made anywhere in this library, and it is here rather than hidden.
  (let [c (spine/sitting-standing-comparison)]
    (is (= 348.86176709999995 (:model-force-n (:sitting c)))
        (str "Wilke sitting must not move at all: "
             (:model-force-n (:sitting c)) " N"))
    (is (= 0.6319959548913042 (:ratio (:sitting c)))
        "and neither may its ratio")
    ;; ⚠ STANDING MOVED TWICE ON 2026-09-11, from 682.4216680362731 N. First to
    ;; 350.4300 when the pelvis stopped taking the whole lordosis and the chord
    ;; started following the measured segmental shape; then to 338.0024 when the
    ;; five lumbar levels stopped sharing one orientation and L4/L5 took its own
    ;; 15.29 deg tilt. Both moves come from the same measured table.
    ;; SITTING DID NOT MOVE AT ALL — the assertion above is still `=` — because
    ;; that posture's lordosis is zero and both changes are multiplied by it.
    (is (math/nearly= 338.00238946039593 (:model-force-n (:standing c)) 1e-9)
        (str "Wilke standing, to 1e-9: " (:model-force-n (:standing c)) " N"))
    (is (math/nearly= -10.85937763960402 (:model-difference-n c) 1e-9)
        (str "and the difference: " (:model-difference-n c) " N — NEGATIVE, so "
             "the model disagrees with Wilke about which posture loads L4/L5"))
    (is (math/nearly= -0.2262370341584171 (:difference-ratio c) 1e-9)
        (str "the ratio is negative and about a fifth in magnitude, where it was "
             "6.949 on 2026-09-10: " (:difference-ratio c)))
    ;; the evidence floor: the two entries are computed on chains rooted at
    ;; DIFFERENT points, which is why one is pinned exactly and the other is not.
    ;; Without this a reader could take the looser tolerance for carelessness.
    (is (= :pelvis-base (:landmark (:root (pose/solve-pose
                                           wilke-body
                                           (:posture (spine/reference-by-id
                                                      :wilke-1999-sitting-relaxed-no-backrest))))))
        "the sitting entry is a seated chain, rooted on its own pelvis at x = 0")
    (is (= :mid-ankle (:landmark (:root (pose/solve-pose
                                         wilke-body
                                         (:posture (spine/reference-by-id
                                                    :wilke-1999-relaxed-standing))))))
        "the standing entry is a foot-rooted chain, whose ankles are not at x = 0")))

;; --- the five lumbar levels stop sharing one orientation (2026-09-11) --------

(deftest the-turn-table-tiles-the-model-s-own-lumbar-levels
  ;; The two halves of this are in two namespaces and neither can see the other:
  ;; `posture/lumbar-turn-nodes` spaces its nodes evenly because a motion segment
  ;; is a fifth of the lumbar spine, and `spine/levels` places five lumbar discs at
  ;; even fifths. If either changed alone the turn a level is given would stop
  ;; being the turn measured across the motion segment below it, silently.
  (let [lumbar (filterv #(= "lumbar" (:segment %)) spine/levels)
        alongs (mapv :along lumbar)
        nodes (mapv first posture/lumbar-turn-nodes)]
    (is (= 5 (count lumbar)) "five lumbar levels")
    (is (= 5 (count posture/lumbar-segmental-shares)) "and five measured spans")
    (is (= alongs (vec (butlast nodes)))
        (str "every level sits exactly on a node: levels at " alongs
             ", nodes at " nodes))
    (is (= 1.0 (last nodes))
        "and the last node is the top of the lumbar spine, which carries no disc")
    ;; the mapping that makes this exact rather than approximate: a disc's lower
    ;; boundary IS the superior endplate of the vertebra below it, which is the
    ;; upper end of the motion segment Mills names
    (is (= ["L5/S1" "L4/L5" "L3/L4" "L2/L3" "L1/L2"] (mapv :name lumbar)))
    (is (= ["L5-S1" "L4-L5" "L3-L4" "L2-L3" "L1-L2"]
           (mapv :span posture/lumbar-segmental-shares))
        (str "and the spans run between the same endplates in the same order — "
             "span k is the turn from level k's own endplate to level k+1's"))))

(deftest the-five-lumbar-levels-have-five-different-axes
  ;; THE GAP THIS CLOSES, named in `levels`' own comment since 2026-09-08: `all
  ;; five still share ONE segment's frame — a reader should not take five lumbar
  ;; rows as five independently oriented joints`. They do not any more.
  (let [lord (pose/solve-pose body posture/quiet-standing)
        flat (pose/solve-pose body posture/quiet-standing-lumbar-neutral)
        lumbar (filterv #(= "lumbar" (:segment %)) spine/levels)
        tilt (fn [p l] (let [[x y _] (:axis (spine/level-point p l))]
                         (math/degrees (Math/atan2 x y))))]
    ;; five distinct orientations under a lordosis
    (let [tilts (mapv #(tilt lord %) lumbar)]
      (is (= 5 (count (distinct tilts))) (str "five distinct axes: " tilts))
      (is (apply > tilts)
          (str "and they run from anterior at the sacrum to posterior at the top, "
               "which is what a lordosis is: " tilts))
      ;; pinned, from Mills' measured shape and Cho's 46.5 deg
      (doseq [[l expected] (map vector lumbar [27.247368421052627 15.287675522780077
                                               2.6139711081927466 -7.382190120214162
                                               -14.254550964743913])]
        (is (math/nearly= expected (tilt lord l) 1e-9)
            (str (:name l) ": " (tilt lord l) " deg from vertical")))
      ;; the sacral end is the pelvis's own rotation and the top is that minus the
      ;; whole lordosis — derived, so the ends are tied to the two measurements
      (is (math/nearly= (pose/pelvic-rotation-deg 46.5) (first tilts) 1e-9)
          "the bottom level's axis IS the pelvis's rotation")
      (is (math/nearly= 46.5 (- (first tilts)
                                (pose/lumbar-tangent-tilt-deg 0.0 46.5 1.0))
                        1e-9)
          "and the whole lordosis is spent between the bottom level and the top"))
    ;; AND THE CONTROL: with no lordosis all five take the segment's own axis, to
    ;; the bit. Without this the test above would pass against a model that had
    ;; simply started scattering axes.
    (let [seg-axis (:long (:frame (pose/seg-at flat "lumbar")))]
      (doseq [l lumbar]
        (is (= seg-axis (:axis (spine/level-point flat l)))
            (str (:name l) ": at zero lordosis the axis is the segment's, exactly"))
        (is (= 0.0 (spine/level-axis-offset-deg flat l))
            (str (:name l) ": and the offset is exactly zero"))))
    ;; and the cervical levels are untouched, which is the scope of the change
    (doseq [l (filterv #(not= "lumbar" (:segment %)) spine/levels)]
      (is (= 0.0 (spine/level-axis-offset-deg lord l))
          (str (:name l) ": only the lumbar spine has a measured shape, so only "
               "its levels are oriented separately — the five cervical levels "
               "sharing `lower_cervical`'s frame is the same gap one region up"))
      (is (= (:long (:frame (pose/seg-at lord (:segment l))))
             (:axis (spine/level-point lord l)))
          (str (:name l) ": exactly the segment's axis")))))

(deftest a-lumbar-level-s-axis-is-the-arc-s-tangent-at-its-own-position
  ;; The tie between `pose`, which states the shape, and `spine`, which reads it.
  ;; Two files could disagree about where a level sits in the turn and nothing
  ;; would notice, because each one is internally consistent.
  (doseq [[trunk lateral lordosis]
          [[0.0 0.0 46.5] [25.0 0.0 30.0] [0.0 15.0 46.5] [10.0 -10.0 -20.0]]]
    (let [pst {:head-flexion-deg 0.0 :trunk-flexion-deg trunk
               :shoulder-flexion-deg 0.0 :elbow-flexion-deg 0.0
               :trunk-lateral-bend-deg lateral :lumbar-lordosis-deg lordosis}
          p (pose/solve-pose body pst)]
      (doseq [l (filterv #(= "lumbar" (:segment %)) spine/levels)]
        (let [axis (:axis (spine/level-point p l))
              expected (pose/lumbar-tangent-tilt-deg trunk lordosis (:along l))
              chord (pose/lumbar-chord-tilt-deg trunk lordosis)
              seg (:long (:frame (pose/seg-at p "lumbar")))]
          ;; the rotation is in the segment's OWN sagittal plane, so the angle
          ;; between the level's axis and the chord is the offset whatever the
          ;; lateral bend does to both of them
          (is (math/nearly= (math/abs* (- expected chord))
                            (math/degrees
                             (Math/acos (math/clamp (math/vdot axis seg) -1.0 1.0)))
                            1e-9)
              (str (:name l) " at trunk " trunk " lateral " lateral ": the angle "
                   "between the level's axis and the segment's is |tangent - "
                   "chord|"))
          ;; and the axis is a unit vector, which a Rodrigues rotation of one must be
          (is (math/nearly= 1.0 (math/vlen axis) 1e-12)
              (str (:name l) ": the axis must stay a unit vector")))))))

(deftest l5s1-now-carries-less-axial-compression-than-l4l5-and-that-is-the-shear
  ;; A RESULT THAT LOOKS LIKE A REGRESSION AND IS NOT ONE, recorded so the next
  ;; reader does not repair it.
  ;;
  ;; The lumbar profile used to fall monotonically from L5/S1 upward, because every
  ;; level took the same cosine of the weight above it and there is more weight
  ;; above the lower ones. With each level taking its own tilt, L5/S1 stands 27.25
  ;; deg from vertical at Cho's standing lordosis and L4/L5 stands 15.29, so L5/S1
  ;; keeps `cos(27.25) = 0.889` of its weight against L4/L5's 0.965 — and the model
  ;; reports LESS axial compression at the lowest lumbar level than at the one
  ;; above it.
  ;;
  ;; That is right about the axial component and wrong about the disc. What is
  ;; missing is the shear: at 27.25 deg the transverse component at L5/S1 is large,
  ;; a real lumbosacral junction resists it with its facets and its own muscles,
  ;; and this model has neither. So the crossing is a visible symptom of a
  ;; limitation this repo has named since 2026-09-08 rather than a new defect.
  (let [tensions (muscle/solve-muscle-tensions
                  body posture/quiet-standing
                  (load/solve-posture-loads body posture/quiet-standing))
        rows (into {} (map (juxt :name identity))
                   (spine/profile body posture/quiet-standing tensions))
        w (fn [n] (:weight-n (get rows n)))]
    (is (< (w "L5/S1") (w "L4/L5"))
        (str "L5/S1 takes " (w "L5/S1") " N axially and L4/L5 takes " (w "L4/L5")
             " N — the lowest lumbar level is no longer the most loaded one"))
    ;; and the reason is the cosine and nothing else: the weight ABOVE L5/S1 is
    ;; still larger, which is the control that this is a projection and not a
    ;; change in what sits on top
    (let [tilt-of (fn [n] (pose/lumbar-tangent-tilt-deg
                           0.0 46.5
                           (:along (first (filter #(= n (:name %)) spine/levels)))))
          undo (fn [n] (/ (w n) (Math/cos (math/radians (tilt-of n)))))]
      (is (> (undo "L5/S1") (undo "L4/L5"))
          (str "before the projection L5/S1 still carries more: " (undo "L5/S1")
               " N against " (undo "L4/L5") " N"))
      (is (math/nearly= (- (undo "L5/S1") (undo "L4/L5"))
                        (- (/ (w "L5/S1") (Math/cos (math/radians (tilt-of "L5/S1"))))
                           (/ (w "L4/L5") (Math/cos (math/radians (tilt-of "L4/L5")))))
                        1e-12)))
    ;; pinned, both of them, so a change that restores the old order has to say
    ;; which of the two it moved
    (is (math/nearly= 327.11760130082604 (w "L5/S1") 1e-9))
    (is (math/nearly= 336.51699901433295 (w "L4/L5") 1e-9))))
