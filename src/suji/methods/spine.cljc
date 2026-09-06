(ns suji.methods.spine
  "suji (筋) — compression, level by level. Pure `.cljc`, stdlib only, no I/O.

  WHY. This actor reported ONE spinal number: the cervical compressive load, a
  lumped value calibrated against Hansraj (2014). It is the right number and it is
  the only one — it says nothing about where along the neck the load peaks, and
  nothing at all about the lumbar spine, where the same posture puts far more force
  through a joint that is also far larger. Neither does a force alone: a disc's
  tolerance is a STRESS, and 500 N through a cervical disc and 500 N through a
  lumbar one are not the same event.

  WHAT IT COMPUTES. For each intervertebral level, the axial force is

      Σ (weight of everything above it, projected on the spine's local axis)
    + Σ (force of every muscle whose line crosses it, projected on the same axis)

  and the stress is that force divided by the level's disc area. The tissue term is
  reported split into `:muscle-n` and `:ligament-n`, because past flexion-relaxation
  the second one IS the answer and calling it muscle would be a different claim.
  The muscle term usually dominates — an extensor works at a short moment arm, so holding a small
  external moment costs a large force, and all of that force presses the joint
  together. That is why a flexed posture loads a spine so much more than the
  weight it carries would suggest, and a model that omitted the muscle term would
  understate every posture by a factor.

  WHAT IT IS NOT. Disc areas are representative adult values scaled with stature,
  not measurements of anybody (G7). Mass above a level is taken as uniform along
  the segment; the segment's own centre of mass is NOT uniform, and this is stated
  rather than hidden — it biases levels near a segment's ends. There is no
  curvature: the model's spine is two straight segments, so it has no lordosis and
  no shear component. NON-DIAGNOSTIC (G1): a stress is a stress.

  VALIDATION STATUS. Two cross-checks live here and they answer different questions.
  `cervical-cross-check` compares this profile against the lumped cervical model
  that IS validated (Hansraj 2014) and reports which of the two carries that
  validation. `lumbar-cross-check` compares the lumbar profile against a published
  in-vivo measurement (Wilke 1999). Neither makes this profile validated; the
  second one measures, in newtons, how far from a measurement it is."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.segment :as segment]))

(def reference-stature-m 1.70)

(def levels
  "Intervertebral levels, proximal to distal along each segment.

  `:along` is the fraction of that segment's length from its PROXIMAL joint, so
  L5/S1 is at 0.0 of the trunk and C7/T1 at 0.0 of the neck. `:disc-area-cm2` is a
  representative adult cross-section at reference stature; it scales with
  stature² because an area does."
  [{:name "L5/S1" :region :lumbar :segment "thorax_abdomen" :along 0.00 :disc-area-cm2 18.0}
   {:name "L4/L5" :region :lumbar :segment "thorax_abdomen" :along 0.07 :disc-area-cm2 17.0}
   {:name "L3/L4" :region :lumbar :segment "thorax_abdomen" :along 0.14 :disc-area-cm2 16.0}
   {:name "L2/L3" :region :lumbar :segment "thorax_abdomen" :along 0.21 :disc-area-cm2 15.0}
   {:name "L1/L2" :region :lumbar :segment "thorax_abdomen" :along 0.28 :disc-area-cm2 14.0}
   {:name "C7/T1" :region :cervical :segment "head_neck" :along 0.00 :disc-area-cm2 5.0}
   {:name "C6/C7" :region :cervical :segment "head_neck" :along 0.06 :disc-area-cm2 4.6}
   {:name "C5/C6" :region :cervical :segment "head_neck" :along 0.12 :disc-area-cm2 4.3}
   {:name "C4/C5" :region :cervical :segment "head_neck" :along 0.18 :disc-area-cm2 4.0}
   {:name "C3/C4" :region :cervical :segment "head_neck" :along 0.24 :disc-area-cm2 3.8}])

(defn disc-area-m2
  "Disc area in m², scaled with stature² from the reference."
  [level stature-m]
  (let [k (/ stature-m reference-stature-m)]
    (* (:disc-area-cm2 level) 1e-4 k k)))

(defn level-point
  "World position of a level, and the spine's local axis there (pointing up the
  chain, i.e. the direction compression acts along)."
  [pose-data level]
  (let [{:keys [proximal frame length-m]} (pose/seg-at pose-data (:segment level))]
    {:point (math/v+ proximal (math/v* (:long frame) (* (:along level) length-m)))
     :axis (:long frame)}))

;; --- what sits above a level -------------------------------------------------

(def ^:private chain-order
  "Segments in ascending order along the spine. A level on the trunk has the whole
  neck above it; a level on the neck has only the neck above it. The arms hang
  from the girdle, which is on the trunk, so they load every trunk level and no
  cervical one."
  {"thorax_abdomen" 0 "head_neck" 1})

(defn- above-fraction
  "How much of `seg` sits above `level` — 1.0 for a segment higher up the chain,
  0.0 for one lower, and the remaining fraction for the segment the level is on.

  UNIFORM along the segment, which is not how mass is actually distributed (see
  `segment`'s `:com-frac`). Stated here because it biases levels near a segment's
  ends, and a reader comparing two adjacent levels should know the bias is in the
  model rather than in the body."
  [level seg]
  (let [lvl-rank (chain-order (:segment level))
        seg-rank (chain-order (:base seg))]
    (cond
      (nil? seg-rank) (if (= 0 lvl-rank) 1.0 0.0)  ;; arms + pelvis: above trunk levels only
      (> seg-rank lvl-rank) 1.0
      (< seg-rank lvl-rank) 0.0
      :else (max 0.0 (- 1.0 (:along level))))))

(defn- weight-above-n
  "Axial component of the weight sitting above a level."
  [body pose-data level]
  (let [w (pose/segment-weights body pose-data)
        {:keys [axis]} (level-point pose-data level)]
    (reduce + 0.0
            (for [seg (:segments pose-data)
                  :when (not= "pelvis" (:base seg))
                  :let [f (above-fraction level seg)]
                  :when (pos? f)]
              ;; gravity is [0,-w,0]; its compressive component along the spine
              ;; axis is w × (axis · up)
              (* f (get w (:name seg)) (nth axis 1))))))

(defn- crosses?
  "Does this muscle's line cross the level? True when its two attachment points
  sit on opposite sides of the level along the spine axis."
  [pose-data stature-m level muscle]
  (let [{:keys [point axis]} (level-point pose-data level)
        {:keys [origin insertion]} (attachment/line-of-action pose-data stature-m muscle)
        h (fn [p] (math/vdot (math/v- p point) axis))]
    (and origin insertion (neg? (* (h origin) (h insertion))))))

(defn- tissue-compression-n
  "Axial component of the forces crossing the level, split by what produced them.

  `{:muscle-n … :ligament-n …}`. It used to be one number called `:muscle-n`, and
  once the posterior ligamentous system landed that name stopped being true:
  measured 2026-09-06 at 60 degrees of trunk flexion, the term was 3,904 N of
  which 3,989 N came from the ligament — the muscles were contributing a NEGATIVE
  net component and the ligament was pressing the joint together on its own. A
  reader taking `:muscle-n` literally would have read a spine compressed by muscle
  where it is compressed by tissue, which is a different statement about the
  posture and about what would change it."
  [pose-data stature-m level tensions]
  (let [{:keys [axis]} (level-point pose-data level)
        by-name (into {} (map (juxt :name identity)) tensions)
        contribution
        (fn [m]
          (let [t (by-name (:name m))
                f (:force-n t)]
            (when (and f (pos? f) (crosses? pose-data stature-m level m))
              (let [{:keys [dir]} (attachment/line-of-action pose-data stature-m m)]
                (when dir
                  ;; only the component ALONG the spine compresses it; the
                  ;; transverse component is shear, which this model does not carry
                  (* f (math/abs* (math/vdot dir axis))))))))]
    {:muscle-n (reduce + 0.0 (keep #(when-not (:ligament? %) (contribution %))
                                   attachment/instances))
     :ligament-n (reduce + 0.0 (keep #(when (:ligament? %) (contribution %))
                                     attachment/instances))}))

(defn level-compression
  "Compression at one level: {:name :region :force-n :stress-mpa :weight-n :muscle-n}."
  [body pose-data tensions level]
  (let [stature-m (:stature-m body)
        weight (weight-above-n body pose-data level)
        {:keys [muscle-n ligament-n]} (tissue-compression-n pose-data stature-m level tensions)
        force (+ weight muscle-n ligament-n)
        area (disc-area-m2 level stature-m)]
    {:name (:name level)
     :region (:region level)
     :weight-n weight
     :muscle-n muscle-n
     :ligament-n ligament-n
     :force-n force
     :disc-area-cm2 (* 1e4 area)
     ;; N / m² = Pa; ÷1e6 = MPa, which is the unit disc tolerances are quoted in
     :stress-mpa (/ force area 1e6)}))

(defn profile
  "Compression at every level, in the order `levels` declares."
  [body posture tensions]
  (let [p (pose/solve-pose body posture)]
    (mapv #(level-compression body p tensions %) levels)))

(defn cervical-cross-check
  "Compare this namespace's C7/T1 force against `load/cervical-load`'s lumped,
  Hansraj-calibrated one, and report the ratio.

  THEY DISAGREE, and which one is trustworthy is not symmetric. The lumped model
  is the leg this actor VALIDATED — it reproduces the published forward-head table
  to within 10% — and it gets there with an effective lever fitted to that table.
  This namespace instead adds the muscle force computed from the muscle's actual
  geometric moment arm, which at large flexion is much shorter and therefore
  demands much more force, all of which presses the joint together. Measured
  2026-09-06 at the laptop-on-lap posture: 232 N lumped, 519 N by level, a ratio
  of 2.2.

  Neither number is offered as the right one here. What is offered is the ratio,
  so a consumer cannot read the level profile as though it inherited the lumped
  model's validation — it did not. The cervical leg remains the validated one; the
  level profile is mechanically constructed and UNVALIDATED, and this function
  exists so that saying so is a computation rather than a sentence somebody has to
  remember to keep true."
  [body posture tensions cervical-load]
  (let [rows (profile body posture tensions)
        c7 (first (filter #(= "C7/T1" (:name %)) rows))
        lumped (:compressive-load-n cervical-load)]
    {:level-force-n (:force-n c7)
     :lumped-force-n lumped
     :ratio (when (and lumped (pos? lumped)) (/ (:force-n c7) lumped))
     :validated :lumped
     :note "the lumped cervical model is the validated one (Hansraj 2014); the level profile is unvalidated"}))

;; --- the lumbar spine, against the literature --------------------------------
;;
;; The lumbar spine is the most-measured structure in this field, so unlike most
;; of what this actor computes there is real data to answer to. Everything below
;; carries its own provenance: the citation, the URL that was actually fetched,
;; and whether the full text or only an abstract was read. A reference value with
;; no provenance is indistinguishable from an invented one.

(def nachemson-pressure-index
  "Nachemson's PRESSURE INDEX — the number that turns an intradiscal pressure into
  a compressive force. It is the largest single assumption in `lumbar-cross-check`
  and it is stated separately so that it can be argued with.

  A PRESSURE IS NOT A FORCE. Wilke measured megapascals in a nucleus; this model
  computes newtons through a joint. Nachemson measured, in vitro, the quotient
  between the pressure recorded in the nucleus and the pressure applied to the
  whole disc, and named it the pressure index (Acta Orthop Scand XXVIII p.282,
  formula p.285):

      I = Pn / (P / Ad)      hence      P = Pn × Ad / I

  where Pn is the nucleus pressure, P the force applied to the whole disc and Ad
  the disc's total cross-sectional area.

  THE VALUE AND ITS SPREAD. Table 5 (p.281) tabulates the index by interspace and
  state of disc. For NORMAL lumbar discs: L1 1.6, L2 1.7, L3 1.5, L4 1.7 — so
  1.5 to 1.7, and the prose at p.285 uses 1.5 as the normal-disc figure. The
  degenerated column (1.2–1.5) is deliberately not used here: Wilke selected a
  disc with `no signs of degeneration or dehydration`.

  IT IS A MODELLING ASSUMPTION, NOT A MEASUREMENT. The index was measured on
  cadaver discs under pure axial load. Using it on an in-vivo pressure assumes the
  same proportionality holds in a living spine that is also being squeezed by its
  own muscles. Wilke's paper performs no such conversion and does not endorse one.
  Every force this namespace derives from a pressure inherits that assumption, and
  `lumbar-cross-check` propagates the 1.5–1.7 range into the reference's spread
  rather than hiding it behind the single number."
  {:mean 1.5
   :range [1.5 1.7]
   :normal-by-interspace {"L1" 1.6 "L2" 1.7 "L3" 1.5 "L4" 1.7}
   :citation (str "Nachemson A. Measurement of Intradiscal Pressure. "
                  "Acta Orthopaedica Scandinavica XXVIII:269-289. "
                  "Definition p.282, formula p.285, Table 5 p.281.")
   :url "https://actaorthop.org/actao/article/download/30762/35650/84307"
   :obtained :full-text})

(defn pressure->compressive-force-n
  "Intradiscal pressure (MPa) → compressive force (N), through Nachemson's index.

      force = pressure × disc area ÷ index

  1 MPa is 1 N/mm², so a pressure in MPa times an area in mm² is already newtons;
  the index then divides. Carrying an assumption as an argument rather than a
  constant is the point — a caller who wants the degenerated-disc index, or none
  at all, passes it and the result says which was used."
  [pressure-mpa disc-area-mm2 index]
  (when (or (nil? pressure-mpa) (nil? disc-area-mm2) (nil? index) (zero? index))
    (throw (ex-info "pressure, area and a non-zero index are all required"
                    {:type :value-error :pressure-mpa pressure-mpa
                     :disc-area-mm2 disc-area-mm2 :index index})))
  (/ (* pressure-mpa disc-area-mm2) index))

(def ^:private wilke-1999
  {:citation (str "Wilke H-J, Neef P, Caimi M, Hoogland T, Claes LE. "
                  "New in vivo measurements of pressures in the intervertebral disc "
                  "in daily life. Spine 1999;24(8):755-762.")
   :url "https://www.fonar.com/pdf/spine_vol_24.No.8.pdf"
   :obtained :full-text
   :level "L4/L5"
   ;; p.756: `was 45 years old, weighed 70 kg, stood 1.68 m`
   :subject {:mass-kg 70.0 :stature-m 1.68}
   ;; p.756: `The cross-sectional area was 1800 mm².`
   :disc-area-mm2 1800.0
   ;; p.761, the authors' own limitation
   :caveat (str "one subject, one disc, one day: `this study was performed with only "
                "one subject, thereby limiting the significance of the data to the "
                "individual trends observed` (p.761)")})

(def lumbar-references
  "In-vivo lumbar reference values, each with the provenance of the number.

  All four are from Table 1 (p.757) of Wilke et al. 1999, the in-vivo L4/L5
  telemetry study. The table gives twenty-nine entries; these are the ones this
  actor could have anything to say about, and only the FIRST of them is actually
  comparable — see `:posture` and `:not-comparable`.

  WHY MOST OF THEM ARE NOT COMPARABLE. A pressure is measured at a posture, and
  Wilke reports the pressure without reporting the trunk angle. To compare the
  model against `sitting with maximum flexion` this namespace would have to CHOOSE
  a trunk angle, and choosing it is the one move that would turn this file from a
  validation into a fit. So those entries carry the published pressure — a real
  number, worth having — and refuse to produce a ratio.

  The comparable one is `sitting relaxed, without backrest`, because Wilke states
  the posture as well as the pressure: `Relaxed sitting on a stool with a normally
  straight back` (p.758). A normally straight back is zero trunk flexion, which is
  a posture this model can hold without anybody choosing an angle."
  [{:id :wilke-1999-sitting-relaxed-no-backrest
    :label "sitting relaxed, without backrest"
    :pressure-mpa 0.46
    ;; p.758: `produced a pressure peak of 0.45 to 0.50 MPa`
    :pressure-range-mpa [0.45 0.50]
    :posture {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
              :elbow-flexion-deg 0.0 :shoulder-elevation-deg 0.0
              :wrist-extension-deg 0.0 :arms-supported false}
    :posture-basis (str "p.758 `Relaxed sitting on a stool with a normally straight "
                        "back` — a straight back is zero trunk flexion. The paper does "
                        "not state where the arms were; they hang, which is what a "
                        "stool with no armrests leaves them doing.")}
   {:id :wilke-1999-sitting-maximum-flexion
    :label "sitting with maximum flexion"
    :pressure-mpa 0.83
    :not-comparable :posture-angle-not-stated-in-source
    :not-comparable-note (str "p.758 gives the pressure and the activity (`simulating, "
                              "for instance, the position of tying shoes`) but no trunk "
                              "angle. Picking one would be fitting, not validating.")}
   {:id :wilke-1999-standing-bent-forward
    :label "standing, bent forward"
    :pressure-mpa 1.10
    :not-comparable :posture-angle-not-stated-in-source
    :not-comparable-note "Table 1 p.757 gives the pressure; no trunk angle is stated."}
   {:id :wilke-1999-relaxed-standing
    :label "relaxed standing"
    :pressure-mpa 0.50
    ;; p.758: `In relaxed standing, intradiscal pressure was reproducibly 0.48 to 0.50 MPa.`
    :pressure-range-mpa [0.48 0.50]
    :not-comparable :standing-is-not-representable
    :not-comparable-note
    (str "This is a SEATED model whose base is the pelvis and which has no thigh "
         "segment, so it cannot tell standing from sitting: it would return the same "
         "force for both. Wilke measures them apart (0.50 standing, 0.46 sitting), and "
         "the model's inability to reproduce that difference is a property of the model "
         "worth saying out loud rather than a number worth producing.")}])

(defn reference-by-id
  "The entry in `lumbar-references` with this id, or nil."
  [id]
  (first (filter #(= id (:id %)) lumbar-references)))

(def default-lumbar-reference-id :wilke-1999-sitting-relaxed-no-backrest)

(defn lumbar-cross-check
  "Compare the lumbar level profile against a published in-vivo measurement, and
  report the disagreement rather than removing it.

  IN THE SHAPE OF `cervical-cross-check`, WITH THE OPPOSITE ASYMMETRY. There, the
  validated side was this actor's own lumped model. Here the validated side is the
  LITERATURE — an in-vivo pressure telemetered from a living L4/L5 disc — and the
  level profile is the unvalidated one. `:validated` says so in both.

  IT TAKES NO BODY AND NO POSTURE, ON PURPOSE. A reference value is stated for a
  particular subject at a particular posture, and the model must be scaled to the
  reference rather than the reference to the model. Wilke's subject was 70 kg and
  1.68 m; this function builds THAT body and holds THAT posture, so a caller
  cannot compare a 90 kg body against a 70 kg measurement by accident.

  WHAT IS COMPARED. Force against force, in newtons. The reference's pressure is
  converted once, by `pressure->compressive-force-n` through
  `nachemson-pressure-index`, using the disc area Wilke MEASURED (1800 mm²) rather
  than the one this model assumes. Comparing forces keeps the disc-area difference
  out of the comparison; `:model-disc-area-mm2` and `:reference-disc-area-mm2` are
  both reported so a reader can see the difference that was kept out of it.

  THE SPREAD. `:reference-force-range-n` combines both spreads the sources state —
  Wilke's own reproducibility range for the posture, and Nachemson's index range
  for normal lumbar discs — so `:within-reference-spread?` is a comparison against
  the reference's own uncertainty and not against a tolerance chosen here.

  A reference the source does not pin a posture to returns `:could-not-obtain`
  with the reason, and no ratio. That is an answer, and it is a different answer
  from agreement."
  ([] (lumbar-cross-check (reference-by-id default-lumbar-reference-id)))
  ([reference]
   (let [{:keys [level subject disc-area-mm2 citation url obtained caveat]} wilke-1999
         base {:reference-id (:id reference)
               :reference-label (:label reference)
               :level level
               :subject subject
               :reference-pressure-mpa (:pressure-mpa reference)
               :reference-disc-area-mm2 disc-area-mm2
               :pressure-index nachemson-pressure-index
               :validated :reference
               :model-validated? false
               :citation citation
               :url url
               :obtained obtained
               :reference-caveat caveat}]
     (if-let [why (:not-comparable reference)]
       (assoc base
              :could-not-obtain why
              :could-not-obtain-note (:not-comparable-note reference)
              :model-force-n nil
              :ratio nil)
       (let [posture (:posture reference)
             body (segment/build-body (:mass-kg subject) (:stature-m subject))
             loads (load/solve-posture-loads body posture)
             tensions (muscle/solve-muscle-tensions body posture loads)
             row (first (filter #(= level (:name %)) (profile body posture tensions)))
             model-n (:force-n row)
             [i-lo i-hi] (:range nachemson-pressure-index)
             [p-lo p-hi] (or (:pressure-range-mpa reference)
                             [(:pressure-mpa reference) (:pressure-mpa reference)])
             ref-n (pressure->compressive-force-n (:pressure-mpa reference)
                                                  disc-area-mm2
                                                  (:mean nachemson-pressure-index))
             lo-n (pressure->compressive-force-n p-lo disc-area-mm2 i-hi)
             hi-n (pressure->compressive-force-n p-hi disc-area-mm2 i-lo)]
         (assoc base
                :posture posture
                :posture-basis (:posture-basis reference)
                :model-force-n model-n
                :model-weight-n (:weight-n row)
                :model-muscle-n (:muscle-n row)
                :model-ligament-n (:ligament-n row)
                :model-stress-mpa (:stress-mpa row)
                :model-disc-area-mm2 (* 100.0 (:disc-area-cm2 row))
                :reference-force-n ref-n
                :reference-force-range-n [lo-n hi-n]
                :ratio (/ model-n ref-n)
                :within-reference-spread? (and (>= model-n lo-n) (<= model-n hi-n))
                :direction (cond (< model-n lo-n) :model-below-reference
                                 (> model-n hi-n) :model-above-reference
                                 :else :within-reference-spread)))))))

(def niosh-1981-compression-criteria
  "The two lumbar compressive forces NIOSH names for workplace DESIGN.

  Not a validation and not about a person. These are occupational design
  thresholds for the L5/S1 disc, quoted here so that a force this model produces
  can be placed on the scale the literature uses. NON-DIAGNOSTIC (G1): comparing a
  newton to a published newton is arithmetic; nothing here says anything about
  anybody's health.

  STATED IN KILOGRAM-FORCE, NOT NEWTONS. The 1981 Work Practices Guide (p.36,
  `Biomechanical design criteria`) says `jobs which place more than 650 kg
  compressive force on the low-back are hazardous to all but the healthiest of
  workers` and that `a much lower level of 350 kg or lower should be viewed as an
  upper limit`. The familiar 3400 N and 6400 N are those two figures times g. The
  1993 revision quotes the lower one directly as `3·4 kN (770 lbs)` (Waters et al.
  Table 1 p.751) and kept it: `the 1991 NIOSH committee decided to maintain the
  1981 biomechanical criterion of 3·4 kN` (p.755).

  The labels `action limit` and `maximum permissible limit` are commonly attached
  to these two numbers. The pages read here do not use those words for them, so
  they are not used here either."
  {:design-upper-limit-kgf 350.0
   :hazardous-above-kgf 650.0
   :design-upper-limit-n (* 350.0 segment/gravity)
   :hazardous-above-n (* 650.0 segment/gravity)
   :level "L5/S1"
   :citations
   [{:citation (str "NIOSH. Work Practices Guide for Manual Lifting. "
                    "DHHS (NIOSH) Publication No. 81-122, 1981, p.36.")
     :url "https://stacks.cdc.gov/view/cdc/209417/cdc_209417_DS1.pdf"
     :obtained :full-text
     :note "scanned; p.36 read as an image — the document has no text layer"}
    {:citation (str "Waters TR, Putz-Anderson V, Garg A, Fine LJ. Revised NIOSH "
                    "equation for the design and evaluation of manual lifting tasks. "
                    "Ergonomics 1993;36(7):749-776, Table 1 p.751 and section 3 p.753.")
     :url "https://stacks.cdc.gov/view/cdc/205542/cdc_205542_DS1.pdf"
     :obtained :full-text}]})

(defn niosh-compression-comparison
  "Place an L5/S1 force on the NIOSH design scale. Arithmetic, not a verdict.

  Returns the force, both published thresholds in newtons, and the ratio to each.
  `:above-design-upper-limit?` and `:above-hazardous?` are comparisons of two
  numbers; what to do about either is a question for somebody who is allowed to
  answer it, which is not this actor (G1)."
  [force-n]
  (let [{:keys [design-upper-limit-n hazardous-above-n]} niosh-1981-compression-criteria]
    {:force-n force-n
     :design-upper-limit-n design-upper-limit-n
     :hazardous-above-n hazardous-above-n
     :ratio-to-design-upper-limit (/ force-n design-upper-limit-n)
     :ratio-to-hazardous (/ force-n hazardous-above-n)
     :above-design-upper-limit? (> force-n design-upper-limit-n)
     :above-hazardous? (> force-n hazardous-above-n)
     :source niosh-1981-compression-criteria}))

(defn attachment-steps
  "Levels whose muscle term is zero while a neighbour's is not — the artefact of
  point attachments.

  A real muscle attaches over a RANGE of vertebrae, so its contribution tapers
  along the spine. This model attaches it at a point, so a level just past that
  point loses the whole force at once. Reporting which levels those are is the
  difference between a reader seeing a step and a reader believing a spine."
  [profile-rows]
  (vec (for [[a b] (partition 2 1 profile-rows)
             :when (and (pos? (:muscle-n a)) (zero? (:muscle-n b)))]
         {:after (:name a) :at (:name b)})))

(defn peak
  "The level carrying the highest stress."
  [profile-rows]
  (when (seq profile-rows)
    (apply max-key :stress-mpa profile-rows)))
