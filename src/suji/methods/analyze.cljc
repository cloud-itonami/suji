(ns suji.methods.analyze
  "suji (筋) — end-to-end: laptop posture → bones load → muscle tension → 強張り. 1:1
  Clojure port of `src/suji/methods/analyze.cljc` (ADR-2606061900). Stdlib only.

      workstation → posture (kinematics) → joint loads (static inverse dynamics)
                  → muscle tensions (%MVC) → stiffness map (Rohmert session dose)

  and reports the comparison (laptop-on-lap vs laptop-on-desk vs external-monitor-at-
  eye-level). The comparison is SELF-REFERENCED (G3); never a ranking of people, never a
  diagnosis (G1).

  NUMERICS (the whole ballgame): Python f-string `{x:.Nf}` is round-half-EVEN (Java's
  String.format is round-half-UP), so `fmt-f` reproduces it via exact BigDecimal.(double)
  + RoundingMode/HALF_EVEN — byte-identical to CPython. The Hansraj cervical-load / Hill
  %MVC / Rohmert dose figures all flow through this formatter.

  House style: pure fns; file I/O only at the #?(:clj) -main edge. A ScenarioResult is a
  kebab-keyword map. Strain sort is STABLE (ties keep Python list order)."
  (:require [clojure.string :as str]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]
            #?(:clj [clojure.java.io :as io])))

;; ── Python `f"{x:.Nf}"` == round-half-EVEN fixed-point (NOT Java HALF_UP) ──────
(defn fmt-f
  "Format x with n digits after the decimal point, round-half-EVEN, byte-identical to
  Python's f-string `{x:.Nf}` for the magnitudes this actor produces."
  [n x]
  #?(:clj
     (let [bd (-> (java.math.BigDecimal. (double x))
                  (.setScale (int n) java.math.RoundingMode/HALF_EVEN))]
       (.toPlainString bd))
     :cljs
     (.toFixed (double x) n)))

(defn fmt-or-dash
  "`fmt-f`, or an em dash when there is no number.

  WHY THIS EXISTS. `render-report` reached straight for `(:mvc-pct s)` and
  `(:stiffness-index s)` and handed them to `fmt-f`, which calls `.doubleValue` on
  them. A REFUSED muscle has neither — the model declined to compute its force —
  and a LIGAMENT has no %MVC at all, because it cannot contract. So the report
  threw a NullPointerException the moment either could reach it, and it had been
  throwing since before 2026-09-06: the command the README advertises,
  `clojure -M -m suji.methods.analyze`, did not run. The test suite could not see
  it because nothing called `render-report`.

  This is the same lesson `muscle/numeric-mvc?` was written for and the fourth
  emit site to learn it: branch on whether the number is THERE, not on why it is
  not. A site that branches on the reason has to be revisited every time a new
  reason appears, and two new ones appeared (ligaments, then the lower limb's
  antagonists) between that function being written and this one being needed."
  [n x]
  (if (number? x) (fmt-f n x) "—"))

(defn worst-stiffness
  "The most-loaded muscle: maximum stiffness index, ties broken by DOSE.

  A REFUSED muscle has no stiffness index, because it has no force to accumulate
  a dose from. Those are skipped rather than compared as nil: `>` on a nil throws,
  and treating them as zero would let a muscle the model could not solve win the
  argument about which one is least loaded.

  THE TIE-BREAK IS NOT COSMETIC, and it is the thing that changed here on
  2026-09-08. This used to be `max(strains, key=stiffness_index)` with Python's
  first-wins-on-ties rule, i.e. the winner among tied muscles was decided by EMIT
  ORDER. The index is bounded above by 1 and rounds to exactly 1.0 in double
  precision once the dose passes about 37, so ties at the top are not rare — they
  are what a heavily loaded posture looks like, and
  `report-test/the-comparison-does-not-let-a-saturated-index-understate-the-change`
  has said so in a comment since 2026-09-07.

  Measured 2026-09-08: the coupled solve raised cervical_extensors at
  `laptop-on-lap` from 22.08% to 39.18% MVC, which put its index back at exactly
  1.0 alongside erector_spinae's. Emit order then handed the report's headline to
  cervical_extensors at dose 68.58, over an erector spinae at dose 166.63 — a
  worst-muscle claim decided by the order of a table.

  The dose is the quantity that still discriminates where the index has
  saturated, so it is what breaks the tie. This is the same argument the report's
  own comparison line makes about the index understating a change, applied to
  choosing the row instead of describing it."
  [strains]
  (let [xs (filter :stiffness-index strains)
        better? (fn [b a]
                  (or (> (:stiffness-index b) (:stiffness-index a))
                      (and (= (:stiffness-index b) (:stiffness-index a))
                           (> (or (:dose b) 0.0) (or (:dose a) 0.0)))))]
    (when (seq xs)
      (reduce (fn [a b] (if (better? b a) b a)) (first xs) (rest xs)))))

