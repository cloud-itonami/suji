(ns suji.methods.posture-test
  "Where every posture's lumbar lordosis came from.

  THIS NAMESPACE EXISTS BECAUSE OF A DEFAULT. `pose/lumbar-chord-tilt-deg` reads
  `(or (:pelvic-tilt-deg posture) 0.0)`, so until 2026-09-09 every posture in this
  library had a lordosis of exactly zero and not one of them said so. Zero is the
  right answer for one posture — Cho et al. 2015 measure a stool at 0.6 deg
  (SD 3.6) — and it was silently the answer for standing, for a deep squat and for
  three seated workstations as well.

  A number that is correct once and unset five times is worse than a wrong number,
  because it reads identically to a measured one. So the tests here are about
  PROVENANCE rather than about physics: every posture the library names must
  declare which kind of number its lordosis is, and the declaration must agree
  with the posture."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.math :as math]
            [suji.methods.posture :as posture]))

(deftest every-named-posture-declares-where-its-lordosis-came-from
  ;; THE GATE. It walks `posture/named-postures`, which lives beside the postures
  ;; rather than being copied into this file — a list written out here would keep
  ;; passing about the postures it already knew while a new one went unchecked.
  (doseq [[nm pst] posture/named-postures]
    (is (contains? pst :pelvic-tilt-deg)
        (str nm ": a posture must STATE its lordosis rather than inheriting the "
             "`(or … 0.0)` default, which is indistinguishable from a measurement"))
    (let [prov (posture/lordosis-provenance-for nm)]
      (is (contains? #{:measured :by-construction :parameter-not-in-source}
                     (:basis prov))
          (str nm ": the provenance must say what KIND of number this is, got "
               (pr-str (:basis prov))))
      (is (math/nearly= (:pelvic-tilt-deg prov) (:pelvic-tilt-deg pst) 1e-12)
          (str nm ": the provenance and the posture must agree — table says "
               (:pelvic-tilt-deg prov) ", posture carries "
               (:pelvic-tilt-deg pst))))))

