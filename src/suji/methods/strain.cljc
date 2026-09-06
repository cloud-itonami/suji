(ns suji.methods.strain
  "suji (筋) — sustained-load strain → 強張り (stiffness) over a work session. 1:1 Clojure
  port of `src/suji/methods/strain.cljc` (ADR-2606061900). Stdlib only.

  緊張 (tension, the instantaneous %MVC from muscle) becomes 強張り (stiffness) when held.
  A static posture is an isometric contraction sustained for the length of a work session,
  and isometric load has a known endurance limit that falls steeply with %MVC (Rohmert).

  Endurance model: T_end(f) ≈ 0.2 · f^-2.32 minutes. Stiffness index = 1 - exp(-dose),
  where dose combines the acute and chronic terms over the session.

  THE INDEX SATURATES, AND SAYS SO. Mathematically it lies in [0,1) and never
  reaches 1; in double precision it reaches exactly 1.0 as soon as the dose passes
  about 37, because 1 - x rounds to 1.0 for any x below 2⁻⁵³. That happens at
  ordinary loads — roughly 50 %MVC held for two hours — which is to say the index
  loses ALL resolution exactly where the load is worst: two postures, one twice as
  demanding as the other, both read 1.00. `:saturated?` marks those, so a consumer
  can render a `>=` rather than presenting a ceiling as a measurement. (Found
  2026-09-06 by a test that asserted `< 1.0` and was right to.)

  VALIDATION STATUS (2026-09-07). This layer used to be graded `mechanically
  motivated but illustrative` and had nothing behind it. It now answers to a
  published meta-analysis of measured endurance times — `endurance-cross-check`,
  in the shape of `spine`'s `cervical-cross-check` / `lumbar-cross-check` — and the
  answer is mixed rather than a pass:

  AGAINST THE POOLED CURVE IT LOOKS GOOD. Over 8–47 %MVC the model sits inside
  even the TIGHT reading of the reference's own 95% prediction interval, and it
  stays inside the wide reading over the whole fitted range — running short at the
  top (at 100 %MVC it says 0.20 min against the pooled fit's 0.37).

  AGAINST THE JOINT-SPECIFIC CURVES IT DISAGREES, AND THE SIGN OF THE
  DISAGREEMENT DEPENDS ON THE MUSCLE. Measured 2026-09-07 on laptop-on-lap, of
  the four muscle groups whose %MVC lands inside the fitted range, two fall
  outside the reference's own wide interval and they fall out on OPPOSITE sides:
  the anterior deltoid at 1.51× and the wrist extensors at 1.78× the published
  time (the model says they can be held longer than the literature measured),
  against the erector spinae at 0.57× (shorter). Extend to the groups sitting
  just below the fitted range and it gets wider still — upper trapezius 2.59×,
  levator scapulae 2.73×.

  THAT IS NOT A CALIBRATION ERROR, IT IS THE SHAPE OF THE MODEL. The reference's
  own between-joint spread at 20 %MVC runs from 4.7 min (shoulder) to 15.9 min
  (ankle), a factor of 3.4 — larger than the model's disagreement with the pooled
  curve anywhere. One curve for every muscle in the body CANNOT be simultaneously
  right for a deltoid and an erector spinae, whatever its coefficients are.
  `joint-spread` computes that, so the limit is a number rather than a caveat.

  BELOW 8 %MVC THE MODEL RETURNS ∞ AND THE PUBLISHED FIT RETURNS A NUMBER
  (54 min at 8 %MVC, 138 min at 5 %MVC). Neither side is measured there — the
  fit's own data starts at 10 %MVC and is concentrated above 25 — so the ∞ is a
  MODELLING CHOICE, and it is now reported as one rather than emitted silently.
  It matters because that is where a desk posture lives: in laptop-on-lap ten of
  this actor's muscle entries sit under the floor and accrue no acute dose at all.

  THE MUSCLE THAT PRODUCES THIS APP'S HEADLINE BAND HAS NO CURVE HERE AT ALL. The
  reference's six regions are ankle, knee, trunk, shoulder, elbow and hand/grip;
  none of them is the neck, and the `very-high` verdict on the comparison table is
  produced by the cervical extensors. `endurance-cross-check` returns
  `:could-not-obtain` for them rather than quietly holding them to the pooled
  curve as though it were their own.

  The model was NOT tuned to close any of these gaps. See `model-form`, which also
  records that the provenance of the constants 0.2 and −2.32 COULD NOT BE OBTAINED.

  NON-DIAGNOSTIC (G1): a stiffness index is a normalised load-time dose, not a medical
  finding. SELF-REFERENCED (G3): indices compared against the SAME member's other postures.

  Numerics: exp/pow resolve on both hosts; infinity goes through suji.methods.math
  (Double/POSITIVE_INFINITY and Double/isInfinite are JVM-only — see that ns)."
  (:require [suji.methods.math :as math]))

(def chronic-threshold-pct 2.0)   ;; below this %MVC, essentially no sustained recruitment
(def chronic-weight 0.45)         ;; weight of the chronic low-load dose vs acute
(def endurance-floor-pct 8.0)     ;; below this %MVC, acute endurance treated as long

(def ^:private inf math/inf)

;; --- what this model actually implements -------------------------------------

(def model-form
  "The endurance relation this namespace implements, stated exactly, and what is
  and is not known about where it came from.

  IT IS A POWER LAW, NOT ROHMERT'S EQUATION. Rohmert's own 1960 curve is usually
  reproduced as a polynomial in (f − 0.15) with a pole at 15 %MVC — a curve that
  goes to infinity AT a stated intensity. This is a plain power law with no pole,
  plus a hard floor bolted on at 8 %MVC to supply the asymptote the functional form
  does not have. Calling it `Rohmert-type` is fair as a statement of family; calling
  it `Rohmert's` would not be, and the docstring above no longer does.

  THE CONSTANTS COULD NOT BE TRACED. 0.2 minutes and −2.32 match no published fit
  that could be obtained (searched 2026-09-07: Frey Law & Avin's seven curves, and
  attempts at El ahrache et al. 2006, Sato et al. 1984 and Monod & Scherrer 1965 —
  see `unobtained-references`). `:provenance :could-not-obtain` says so. That is
  not a reason to change them: an untraced constant that agrees with a measured
  curve over the range the app uses is a better position than a traced constant
  nobody checked. It IS a reason not to describe them as published."
  {:relation-minutes "T_end(f) = 0.2 · f^-2.32   (f = %MVC / 100)"
   :relation-seconds "ET(f)    = 12.0 · f^-2.32"
   :coefficient-minutes 0.2
   :exponent -2.32
   :family :power
   :low-load-floor-pct endurance-floor-pct
   :floor-is-a-modelling-choice true
   :provenance :could-not-obtain
   :provenance-note
   (str "no published fit with the coefficients 0.2 min and -2.32 was located. "
        "The family (a power law in the fraction of MVC) is the one Rohmert's "
        "curve belongs to and the one most subsequent fits use; the coefficients "
        "are this actor's own until somebody finds them a source.")})

;; --- the literature this layer answers to ------------------------------------
;;
;; Same discipline as `spine`: the citation, the URL that was actually fetched,
;; and whether the full text or only an abstract was read. A reference value with
;; no provenance is indistinguishable from an invented one.

(def frey-law-2010
  "The reference: a meta-analysis of MEASURED isometric endurance times.

  Seven power-law fits — one pooled and one per joint region — over 194 published
  studies. Table 2's caption, verbatim:

      `Power ( Time = bo*(MVC) b1 ) and exponential ( Time = bo*exp (MVC*b1)) model
       coefficients by joint, where intensity (% MVC) values are between 0.0 and
       1.0; time is in seconds.`

  and its footnote, verbatim: `ET = Endurance time (sec).` So the coefficients
  below take a FRACTION and return SECONDS; `reference-endurance-minutes` converts,
  because everything else in this namespace is in minutes.

  WHAT COUNTED AS AN ENDURANCE TIME, verbatim: `studies involving healthy, human
  subjects with a mean reported age between 18–50 years; isometric tasks performed
  until volitional failure; relative intensity based on maximum voluntary
  contraction (%MVC); mean maximal endurance time reported; single-joint
  involvement (per fatigue task); and published in English.` A held posture is not
  a contraction held to volitional failure, and this actor is not claiming it is —
  what is compared is one endurance-time curve against another.

  THE FITTED RANGE, verbatim: `Ninety-five percent confidence intervals (95% CI) of
  the model mean values were calculated for each of the joint-specific and
  generalised fatigue models (power and exponential functions) for intensities
  ranging from 0.01 to 1 (10% to 100% maximum)`. The paper's own parenthetical
  disagrees with its own number — 0.01 is 1%, not 10% — so `:fitted-range-pct`
  takes the WIDER-of-the-two-readings-is-not-safe route and uses 10%, the value
  stated in words, and `:fitted-range-note` records the discrepancy rather than
  resolving it silently. Where the data actually is, verbatim: `the preponderance
  of data at intensities greater than 25% MVC`.

  THERE IS NO NECK. The six regions are ankle, knee, trunk, shoulder, elbow and
  hand/grip. See `task->reference-region`."
  {:citation (str "Frey Law LA, Avin KG. Endurance time is joint-specific: a "
                  "modelling and meta-analysis investigation. "
                  "Ergonomics 2010;53(1):109-129. Table 2.")
   :url "https://pmc.ncbi.nlm.nih.gov/articles/PMC2891087/"
   :obtained :full-text
   :scale "194 publications, 369 data points"
   :intensity-unit :fraction-of-mvc
   :time-unit :seconds
   :fitted-range-pct [10.0 100.0]
   :fitted-range-note
   (str "the paper writes `intensities ranging from 0.01 to 1 (10% to 100% "
        "maximum)`; 0.01 is 1%, not 10%. 10% is used here because it is the bound "
        "stated in words, and because the same paper says the data are "
        "concentrated above 25% MVC.")
   ;; ET = b0 · f^b1 seconds, f in [0,1]
   :power-models
   {:general  {:b0 21.92 :b1 -1.98 :r2 0.814}
    :ankle    {:b0 34.71 :b1 -2.06 :r2 0.884}
    :trunk    {:b0 22.69 :b1 -2.27 :r2 0.885}
    :elbow    {:b0 17.98 :b1 -2.21 :r2 0.915}
    :grip     {:b0 33.55 :b1 -1.61 :r2 0.748}
    :knee     {:b0 19.38 :b1 -1.88 :r2 0.789}
    :shoulder {:b0 14.86 :b1 -1.83 :r2 0.897}}
   :joint-order-quote
   (str "`Overall, the ankle was most fatigue-resistant, followed by the trunk, "
        "hand/grip, elbow, knee and finally the shoulder was most fatigable.`")})

(def reference-prediction-interval
  "The reference's OWN uncertainty, which is what `endurance-cross-check` compares
  against — not a tolerance chosen here.

  Verbatim, from the follow-up paper by the same first author:

      `Our prior meta-analysis demonstrated the expected variation in ET (i.e., the
       percent difference between the 95% PI curve and the mean expected curve for
       ET) ranged from 29–47% (Frey Law and Avin, 2010).`

  PROVENANCE IS SPLIT AND IS STATED SPLIT. The sentence is in the 2012 paper, read
  in full, describing a property of the 2010 paper. The 2010 text read here plots
  its intervals and does not print this figure. So the number is attributed to the
  paper it was read in, not to the paper it is about.

  Two bands rather than one, because 29–47% is a RANGE and a single point on a
  single curve does not say which end applies: `:narrow` is ±29%, `:wide` is ±47%.
  `endurance-cross-check` reports the model against both. Collapsing them to one
  would make an arbitrary choice look like a measurement."
  {:narrow-fraction 0.29
   :wide-fraction 0.47
   :citation (str "Frey-Law LA, Looft JM, Heitsman J. A three-compartment muscle "
                  "fatigue model accurately predicts joint-specific maximum "
                  "endurance times for sustained isometric tasks. "
                  "J Biomech 2012;45(10):1803-1808, Discussion.")
   :url "https://pmc.ncbi.nlm.nih.gov/articles/PMC3397684/"
   :obtained :full-text
   :describes "Frey Law & Avin 2010"})

(def measured-single-point
  "One directly measured endurance time, carried so the comparison is not entirely
  against other people's regressions.

  Handgrip held to task failure at 15 %MVC by 14 healthy young men, forearm at
  heart level. Verbatim: `MICT at 15% MVC was significantly shorter by 66.3 and
  86.2 s with forearm position + 27.5 cm (389.6 ± 23.3 s) as compared to 0.0 cm
  (455.9 ± 34.1 s) and − 27.5 cm (475.8 ± 35.0 s)`. The reference position is the
  0.0 cm one: 455.9 ± 34.1 s = 7.60 ± 0.57 min.

  IT IS ONE MUSCLE GROUP AT ONE INTENSITY, and the same paper is a demonstration
  that endurance at that intensity moves with something as incidental as the height
  of the forearm — 66 s of the 456, from raising it 27.5 cm. It is here to show the
  size of the real spread, not to be met.

  Only this one number is carried: the paper's other four intensities appear in a
  figure and not in its text, so they were not read as values."
  {:citation (str "Heinzl L, Risse S, Schwarzbach H, Hildebrandt O, Koehler U, "
                  "Koenig AM, Mahnken AH, Kinscherf R, Hildebrandt W. Forearm "
                  "elevation impairs local static handgrip endurance likely "
                  "through reduction in vascular conductance and perfusion "
                  "pressure: revisiting Rohmert's curve. "
                  "Scientific Reports 2025;15:1250.")
   :url "https://www.nature.com/articles/s41598-024-83939-7"
   :obtained :full-text
   :muscle-group "handgrip"
   :mvc-pct 15.0
   :minutes (/ 455.9 60.0)
   :sd-minutes (/ 34.1 60.0)
   :n 14
   :position-effect-note
   (str "the same paper measured 389.6 ± 23.3 s at the same 15 %MVC with the "
        "forearm 27.5 cm higher — a 15% change in endurance from limb height "
        "alone, which bounds how precisely any single curve can be expected to "
        "predict a held posture.")})

(def unobtained-references
  "References that were looked for and NOT obtained. Listed because a gap that is
  named is different from a gap that is invisible, and because the absence of these
  is the reason `model-form` cannot say where its constants came from.

  No number from any of these appears anywhere in this namespace."
  [{:citation (str "El ahrache K, Imbeau D, Farbos B. Percentile values for "
                   "determining maximum endurance times for static muscular work. "
                   "Int J Industrial Ergonomics 2006;36(2):99-108.")
    :wanted "its table of 24 published MET models — the widest survey of the fits"
    :outcome :could-not-obtain
    :reason :paywalled-abstract-shell-only}
   {:citation (str "Rohmert W. Die Grundlage der Beurteilung statischer Arbeit. "
                   "Westdeutscher Verlag, 1960.")
    :wanted "the original curves, and the original statement of the 15 %MVC limit"
    :outcome :could-not-obtain
    :reason :not-available-online-in-any-form
    :note (str "the widely repeated `15 %MVC can be held indefinitely` is NOT "
               "quoted in this repo, because no source stating it was read. What "
               "was read is a physiological statement about blood flow: `only at "
               "< 15% MVC does muscle blood flow reach an equilibrium and meets "
               "metabolic requirements with stable tissue pO2` (Heinzl 2025). "
               "That is a different claim from an infinite holding time.")}
   {:citation (str "Sato H, Ohashi J, Iwanaga K, Yoshitake R, Shimada K. Endurance "
                   "time and fatigue in static contractions. "
                   "J Human Ergology 1984;13(2):147-154.")
    :wanted "its power-law coefficients"
    :outcome :could-not-obtain
    :reason :pdf-mirror-returned-zero-bytes}
   {:citation "Monod H, Scherrer J. The work capacity of a synergic muscular group. Ergonomics 1965;8(3):329-338."
    :wanted "its coefficients"
    :outcome :could-not-obtain
    :reason :paywalled}])

(def task->reference-region
  "Which of the reference's six joint regions each of this actor's equilibrium
  tasks belongs to — and, where the honest answer is `none`, that.

  `:basis` is the load-bearing field:

    `:named-in-source`    the reference measured that region by name.
    `:nearest-region`     the reference has a neighbouring region measured on a
                          DIFFERENT task. A comparison is still worth making and
                          is still not the same claim.
    `:absent-from-source` the reference has no curve for this at all.

  THE NECK IS `:absent-from-source`, AND IT IS THE ONE THAT MATTERS. Frey Law &
  Avin's regions are ankle, knee, trunk, shoulder, elbow and hand/grip. This
  actor's headline verdict — the `very-high` band on the comparison table — is
  produced by the cervical extensors, and there is no published neck curve here to
  hold them to. Falling back to the pooled curve and not saying so would be the
  bad move: the pooled curve is dominated by the limb data that is in the
  meta-analysis, and the neck is not in it."
  {:cervical-extension       {:region nil :basis :absent-from-source
                              :note "no neck/cervical region in the reference"}
   :cervical-lateral-flexion {:region nil :basis :absent-from-source
                              :note "no neck/cervical region in the reference"}
   :scapular-suspension      {:region :shoulder :basis :named-in-source
                              :note "the reference names the trapezius as a shoulder muscle"}
   :shoulder-flexion         {:region :shoulder :basis :named-in-source}
   :shoulder-abduction       {:region :shoulder :basis :named-in-source}
   :trunk-extension          {:region :trunk :basis :named-in-source}
   :trunk-lateral-flexion    {:region :trunk :basis :named-in-source}
   :elbow-flexion            {:region :elbow :basis :named-in-source}
   :wrist-flexion            {:region :grip :basis :nearest-region
                              :note (str "the reference's hand/grip region is grip "
                                         "FORCE held to failure; this task is the "
                                         "wrist held in a posture. Same limb "
                                         "segment, different contraction.")}})

;; --- endurance ---------------------------------------------------------------

(defn endurance-minutes
  "Rohmert-type isometric endurance time (minutes) at a given %MVC. Returns ∞ below the
  endurance floor (low-load static work has no acute failure point).

  RETURNS A BARE NUMBER, WHICH IS THE PROBLEM `endurance` EXISTS TO FIX. A power
  law answers at every input, so this will hand back 0.16 min for 112 %MVC — a
  confident holding time for a load the muscle cannot produce — and ∞ for 5 %MVC,
  where the published fit returns 138 minutes. Callers that need to know whether
  the number was inside the range anything was fitted over should call `endurance`.
  This function is kept because `analyze` and the existing suite call it and its
  arithmetic is unchanged."
  [mvc-pct]
  (if (<= mvc-pct endurance-floor-pct)
    inf
    (let [f (/ mvc-pct 100.0)]
      (* 0.2 (Math/pow f -2.32)))))

(defn endurance-position
  "Where a %MVC sits relative to the two boundaries that matter: this model's own
  low-load floor, and the range the reference's data was fitted over.

    `:below-endurance-floor`  ≤ 8 %MVC — the model returns ∞ here, by construction.
    `:below-fitted-range`     8–10 %MVC — a finite number, but below where the
                              reference has data, so nothing checks it.
    `:within-fitted-range`    10–100 %MVC.
    `:above-maximum-voluntary-contraction`
                              > 100 %MVC. `muscle` deliberately does not clamp
                              %MVC (a posture demanding more than the muscle can
                              give is a mechanical fact worth reporting), so this
                              arrives here, and a power law will happily price it.
                              An endurance time is defined as how long a
                              sub-maximal load is held; above maximum there is no
                              such quantity to extrapolate to."
  [mvc-pct]
  (let [[lo hi] (:fitted-range-pct frey-law-2010)]
    (cond
      (<= mvc-pct endurance-floor-pct) :below-endurance-floor
      (< mvc-pct lo) :below-fitted-range
      (<= mvc-pct hi) :within-fitted-range
      :else :above-maximum-voluntary-contraction)))

(defn endurance
  "`endurance-minutes` with the caveat attached to the number instead of to the
  reader's memory.

  `:extrapolated?` is true for everything except `:within-fitted-range`. It is not
  a refusal — the number is still there, and for a light desk posture the ∞ is
  arguably the more useful answer — but a consumer can now tell an answer from a
  guess, which was previously impossible from the value alone."
  [mvc-pct]
  (let [position (endurance-position mvc-pct)
        minutes (endurance-minutes mvc-pct)]
    {:mvc-pct mvc-pct
     :minutes minutes
     :position position
     :extrapolated? (not= position :within-fitted-range)
     :unbounded? (math/infinite? minutes)
     :fitted-range-pct (:fitted-range-pct frey-law-2010)
     :note (case position
             :below-endurance-floor
             (str "the model returns no acute failure point below "
                  endurance-floor-pct " %MVC. That is a modelling choice, not a "
                  "measurement: the reference's power fit has no asymptote and "
                  "returns a finite time at any intensity, and its data does not "
                  "reach down here either.")
             :below-fitted-range
             "above this model's floor but below where the reference has data"
             :within-fitted-range
             "inside the range the reference's fit was obtained over"
             :above-maximum-voluntary-contraction
             (str "above maximum voluntary contraction. The posture asks for more "
                  "force than the muscle can produce, so there is no holding time "
                  "to predict; the number is the power law continued past its "
                  "meaning."))}))

(defn reference-endurance-minutes
  "Endurance time (minutes) from one of the reference's published power fits.

  `ET = b0 · f^b1` seconds, converted. nil for a region the reference does not
  have, so a caller cannot get a number out of a curve that does not exist."
  [region mvc-pct]
  (when-let [{:keys [b0 b1]} (get-in frey-law-2010 [:power-models region])]
    (if (<= mvc-pct 0.0)
      inf
      (/ (* b0 (Math/pow (/ mvc-pct 100.0) b1)) 60.0))))

(defn joint-spread
  "How much the reference's six joint curves disagree WITH EACH OTHER at one
  intensity.

  This is the finding that limits what a single whole-body curve can be. At
  20 %MVC the published fits run from the shoulder to the ankle by a factor of
  well over three; this model has one curve for every muscle in the body, so it
  cannot be simultaneously right for a deltoid and an erector spinae no matter what
  its coefficients are. Reporting the spread makes that a computation."
  [mvc-pct]
  (let [regions (remove #{:general} (keys (:power-models frey-law-2010)))
        by-region (into {} (map (fn [r] [r (reference-endurance-minutes r mvc-pct)])) regions)
        vals* (vals by-region)
        lo (reduce min vals*)
        hi (reduce max vals*)]
    {:mvc-pct mvc-pct
     :by-region by-region
     :min-minutes lo
     :max-minutes hi
     :fold (when (pos? lo) (/ hi lo))
     :citation (:citation frey-law-2010)
     :url (:url frey-law-2010)
     :obtained (:obtained frey-law-2010)}))

(defn endurance-cross-check
  "Compare this model's endurance time against the published fit, and report the
  disagreement rather than removing it.

  IN THE SHAPE OF `spine`'s CROSS-CHECKS, AND WITH THE SAME ASYMMETRY AS THE
  LUMBAR ONE. `:validated :reference` — the measured side is the literature's,
  this model's curve is the unvalidated one, and `:model-validated? false` says so
  in the value rather than in a sentence somebody has to remember to keep true.

  WHAT IS COMPARED. Endurance time against endurance time, in minutes, at the same
  %MVC. Nothing is converted and nothing is fitted.

  THE SPREAD. `:reference-range-minutes` is the reference's own 95% prediction
  interval, which the source states as a RANGE (29–47%) rather than a number — so
  both bands are reported and neither is presented as the answer.
  `:within-reference-spread?` uses the wide one, `:within-narrow-reference-spread?`
  the tight one. Against the POOLED curve the model passes the wide band over the
  whole fitted range and leaves the tight one above about 47 %MVC. Against the
  JOINT-SPECIFIC curves it leaves the wide band at the shoulder and at the wrist
  in ordinary desk postures — which is the more informative answer of the two, and
  the one this actor's `session-cross-check` reports.

  A REGION THE REFERENCE DOES NOT HAVE RETURNS `:could-not-obtain`, with no ratio.
  That is an answer, and it is a different answer from agreement. The cervical
  extensors — this actor's headline muscle — take that branch.

  AN UNBOUNDED MODEL ANSWER RETURNS NO RATIO EITHER. Below 8 %MVC this model says
  ∞ and the reference says a finite number; the ratio is not a number and pretending
  it is one would put an infinity into a report. `:direction` names it instead."
  ([mvc-pct] (endurance-cross-check :general mvc-pct))
  ([region mvc-pct]
   (let [model (endurance mvc-pct)
         base {:mvc-pct mvc-pct
               :region region
               :model-minutes (:minutes model)
               :model-position (:position model)
               :model-extrapolated? (:extrapolated? model)
               :model-unbounded? (:unbounded? model)
               :model-form model-form
               :validated :reference
               :model-validated? false
               :citation (:citation frey-law-2010)
               :url (:url frey-law-2010)
               :obtained (:obtained frey-law-2010)
               :reference-spread (select-keys reference-prediction-interval
                                              [:narrow-fraction :wide-fraction
                                               :citation :url :obtained])}
         ref-min (reference-endurance-minutes region mvc-pct)]
     (cond
       (nil? ref-min)
       (assoc base
              :could-not-obtain :no-published-curve-for-this-region
              :could-not-obtain-note
              (str "the reference has curves for "
                   (pr-str (sort (keys (:power-models frey-law-2010))))
                   " and this is not one of them")
              :reference-minutes nil
              :ratio nil)

       (:unbounded? model)
       (assoc base
              :reference-minutes ref-min
              :ratio nil
              :direction :model-unbounded-reference-finite
              :within-reference-spread? false
              :within-narrow-reference-spread? false
              :could-not-obtain :model-returns-no-finite-endurance
              :could-not-obtain-note
              (str "below " endurance-floor-pct " %MVC this model returns no acute "
                   "failure point at all, so there is no ratio to a finite "
                   "reference time. The reference's fit returns a number here, but "
                   "its data does not reach this far down either."))

       :else
       (let [{:keys [narrow-fraction wide-fraction]} reference-prediction-interval
             wide [(* ref-min (- 1.0 wide-fraction)) (* ref-min (+ 1.0 wide-fraction))]
             narrow [(* ref-min (- 1.0 narrow-fraction)) (* ref-min (+ 1.0 narrow-fraction))]
             m (:minutes model)
             in? (fn [[lo hi]] (and (>= m lo) (<= m hi)))]
         (assoc base
                :reference-minutes ref-min
                :reference-range-minutes wide
                :reference-narrow-range-minutes narrow
                :ratio (/ m ref-min)
                :within-reference-spread? (in? wide)
                :within-narrow-reference-spread? (in? narrow)
                :direction (cond (< m (first wide)) :model-below-reference
                                 (> m (second wide)) :model-above-reference
                                 :else :within-reference-spread)))))))

(defn tension-cross-check
  "`endurance-cross-check` for one of this actor's muscles, routed to the
  reference region its equilibrium task belongs to.

  Takes a tension or strain map (anything carrying `:mvc-pct` and `:task`). A
  refused entry — `muscle` declined to compute its force — has no %MVC and so has
  nothing to cross-check; it returns `:could-not-obtain` for that reason, which is
  a third distinct reason from `no published curve` and `model unbounded`."
  [t]
  (let [{:keys [region basis note]} (get task->reference-region (:task t)
                                         {:region nil :basis :absent-from-source
                                          :note "task not mapped to a reference region"})]
    (cond
      (or (:refused t) (nil? (:mvc-pct t)))
      {:name (:name t) :task (:task t) :region region :region-basis basis
       :mvc-pct nil
       :could-not-obtain (or (:refused t) :no-mvc)
       :could-not-obtain-note "no %MVC was computed for this entry, so there is no dose and nothing to compare"
       :validated :reference :model-validated? false}

      :else
      (assoc (endurance-cross-check region (:mvc-pct t))
             :name (:name t)
             :task (:task t)
             :region-basis basis
             :region-note note))))

(defn session-cross-check
  "`tension-cross-check` across a whole set of muscles, plus a count of how the
  answers came out.

  `:summary` exists so a reader cannot skim a list of thirty rows and come away
  with the impression that everything was checked. `:compared` is the only bucket
  in which a ratio was actually produced."
  [tensions]
  (let [rows (mapv tension-cross-check tensions)
        bucket (fn [r] (cond (:could-not-obtain r) (:could-not-obtain r) :else :compared))]
    {:rows rows
     :summary (frequencies (map bucket rows))
     :compared (count (remove :could-not-obtain rows))
     :total (count rows)}))

;; --- the dose ----------------------------------------------------------------

(defn- muscle-strain* [t session-minutes]
  (let [mvc-pct (:mvc-pct t)
        e (endurance mvc-pct)
        t-end (:minutes e)
        acute (if (math/infinite? t-end) 0.0 (/ session-minutes t-end))
        excess (/ (max 0.0 (- mvc-pct chronic-threshold-pct)) 100.0)
        chronic (* chronic-weight excess (/ session-minutes 60.0))
        dose (+ acute chronic)
        stiffness (- 1.0 (Math/exp (- dose)))
        ;; `>= 1.0` rather than `= 1.0`: the comparison is about what the double
        ;; can still distinguish, not about an exact value.
        saturated? (>= stiffness 1.0)]
    {:name (:name t)
     :task (:task t)
     :mvc-pct mvc-pct
     :session-minutes session-minutes
     :endurance-minutes t-end
     ;; the caveat travels with the number it qualifies. Before 2026-09-07 the
     ;; dose from a 5 %MVC posture (endurance ∞, acute term zero) and the dose
     ;; from a 112 %MVC posture (endurance extrapolated past maximum voluntary
     ;; contraction) were reported in exactly the same shape as a dose the
     ;; reference range covers, and nothing in the value said which was which.
     :endurance-position (:position e)
     :endurance-extrapolated? (:extrapolated? e)
     :unbounded-endurance? (:unbounded? e)
     :acute-dose acute
     :chronic-dose chronic
     :stiffness-index stiffness
     :saturated? saturated?
     :over-endurance (and (not (math/infinite? t-end)) (> session-minutes t-end))}))

(defn muscle-strain
  "Stiffness accrued by one muscle holding `mvc-pct` for `session-minutes`.

  A REFUSAL PASSES THROUGH. `muscle/solve-muscle-tensions` can decline to compute
  a force — when a straight-line muscle's line of action passes too close to the
  joint it acts about, the required force diverges and the model says so instead
  of returning a number. Such an entry has no `:mvc-pct`, and there is no dose to
  accumulate from a load nobody computed. Before 2026-09-06 this function reached
  straight for `(:mvc-pct t)` and threw a NullPointerException on the arithmetic
  two lines later — which is the right thing happening for the wrong reason: the
  caller learned there was a problem, but from a crash rather than from an answer,
  and only if it happened to exercise the posture."
  [t session-minutes]
  (when (< session-minutes 0)
    (throw (ex-info "session_minutes must be >= 0" {:type :value-error})))
  (if (or (:refused t) (nil? (:mvc-pct t)))
    {:name (:name t)
     :task (:task t)
     :mvc-pct nil
     :session-minutes session-minutes
     :refused (or (:refused t) :no-mvc)
     :endurance-minutes nil
     :endurance-position nil
     :endurance-extrapolated? nil
     :unbounded-endurance? nil
     :acute-dose nil
     :chronic-dose nil
     :stiffness-index nil
     :saturated? false
     :over-endurance nil}
    (muscle-strain* t session-minutes)))

(defn session-strain
  "Stiffness map for a whole work session (default 2 hours of continuous posture)."
  ([tensions] (session-strain tensions 120.0))
  ([tensions session-minutes]
   (mapv #(muscle-strain % session-minutes) tensions)))

(defn stiffness-band
  "A coarse human-readable band for the stiffness index (display only,
  non-diagnostic). A nil index — the model refused this muscle's load — gets its
  own band rather than falling into `low`, which would read as the best case."
  [stiffness-index]
  (cond
    (nil? stiffness-index) "not-computed"
    (< stiffness-index 0.20) "low"
    (< stiffness-index 0.45) "moderate"
    (< stiffness-index 0.70) "high"
    :else "very-high"))