(defn analyze-workstation
  ([body ws] (analyze-workstation body ws 120.0))
  ([body ws session-minutes]
   (let [p (posture/posture-from-workstation ws)
         loads (load/solve-posture-loads body p)
         tensions (muscle/solve-muscle-tensions body p loads)
         strains (strain/session-strain tensions session-minutes)]
     {:workstation (:name ws) :posture p :loads loads
      :tensions tensions :strains strains})))

(defn analyze-all
  ([] (analyze-all 70.0 1.70 120.0))
  ([total-mass-kg stature-m session-minutes]
   (let [body (segment/build-body total-mass-kg stature-m)]
     (mapv #(analyze-workstation body % session-minutes) posture/reference-workstations))))

(defn analyze-all*
  "Keyword-style entry mirroring analyze_all(session_minutes=…)."
  [& {:keys [total-mass-kg stature-m session-minutes]
      :or {total-mass-kg 70.0 stature-m 1.70 session-minutes 120.0}}]
  (analyze-all total-mass-kg stature-m session-minutes))

(defn- stable-sort-by-neg-stiffness
  "sorted(strains, key=lambda s: -s.stiffness_index) — stable; ties keep input order.

  A refused muscle has no index and sorts last rather than throwing. It is kept in
  the list: a muscle that disappears from a report when the model could not solve
  it reads as a muscle that was fine."
  [strains]
  (sort-by #(if-let [x (:stiffness-index %)] (- x) 1.0) strains))

(defn render-report
  "Render the report markdown (1:1 with render_report)."
  ([results] (render-report results 120.0))
  ([results session-minutes]
   (let [L (transient [])
         add! (fn [s] (conj! L s))]
     (add! "# suji 筋 — laptop posture biomechanics report")
     (add! "")
     (add! (str "> NON-DIAGNOSTIC (G1, 医師法 §17): physical loads only — moments, forces, "
                "%MVC, a normalised stiffness dose. NOT a diagnosis or treatment. "
                "`:representative` adult; cervical leg validated vs Hansraj 2014."))
     (add! (str "> Session held: " (fmt-f 0 session-minutes) " min continuous."))
     (add! "")
     ;; Cervical (tech-neck) headline table
     (add! "## Cervical spine load (forward head / tech-neck)")
     (add! "")
     ;; HEAD TILT, not head flexion, since 2026-09-07. The cervical model is a
     ;; function of the head's angle from VERTICAL, which is trunk + head, and this
     ;; column used to print the neck angle alone beside a load computed from — as
     ;; of that date — the sum. Every workstation here leans the trunk, so the two
     ;; are never the same number: laptop-on-lap is 43.5° at the neck and 63.5°
     ;; from vertical. The posture's own head and trunk angles are printed per
     ;; scenario below, where they say which of the two a reader is looking at.
     (add! "| workstation | head tilt from vertical | neck load | ×head-weight |")
     (add! "|---|---|---|---|")
     (doseq [r results]
       (let [c (get-in r [:loads :cervical])]
         (add! (str "| " (:workstation r) " | " (fmt-f 0 (:head-tilt-deg c)) "° | "
                    (fmt-f 1 (:compressive-load-kgf c)) " kgf | "
                    (fmt-f 1 (:multiplier-vs-head c)) "× |"))))
     (add! "")
     ;; Per-scenario stiffness map
     (doseq [r results]
       (add! (str "## " (:workstation r)))
       (add! "")
       (let [p (:posture r)]
         (add! (str "- posture: head " (fmt-f 0 (:head-flexion-deg p)) "° · trunk "
                    (fmt-f 0 (:trunk-flexion-deg p)) "° · shoulder "
                    (fmt-f 0 (:shoulder-flexion-deg p)) "° · arms "
                    (if (:arms-supported p) "supported" "UNSUPPORTED")))
         ;; The support mode belongs on the page for the same reason it belongs in
         ;; the datom: it is what decides the hip, knee and ankle loads, and every
         ;; scenario in this report is SEATED. A reader who takes these figures for
         ;; a standing body is out by a factor of about thirty at the lower limb.
         (add! (str "- support: " (name (posture/support-mode p)) " · hip "
                    (fmt-f 0 (or (:hip-flexion-deg p) 0.0)) "° · knee "
                    (fmt-f 0 (or (:knee-flexion-deg p) 0.0)) "° · ankle "
                    (fmt-f 0 (or (:ankle-dorsiflexion-deg p) 0.0)) "°")))
       (add! "")
       (add! "| muscle | tension %MVC | endurance | stiffness (強張り) | dose | band |")
       (add! "|---|---|---|---|---|---|")
       (doseq [s (stable-sort-by-neg-stiffness (:strains r))]
         (let [;; The endurance figure carries WHERE it sits relative to the range
               ;; the published curve was fitted over, and the report used to drop
               ;; that. A bare `∞` for a muscle at 6 %MVC is the model's own floor,
               ;; not a measured result, and 20 of the 48 rows at the default
               ;; posture sit below it — which is where a desk posture lives.
               ;; `strain` already computes the position; this only stops
               ;; discarding it.
               pos (case (:endurance-position s)
                     :below-endurance-floor "（モデルの床未満）"
                     :below-fitted-range "（適合域より下）"
                     :above-maximum-voluntary-contraction "（MVC 超）"
                     nil)
               end (cond
                     (nil? (:endurance-minutes s)) "—"
                     (math/infinite? (:endurance-minutes s)) (str "∞" pos)
                     :else (str (fmt-f 0 (:endurance-minutes s)) " min" pos))
               ;; a refused muscle stays in the table with a dash. Dropping it
               ;; would make a muscle the model could not solve read as a muscle
               ;; that was fine — the same reason `stable-sort-by-neg-stiffness`
               ;; keeps it in the list rather than filtering it out.
               mvc (if (number? (:mvc-pct s)) (str (fmt-f 0 (:mvc-pct s)) "%") "—")
               ;; THE INDEX TIES AND THE DOSE DOES NOT. `strain/band-resolution`
               ;; computes where the index can and cannot distinguish; measured on
               ;; these very scenarios at a 120-minute session, the seventeen rows
               ;; it calls `very-high` span a dose of 1.93 to 165.95 — a factor of
               ;; 86 — and take seven distinct values at the two decimals this
               ;; table prints. So the dose is printed beside the index rather
               ;; than left inside the map: it is the column that orders the rows
               ;; the index cannot separate.
               idx (cond
                     (not (number? (:stiffness-index s))) "—"
                     ;; `≥`, because the index is at its ceiling and the number
                     ;; under it is not recoverable from the printed value
                     (= :saturated (:index-resolution s)) (str "≥" (fmt-f 2 (:stiffness-index s)))
                     :else (fmt-f 2 (:stiffness-index s)))
               dose (if (number? (:dose s)) (fmt-f 2 (:dose s)) "—")]
           (add! (str "| " (:name s) " | " mvc " | " end " | "
                      idx " | " dose " | "
                      (strain/stiffness-band (:stiffness-index s)) " |"))))
       ;; Why each dashed row has no %MVC. The table renders every one of them as
       ;; `—`, which correctly says "no number" and cannot say WHICH KIND of no
       ;; number — and there are two, which this library is careful to keep apart
       ;; (`muscle/numeric-mvc?`: "the model declined to compute a force" versus
       ;; "the entry is a LIGAMENT, which cannot contract and therefore has no
       ;; maximum voluntary contraction to be a fraction of"). A reader of the
       ;; table alone cannot tell a ligament from a refused antagonist, so the
       ;; distinction the model makes does not survive to the page.
       (let [by-reason (->> (:strains r)
                            (remove muscle/numeric-mvc?)
                            (group-by #(or (:refused %) :no-mvc))
                            (sort-by (comp name key)))]
         (when (seq by-reason)
           (add! "")
           (add! "無い理由（%MVC 欄が — の行）:")
           (doseq [[reason ss] by-reason]
             (add! (str "- `" (name reason) "` — "
                        (str/join "、" (map :name ss)))))))
       (let [w (worst-stiffness (:strains r))]
         (add! "")
         (add! (if w
                 (str "- worst: **" (:name w) "** stiffness "
                      (fmt-or-dash 2 (:stiffness-index w))
                      " (" (strain/stiffness-band (:stiffness-index w)) ")")
                 ;; `worst-stiffness` returns nil when the model could not solve a
                 ;; single muscle at this posture. That is a real state and it must
                 ;; not print as a blank line that reads like nothing was wrong.
                 "- worst: — (this model could not solve any muscle at this posture)"))
         (add! "")))
     ;; Comparison / Wellbecoming guidance
     (let [;; the baseline is the laptop-on-lap scenario when there is one. It is
           ;; looked up BY NAME, and a caller who renders any other set of results
           ;; got nil and a NullPointerException two lines down — a second way this
           ;; function could not be called with input it was never given. The
           ;; fallback is the first result, which is what a one-scenario report
           ;; means by "the baseline".
           base (or (first (filter #(= (:workstation %) "laptop-on-lap") results))
                    (first results))
           ;; a scenario in which nothing could be solved has no worst muscle, and
           ;; comparing nil threw here as soon as one could occur
           worst-of (fn [r] (or (:stiffness-index (worst-stiffness (:strains r))) 0.0))
           best (reduce (fn [a b] (if (< (worst-of b) (worst-of a)) b a))
                        (first results) (rest results))
           bc (get-in base [:loads :cervical :compressive-load-kgf])
           fc (get-in best [:loads :cervical :compressive-load-kgf])
           bw (worst-stiffness (:strains base))
           bestw (worst-stiffness (:strains best))
           nm (fn [x] (if x (:name x) "—"))]
       (add! "## Comparison (self-referenced Wellbecoming, G3)")
       (add! "")
       (add! (str "- `" (:workstation base) "` neck load " (fmt-f 1 bc) " kgf → `"
                  (:workstation best) "` " (fmt-f 1 fc) " kgf (**−"
                  (fmt-f 0 (* (- 1 (/ fc bc)) 100)) "%** cervical compressive load)."))
       ;; ⚠ THE INDICES ARE NOT COMPARABLE HERE and the report used to compare
       ;; them anyway. At a 120-minute session the laptop end is SATURATED — its
       ;; worst muscle sits at the index's ceiling — so the printed pair reads
       ;; 1.00 → 0.98 and says the change barely helped, while the dose behind it
       ;; goes 158.35 → 3.87, a factor of 41. The index understates the
       ;; improvement by more than an order of magnitude at exactly the comparison
       ;; the report exists to make.
       ;;
       ;; The dose is stated alongside, and a saturated end says so. A ratio of
       ;; two indices is not printed at all: across the endurance floor it also
       ;; runs the other way — 0.04 against 0.85 looks like a factor of twenty and
       ;; is produced by 0.02 %MVC of difference — and either direction would be a
       ;; comparative claim the model cannot support (G3).
       (let [sat? (fn [x] (= :saturated (:index-resolution x)))
             ix (fn [x] (str (when (sat? x) "≥") (fmt-or-dash 2 (:stiffness-index x))))]
         (add! (str "- worst-muscle stiffness " (ix bw) " (" (nm bw) ") → "
                    (ix bestw) " (" (nm bestw) ")."))
         (add! (str "  dose " (fmt-or-dash 2 (:dose bw)) " → " (fmt-or-dash 2 (:dose bestw))
                    (when (or (sat? bw) (sat? bestw))
                      (str " —— 指数は" (if (sat? bw) "前者" "後者")
                           "で天井に達しているので、指数の差は改善を過小に示す。"
                           "順序を決めているのはドーズの側である。")))))
       (add! (str "- mechanism, not advice: raising the screen toward eye level reduces head "
                  "flexion (the dominant cervical-load term); supporting the forearms unloads "
                  "the upper trapezius (the 肩こり muscle). A clinician (mitate/iyashi) owns any "
                  "health interpretation."))
       (add! ""))
     (str/join "\n" (persistent! L)))))

#?(:clj
   (defn -main
     "CLI entry: analyze the reference workstations → generated posture report."
     [& _argv]
     (let [session 120.0
           results (analyze-all 70.0 1.70 session)
           report (render-report results session)
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getParentFile .getParentFile))
                    (io/file "."))
           out-dir (io/file here "out")
           path (io/file out-dir "posture-report.md")]
       (.mkdirs out-dir)
       (spit path report)
       (println report)
       (println (str "\n[wrote " path "]"))
       0)))
