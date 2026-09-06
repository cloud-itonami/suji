(ns suji.methods.spine-test
  "Level-by-level spinal compression: that the muscle term is there and dominant,
  that the profile is ordered the way the physics implies, and that the model says
  where it disagrees with the leg this actor actually validated."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
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
    (is (math/nearly= 1.719 (:ratio x) 0.01)
        (str "today's disagreement, measured rather than banded: " x))
    (is (math/nearly= 470.299 (:level-force-n x) 0.01)
        (str "the C7/T1 force with only the muscles that reach a neck in it: " x))
    (is (math/nearly= 273.62 (:lumped-force-n x) 0.01)
        "the lumped side did not move; this repair is on the profile side only")))

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
    (is (= "thorax_abdomen" (:segment (:insertion es))) "and inserts above it")
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
                      :origin {:segment "thorax_abdomen" :along 0.97}
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
                                    :insertion {:segment "thorax_abdomen" :along 0.5}}))]
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
  ;; and standing is refused for a different reason: a seated model with no thigh
  ;; segment cannot tell standing from sitting, while Wilke measures them apart
  (let [x (spine/lumbar-cross-check
           (spine/reference-by-id :wilke-1999-relaxed-standing))]
    (is (= :standing-is-not-representable (:could-not-obtain x)))
    (is (nil? (:ratio x)))))

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
    :origin {:segment "thorax_abdomen" :along 0.88 :ant -0.0141 :lat 0.0}
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
