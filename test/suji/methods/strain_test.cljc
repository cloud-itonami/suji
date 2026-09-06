(ns suji.methods.strain-test
  "suji (筋) — the sustained-isometric dose layer against the published endurance
  literature.

  WHAT THESE TESTS ARE FOR. `muscle-strain-test` already checks that the dose layer
  is internally consistent — that endurance falls with load, that the index grows
  with time, that a refusal passes through. None of that can tell whether the curve
  is the right curve. These tests hold it to numbers somebody measured, and where
  they find a disagreement they PIN THE DISAGREEMENT rather than the agreement, so
  that closing the gap by tuning a coefficient fails a test and has to be argued
  for.

  Reference values are recomputed here from the coefficients as printed in the
  source's Table 2, not read back out of `strain`, so a transcription error in the
  namespace under test cannot agree with itself."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]))

;; --- independent restatements of the two curves ------------------------------
;; Written out longhand from the papers rather than referred to `strain`, because
;; a test that asks the code under test for its own reference value only checks
;; that the code is self-consistent.

(defn- model-minutes*
  "T_end = 0.2 · f^-2.32 minutes, as `strain`'s docstring states it."
  [mvc-pct]
  (* 0.2 (Math/pow (/ mvc-pct 100.0) -2.32)))

(def ^:private table-2
  "Frey Law & Avin (2010) Table 2, power models. `ET = b0 · MVC^b1`, MVC a
  fraction, ET in seconds."
  {:general  [21.92 -1.98]
   :ankle    [34.71 -2.06]
   :trunk    [22.69 -2.27]
   :elbow    [17.98 -2.21]
   :grip     [33.55 -1.61]
   :knee     [19.38 -1.88]
   :shoulder [14.86 -1.83]})

(defn- reference-minutes* [region mvc-pct]
  (let [[b0 b1] (table-2 region)]
    (/ (* b0 (Math/pow (/ mvc-pct 100.0) b1)) 60.0)))

;; --- what the model implements ----------------------------------------------

(deftest the-implemented-curve-is-the-one-the-docstring-claims
  ;; The docstring says `T_end(f) ≈ 0.2 · f^-2.32 minutes`. That sentence and the
  ;; arithmetic two lines below it are separate artefacts and have drifted apart in
  ;; this repo before (see the README's Corrections). This asserts they have not.
  (doseq [p [10.0 15.0 25.0 41.0 60.0 100.0]]
    (is (math/nearly= (strain/endurance-minutes p) (model-minutes* p) 1e-9)
        (str "endurance-minutes is not 0.2·f^-2.32 at " p " %MVC")))
  ;; and `model-form` must restate the SAME two constants, not a remembered pair
  (is (= 0.2 (:coefficient-minutes strain/model-form)))
  (is (= -2.32 (:exponent strain/model-form)))
  (is (math/nearly= (strain/endurance-minutes 30.0)
                    (* (:coefficient-minutes strain/model-form)
                       (Math/pow 0.30 (:exponent strain/model-form)))
                    1e-9))
  ;; the seconds form in `model-form` is the same curve scaled, and 0.2 min = 12 s
  (is (math/nearly= 12.0 (* 60.0 (:coefficient-minutes strain/model-form)) 1e-9)))

(deftest the-constants-are-not-claimed-to-be-published
  ;; The provenance of 0.2 and -2.32 could not be traced to any fit that was
  ;; obtained. Saying so is the honest state; a later `:provenance :full-text`
  ;; must arrive together with a citation, not on its own.
  (is (= :could-not-obtain (:provenance strain/model-form)))
  (is (string? (:provenance-note strain/model-form)))
  ;; and the unobtained references are listed rather than silently absent
  (is (<= 3 (count strain/unobtained-references)))
  (doseq [r strain/unobtained-references]
    (is (= :could-not-obtain (:outcome r)) (str "unobtained entry with no outcome: " (:citation r)))
    (is (keyword? (:reason r)))
    (is (string? (:citation r)))))

;; --- the reference itself ----------------------------------------------------

(deftest the-published-coefficients-are-transcribed-correctly
  ;; Table 2 as printed. A single digit wrong here would move every ratio in this
  ;; file and nothing else would notice.
  (doseq [[region [b0 b1]] table-2]
    (let [m (get-in strain/frey-law-2010 [:power-models region])]
      (is (= b0 (:b0 m)) (str region " b0"))
      (is (= b1 (:b1 m)) (str region " b1"))))
  ;; the units the caption states: fraction in, seconds out — so a conversion has
  ;; to happen, and the namespace must be doing it
  (is (= :seconds (:time-unit strain/frey-law-2010)))
  (is (= :fraction-of-mvc (:intensity-unit strain/frey-law-2010)))
  (is (math/nearly= (strain/reference-endurance-minutes :general 50.0)
                    (/ (* 21.92 (Math/pow 0.5 -1.98)) 60.0)
                    1e-9)
      "reference-endurance-minutes must convert the published seconds to minutes"))

(deftest a-region-the-reference-does-not-have-returns-nil-not-a-number
  ;; nil rather than an exception and rather than a fallback to the pooled curve:
  ;; a curve that does not exist must not be usable as if it did.
  (is (nil? (strain/reference-endurance-minutes :neck 40.0)))
  (is (nil? (strain/reference-endurance-minutes nil 40.0)))
  (is (number? (strain/reference-endurance-minutes :shoulder 40.0))))

(deftest every-reference-value-carries-its-provenance
  ;; The rule this repo already applies in `spine`: a reference value with no
  ;; provenance is indistinguishable from an invented one.
  (doseq [[label m] [["frey-law-2010" strain/frey-law-2010]
                     ["reference-prediction-interval" strain/reference-prediction-interval]
                     ["measured-single-point" strain/measured-single-point]]]
    (is (string? (:citation m)) (str label " citation"))
    (is (string? (:url m)) (str label " url"))
    (is (contains? #{:full-text :abstract} (:obtained m))
        (str label " must say whether the full text or only an abstract was read"))))

;; --- the model against the pooled curve --------------------------------------

(deftest against-the-pooled-curve-the-model-agrees-in-the-middle
  ;; 15 and 25 %MVC are inside the reference's fitted range and inside even the
  ;; tight reading of its own prediction interval.
  (doseq [p [15.0 25.0 41.0]]
    (let [c (strain/endurance-cross-check p)]
      (is (= :within-fitted-range (:model-position c)) (str p " should be inside the fitted range"))
      (is (:within-reference-spread? c) (str p " left the wide band"))
      (is (:within-narrow-reference-spread? c) (str p " left the tight band"))
      (is (math/nearly= (:reference-minutes c) (reference-minutes* :general p) 1e-9)
          (str p " reference value")))))

(deftest against-the-pooled-curve-the-model-runs-short-at-high-load
  ;; and the disagreement is stated as a direction, not left to a reader to
  ;; notice. At 100 %MVC the model says 0.20 min where the pooled fit says 0.37.
  (let [c (strain/endurance-cross-check 100.0)]
    (is (< (:ratio c) 0.71)
        "at 100 %MVC the model must be outside the tight ±29% band, below it")
    (is (not (:within-narrow-reference-spread? c)))
    (is (math/nearly= (:model-minutes c) 0.2 1e-12)
        "0.2 min is the model's value at exactly maximum voluntary contraction")
    (is (math/nearly= (:reference-minutes c) (/ 21.92 60.0) 1e-9)
        "the pooled fit at f=1 is just b0 seconds")))

;; --- the model against the joint-specific curves -----------------------------

(deftest against-the-shoulder-curve-the-model-disagrees-and-says-which-way
  ;; This is the finding, and it is pinned so that closing it by tuning fails.
  ;; The shoulder is the reference's most fatigable region and this model has one
  ;; curve for the whole body, so it necessarily over-predicts here.
  (let [c (strain/endurance-cross-check :shoulder 27.7)]
    (is (> (:ratio c) 1.47)
        "the model must be ABOVE the reference's own wide band at the shoulder")
    (is (false? (:within-reference-spread? c)))
    (is (= :model-above-reference (:direction c))
        "and the direction must name which way, not merely that they differ")
    (is (math/nearly= (:reference-minutes c) (reference-minutes* :shoulder 27.7) 1e-9)))
  ;; the trunk goes the OTHER way at the same posture, which is the point:
  ;; one whole-body curve cannot be right for both.
  (let [c (strain/endurance-cross-check :trunk 25.7)]
    (is (< (:ratio c) 1.0)
        "against the trunk curve the same model runs short")))

(deftest the-between-joint-spread-is-bigger-than-the-model-s-own-error
  (let [s (strain/joint-spread 20.0)
        pooled (strain/endurance-cross-check 20.0)]
    (is (> (:fold s) 3.0)
        "the reference's six curves must differ by more than threefold at 20 %MVC")
    ;; the paper's own ordering: `the ankle was most fatigue-resistant ... the
    ;; shoulder was most fatigable`. If the transcription swapped two rows this
    ;; would not hold.
    (is (= :ankle (key (apply max-key val (:by-region s)))))
    (is (= :shoulder (key (apply min-key val (:by-region s)))))
    ;; and the spread dwarfs how far the model is from the pooled curve there
    (is (> (:fold s) (/ 1.0 (:ratio pooled)))
        "if the model's own error exceeded the between-joint spread, a single curve would be the smaller problem")))

;; --- the two extrapolation boundaries ----------------------------------------

(deftest below-the-floor-the-model-is-unbounded-and-refuses-a-ratio
  ;; A desk posture lives here. The model says the load can be held forever; the
  ;; published fit returns 138 minutes at 5 %MVC. Neither side is measured, and
  ;; the check must say that rather than producing a number either way.
  (let [c (strain/endurance-cross-check 5.0)]
    (is (:model-unbounded? c))
    (is (= :below-endurance-floor (:model-position c)))
    (is (nil? (:ratio c)) "an infinity must not be divided into a report")
    (is (= :model-returns-no-finite-endurance (:could-not-obtain c))
        "and the reason must be this one, not the no-curve one")
    (is (= :model-unbounded-reference-finite (:direction c)))
    (is (> (:reference-minutes c) 100.0)
        "the reference does return a finite number here, which is the disagreement"))
  ;; the floor is a modelling choice and is labelled as one
  (is (true? (:floor-is-a-modelling-choice strain/model-form)))
  (is (= 8.0 strain/endurance-floor-pct))
  ;; and the floor sits BELOW the reference's fitted range, so nothing measured
  ;; covers the region where this model answers `forever`
  (is (< strain/endurance-floor-pct (first (:fitted-range-pct strain/frey-law-2010)))))

(deftest above-maximum-voluntary-contraction-the-answer-is-marked-not-clamped
  ;; `muscle` deliberately does not clamp %MVC above 100, so this layer receives
  ;; values above it. The number is still produced — clamping would erase the
  ;; finding — but it must no longer be produced silently.
  (let [e (strain/endurance 111.6)]
    (is (= :above-maximum-voluntary-contraction (:position e)))
    (is (:extrapolated? e))
    (is (number? (:minutes e)) "the value is reported, not refused")
    (is (< (:minutes e) 0.2) "and it is the power law continued, i.e. shorter than at 100 %MVC"))
  ;; the boundary is the reference's stated upper bound, not a number chosen here
  (is (= 100.0 (second (:fitted-range-pct strain/frey-law-2010))))
  (is (= :within-fitted-range (:position (strain/endurance 100.0))))
  (is (= :above-maximum-voluntary-contraction (:position (strain/endurance 100.001)))))

(deftest the-region-between-the-floor-and-the-fitted-range-is-its-own-answer
  ;; 8–10 %MVC: the model returns a finite number, and nothing measured covers it.
  ;; Two of this actor's muscles sit here in an ordinary laptop posture.
  (let [e (strain/endurance 9.0)]
    (is (= :below-fitted-range (:position e)))
    (is (:extrapolated? e))
    (is (not (:unbounded? e)))
    (is (number? (:minutes e)))))

;; --- the flag travels with the dose ------------------------------------------

(deftest the-dose-carries-the-extrapolation-flag
  ;; Before 2026-09-07 a dose built on an extrapolated endurance time and a dose
  ;; built on one inside the fitted range were reported in exactly the same shape,
  ;; and nothing in the value said which was which.
  (let [inside (strain/muscle-strain {:name "x" :mvc-pct 20.0} 120.0)
        under (strain/muscle-strain {:name "x" :mvc-pct 5.0} 120.0)
        over (strain/muscle-strain {:name "x" :mvc-pct 111.6} 120.0)]
    (is (false? (:endurance-extrapolated? inside)))
    (is (= :within-fitted-range (:endurance-position inside)))
    (is (false? (:unbounded-endurance? inside)))

    (is (true? (:endurance-extrapolated? under)))
    (is (true? (:unbounded-endurance? under)))
    (is (= :below-endurance-floor (:endurance-position under)))
    (is (zero? (:acute-dose under)) "no acute dose accrues below the floor")

    (is (true? (:endurance-extrapolated? over)))
    (is (= :above-maximum-voluntary-contraction (:endurance-position over))))
  ;; a refused entry has no position either — nil, not a default that reads as
  ;; `inside the range`
  (let [r (strain/muscle-strain {:name "x" :refused :coefficient-below-floor :mvc-pct nil} 120.0)]
    (is (nil? (:endurance-position r)))
    (is (nil? (:endurance-extrapolated? r)))
    (is (= :coefficient-below-floor (:refused r)))))

;; --- routing this actor's muscles to the reference's regions -----------------

(deftest the-neck-has-no-published-curve-and-the-check-refuses-rather-than-substituting
  ;; The cervical extensors produce this app's headline band. The reference has no
  ;; neck region, so there is nothing to hold them to, and falling back to the
  ;; pooled curve without saying so would be the bad move.
  (is (not-any? #{:neck :cervical :head} (keys (:power-models strain/frey-law-2010)))
      "if a neck curve ever appears in the reference this test should be deleted, not weakened")
  (is (nil? (:region (strain/task->reference-region :cervical-extension))))
  (is (= :absent-from-source (:basis (strain/task->reference-region :cervical-extension))))
  (let [c (strain/tension-cross-check {:name "cervical_extensors"
                                       :task :cervical-extension
                                       :mvc-pct 41.0})]
    (is (= :no-published-curve-for-this-region (:could-not-obtain c))
        "the reason must be the missing curve, not a missing %MVC and not an unbounded model")
    (is (nil? (:ratio c)))
    (is (nil? (:reference-minutes c)))))

(deftest a-refusal-and-a-missing-curve-are-different-answers
  ;; Three distinct reasons a comparison can fail to produce a ratio, and they must
  ;; not collapse into one another: a muscle the model refused, a region the
  ;; literature does not cover, and a load below this model's own floor.
  (let [refused (strain/tension-cross-check {:name "a" :task :shoulder-flexion
                                             :refused :acts-the-wrong-way :mvc-pct nil})
        no-curve (strain/tension-cross-check {:name "b" :task :cervical-extension :mvc-pct 30.0})
        unbounded (strain/tension-cross-check {:name "c" :task :shoulder-flexion :mvc-pct 3.0})]
    (is (= :acts-the-wrong-way (:could-not-obtain refused)))
    (is (= :no-published-curve-for-this-region (:could-not-obtain no-curve)))
    (is (= :model-returns-no-finite-endurance (:could-not-obtain unbounded)))
    (is (= 3 (count (set (map :could-not-obtain [refused no-curve unbounded])))))))

(deftest the-wrist-is-mapped-to-the-nearest-region-and-labelled-as-nearest
  ;; The reference's hand/grip region is grip force held to failure; this actor's
  ;; wrist task is a posture. Comparing them is worth doing and is not the same
  ;; claim as comparing a trunk to a trunk, so the basis is carried.
  (is (= :grip (:region (strain/task->reference-region :wrist-flexion))))
  (is (= :nearest-region (:basis (strain/task->reference-region :wrist-flexion))))
  (is (= :named-in-source (:basis (strain/task->reference-region :trunk-extension))))
  (let [c (strain/tension-cross-check {:name "wrist_extensors" :task :wrist-flexion :mvc-pct 10.4})]
    (is (= :nearest-region (:region-basis c))
        "a nearest-region comparison must not be presented as a named-region one")
    (is (number? (:ratio c)))))

;; --- a whole posture ---------------------------------------------------------

(deftest a-session-cross-check-separates-what-was-checked-from-what-was-not
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        tensions (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p))
        sc (strain/session-cross-check tensions)]
    (is (= (:total sc) (count tensions)))
    (is (< (:compared sc) (:total sc))
        "if everything compared, the could-not-obtain branches are not being reached")
    (is (pos? (:compared sc)))
    ;; the summary must not let a reader skim the rows and conclude everything was
    ;; checked: the buckets are named and counted
    (is (contains? (:summary sc) :compared))
    (is (contains? (:summary sc) :no-published-curve-for-this-region)
        "the cervical extensors are in this posture and have no curve")
    (is (contains? (:summary sc) :model-returns-no-finite-endurance)
        "and several groups sit below the model's own floor")
    ;; and the disagreement is present in a real posture, not only at chosen inputs
    (let [outside (filter #(false? (:within-reference-spread? %))
                          (remove :could-not-obtain (:rows sc)))]
      (is (seq outside)
          "at least one muscle in laptop-on-lap must fall outside the reference's own spread — the layer is not validated"))))

(deftest the-measured-single-point-is-carried-with-its-own-caveat
  ;; One directly measured endurance time, so the comparison is not entirely
  ;; against other people's regressions. It is not asserted to be met: the same
  ;; paper shows the value moving 15% with the height of the forearm.
  (let [m strain/measured-single-point]
    (is (= 15.0 (:mvc-pct m)))
    (is (math/nearly= (:minutes m) (/ 455.9 60.0) 1e-9))
    (is (= 14 (:n m)))
    (is (string? (:position-effect-note m))))
  ;; the model at that intensity is more than twice the measured handgrip time —
  ;; recorded, not tuned away
  (is (> (/ (strain/endurance-minutes 15.0) (:minutes strain/measured-single-point)) 2.0)))
