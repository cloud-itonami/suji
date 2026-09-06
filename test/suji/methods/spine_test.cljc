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

(defn- refusal-data
  "The ex-data of whatever `f` threw, or nil if it returned."
  [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) e (ex-data e))))

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

(deftest passive-tension-smoothed-the-profile
  ;; the measured consequence, kept so that losing it would be visible
  (let [rows (:rows (run lap))]
    (is (empty? (spine/attachment-steps rows))
        (str "no level loses its whole muscle term any more: "
             (mapv (juxt :name :muscle-n) rows)))
    (is (every? #(pos? (:muscle-n %)) (remove #(= "C3/C4" (:name %)) rows))
        "every level below the top of the neck carries some muscle force")))
