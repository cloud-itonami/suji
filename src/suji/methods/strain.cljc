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

  AND IT IS BLIND AT THE BOTTOM TOO, WHICH NOTHING SAID UNTIL 2026-09-07. The
  saturation above is a CEILING; the fault at the other end is a GAP, and it does
  more damage because it lands on the band, which is the column a reader actually
  reads. `endurance-minutes` steps from ∞ to about 70 minutes at the 8 %MVC floor,
  so the acute term switches on at full size: at a 120 minute session the index
  jumps from 0.053 to 0.829 across 0.001 %MVC. That step is wider than `moderate`
  and `high` put together, so nothing can land in either — they are not rare, they
  are UNREACHABLE, and the four-rung scale is a threshold test on `%MVC > 8`.

  MEASURED 2026-09-07 ON THE THREE REFERENCE WORKSTATIONS. At 120 min the 144
  muscle entries come out `{low 99, not-computed 28, very-high 17}`; at 480 min,
  identical. Only at 30 min do all four bands appear, because below 40.6 min the
  step is not yet wide enough to clear `moderate`. The 17 `very-high` rows span
  8.3 to 57.4 %MVC — a factor of 6.9 in load, 88 in endurance time, 86 in dose —
  under one word. `band-resolution` computes all of this by inverting the model,
  and `floor-discontinuity` reports the step, including the part that cuts against
  the floor: at the floor the model's 70.1 min and the pooled published fit's 54.3
  min agree to within the reference's own prediction interval, so the ∞ is
  switched on while the power law was still inside the literature's spread.
  NOTHING WAS SMOOTHED. A blend width would have to be invented, and the region it
  would span is one where neither side has measured anything.

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
  Avin's regions are ankle, knee, trunk, shoulder, elbow and hand/grip. (The HIP is
  missing too — the paper lists `hip` among its search terms and fits no hip curve —
  but the hip has a defensible nearest region and the neck does not.) This
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
                                         "segment, different contraction.")}
   :knee-extension           {:region :knee :basis :named-in-source}
   :ankle-plantarflexion     {:region :ankle :basis :named-in-source}
   :hip-extension            {:region :trunk :basis :nearest-region
                              :note (str "the reference searched for `hip` and "
                                         "fitted no hip curve — its six regions "
                                         "are ankle, knee, trunk, shoulder, elbow "
                                         "and hand/grip. `:trunk` is the nearest, "
                                         "and the reference's own discussion "
                                         "groups them: it reports the prior review "
                                         "considering `general fatigue models, "
                                         "upper limb (shoulder, elbow, hand) "
                                         "models, and trunk/hip models`, and calls "
                                         "that grouping `consistent with our power "
                                         "ET models`. Nearest, not named.")}})

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

(defn- chronic-dose
  "The chronic (low-load, no-failure-point) term of the dose. Split out of
  `muscle-strain*` unchanged so that the inversion below and the dose the report
  prints are computed by one expression rather than two."
  [mvc-pct session-minutes]
  (* chronic-weight (/ (max 0.0 (- mvc-pct chronic-threshold-pct)) 100.0)
     (/ session-minutes 60.0)))

