(ns suji.cells.load-solve.state-machine
  "Phase state machine for the suji (筋) load_solve cell — the coded heart.
  1:1 Clojure port of data/cells/load_solve/state_machine.cljc (ADR-2606061900).

  Drives one posture through static inverse dynamics → muscle %MVC, and enforces the
  load-bearing invariant: the result is NON-DIAGNOSTIC by construction.

  Invariants enforced here:
    G1  — NON-DIAGNOSTIC (医師法 §17): the emitted payload may carry mechanical fields
          only; any diagnosis/disease/prescription/treatment key is REFUSED.
    G9  — kotoba-EAVT: outputs are shaped as jointLoad + muscleTension Datoms.
    G10 — mechanically-grounded only: muscle groups from the Hill-model SPECS table.

  Conventions: dataclass LoadState → a plain map with the SAME string field keys the
  Python `__dict__` carries (`:loads` mirrors the `_loads` attr). Phase enum values stay
  strings; record/Datom payload keys keep the Python string keys; ValueError → ex-info.
  Numerics: Python round(v, n) is round-half-EVEN → `py-round`."
  (:require [clojure.string :as str]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.segment :as segment]))

;; Keys a strain/load payload may NEVER contain (医師法 §17 force-separation, G1).
(def forbidden-clinical-keys
  #{"diagnosis" "disease" "icd" "icd10" "prescription" "treatment"
    "medication" "condition" "pathology" "prognosis"})

;; ── LoadPhase (enum — Python value identities preserved) ──
(def load-phases
  {:init "init"
   :solved "solved"
   :distributed "distributed"
   :nondiagnostic-ok "nondiagnostic_ok"
   :emitted "emitted"})

(def phase-init (:init load-phases))
(def phase-solved (:solved load-phases))
(def phase-distributed (:distributed load-phases))
(def phase-nondiagnostic-ok (:nondiagnostic-ok load-phases))
(def phase-emitted (:emitted load-phases))

;; ── LoadState (dataclass → plain map, field defaults) ──
(defn load-state
  "Construct a LoadState (overrides via kwargs map merge)."
  ([] (load-state {}))
  ([overrides]
   (merge {"phase" phase-init
           "total_mass_kg" 70.0
           "stature_m" 1.70
           "posture" {}
           "joint_loads" []
           "muscle_tensions" []
           "emitted" []}
          overrides)))

(defn- posture-from-state
  "_posture_from_state: the posture dict → the kebab-keyword Posture map load/muscle consume."
  [s]
  (let [p (get s "posture")]
    {:head-flexion-deg (double (get p "headFlexDeg"))
     :trunk-flexion-deg (double (get p "trunkFlexDeg" 5.0))
     :shoulder-flexion-deg (double (get p "shoulderFlexDeg" 15.0))
     :elbow-flexion-deg (double (get p "elbowFlexDeg" 90.0))
     :shoulder-elevation-deg (double (get p "shoulderElevationDeg" 0.0))
     :arms-supported (boolean (get p "armsSupported" true))}))

(defn transition-static-inverse-dynamics
  "RNEA gravity-term solve: posture → per-joint moment + cervical compressive load."
  [s]
  (when (not= (get s "phase") phase-init)
    (throw (ex-info (str "static_inverse_dynamics requires INIT, got " (get s "phase"))
                    {:type :value-error})))
  (let [body (segment/build-body (get s "total_mass_kg") (get s "stature_m"))
        loads (load/solve-posture-loads body (posture-from-state s))
        cerv (:cervical loads)
        head {"joint" "cervicothoracic"
              "momentNm" (math/round-to (:extensor-moment-nm cerv) 4)
              "compressiveKgf" (math/round-to (:compressive-load-kgf cerv) 2)
              "multVsHead" (math/round-to (:multiplier-vs-head cerv) 2)}
        rest (->> (:joints loads)
                  (remove #(= (:joint %) "cervicothoracic"))
                  (mapv (fn [j] {"joint" (:joint j) "momentNm" (math/round-to (:moment-nm j) 4)})))
        out (into [head] rest)]
    (assoc s
           "joint_loads" out
           "_loads" loads
           "phase" phase-solved)))

(defn transition-muscle-distribute
  "Hill-type distribution: joint moments → per-muscle force + %MVC (緊張)."
  [s]
  (when (not= (get s "phase") phase-solved)
    (throw (ex-info (str "muscle_distribute requires SOLVED, got " (get s "phase"))
                    {:type :value-error})))
  (let [body (segment/build-body (get s "total_mass_kg") (get s "stature_m"))
        tensions (muscle/solve-muscle-tensions body (posture-from-state s) (get s "_loads"))
        mt (mapv (fn [t]
                   {"group" (str/replace (:name t) "_" "-")
                    "forceN" (math/round-to (:force-n t) 2)
                    "mvcPct" (math/round-to (:mvc-pct t) 2)})
                 tensions)]
    (assoc s "muscle_tensions" mt "phase" phase-distributed)))

(defn transition-assert-nondiagnostic
  "G1: refuse if any payload field is a clinical/diagnostic key (医師法 §17)."
  [s]
  (when (not= (get s "phase") phase-distributed)
    (throw (ex-info (str "assert_nondiagnostic requires DISTRIBUTED, got " (get s "phase"))
                    {:type :value-error})))
  (doseq [rec (concat (get s "joint_loads") (get s "muscle_tensions"))]
    (let [bad (filter #(contains? forbidden-clinical-keys (str/lower-case %)) (keys rec))]
      (when (seq bad)
        (throw (ex-info (str "G1 non-diagnostic violation: clinical key(s) " (vec bad) " present")
                        {:type :value-error})))))
  (assoc s "phase" phase-nondiagnostic-ok))

(defn transition-emit
  "Shape the verified loads + tensions as kotoba Datoms (G9)."
  [s]
  (when (not= (get s "phase") phase-nondiagnostic-ok)
    (throw (ex-info (str "emit requires NONDIAGNOSTIC_OK, got " (get s "phase"))
                    {:type :value-error})))
  (let [pid (get-in s ["posture" "postureId"] "p-adhoc")
        load-datoms (map-indexed
                     (fn [i j]
                       {"load/id" (str pid "-load-" i) "load/posture" pid
                        "load/joint" (get j "joint") "load/moment-nm" (get j "momentNm")})
                     (get s "joint_loads"))
        musc-datoms (map-indexed
                     (fn [i m]
                       {"muscle/id" (str pid "-musc-" i) "muscle/posture" pid
                        "muscle/group" (get m "group") "muscle/mvc-pct" (get m "mvcPct")})
                     (get s "muscle_tensions"))
        datoms (vec (concat load-datoms musc-datoms))]
    (assoc s "emitted" datoms "phase" phase-emitted)))

(defn solve
  "R0 scaffold: .solve() raises until Council activation (ADR-2606061900 §Roadmap)."
  [_input-state]
  (throw (ex-info (str "suji R0 scaffold: activate load_solve via Council ADR "
                       "(post-2606061900 ratification; live kizashi-fed solves Lv6+ + operator gated)")
                  {:scaffold true})))