(deftest a-measured-lordosis-agrees-with-the-table-it-says-it-came-from
  ;; A citation that does not match the number it cites is worse than no citation.
  ;; Every `:measured` entry has to reduce to `pelvic-tilt-for`, and its stated
  ;; mean and SD have to be the row Cho actually published.
  (let [measured (filter #(= :measured (:basis (val %))) posture/lordosis-provenance)]
    (is (pos? (count measured))
        (str "at least one posture must have a lordosis that was measured, or "
             "this whole exercise did nothing"))
    (doseq [[nm prov] measured]
      (let [row (get posture/lumbar-lordosis (:from prov))]
        (is (some? row)
            (str nm ": `:from` must name a row of `lumbar-lordosis`, got "
                 (pr-str (:from prov))))
        (is (math/nearly= (:deg row) (:measured-lordosis-deg prov) 1e-12)
            (str nm ": the stated lordosis must be the published one"))
        (is (math/nearly= (:sd row) (:sd-deg prov) 1e-12)
            (str nm ": the stated spread must be the published one — a mean "
                 "without its scatter reads as a precision this cohort does not "
                 "have"))
        (is (math/nearly= (posture/pelvic-tilt-for (:from prov))
                          (:pelvic-tilt-deg prov) 1e-12)
            (str nm ": the tilt must be the published lordosis measured from this "
                 "model's neutral, not a number written beside a citation"))))))

(deftest a-posture-with-no-measured-lordosis-says-so-and-says-which-way-it-is-wrong
  ;; `:parameter-not-in-source` is not a shrug. A posture whose lordosis nobody
  ;; measured still HAS one, so the entry has to name what was searched and which
  ;; way the model's neutral errs — otherwise the marker is a nicer-looking
  ;; version of the silent zero it replaced.
  (let [unsourced (filter #(= :parameter-not-in-source (:basis (val %)))
                          posture/lordosis-provenance)]
    (is (pos? (count unsourced))
        (str "some postures have no measured lordosis, and pretending otherwise "
             "would be the failure this table exists to prevent"))
    (doseq [[nm prov] unsourced]
      (let [pns (:parameter-not-in-source prov)]
        (is (= :pelvic-tilt-deg (:parameter pns))
            (str nm ": the marker must name the parameter"))
        (is (= 0.0 (:value pns))
            (str nm ": an unsourced posture holds the model's neutral"))
        (is (some? (:searched pns))
            (str nm ": it must say WHERE it was looked for, so the next reader "
                 "does not repeat the search"))
        (is (string? (:direction pns))
            (str nm ": and which way the neutral is wrong, which is the half a "
                 "bare `not stated` leaves out"))))))

(deftest the-only-zero-in-this-library-that-is-a-measurement-is-the-stool
  ;; THE POINT OF THE WHOLE BRANCH, stated as one assertion. Six of the seven
  ;; named postures carry a lordosis of zero. Exactly ONE of those zeros is a
  ;; measurement — `seated-posture`'s, which is Cho's stool at 0.6 deg measured
  ;; against Cho's stool — and the rest are the model's neutral, held for reasons
  ;; that are now written down. Before 2026-09-09 all six were the same
  ;; indistinguishable zero.
  (let [zeros (filter #(zero? (:pelvic-tilt-deg (val %))) posture/lordosis-provenance)
        measured-zeros (filter #(= :measured (:basis (val %))) zeros)]
    (is (= 1 (count measured-zeros))
        (str "exactly one zero in this library is a measurement, got "
             (pr-str (mapv key measured-zeros))))
    (is (= "seated-posture" (key (first measured-zeros)))
        "and it is the upright unsupported sit, which is what a stool is")
    (is (= :stool (:from (val (first measured-zeros))))))
  ;; and the one posture that is NOT zero is the one Cho measured a spine standing
  (is (math/nearly= 46.5 (:pelvic-tilt-deg posture/quiet-standing) 1e-12)
      "quiet standing carries Cho's standing lordosis, measured from her stool")
  (is (math/nearly= (posture/pelvic-tilt-for :standing)
                    (:pelvic-tilt-deg posture/quiet-standing) 1e-12)
      "derived from the table rather than written as a literal beside it"))

(deftest standing-and-the-stool-differ-by-what-cho-measured-between-them
  ;; The regression this branch exists to prevent: a `quiet-standing` that has
  ;; quietly gone back to a stool's lordosis. Asserted as the DIFFERENCE between
  ;; the two postures against the difference between the two published rows, so
  ;; the check survives a correction to either number.
  (let [published (- (:deg (:standing posture/lumbar-lordosis))
                     (:deg (:stool posture/lumbar-lordosis)))
        model (- (:pelvic-tilt-deg posture/quiet-standing)
                 (:pelvic-tilt-deg (posture/seated-posture)))]
    (is (math/nearly= published model 1e-12)
        (str "standing and the stool must differ by the " published " deg Cho "
             "measured between them; this model has " model))
    (is (> model 40.0)
        "and it is a large angle, not a rounding difference")))

(deftest the-provenance-lookup-refuses-a-posture-it-has-no-entry-for
  ;; The control on the gate itself. `lordosis-provenance-for` must not answer
  ;; `nil` for a posture it does not know, because nil and `no lordosis` are the
  ;; same value and the whole failure this table replaces was a missing value
  ;; reading as a stated one.
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (posture/lordosis-provenance-for "a-posture-nobody-defined"))
      "an unknown posture is refused rather than answered with nil"))

;; --- how the lordosis is SPENT (2026-09-11) ----------------------------------
;;
;; The tests above are about where a posture's lordosis NUMBER came from. These
;; are about what happens to it: how much of it turns the pelvis, and how the rest
;; divides between the five lumbar motion segments. Both used to be answered by
;; this model without a source — all of it turned the pelvis, and the five levels
;; shared one orientation.

(deftest the-segmental-shares-are-mills-table-1-and-nothing-else
  ;; The transcription check. Every share must be the source's own degree figure
  ;; over the source's own total, so a mistyped row is a failure here rather than
  ;; a silently different spine.
  (let [segs (get-in posture/spinopelvic-motion [:standing :segments])
        total (reduce + 0.0 (map :deg segs))]
    (is (= ["L5-S1" "L4-L5" "L3-L4" "L2-L3" "L1-L2"] (mapv :span segs))
        "ordered from the sacrum upward, because the turn accumulates that way")
    (is (= [13.4 14.2 11.2 7.7 5.6] (mapv :deg segs))
        "Mills Table 1, standing column")
    (is (math/nearly= 52.1 total 1e-12)
        (str "the five spans sum to 52.1 deg, against the 53.3 the same table "
             "reports as the Cobb L1-S1 lordosis; got " total))
    (is (math/nearly= 1.0 (reduce + 0.0 (map :share posture/lumbar-segmental-shares)) 1e-12)
        "and the shares are that column normalised, so they sum to one")
    (doseq [[s expected] (map vector posture/lumbar-segmental-shares segs)]
      (is (math/nearly= (/ (:deg expected) total) (:share s) 1e-15)
          (str (:span s) ": the share must be derived from the degrees, not written")))))

(deftest the-lower-two-motion-segments-carry-more-than-their-length
  ;; The claim the literature makes and the reason a uniform distribution is
  ;; wrong: the turn is concentrated at the bottom. Asserted against the LENGTH
  ;; share those two spans occupy, because equality there is exactly what constant
  ;; curvature assumes.
  (let [lower (reduce + 0.0 (map :share (take 2 posture/lumbar-segmental-shares)))
        length-share (/ 2.0 (count posture/lumbar-segmental-shares))]
    (is (math/nearly= 0.5297504798464491 lower 1e-12)
        (str "L5-S1 and L4-L5 carry 53.0% of Mills' standing lordosis, got " lower))
    (is (> lower length-share)
        (str "and that is more than the " length-share " of the length they "
             "occupy — which is the whole disagreement with constant curvature"))
    ;; the control that the concentration is at the BOTTOM and not merely uneven —
    ;; and it is NOT monotone. Bernhardt & Bridwell's abstract says lordosis
    ;; `gradually increases at each level caudally to the sacrum`; this cohort
    ;; rises from L1-L2 down to L4-L5 and then falls back at L5-S1 (13.4 against
    ;; 14.2). The two sources disagree about the last step and this asserts the
    ;; one installed, so that a future table which IS monotone fails here rather
    ;; than arriving unremarked.
    (is (apply > (map :share (rest posture/lumbar-segmental-shares)))
        "L4-L5 upward: each span carries more than the one above it")
    (is (< (:share (first posture/lumbar-segmental-shares))
           (:share (second posture/lumbar-segmental-shares)))
        (str "and L5-S1 carries LESS than L4-L5, which is where this cohort "
             "parts company with the classic per-level description"))
    (is (= "L4-L5" (:span (apply max-key :share posture/lumbar-segmental-shares)))
        "so the apex of the turn is L4-L5")))

(deftest a-uniform-turn-table-puts-the-chord-at-one-half
  ;; THE CONTROL ON THE MACHINERY. Constant curvature is the shape this model
  ;; assumed until 2026-09-11, and under it the chord bisects the two end
  ;; tangents — `trunk + lordosis/2`, which is what `pose/lumbar-chord-tilt-deg`
  ;; used to compute directly. Feed the same derivation a uniform shape and it has
  ;; to return exactly 0.5, or the new machinery has changed the answer for a
  ;; reason that is not the measurement.
  (let [uniform (posture/turn-nodes (repeat 5 {:share 0.2}))]
    (is (= 0.5 (posture/chord-turn-fraction uniform))
        "a uniform turn puts the mean tangent exactly halfway — to the bit")
    (is (= [0.0 0.2 0.4 0.6 0.8 1.0] (mapv first uniform))
        "and the length fractions are exact fifths"))
  (is (math/nearly= 0.5848368522072935 posture/lumbar-chord-turn-fraction 1e-12)
      (str "the measured shape puts it at 0.5848 instead — nearer the top end, "
           "because the arc turns faster at the bottom; got "
           posture/lumbar-chord-turn-fraction))
  (is (> posture/lumbar-chord-turn-fraction 0.5)
      "and that direction is the one a bottom-heavy turn has to produce"))

(deftest the-turn-table-runs-end-to-end
  ;; A table that did not reach 1.0 would leave part of the lordosis unspent, and
  ;; the model would quietly have less curvature than the posture states.
  (let [nodes posture/lumbar-turn-nodes]
    (is (= 6 (count nodes)) "five spans have six endplates")
    (is (= [0.0 0.0] (first nodes)) "the sacral endplate is the origin of the turn")
    (is (= 1.0 (first (last nodes))) "and the L1 endplate is the top of the length")
    (is (math/nearly= 1.0 (second (last nodes)) 1e-12)
        "and has turned through all of it")
    (is (apply < (map second (rest nodes)))
        "the turn is monotone: no span unwinds the one below it"))
  (doseq [[t c] posture/lumbar-turn-nodes]
    (is (math/nearly= c (posture/lumbar-turn-fraction t) 1e-12)
        (str "the interpolator must return the node's own value at " t)))
  (is (math/nearly= 0.39347408829174657 (posture/lumbar-turn-fraction 0.3) 1e-12)
      "and interpolate linearly inside a span, which is the one assumption left"))

(deftest the-mean-angle-chord-and-the-vector-chord-agree-to-a-twentieth-of-a-degree
  ;; `lumbar-chord-turn-fraction` is the mean ANGLE of the tangent, which is the
  ;; chord direction exactly for a circular arc and approximately otherwise. This
  ;; measures the approximation rather than asserting it is small: it integrates
  ;; the tangent VECTOR over the measured shape at Cho's standing lordosis and
  ;; compares the two directions.
  (let [lordosis 46.5
        share posture/sacral-slope-share-of-lordosis
        tilt (fn [t] (* lordosis (- share (posture/lumbar-turn-fraction t))))
        ;; ∫ (sin θ, cos θ) dt with θ linear on each span
        piece (fn [[[t0 _] [t1 _]]]
                (let [a (math/radians (tilt t0))
                      b (math/radians (tilt t1))
                      dt (- t1 t0)
                      dth (- b a)]
                  (if (< (math/abs* dth) 1e-12)
                    [(* dt (Math/sin a)) (* dt (Math/cos a))]
                    [(* (/ dt dth) (- (Math/cos a) (Math/cos b)))
                     (* (/ dt dth) (- (Math/sin b) (Math/sin a)))])))
        [sx cy] (reduce (fn [[a b] [c d]] [(+ a c) (+ b d)])
                        [0.0 0.0]
                        (map piece (partition 2 1 posture/lumbar-turn-nodes)))
        vector-chord-deg (math/degrees (Math/atan2 sx cy))
        mean-angle-deg (* lordosis (- share posture/lumbar-chord-turn-fraction))]
    (is (< (math/abs* vector-chord-deg) 0.01)
        (str "the vector chord is " vector-chord-deg " deg — the lumbar spine's "
             "two ends are one above the other at Cho's standing lordosis"))
    (is (< (math/abs* (- vector-chord-deg mean-angle-deg)) 0.06)
        (str "and the two definitions of the chord differ by "
             (math/abs* (- vector-chord-deg mean-angle-deg)) " deg, which is the "
             "size of the approximation the model makes by using the mean angle, "
             mean-angle-deg))
    ;; the part the model does NOT represent, sized rather than mentioned
    (let [straightness (Math/sqrt (+ (* sx sx) (* cy cy)))]
      (is (< 0.96 straightness 0.98)
          (str "and the straight distance between the lumbar spine's two ends is "
               straightness " of its arc length — a 3% shortening this model does "
               "not carry, because it treats the segment's length as fixed")))))

(deftest the-pelvic-share-is-the-ratio-of-two-numbers-the-source-reports
  ;; The partition this repo said on 2026-09-08 could not be sourced. It can:
  ;; Mills reports ΔSS and ΔLL for the same 50 subjects over the same posture
  ;; change, and the pelvis's share is their ratio. Asserted as the ratio rather
  ;; than as 0.586, so a correction to either transcribed figure moves it.
  (let [d (:standing-to-relaxed-seated posture/spinopelvic-motion)
        ss (get-in d [:sacral-slope :deg])
        ll (get-in d [:lumbar-lordosis :deg])]
    (is (= 16.7 ss) "ΔSS, standing to relaxed-seated")
    (is (= 28.5 ll) "ΔLL over the same change")
    (is (= (/ ss ll) posture/sacral-slope-share-of-lordosis)
        "the share is derived from the two, to the bit")
    ;; the identity that makes ΔSS a rigid pelvic rotation rather than a proxy
    (is (= (- ss) (get-in d [:pelvic-tilt :deg]))
        (str "and it IS the pelvis turning: PI = PT + SS is a constant of one "
             "pelvis, so ΔSS = -ΔPT, and the source reports both halves"))
    ;; the independent estimate, from a change more than twice as large
    (let [regression (get-in posture/spinopelvic-motion
                             [:sacral-slope-per-lordosis-regression :slope])]
      (is (= 0.63 regression))
      (is (< (math/abs* (- regression posture/sacral-slope-share-of-lordosis))
             (* 0.08 regression))
          (str "the same paper's regression over a 61 deg lordosis change gives "
               regression ", within 8% of the ratio measured over 28.5 deg — "
               "which is the only evidence available that the share does not "
               "depend on how far the posture moves"))))
  (is (< 0.5 posture/sacral-slope-share-of-lordosis 0.7)
      "the pelvis supplies most of a lordosis change, and not all of it")
  (is (< posture/sacral-slope-share-of-lordosis 1.0)
      (str "and NOT all of it, which is what this model spent until 2026-09-11 — "
           "the assertion this whole table exists to make")))

(deftest every-lordosis-source-says-what-was-obtained-of-it
  ;; G7. A source read in full and a source read as an abstract are different
  ;; evidence, and a repo that does not mark which is which cannot be audited.
  ;; Every record here must say, and an abstract-only record must say why.
  (doseq [rec (concat [posture/spinopelvic-motion
                       posture/lordosis-distribution-review
                       posture/lumbar-lordosis]
                      posture/lordosis-sources-not-installed)]
    (is (contains? #{:full-text :abstract} (:obtained rec))
        (str (:citation rec) ": :obtained must be :full-text or :abstract"))
    (is (string? (:citation rec)) "and carry its citation")
    (when (= :abstract (:obtained rec))
      (is (or (:could-not-obtain-full-text rec) (:why-not-installed rec))
          (str (:citation rec) ": an abstract-only source must say why the full "
               "text was not obtained, or why it is not installed"))))
  ;; the two that ARE installed, named so that adding a third silently is a failure
  (is (= :full-text (:obtained posture/spinopelvic-motion))
      "the cohort whose numbers this model runs on was read in full")
  (is (= :abstract (:obtained posture/lordosis-distribution-review))
      (str "and the pooled review that disagrees with it was not — Europe PMC "
           "returns 404 for its full text, which is recorded rather than "
           "presented as a read paper"))
  (is (= 6 (count posture/lordosis-sources-not-installed))
      "six sources were checked and supplied no number this model uses"))

(deftest the-review-and-the-installed-cohort-disagree-and-both-are-recorded
  ;; The disagreement is the finding. Ge et al. pool twelve studies and put 65.1%
  ;; of the lordosis below L4; Mills' 50 volunteers put 53.0% there. Averaging
  ;; them would be choosing; this asserts the gap so it cannot close by accident.
  (let [pooled (/ (get-in posture/lordosis-distribution-review
                          [:lordosis-distribution-index :pct])
                  100.0)
        installed (reduce + 0.0 (map :share (take 2 posture/lumbar-segmental-shares)))]
    (is (> pooled installed)
        "the pooled review puts MORE of the lordosis in the lower arc")
    (is (> (- pooled installed) 0.10)
        (str "and the gap is more than ten percentage points: " pooled
             " against " installed))
    ;; and the review's own spread is wide enough to contain the installed figure,
    ;; which is why this is a disagreement between cohorts and not an error
    (let [[lo hi] (get-in posture/lordosis-distribution-review
                          [:proximal-lumbar-lordosis-l1-l4 :ci-95])
          installed-pll (* (get-in posture/spinopelvic-motion
                                   [:standing :lumbar-lordosis :deg])
                           (- 1.0 installed))]
      (is (< lo installed-pll hi)
          (str "Mills' upper-arc lordosis, " installed-pll " deg, is inside the "
               "review's own 95% interval " [lo hi] " — which is wider than its "
               "own mean")))))