(defn dose
  "The dimensionless load-time dose at `mvc-pct` held for `session-minutes`, in
  its two terms.

  IT WAS ALREADY BEING COMPUTED AND THROWN AWAY. `muscle-strain` reported
  `:acute-dose` and `:chronic-dose` but not their sum, and the sum is the quantity
  the index is a saturating transform of — `stiffness = 1 - exp(-dose)`. That
  transform is the right shape for a probability and the wrong shape for a
  comparison of magnitudes, which is what this actor exists to make (G3, the same
  member's postures against each other). Over the three reference workstations at
  120 min the doses run 0.0 to 166; the indices they map to run 0.0 to 1.0 and
  three of them are 1.00 to two decimals. The dose is the figure that still has
  range where the index has none, so it is now reported."
  [mvc-pct session-minutes]
  (let [t-end (endurance-minutes mvc-pct)
        acute (if (math/infinite? t-end) 0.0 (/ session-minutes t-end))
        chronic (chronic-dose mvc-pct session-minutes)]
    {:acute acute :chronic chronic :total (+ acute chronic)}))

(defn index-at
  "The stiffness index at a %MVC and a session length, without building a whole
  strain map. Same arithmetic as `muscle-strain`."
  [mvc-pct session-minutes]
  (- 1.0 (Math/exp (- (:total (dose mvc-pct session-minutes))))))

(defn index-resolution
  "Which regime of the index a single strain entry sits in, as one keyword, so a
  consumer does not have to reassemble it from three flags at every render site.

    `:not-computed`          the model refused this muscle; there is no index.
    `:below-endurance-floor` the acute term is switched off by construction. The
                             index still moves with load here, but it is separated
                             from every above-floor index by the discontinuity, so
                             the two are not on a common scale and the ratio
                             between them is not a load ratio.
    `:saturated`             the index is exactly 1.0 and has no resolution left.
    `:distinguishing`        the index is actually carrying information.

  THIS IS THE `G3` KEY. The comparison this actor exists to make is one member's
  posture against their own other posture. `0.04` against `0.85` reads as a factor
  of twenty and is produced by 0.02 %MVC across the floor; `1.00` against `1.00`
  reads as a tie and can be a factor of two in load. Both are misreadings of a
  correct number, and both are avoidable if the render knows which regime each row
  is in."
  [entry]
  (cond
    (nil? (:stiffness-index entry)) :not-computed
    (:unbounded-endurance? entry) :below-endurance-floor
    (:saturated? entry) :saturated
    :else :distinguishing))

(defn- muscle-strain* [t session-minutes]
  (let [mvc-pct (:mvc-pct t)
        e (endurance mvc-pct)
        t-end (:minutes e)
        ;; one expression for the dose, shared with the inversion in
        ;; `band-resolution`. The arithmetic is unchanged: `dose` is the same
        ;; two terms in the same order this function used to write inline.
        {:keys [acute chronic] total :total} (dose mvc-pct session-minutes)
        stiffness (- 1.0 (Math/exp (- total)))
        ;; `>= 1.0` rather than `= 1.0`: the comparison is about what the double
        ;; can still distinguish, not about an exact value.
        saturated? (>= stiffness 1.0)
        entry {:unbounded-endurance? (:unbounded? e)
               :saturated? saturated?
               :stiffness-index stiffness}]
    {:name (:name t)
     :group (:group t)
     :side (:side t)
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
     ;; the sum was computed and discarded. It is the quantity the index is a
     ;; saturating transform of, and it still has range where the index does
     ;; not — see `dose` and `band-resolution`.
     :dose total
     :stiffness-index stiffness
     :saturated? saturated?
     ;; which regime of the index this row is in, as one keyword, so a render
     ;; does not have to reassemble it from three flags. See `index-resolution`.
     :index-resolution (index-resolution entry)
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
     ;; `:group` and `:side` travel with the dose, because the anatomy layer had
     ;; already separated them and re-joining them into `:name` made every
     ;; consumer split the string back apart. `attachment/instances` sets both on
     ;; every instance and `solve-muscle-tensions` merges them through; this is
     ;; the one hop that used to drop them, which is why `datoms` had to recover
     ;; the pair by looking for a "/" — a parser for a grammar nothing declared.
     :group (:group t)
     :side (:side t)
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
     :dose nil
     :index-resolution :not-computed
     :over-endurance nil}
    (muscle-strain* t session-minutes)))

(defn session-strain
  "Stiffness map for a whole work session (default 2 hours of continuous posture)."
  ([tensions] (session-strain tensions 120.0))
  ([tensions session-minutes]
   (mapv #(muscle-strain % session-minutes) tensions)))

;; --- the band scale, and what it can actually resolve -------------------------

(def band-thresholds
  "The band cut points, as data, because `band-resolution` has to invert them and
  a second copy of `0.20 / 0.45 / 0.70` would let the two drift apart. Read as
  `[upper-exclusive-bound label]`, ascending, with a final `nil` bound for the
  open top band. `stiffness-band` is now a lookup over this vector and returns
  exactly what it returned before."
  [[0.20 "low"]
   [0.45 "moderate"]
   [0.70 "high"]
   [nil  "very-high"]])

(defn stiffness-band
  "A coarse human-readable band for the stiffness index (display only,
  non-diagnostic). A nil index — the model refused this muscle's load — gets its
  own band rather than falling into `low`, which would read as the best case.

  THIS IS THE HEADLINE COLUMN AND AT THIS APP'S SESSION LENGTHS IT CARRIES ONE
  BIT. See `band-resolution`: `moderate` and `high` are not merely unvisited by
  the reference postures, they are UNREACHABLE — the index steps across their
  entire combined width in one discontinuity at the endurance floor. Measured
  2026-09-07 on the three reference workstations at 120 min, the 144 muscle
  entries come out `{low 99, not-computed 28, very-high 17}`, and the 17
  `very-high` span 8.3 %MVC to 57.4 %MVC — a factor of 6.9 in load, 88 in
  endurance time and 86 in dose — under one word. Nothing here is tuned to hide that; the
  thresholds are unchanged and `band-resolution` computes the damage."
  [stiffness-index]
  (if (nil? stiffness-index)
    "not-computed"
    (loop [[[bound label] & more] band-thresholds]
      (if (or (nil? bound) (< stiffness-index bound))
        label
        (recur more)))))

(def maximum-voluntary-pct
  "The top of the %MVC axis the resolution analysis searches over, which is 100 —
  maximum voluntary contraction, and also the top of the range the reference's
  fit was obtained over. Taken from `frey-law-2010` rather than written again so
  the two cannot disagree. Above it `endurance-position` already says there is no
  holding time to extrapolate to, so an interval that ran past it would be
  measuring the width of a region the model itself disclaims."
  (second (:fitted-range-pct frey-law-2010)))

(defn floor-discontinuity
  "The step the index takes across the endurance floor, and the reason it is not
  obviously wrong.

  WHAT HAPPENS. `endurance-minutes` returns ∞ at or below 8 %MVC and a finite
  number above it, so the acute term switches on at full size rather than growing
  from zero: at the floor the model's own power law is already at about 70
  minutes, so a two-hour session picks up 1.71 of dose in the width of a rounding
  error. The index steps from 0.053 to 0.829 across 0.001 %MVC.

  WHY IT IS NOT SIMPLY A BUG. Below the floor the model is asserting that the load
  can be held indefinitely. That is a claim, not an omission, and the reference
  cannot settle it: Frey Law & Avin's fit has no pole, returns 54 min at 8 %MVC
  and 138 min at 5 %MVC, and its own data does not reach below 10 %MVC. So the two
  sides disagree about a region where neither has measured anything.

  WHAT IS NEW HERE, AND IT CUTS AGAINST THE FLOOR. At the floor the two curves are
  still AGREEING: the model's 70.12 min against the pooled fit's 54.27 min is a
  ratio of 1.29, inside the reference's own wide (±47%) prediction interval. So
  the ∞ is not switched on at a point where the power law had started to misbehave
  — it is switched on while the power law was still inside the literature's spread.
  `:model-and-reference-agree-at-the-floor?` is that comparison, computed rather
  than asserted. It is reported and NOT acted on: removing the floor would replace
  one unmeasured claim (`indefinitely`) with another (`54 minutes`), and this
  namespace does not have the data to prefer either.

  NO SMOOTHING CONSTANT IS INTRODUCED. A blend width over which to fair the step
  away would have to come from somewhere, and there is nowhere for it to come
  from: the reference has no data in the region, and a width chosen so the
  histogram looks better is the invented constant this repo has spent days
  removing. The step is reported at full size."
  [session-minutes]
  (let [below endurance-floor-pct
        ;; the limit of the power law as %MVC approaches the floor from above.
        ;; `endurance-minutes` uses `<=`, so the floor itself takes the ∞ branch;
        ;; this is the value the very next representable input gets.
        t-end-above (* (:coefficient-minutes model-form)
                       (Math/pow (/ endurance-floor-pct 100.0)
                                 (:exponent model-form)))
        chronic (chronic-dose below session-minutes)
        dose-below chronic
        dose-above (+ chronic (/ session-minutes t-end-above))
        idx-below (- 1.0 (Math/exp (- dose-below)))
        idx-above (- 1.0 (Math/exp (- dose-above)))
        ref-min (reference-endurance-minutes :general endurance-floor-pct)
        ratio (/ t-end-above ref-min)
        {:keys [narrow-fraction wide-fraction]} reference-prediction-interval]
    {:session-minutes session-minutes
     :mvc-pct endurance-floor-pct
     :endurance-minutes-below inf
     :endurance-minutes-above t-end-above
     :dose-below dose-below
     :dose-above dose-above
     :index-below idx-below
     :index-above idx-above
     :index-gap (- idx-above idx-below)
     :band-below (stiffness-band idx-below)
     :band-above (stiffness-band idx-above)
     :reference-minutes-at-floor ref-min
     :ratio-just-above-floor ratio
     :model-and-reference-agree-at-the-floor?
     (and (>= ratio (- 1.0 wide-fraction)) (<= ratio (+ 1.0 wide-fraction)))
     :agree-under-narrow-reading?
     (and (>= ratio (- 1.0 narrow-fraction)) (<= ratio (+ 1.0 narrow-fraction)))
     :floor-is-a-modelling-choice true
     :citation (:citation frey-law-2010)
     :url (:url frey-law-2010)
     :obtained (:obtained frey-law-2010)}))

;; Inverting the index. Two branches, because the model has two: below the floor
;; the dose is the chronic term alone and inverts in closed form; above it the
;; dose is a power plus a linear term and does not, so it is bisected. The
;; bisection's 1e-12 / 200-iteration stop is a convergence bound on a monotone
;; function, not a parameter of the model.

(defn- mvc-below-floor-at-index
  "Smallest %MVC at or below the endurance floor whose index reaches `y`, or nil
   when the chronic term never gets there before the floor."
  [y session-minutes]
  (cond
    (<= y 0.0) 0.0
    (>= y 1.0) nil
    (<= session-minutes 0.0) nil
    :else
    (let [d (- (Math/log (- 1.0 y)))
          k (* chronic-weight (/ session-minutes 60.0))
          f (+ chronic-threshold-pct (* 100.0 (/ d k)))]
      (when (<= f endurance-floor-pct) f))))

(defn- mvc-above-floor-at-index
  "Infimum of the %MVC in [floor, 100] whose index reaches `y`, or nil when even
   maximum voluntary contraction does not get there."
  [y session-minutes]
  (let [idx #(index-at % session-minutes)]
    (when (>= (idx maximum-voluntary-pct) y)
      (loop [a endurance-floor-pct b maximum-voluntary-pct n 0]
        (if (or (>= n 200) (< (- b a) 1e-12))
          b
          (let [m (* 0.5 (+ a b))]
            (if (>= (idx m) y) (recur a m (inc n)) (recur m b (inc n)))))))))

(defn- band-segments
  "The %MVC intervals that land in the band `[lo, hi)` at this session length, one
   per branch of the model, with zero-width intervals dropped."
  [lo hi session-minutes]
  (let [seg (fn [start end] (when (and start end (> end start)) [start end]))
        below (seg (mvc-below-floor-at-index lo session-minutes)
                   (if (nil? hi)
                     endurance-floor-pct
                     (or (mvc-below-floor-at-index hi session-minutes)
                         endurance-floor-pct)))
        above (seg (mvc-above-floor-at-index lo session-minutes)
                   (if (nil? hi)
                     maximum-voluntary-pct
                     (or (mvc-above-floor-at-index hi session-minutes)
                         maximum-voluntary-pct)))]
    (vec (remove nil? [below above]))))

(defn band-resolution
  "What the four-band headline column can actually distinguish at a given session
  length — computed by inverting the model, not by sampling it.

  THE FINDING THIS EXISTS FOR. The index is discontinuous at the endurance floor
  (see `floor-discontinuity`), and at ordinary session lengths the step is wider
  than the two middle bands put together. Nothing can land in a band that lies
  entirely inside the step, so `moderate` and `high` are not rare — they are
  unreachable, and the column a reader reads is a threshold test on one number,
  `%MVC > 8`.

  `:unreachable-window-minutes` is the closed-form answer to `from what session
  length`: a band `[lo, hi)` becomes unreachable once the index just above the
  floor passes `hi`, and becomes reachable again if the chronic term below the
  floor ever climbs to `lo`. Both ends are solved from `chronic-weight`,
  `chronic-threshold-pct`, `endurance-floor-pct` and the power-law constants; no
  number is chosen here. Measured with the thresholds as they stand: `moderate`
  dies at 40.6 min and returns at 495.9, `high` dies at 81.8 and returns at
  1328.4. The app's default session is 120 min and its stated range runs to a
  working day, so both middle bands are dead across the whole of it.

  THE TOP IS BLIND TOO, BY A DIFFERENT MECHANISM. `:saturation-onset-pct` is the
  %MVC above which the index is exactly 1.0 in double precision — 30.2 %MVC at
  120 min, 16.6 at 480. Above it the index is not coarse, it is constant, and
  `:saturated-width-pct` says how much of the axis that is (70 of the 100 points
  at 120 min). `:distinguishing-range-pct` is what is left in between: the only
  window above the floor where the index moves at all. The bottom loses resolution
  to a GAP and the top loses it to a CEILING; they are different faults with the
  same consequence, and until now only the ceiling was written down.

  `:reachable-bands` is the honest size of the instrument.

  SEGMENT ENDPOINTS. `:mvc-pct-segments` are `[a, b)` on the %MVC axis, with two
  exceptions forced by the model rather than chosen: a segment is OPEN at `a` when
  `a` is the endurance floor, and CLOSED at `b` when `b` is the floor or the top of
  the axis. `endurance-minutes` tests `<=` against the floor, so the floor point
  itself belongs to the branch below it. Under that convention the inversion
  reproduces a 0.05 %MVC sweep of the model exactly, which is what
  `band-resolution-inversion-agrees-with-sampling-the-model` asserts."
  [session-minutes]
  (let [pairs (map vector
                   (cons 0.0 (map first (butlast band-thresholds)))
                   (map first band-thresholds))
        ;; d(dose)/d(session-minutes) on each side of the floor. Both are linear
        ;; in the session length, so each band's reachability window has a closed
        ;; form rather than needing a search over session lengths.
        t-end-above (* (:coefficient-minutes model-form)
                       (Math/pow (/ endurance-floor-pct 100.0) (:exponent model-form)))
        k-below (* chronic-weight
                   (/ (- endurance-floor-pct chronic-threshold-pct) 100.0)
                   (/ 1.0 60.0))
        k-above (+ k-below (/ 1.0 t-end-above))
        window (fn [lo hi]
                 (when (and hi (pos? lo))
                   [(/ (- (Math/log (- 1.0 hi))) k-above)
                    (/ (- (Math/log (- 1.0 lo))) k-below)]))
        bands (mapv (fn [[lo hi] [_ label]]
                      (let [segs (band-segments lo hi session-minutes)
                            w (reduce + 0.0 (map (fn [[a b]] (- b a)) segs))]
                        {:band label
                         :index-range [lo hi]
                         :mvc-pct-segments segs
                         :mvc-pct-width w
                         :reachable? (pos? w)
                         :unreachable-window-minutes (window lo hi)}))
                    pairs band-thresholds)
        onset (mvc-above-floor-at-index 1.0 session-minutes)]
    {:session-minutes session-minutes
     :mvc-pct-axis [0.0 maximum-voluntary-pct]
     :bands bands
     :reachable-bands (mapv :band (filter :reachable? bands))
     :unreachable-bands (mapv :band (remove :reachable? bands))
     :floor-gap (select-keys (floor-discontinuity session-minutes)
                             [:index-below :index-above :index-gap
                              :band-below :band-above])
     :saturation-onset-pct onset
     :saturated-width-pct (when onset (- maximum-voluntary-pct onset))
     :distinguishing-range-pct [endurance-floor-pct (or onset maximum-voluntary-pct)]}))
