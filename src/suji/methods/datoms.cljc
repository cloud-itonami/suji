(ns suji.methods.datoms
  "suji (筋) — emit analyze results as kotoba EAVT Datoms (G9). 1:1 Clojure port of
  `src/suji/methods/datoms.cljc` (ADR-2606061900). Stdlib only.

  Projects the physics analyze run into the canonical kotoba Datom-log shape (schema.edn):
  a body / posture / joint-load / muscle-tension / strain is a replayable, as-of Datom.

  NON-DIAGNOSTIC (G1): every emitted attribute is a mechanical quantity declared in the
  schema; test-datoms drift-locks the emitted attribute set against schema.edn.

  NUMERICS: Python `round(v, n)` is round-half-EVEN; `render_edn` then writes floats via
  Python `str()` (shortest round-trip repr). `py-round` reproduces round() via exact
  BigDecimal.(double) + HALF_EVEN → nearest double, and `fmt` renders a double via
  Double/toString — byte-identical to Python's repr for these rounded magnitudes.

  KEY ORDER: each datom is an ordered (array-map) map so the rendered `k v` pairs come out
  in the exact Python dict-literal order. as-of / session-minutes carry their original
  numeric type (idx is a Long, session-minutes stays the float passed in)."
  (:require [clojure.string :as str]
            [suji.methods.analyze :as analyze]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.strain :as strain]
            #?(:clj [clojure.java.io :as io])))

(def ^:private joint-kw
  ;; A joint missing from this map used to emit a nil keyword into the datom and
  ;; then fail two lines later on the arithmetic. Adding a joint to `load` and
  ;; forgetting it here is the obvious mistake, so the fallback derives the
  ;; keyword instead of returning nothing.
  {"cervicothoracic" ":cervicothoracic" "shoulder" ":shoulder"
   "elbow" ":elbow" "wrist" ":wrist" "lumbosacral" ":lumbosacral"
   "hip" ":hip" "knee" ":knee" "ankle" ":ankle"})

(defn- muscle-kw [name]
  (str ":" (str/replace name "_" "-")))

(defn body-datom [body-id total-mass-kg stature-m]
  (array-map
   ":body/id" body-id
   ":body/total-mass-kg" (math/round-to total-mass-kg 2)
   ":body/stature-m" (math/round-to stature-m 3)
   ":body/representative" true))

(defn scenario-datoms
  "All datoms for one workstation scenario: posture + loads + tensions + strains."
  [result body-id idx]
  (let [pid (str "p-" (:workstation result))
        p (:posture result)
        cerv (get-in result [:loads :cervical])
        posture-d (array-map
                   ":posture/id" pid ":posture/body" body-id
                   ":posture/workstation" (:workstation result)
                   ":posture/head-flex-deg" (math/round-to (:head-flexion-deg p) 2)
                   ":posture/trunk-flex-deg" (math/round-to (:trunk-flexion-deg p) 2)
                   ":posture/shoulder-flex-deg" (math/round-to (:shoulder-flexion-deg p) 2)
                   ":posture/arms-supported" (:arms-supported p)
                   ;; THE SUPPORT MODE IS PART OF THE POSTURE'S IDENTITY, not a note
                   ;; about it. Two postures with these same upper-body angles load
                   ;; the hip, knee and ankle by a factor of about thirty apart
                   ;; depending on it, so a datom log without it records two
                   ;; different bodies as the same one.
                   ":posture/support" (str (posture/support-mode p))
                   ":posture/hip-flex-deg" (math/round-to (or (:hip-flexion-deg p) 0.0) 2)
                   ":posture/knee-flex-deg" (math/round-to (or (:knee-flexion-deg p) 0.0) 2)
                   ":posture/ankle-dorsiflex-deg" (math/round-to (or (:ankle-dorsiflexion-deg p) 0.0) 2)
                   ":posture/as-of" idx)
        cerv-d (array-map
                ":load/id" (str pid "-load-cerv") ":load/posture" pid
                ":load/joint" ":cervicothoracic"
                ":load/moment-nm" (math/round-to (:extensor-moment-nm cerv) 4)
                ":load/compressive-kgf" (math/round-to (:compressive-load-kgf cerv) 2)
                ":load/mult-vs-head" (math/round-to (:multiplier-vs-head cerv) 2))
        joint-ds (->> (get-in result [:loads :joints])
                      (remove #(= (:joint %) "cervicothoracic"))
                      (mapv (fn [j]
                              (cond-> (array-map
                                       ":load/id" (str pid "-load-" (:joint j))
                                       ":load/posture" pid
                                       ":load/joint" (or (joint-kw (:joint j))
                                                         (str ":" (:joint j)))
                                       ":load/moment-nm" (math/round-to (:moment-nm j) 4))
                                ;; the lower limb carries one more quantity than a
                                ;; moment, and it is the one the support mode decides
                                (:supported-weight-n j)
                                (assoc ":load/supported-weight-n"
                                       (math/round-to (+ (:left (:supported-weight-n j))
                                                         (:right (:supported-weight-n j)))
                                                      2))))))
        muscle-ds (mapv (fn [t]
                          ;; a REFUSED muscle has no force and no %MVC; rounding
                          ;; a nil threw here the moment a refusal could reach
                          ;; this far. The datom carries the reason instead —
                          ;; omitting the muscle would make an unanswered load
                          ;; indistinguishable from an absent one.
                          ;; branch on whether the number is THERE, not on why
                          ;; it is not — a ligament has no %MVC and is not refused
                          (if-not (muscle/numeric-mvc? t)
                            (array-map
                             ":muscle/id" (str pid "-musc-" (:name t)) ":muscle/posture" pid
                             ":muscle/group" (muscle-kw (:name t))
                             ":muscle/refused" (if (:refused t) (str ":" (name (:refused t))) ":none")
                             ":muscle/antagonist" (boolean (:antagonist? t)))
                            (array-map
                             ":muscle/id" (str pid "-musc-" (:name t)) ":muscle/posture" pid
                             ":muscle/group" (muscle-kw (:name t))
                             ":muscle/force-n" (math/round-to (:force-n t) 2)
                             ":muscle/mvc-pct" (math/round-to (:mvc-pct t) 2))))
                        (:tensions result))
        strain-ds (mapv (fn [st]
                          (let [end (cond
                                      (nil? (:endurance-minutes st)) -1.0
                                      (math/infinite? (:endurance-minutes st)) -1.0
                                      :else (math/round-to (:endurance-minutes st) 2))]
                            (array-map
                             ":strain/id" (str pid "-strain-" (:name st)) ":strain/posture" pid
                             ":strain/group" (muscle-kw (:name st))
                             ":strain/session-min" (:session-minutes st)
                             ":strain/endurance-min" end
                             ;; -1 for "not computed", the same sentinel this file
                             ;; already uses for an infinite endurance. A nil here
                             ;; renders as an empty slot and the map literal comes
                             ;; back with an odd number of forms.
                             ":strain/stiffness" (if (:stiffness-index st)
                                                   (math/round-to (:stiffness-index st) 4)
                                                   -1.0)
                             ":strain/band" (str ":" (strain/stiffness-band (:stiffness-index st)))
                             ":strain/saturated" (boolean (:saturated? st))
                             ":strain/as-of" idx)))
                        (:strains result))]
    (-> [posture-d cerv-d]
        (into joint-ds)
        (into muscle-ds)
        (into strain-ds))))

(defn results-to-datoms
  "Project a full analyze run into a flat vector of kotoba Datoms (body shared)."
  ([results] (results-to-datoms results 70.0 1.70 "ref-adult-70-170"))
  ([results total-mass-kg stature-m body-id]
   (into [(body-datom body-id total-mass-kg stature-m)]
         (mapcat (fn [[idx r]] (scenario-datoms r body-id idx))
                 (map-indexed vector results)))))

(defn- py-float-repr
  "Python str(float): shortest round-trip decimal. For the magnitudes this actor emits
  (HALF_EVEN-rounded to ≤4 decimals, 1e-4 ≤ |v| < 1e16, or 0.0) Python never uses
  scientific notation, so expand Double/toString's shortest digits to plain decimal,
  preserving the always-present `.0` on integral floats."
  [d]
  #?(:clj
     (let [s (Double/toString d)]
       (if (or (str/includes? s "E") (str/includes? s "e"))
         ;; expand the shortest repr to plain form (no scientific in our range)
         (let [bd (java.math.BigDecimal. s)
               plain (.toPlainString bd)
               plain (if (str/includes? plain ".")
                       (let [stripped (str/replace plain #"0+$" "")]
                         (if (str/ends-with? stripped ".") (str stripped "0") stripped))
                       (str plain ".0"))]
           plain)
         s))
     :cljs (str d)))

(defn- fmt
  "Port of _fmt: bool → true/false; \":…\" kept literal; other string → quoted; double →
  Python str() shortest repr; else str()."
  [v]
  (cond
    (true? v) "true"
    (false? v) "false"
    (and (string? v) (str/starts-with? v ":")) v
    (string? v) (str "\"" v "\"")
    (double? v) (py-float-repr v)
    :else (str v)))

(defn render-edn
  "Render datoms as seed-style EDN (one map per line)."
  [datoms]
  (let [lines (transient
               [";; suji (筋) analyze → kotoba Datoms (G9; generated by src/suji/methods/datoms.cljc)."
                ";; NON-DIAGNOSTIC (G1): mechanical attributes only. Self-referenced (G3). 非終末論."
                "["])]
    (doseq [d datoms]
      (let [body (str/join " " (map (fn [[k v]] (str k " " (fmt v))) d))]
        (conj! lines (str " {" body "}"))))
    (conj! lines "]")
    (str/join "\n" (persistent! lines))))

#?(:clj
   (defn -main
     [& _argv]
     (let [results (analyze/analyze-all 70.0 1.70 120.0)
           datoms (results-to-datoms results)
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getParentFile .getParentFile))
                    (io/file "."))
           out-dir (io/file here "out")
           path (io/file out-dir "posture-datoms.edn")]
       (.mkdirs out-dir)
       (spit path (str (render-edn datoms) "\n"))
       (println (str "[wrote " (count datoms) " datoms → " path "]"))
       0)))
