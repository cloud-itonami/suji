(ns suji.methods.recruit
  "suji (筋) — how a load is shared between the muscles that can carry it.
  Pure `.cljc`, stdlib only, closed form, no iterative solver.

  THE PROBLEM THIS SOLVES. More than one muscle can carry the same load, so the
  equilibrium equation has more unknowns than equations and does not determine the
  forces. Something has to choose. Until 2026-09-06 this actor chose with three
  unrelated hand-written expressions — `(+ 0.3 (* 0.7 elev))` for upper trapezius,
  a `0.5 ×` of a similar shape for levator scapulae, a `0.15 ×` term for the head.
  They are not wrong so much as unaccountable: nothing states what they optimise,
  they cannot be checked against anything, and they do not move when the anatomy
  moves.

  THE CRITERION. Crowninshield & Brand (1981): minimise Σ (F_i / F_max,i)³ subject
  to the equilibrium Σ c_i F_i = T and F_i ≥ 0. The cubed stress criterion is the
  standard choice in musculoskeletal static optimisation because it predicts the
  observed pattern — synergists share, and the share tilts toward the muscle with
  the better leverage and the larger cross-section — without anybody tuning it.

  It has a closed form, which is why there is no solver here. With a_i = F_max,i:

      minimise Σ (F_i/a_i)³  s.t.  Σ c_i F_i = T
      ⇒ 3 F_i²/a_i³ = λ c_i   ⇒   F_i ∝ a_i^{3/2} √c_i
      ⇒ F_i = a_i^{3/2} √c_i · T / Σ (a_j c_j)^{3/2}

  Substituting back gives Σ c_i F_i = T exactly, which `recruit-test` checks rather
  than assumes.

  ONE FUNCTION, TWO TASKS. `c_i` is a moment arm in metres when the equilibrium is
  a moment, and a dimensionless direction cosine when it is a suspended force (see
  `attachment/effectiveness`). The algebra is identical; the units are not, and
  mixing muscles from different tasks in one call would balance a moment against a
  force. `share` therefore takes one task's muscles and one task's load.

  REFUSAL. A muscle whose coefficient is nil, non-positive, or below `min-coeff`
  is NOT given a force — it is reported as refused, with a reason. Dividing a load
  by a coefficient approaching zero produces an arbitrarily large force that looks
  like a finding: at 90° of shoulder flexion this model's straight-line anterior
  deltoid passes through the joint it acts about, and the honest output there is
  『this posture is outside the model』 rather than a number. See `min-coeff`.

  NON-DIAGNOSTIC (G1): a share of a load is a force."
  (:require [suji.methods.math :as math]))

(def min-coeff
  "Floor on a task coefficient, below which this model declines to distribute.

  A straight line between two attachment points has no wrapping surface, so a
  muscle's line of action can pass arbitrarily close to the joint it acts about —
  which real muscles avoid by wrapping over bone. Near that configuration the
  required force diverges. 5 mm of moment arm (or a cosine of 0.005) is where this
  model stops claiming to know, chosen to sit below every arm the reference
  postures actually use and above the degenerate region. Raising it is a modelling
  decision; silently dividing through it is not."
  0.005)

(defn- pow32 [x] (* x (Math/sqrt x)))

(defn share
  "Distribute `load` across `candidates` by the cubed-stress criterion.

  Each candidate is {:name :f-max-n :coeff}. Returns a vector in the SAME order,
  each entry either {:name :force-n :coeff :f-max-n} or
  {:name :refused <reason>} — never a force computed through a coefficient the
  model refused.

  A zero or negative load produces zero forces rather than a refusal: holding
  nothing is a real answer."
  [candidates load]
  (let [usable (filter (fn [{:keys [coeff f-max-n]}]
                         (and (number? coeff) (>= coeff min-coeff)
                              (number? f-max-n) (pos? f-max-n)))
                       candidates)
        denom (reduce + 0.0 (map (fn [{:keys [f-max-n coeff]}] (pow32 (* f-max-n coeff))) usable))]
    (mapv (fn [{:keys [name coeff f-max-n] :as c}]
            (cond
              (not (number? coeff))
              {:name name :refused :no-line-of-action
               :note "the muscle's line of action is degenerate at this posture"}

              (< coeff min-coeff)
              {:name name :refused :coefficient-below-floor :coeff coeff
               :note (str "leverage " (math/fmt-fixed coeff 5)
                          " is below the floor " min-coeff
                          " — a straight-line model has no wrapping surface here")}

              (<= load 0.0)
              {:name name :force-n 0.0 :coeff coeff :f-max-n f-max-n}

              (<= denom 0.0)
              {:name name :refused :no-usable-synergist
               :note "no muscle in this task has usable leverage at this posture"}

              :else
              {:name name
               :coeff coeff
               :f-max-n f-max-n
               :force-n (/ (* (pow32 f-max-n) (Math/sqrt coeff) load) denom)}))
          candidates)))

(defn residual
  "Σ c_i F_i − load over the entries that got a force. The criterion is exact, so
  this is zero up to floating point WHENEVER nothing was refused; when something
  was refused it is the part of the load this model could not place, and reporting
  it is the difference between an incomplete answer and a wrong one."
  [shared load]
  (let [carried (reduce + 0.0 (keep (fn [{:keys [coeff force-n]}]
                                      (when (and coeff force-n) (* coeff force-n)))
                                    shared))]
    (- carried load)))

(defn complete?
  "True when every candidate got a force — i.e. the whole load was placed."
  [shared]
  (every? #(contains? % :force-n) shared))
