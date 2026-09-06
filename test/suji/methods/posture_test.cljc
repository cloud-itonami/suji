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
