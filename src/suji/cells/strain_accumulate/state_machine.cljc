(ns suji.cells.strain-accumulate.state-machine
  "Phase state machine for the suji (筋) strain_accumulate cell — second coded cell.
  1:1 Clojure port of data/cells/strain_accumulate/state_machine.cljc (ADR-2606061900).

  Takes per-muscle tensions (%MVC, 緊張) + a held session duration and accumulates the
  Rohmert sustained-isometric dose into a 強張り (stiffness) map.

  Invariants enforced here:
    G1  — NON-DIAGNOSTIC (医師法 §17): mechanical fields only; clinical keys refused
          (the load_solve forbidden-clinical-keys reuse).
    G3  — SELF-REFERENCED: no population-ranking field (percentile/rank/cohort/vsOthers).
    G9  — kotoba-EAVT: outputs are shaped as strainReport Datoms (as-of; 非終末論).
    G10 — mechanically-grounded only: bands from strain/stiffness-band (Rohmert dose).

  Conventions mirror load_solve: dataclass StrainState → a plain map with string field
  keys; phase enum values stay strings; ValueError → ex-info; round-half-EVEN via py-round."
  (:require [clojure.string :as str]
            [suji.cells.load-solve.state-machine :as load-sm]
            [suji.methods.math :as math]
            [suji.methods.strain :as strain]))

;; G1 — reuse the load_solve clinical-key denylist (single source of the rule).
(def forbidden-clinical-keys load-sm/forbidden-clinical-keys)

;; G3 — fields that would turn a self-referenced trajectory into a population ranking.
(def forbidden-ranking-keys
  #{"percentile" "rank" "ranking" "cohort" "vsothers" "populationmean"
    "zscore" "leaderboard" "scoreofsoul"})

;; ── StrainPhase (enum — Python value identities preserved) ──
(def strain-phases
  {:init "init"
   :dosed "dosed"
   :banded "banded"
   :self-ref-ok "self_ref_ok"
   :emitted "emitted"})

(def phase-init (:init strain-phases))
(def phase-dosed (:dosed strain-phases))
(def phase-banded (:banded strain-phases))
(def phase-self-ref-ok (:self-ref-ok strain-phases))
(def phase-emitted (:emitted strain-phases))

;; ── StrainState (dataclass → plain map, field defaults) ──
(defn strain-state
  ([] (strain-state {}))
  ([overrides]
   (merge {"phase" phase-init
           "posture_id" "p-adhoc"
           "session_minutes" 120.0
           "tensions" []   ; [{"group" .. "mvcPct" ..}]
           "strains" []
           "emitted" []}
          overrides)))

(defn transition-rohmert-dose
  "Accumulate the Rohmert sustained-isometric dose per muscle (緊張 → 強張り)."
  [s]
  (when (not= (get s "phase") phase-init)
    (throw (ex-info (str "rohmert_dose requires INIT, got " (get s "phase"))
                    {:type :value-error})))
  (when (empty? (get s "tensions"))
    (throw (ex-info "no muscle tensions to accumulate" {:type :value-error})))
  (let [session (get s "session_minutes")
        out (mapv
             (fn [t]
               (let [mvc (double (get t "mvcPct"))
                     mt {:name (get t "group") :force-n 0.0 :f-max-n 1.0 :mvc-pct mvc}
                     st (strain/muscle-strain mt session)
                     em (:endurance-minutes st)
                     end (if (math/infinite? em)
                           -1.0
                           (math/round-to em 2))]
                 {"group" (get t "group")
                  "mvcPct" (math/round-to mvc 2)
                  "sessionMinutes" session
                  "enduranceMinutes" end
                  "stiffnessIndex" (math/round-to (:stiffness-index st) 4)}))
             (get s "tensions"))]
    (assoc s "strains" out "phase" phase-dosed)))

(defn transition-band
  "Attach the coarse display band (Rohmert dose → low/moderate/high/very-high)."
  [s]
  (when (not= (get s "phase") phase-dosed)
    (throw (ex-info (str "band requires DOSED, got " (get s "phase")) {:type :value-error})))
  (let [strains (mapv #(assoc % "band" (strain/stiffness-band (get % "stiffnessIndex")))
                      (get s "strains"))]
    (assoc s "strains" strains "phase" phase-banded)))

(defn transition-assert-self-referenced
  "G1 + G3: refuse clinical keys AND any population-ranking field."
  [s]
  (when (not= (get s "phase") phase-banded)
    (throw (ex-info (str "assert_self_referenced requires BANDED, got " (get s "phase"))
                    {:type :value-error})))
  (doseq [rec (get s "strains")]
    (doseq [k (keys rec)]
      (let [kl (str/lower-case k)]
        (when (contains? forbidden-clinical-keys kl)
          (throw (ex-info (str "G1 non-diagnostic violation: clinical key '" k "'")
                          {:type :value-error})))
        (when (contains? forbidden-ranking-keys kl)
          (throw (ex-info (str "G3 self-referenced violation: ranking key '" k "'")
                          {:type :value-error})))))
    (let [si (get rec "stiffnessIndex")]
      (when-not (and (<= 0.0 si) (<= si 1.0))
        (throw (ex-info (str "stiffnessIndex out of [0,1]: " si) {:type :value-error})))))
  (assoc s "phase" phase-self-ref-ok))

(defn transition-emit
  "Shape the verified strain map as kotoba strainReport Datoms (G9, as-of)."
  [s]
  (when (not= (get s "phase") phase-self-ref-ok)
    (throw (ex-info (str "emit requires SELF_REF_OK, got " (get s "phase")) {:type :value-error})))
  (let [pid (get s "posture_id")
        datoms (vec (map-indexed
                     (fn [i rec]
                       {"strain/id" (str pid "-strain-" i) "strain/posture" pid
                        "strain/group" (get rec "group") "strain/session-min" (get rec "sessionMinutes")
                        "strain/stiffness" (get rec "stiffnessIndex") "strain/band" (get rec "band")
                        "strain/as-of" 0})
                     (get s "strains")))]
    (assoc s "emitted" datoms "phase" phase-emitted)))

(defn solve
  "R0 scaffold: .solve() raises until Council activation (ADR-2606061900 §Roadmap)."
  [_input-state]
  (throw (ex-info (str "suji R0 scaffold: activate strain_accumulate via Council ADR "
                       "(post-2606061900 ratification; live kizashi-fed solves Lv6+ + operator gated)")
                  {:scaffold true})))
