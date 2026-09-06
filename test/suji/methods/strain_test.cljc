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

(deftest the-lower-limb-routes-to-the-regions-the-reference-actually-fitted
  ;; The knee and the ankle ARE in the meta-analysis by name, so the lower limb
  ;; that landed 2026-09-07 can be held to its own curves rather than to the
  ;; pooled one. The HIP is not: the paper lists `hip` among its search terms and
  ;; fits no hip curve. Unlike the neck it has a defensible nearest region — the
  ;; reference's own discussion groups trunk and hip — so it routes to `:trunk`
  ;; and is labelled `:nearest-region`, which is a different claim from `:named`.
  (is (= :knee (:region (strain/task->reference-region :knee-extension))))
  (is (= :named-in-source (:basis (strain/task->reference-region :knee-extension))))
  (is (= :ankle (:region (strain/task->reference-region :ankle-plantarflexion))))
  (is (= :named-in-source (:basis (strain/task->reference-region :ankle-plantarflexion))))
  (is (= :trunk (:region (strain/task->reference-region :hip-extension))))
  (is (= :nearest-region (:basis (strain/task->reference-region :hip-extension)))
      "there is no hip curve in the reference; calling this one `named` would claim there is")
  (is (not-any? #{:hip} (keys (:power-models strain/frey-law-2010))))
  ;; every task this actor's anatomy declares must be routed, or a muscle silently
  ;; falls through to the default and is reported as having no curve when the
  ;; reference has one
  (doseq [t [:cervical-extension :cervical-lateral-flexion :scapular-suspension
             :shoulder-flexion :shoulder-abduction :trunk-extension
             :trunk-lateral-flexion :elbow-flexion :wrist-flexion
             :knee-extension :ankle-plantarflexion :hip-extension]]
    (is (contains? strain/task->reference-region t)
        (str "task " t " is not routed to a reference region"))))

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

;; --- what the headline band can actually resolve ------------------------------
;;
;; The band is the column the README's answer table and the browser comparison
;; view are read through. These tests pin the finding that at this app's session
;; lengths it distinguishes two states, and they pin it from BOTH sides: by
;; inverting the model (`band-resolution`) and by sweeping it, so an inversion bug
;; and a modelling change cannot cancel.

(defn- sweep
  "The %MVC axis at 0.05 steps. `(/ i 20.0)` rather than `(* 0.05 i)` because the
  latter is not exact at 8.0 and the floor is the point under test."
  []
  (map #(/ % 20.0) (range 0 2001)))

(defn- in-segment?
  "The endpoint convention `band-resolution` documents: `[a, b)`, open at `a` when
  `a` is the endurance floor, closed at `b` when `b` is the floor or the top of
  the axis."
  [[a b] f]
  (and (if (= a strain/endurance-floor-pct) (> f a) (>= f a))
       (if (or (= b strain/endurance-floor-pct) (= b 100.0)) (<= f b) (< f b))))

(deftest the-two-middle-bands-are-unreachable-at-this-app-s-session-lengths
  ;; NOT "no reference posture happens to land there" — unreachable. The index
  ;; steps across their entire combined width at the endurance floor, so no %MVC
  ;; whatsoever maps into them.
  (doseq [t [120.0 480.0]]
    (let [r (strain/band-resolution t)]
      (is (= ["moderate" "high"] (:unreachable-bands r))
          (str "at " t " min the middle bands must be unreachable, not merely unvisited"))
      (is (= ["low" "very-high"] (:reachable-bands r)))
      (doseq [b (:bands r) :when (#{"moderate" "high"} (:band b))]
        (is (zero? (:mvc-pct-width b))
            (str (:band b) " claims " (:mvc-pct-width b) " %MVC of the axis")))))
  ;; and at 30 min all four ARE reachable, so the assertion above is about the
  ;; session length and not about a band function that can only return two things
  (is (= ["low" "moderate" "high" "very-high"]
         (:reachable-bands (strain/band-resolution 30.0)))
      "at 30 min the floor step is not yet wide enough to clear `moderate`"))

(deftest the-session-length-at-which-each-band-dies-is-solved-not-sampled
  ;; `:unreachable-window-minutes` is closed form from `chronic-weight`,
  ;; `chronic-threshold-pct`, `endurance-floor-pct` and the power-law constants.
  ;; It is checked here by asking `band-resolution` on either side of each end,
  ;; which is a different computation (the inversion) than the one that produced
  ;; the window.
  (doseq [band ["moderate" "high"]]
    (let [b (first (filter #(= band (:band %)) (:bands (strain/band-resolution 120.0))))
          [from to] (:unreachable-window-minutes b)]
      (is (number? from))
      (is (number? to))
      (is (< from to))
      (is (some #(and (= band (:band %)) (:reachable? %))
                (:bands (strain/band-resolution (* from 0.99))))
          (str band " must still be reachable just before " from " min"))
      (is (some #(and (= band (:band %)) (not (:reachable? %)))
                (:bands (strain/band-resolution (* from 1.01))))
          (str band " must be unreachable just after " from " min"))
      (is (some #(and (= band (:band %)) (:reachable? %))
                (:bands (strain/band-resolution (* to 1.01))))
          (str band " returns above " to " min, from the chronic term below the floor"))))
  ;; the numbers themselves, recomputed longhand from the model's constants — the
  ;; window is `-ln(1-hi)/k_above` and `-ln(1-lo)/k_below`, where k is d(dose)/dT
  ;; on each side of the floor. Asking `band-resolution` for its own answer would
  ;; only check that it is self-consistent.
  (let [bands (:bands (strain/band-resolution 120.0))
        w (fn [n] (:unreachable-window-minutes (first (filter #(= n (:band %)) bands))))
        k-below (* strain/chronic-weight
                   (/ (- strain/endurance-floor-pct strain/chronic-threshold-pct) 100.0)
                   (/ 1.0 60.0))
        k-above (+ k-below (/ 1.0 (model-minutes* strain/endurance-floor-pct)))]
    (is (math/nearly= (first (w "moderate")) (/ (- (Math/log 0.55)) k-above) 1e-9))
    (is (math/nearly= (first (w "high")) (/ (- (Math/log 0.30)) k-above) 1e-9))
    (is (math/nearly= (second (w "moderate")) (/ (- (Math/log 0.80)) k-below) 1e-9))
    ;; and the values those come out at, so moving a coefficient is visible
    (is (math/nearly= (first (w "moderate")) 40.6398 1e-3))
    (is (math/nearly= (first (w "high")) 81.8437 1e-3))
    (is (math/nearly= (second (w "moderate")) 495.8746 1e-3))))

(deftest band-resolution-inversion-agrees-with-sampling-the-model
  ;; The inversion is the claim; this sweeps the model itself and requires the two
  ;; to agree exactly, band by band, at three session lengths. An inversion that
  ;; quietly widened a segment would make the finding look smaller than it is.
  (doseq [t [30.0 120.0 480.0 600.0]]
    (let [r (strain/band-resolution t)
          xs (sweep)
          sampled (frequencies (map #(strain/stiffness-band (strain/index-at % t)) xs))]
      (doseq [b (:bands r)]
        (let [predicted (count (filter (fn [f] (some #(in-segment? % f) (:mvc-pct-segments b))) xs))]
          (is (= (get sampled (:band b) 0) predicted)
              (str "at " t " min, band " (:band b) ": swept "
                   (get sampled (:band b) 0) " of " (count xs)
                   " samples, inversion predicts " predicted)))))))

(deftest the-floor-step-is-wider-than-the-two-middle-bands-put-together
  (let [d (strain/floor-discontinuity 120.0)
        middle (- 0.70 0.20)]
    (is (math/nearly= (:index-below d) 0.0525679 1e-6))
    (is (math/nearly= (:index-above d) 0.8288604 1e-6))
    (is (> (:index-gap d) middle)
        (str "the step is " (:index-gap d) " and the two middle bands span " middle))
    (is (= "low" (:band-below d)))
    (is (= "very-high" (:band-above d)))
    ;; and it is a step in the ACUTE term, not in the curve: endurance goes from
    ;; unbounded straight to 70 minutes
    (is (math/infinite? (:endurance-minutes-below d)))
    (is (math/nearly= (:endurance-minutes-above d) 70.1231 1e-3))))

(deftest at-the-floor-the-model-and-the-published-fit-still-agree
  ;; This is the part of the finding that cuts AGAINST the floor, so it is pinned
  ;; in both directions. The model's power law at the floor is 70.12 min; the
  ;; pooled fit is 54.27; the ratio 1.29 is inside the reference's own wide (47%)
  ;; interval and outside the tight (29%) one. So the infinity is switched on
  ;; while the two curves were still agreeing — which is a reason to report the
  ;; step, and not on its own a reason to remove the floor: below it neither side
  ;; has measured anything.
  (let [d (strain/floor-discontinuity 120.0)]
    (is (math/nearly= (:reference-minutes-at-floor d)
                      (reference-minutes* :general strain/endurance-floor-pct) 1e-9)
        "recomputed from Table 2 longhand, not read back out of `strain`")
    (is (math/nearly= (:ratio-just-above-floor d) 1.29208 1e-4))
    (is (true? (:model-and-reference-agree-at-the-floor? d)))
    (is (false? (:agree-under-narrow-reading? d))
        "the tight reading of the reference's own range does NOT cover it; collapsing the two would hide that")
    (is (true? (:floor-is-a-modelling-choice d)))))

(deftest the-index-is-blind-at-the-top-before-the-axis-ends
  ;; `:saturated?` already marked individual rows. This states the size of the
  ;; blind region: at 120 min the index is exactly 1.0 from 30.2 %MVC upward, so
  ;; 70 of the 100 points on the axis carry no information at all.
  (let [r (strain/band-resolution 120.0)
        onset (:saturation-onset-pct r)]
    (is (number? onset))
    (is (< onset 100.0))
    (is (math/nearly= onset 30.1551 1e-3))
    (is (math/nearly= (:saturated-width-pct r) (- 100.0 onset) 1e-9))
    (is (> (:saturated-width-pct r) 50.0)
        "more than half the axis maps to one value")
    ;; the consequence, stated as an identity rather than as a caveat
    (is (= (strain/index-at 50.0 120.0) (strain/index-at 100.0 120.0)))
    (is (= 1.0 (strain/index-at 50.0 120.0)))
    ;; and it moves with the session, so it is not a property of the axis
    (is (< (:saturation-onset-pct (strain/band-resolution 480.0)) onset))
    (is (> (:saturation-onset-pct (strain/band-resolution 30.0)) onset))))

(deftest the-dose-still-has-range-where-the-index-has-none
  ;; The reason the dose is now reported. Two loads the index cannot tell apart at
  ;; all differ by a factor of five in the quantity the index is a transform of.
  (let [a (strain/dose 50.0 120.0)
        b (strain/dose 100.0 120.0)]
    (is (= (strain/index-at 50.0 120.0) (strain/index-at 100.0 120.0)))
    (is (> (/ (:total b) (:total a)) 4.0)
        (str "dose " (:total a) " vs " (:total b))))
  ;; and it is the same arithmetic muscle-strain used to do inline
  (doseq [f [0.0 1.0 5.0 8.0 12.0 41.0 100.0]]
    (let [d (strain/dose f 120.0)
          t-end (strain/endurance-minutes f)
          acute* (if (math/infinite? t-end) 0.0 (/ 120.0 t-end))
          chronic* (* strain/chronic-weight
                      (/ (max 0.0 (- f strain/chronic-threshold-pct)) 100.0)
                      (/ 120.0 60.0))]
      (is (= acute* (:acute d)))
      (is (= chronic* (:chronic d)))
      (is (= (+ acute* chronic*) (:total d)))
      (is (= (- 1.0 (Math/exp (- (:total d)))) (strain/index-at f 120.0))))))

(deftest every-strain-row-names-which-regime-of-the-index-it-is-in
  (let [row (fn [f] (strain/muscle-strain {:name "m" :task :trunk-extension :mvc-pct f} 120.0))]
    (is (= :below-endurance-floor (:index-resolution (row 5.0))))
    (is (= :distinguishing (:index-resolution (row 12.0))))
    (is (= :saturated (:index-resolution (row 60.0))))
    (is (= :not-computed
           (:index-resolution (strain/muscle-strain
                               {:name "m" :task :trunk-extension :refused :diverges} 120.0)))))
  ;; the keyword must agree with the flags it summarises, on every row of a real
  ;; posture, or it is a second opinion rather than a convenience
  (let [body (segment/build-body 70.0 1.70)
        p (posture/posture-from-workstation posture/laptop-on-lap)
        strains (strain/session-strain
                 (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p)) 120.0)]
    (is (seq strains))
    (doseq [s strains]
      (is (= (:index-resolution s)
             (cond (nil? (:stiffness-index s)) :not-computed
                   (:unbounded-endurance? s) :below-endurance-floor
                   (:saturated? s) :saturated
                   :else :distinguishing))))
    ;; the dose is present wherever an index is, and absent where none was computed
    (doseq [s strains]
      (is (= (some? (:dose s)) (some? (:stiffness-index s)))))))

(deftest the-reference-postures-produce-two-computed-bands-and-hide-a-factor-of-seven
  ;; The finding on the data the product actually reports, not on a uniform sweep
  ;; of an axis nothing occupies.
  (let [body (segment/build-body 70.0 1.70)
        strains (mapcat (fn [ws]
                          (let [p (posture/posture-from-workstation ws)]
                            (strain/session-strain
                             (muscle/solve-muscle-tensions body p (load/solve-posture-loads body p))
                             120.0)))
                        posture/reference-workstations)
        hist (frequencies (map #(strain/stiffness-band (:stiffness-index %)) strains))
        vh (filter #(= "very-high" (strain/stiffness-band (:stiffness-index %))) strains)
        mvcs (map :mvc-pct vh)]
    (is (= #{"low" "very-high" "not-computed"} (set (keys hist)))
        (str "the reference postures reach " (pr-str hist)
             " — `moderate` and `high` are structurally unavailable, not merely unvisited"))
    (is (pos? (count vh)))
    (is (> (/ (apply max mvcs) (apply min mvcs)) 6.0)
        (str "the `very-high` rows span " (apply min mvcs) " to " (apply max mvcs)
             " %MVC under one word"))
    ;; and the dose, which is now reported, does distinguish them
    (let [doses (map :dose vh)]
      ;; measured 2026-09-07: 1.93 to 165.95, a factor of 86. The bound is stated
      ;; below what was measured so that this asserts the separation exists, not
      ;; that it has one exact size.
      (is (> (/ (apply max doses) (apply min doses)) 50.0)
          (str "the quantity the index is a transform of separates the same rows "
               (apply min doses) " .. " (apply max doses))))))
