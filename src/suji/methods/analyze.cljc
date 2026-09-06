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

(defn worst-stiffness
  "max(strains, key=stiffness_index) — Python max returns the FIRST max on ties.

  A REFUSED muscle has no stiffness index, because it has no force to accumulate
  a dose from. Those are skipped rather than compared as nil: `>` on a nil throws,
  and treating them as zero would let a muscle the model could not solve win the
  argument about which one is least loaded."
  [strains]
  (let [xs (filter :stiffness-index strains)]
    (when (seq xs)
      (reduce (fn [a b] (if (> (:stiffness-index b) (:stiffness-index a)) b a))
              (first xs) (rest xs)))))

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
     (add! "| workstation | head flexion | neck load | ×head-weight |")
     (add! "|---|---|---|---|")
     (doseq [r results]
       (let [c (get-in r [:loads :cervical])]
         (add! (str "| " (:workstation r) " | " (fmt-f 0 (:head-flexion-deg c)) "° | "
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
       (add! "| muscle | tension %MVC | endurance | stiffness (強張り) | band |")
       (add! "|---|---|---|---|---|")
       (doseq [s (stable-sort-by-neg-stiffness (:strains r))]
         (let [end (cond
                     (nil? (:endurance-minutes s)) "—"
                     (math/infinite? (:endurance-minutes s)) "∞"
                     :else (str (fmt-f 0 (:endurance-minutes s)) " min"))]
           (add! (str "| " (:name s) " | " (fmt-f 0 (:mvc-pct s)) "% | " end " | "
                      (fmt-f 2 (:stiffness-index s)) " | "
                      (strain/stiffness-band (:stiffness-index s)) " |"))))
       (let [w (worst-stiffness (:strains r))]
         (add! "")
         (add! (str "- worst: **" (:name w) "** stiffness " (fmt-f 2 (:stiffness-index w))
                    " (" (strain/stiffness-band (:stiffness-index w)) ")"))
         (add! "")))
     ;; Comparison / Wellbecoming guidance
     (let [base (first (filter #(= (:workstation %) "laptop-on-lap") results))
           best (reduce (fn [a b]
                          (if (< (:stiffness-index (worst-stiffness (:strains b)))
                                 (:stiffness-index (worst-stiffness (:strains a))))
                            b a))
                        (first results) (rest results))
           bc (get-in base [:loads :cervical :compressive-load-kgf])
           fc (get-in best [:loads :cervical :compressive-load-kgf])
           bw (worst-stiffness (:strains base))
           bestw (worst-stiffness (:strains best))]
       (add! "## Comparison (self-referenced Wellbecoming, G3)")
       (add! "")
       (add! (str "- `" (:workstation base) "` neck load " (fmt-f 1 bc) " kgf → `"
                  (:workstation best) "` " (fmt-f 1 fc) " kgf (**−"
                  (fmt-f 0 (* (- 1 (/ fc bc)) 100)) "%** cervical compressive load)."))
       (add! (str "- worst-muscle stiffness " (fmt-f 2 (:stiffness-index bw)) " ("
                  (:name bw) ") → " (fmt-f 2 (:stiffness-index bestw)) " ("
                  (:name bestw) ")."))
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
