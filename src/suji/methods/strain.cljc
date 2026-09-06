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

  NON-DIAGNOSTIC (G1): a stiffness index is a normalised load-time dose, not a medical
  finding. SELF-REFERENCED (G3): indices compared against the SAME member's other postures.

  Numerics: exp/pow resolve on both hosts; infinity goes through suji.methods.math
  (Double/POSITIVE_INFINITY and Double/isInfinite are JVM-only — see that ns)."
  (:require [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]))

(def chronic-threshold-pct 2.0)   ;; below this %MVC, essentially no sustained recruitment
(def chronic-weight 0.45)         ;; weight of the chronic low-load dose vs acute
(def endurance-floor-pct 8.0)     ;; below this %MVC, acute endurance treated as long

(def ^:private inf math/inf)

(defn endurance-minutes
  "Rohmert-type isometric endurance time (minutes) at a given %MVC. Returns ∞ below the
  endurance floor (low-load static work has no acute failure point)."
  [mvc-pct]
  (if (<= mvc-pct endurance-floor-pct)
    inf
    (let [f (/ mvc-pct 100.0)]
      (* 0.2 (Math/pow f -2.32)))))

(defn muscle-strain
  "Stiffness accrued by one muscle holding `mvc-pct` for `session-minutes`."
  [t session-minutes]
  (when (< session-minutes 0)
    (throw (ex-info "session_minutes must be >= 0" {:type :value-error})))
  (let [mvc-pct (:mvc-pct t)
        t-end (endurance-minutes mvc-pct)
        acute (if (math/infinite? t-end) 0.0 (/ session-minutes t-end))
        excess (/ (max 0.0 (- mvc-pct chronic-threshold-pct)) 100.0)
        chronic (* chronic-weight excess (/ session-minutes 60.0))
        dose (+ acute chronic)
        stiffness (- 1.0 (Math/exp (- dose)))
        ;; `>= 1.0` rather than `= 1.0`: the comparison is about what the double
        ;; can still distinguish, not about an exact value.
        saturated? (>= stiffness 1.0)]
    {:name (:name t)
     :mvc-pct mvc-pct
     :session-minutes session-minutes
     :endurance-minutes t-end
     :acute-dose acute
     :chronic-dose chronic
     :stiffness-index stiffness
     :saturated? saturated?
     :over-endurance (and (not (math/infinite? t-end)) (> session-minutes t-end))}))

(defn session-strain
  "Stiffness map for a whole work session (default 2 hours of continuous posture)."
  ([tensions] (session-strain tensions 120.0))
  ([tensions session-minutes]
   (mapv #(muscle-strain % session-minutes) tensions)))

(defn stiffness-band
  "A coarse human-readable band for the stiffness index (display only, non-diagnostic)."
  [stiffness-index]
  (cond
    (< stiffness-index 0.20) "low"
    (< stiffness-index 0.45) "moderate"
    (< stiffness-index 0.70) "high"
    :else "very-high"))
