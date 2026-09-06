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

  REFUSAL, in three kinds, because they have three different fixes. A coefficient
  that is nil means there is no line of action to speak of. One that is
  NON-POSITIVE means the muscle acts the wrong way at this posture — it would add
  to the load — and no amount of wrapping surface changes that; the posture has
  left the range this line of action represents. One that is merely small means
  the leverage is real but unresolvable by a straight line, which is what wrapping
  surfaces are for. A refused muscle is NOT given a force. Dividing a load
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
  (let [;; PASSIVE TENSION IS NOT DISTRIBUTED — it is determined by length and is
        ;; there whether the nervous system wants it or not. It is subtracted from
        ;; the load first, through each muscle's own coefficient (an antagonist's
        ;; passive pull ADDS to what the agonists must supply), and only the
        ;; remainder is shared. Distributing it alongside the active forces would
        ;; let the criterion "choose" a force that is not chosen.
        passive-moment (reduce + 0.0
                               (for [{:keys [coeff passive-n]} candidates
                                     :when (and (number? coeff) (number? passive-n))]
                                 (* coeff passive-n)))
        load (- load passive-moment)
        ;; passive tissue can carry the whole thing, and then no activation is
        ;; needed. A negative remainder is not an error and not a reason to
        ;; activate an antagonist: it is flexion-relaxation.
        load (max 0.0 load)
        usable (filter (fn [{:keys [coeff f-max-n]}]
                         (and (number? coeff) (>= coeff min-coeff)
                              (number? f-max-n) (pos? f-max-n)))
                       candidates)
        denom (reduce + 0.0 (map (fn [{:keys [f-max-n coeff]}] (pow32 (* f-max-n coeff))) usable))]
    (mapv (fn [{:keys [name coeff f-max-n] :as c}]
            (cond
              (not (number? coeff))
              {:name name :refused :no-line-of-action
               :note "the muscle's line of action is degenerate at this posture"}

              (<= coeff 0.0)
              {:name name :refused :acts-the-wrong-way :coeff coeff
               :note (str "at this posture the line of action gives "
                          (math/fmt-fixed coeff 5)
                          " — the muscle would add to the load rather than resist it. "
                          "No wrapping surface fixes this; it is the posture leaving "
                          "the range this line of action can represent.")}

              (< coeff min-coeff)
              {:name name :refused :coefficient-below-floor :coeff coeff
               :note (str "leverage " (math/fmt-fixed coeff 5)
                          " is below the floor " min-coeff
                          " — a straight-line model has no wrapping surface here")}

              (<= load 0.0)
              ;; nothing is asked of the contractile machinery — but the tissue's
              ;; own force is still there, and dropping it here reported a muscle
              ;; transmitting zero while its passive term held the whole posture
              {:name name :coeff coeff :f-max-n f-max-n
               :active-n 0.0
               :passive-n (or (:passive-n c) 0.0)
               :force-n (or (:passive-n c) 0.0)}

              (<= denom 0.0)
              {:name name :refused :no-usable-synergist
               :passive-n (or (:passive-n c) 0.0)
               :note "no muscle in this task has usable leverage at this posture"}

              :else
              (let [active (if (pos? denom)
                             (/ (* (pow32 f-max-n) (Math/sqrt coeff) load) denom)
                             0.0)
                    passive (or (:passive-n c) 0.0)]
                {:name name
                 :coeff coeff
                 :f-max-n f-max-n
                 :active-n active
                 :passive-n passive
                 ;; what the muscle is actually transmitting: what it chose to
                 ;; produce plus what its tissue produces regardless
                 :force-n (+ active passive)})))
          candidates)))

(defn residual
  "Σ c_i F_i − load over the entries that got a force, counting the passive term.
  The criterion is exact, so this is zero up to floating point WHENEVER nothing
  was refused AND the passive tissue did not over-carry; when something was
  refused it is the part of the load this model could not place, and when passive
  tension exceeds the load it is the surplus the tissue supplies without being
  asked."
  [shared load]
  (let [carried (reduce + 0.0 (keep (fn [{:keys [coeff force-n]}]
                                      (when (and coeff force-n) (* coeff force-n)))
                                    shared))]
    (- carried load)))

(defn carried?
  "True when the load was placed — some candidate took it, or there was none to
  take.

  THIS IS NOT `every candidate got a force`. For a mirror-paired task — lateral
  flexion, abduction — exactly one side resists at any instant and the other is
  its antagonist, which a static optimum does not co-contract. Refusing the
  antagonist is the right answer, not an incomplete one, and counting it as
  incomplete made every frontal-plane posture look unanswerable the moment the
  frontal muscles were added."
  [shared]
  (boolean (or (empty? shared) (some :force-n shared))))

(defn all-placed?
  "True when EVERY candidate got a force. Rarely what a consumer wants — for a
  mirror-paired task it is false by construction — but it is what a test of the
  refusal machinery itself is asking."
  [shared]
  (every? #(contains? % :force-n) shared))

(defn complete?
  "True when the load was placed. Kept as the name consumers already use;
  see `carried?` for why it is not `every candidate got a force`."
  [shared]
  (carried? shared))

;; ═══════════════════════════════════════════════════════════════════════════════
;; THE COUPLED SOLVE — the same criterion over MORE THAN ONE equilibrium at once.
;; ═══════════════════════════════════════════════════════════════════════════════
;;
;; WHY `share` IS NOT ENOUGH, AND WHY THAT WAS NOT A DETAIL. Real muscles cross
;; more than one joint. Rectus femoris and the hamstrings span hip and knee,
;; gastrocnemius spans knee and ankle, semispinalis and splenius capitis span the
;; cervicothoracic junction and the atlanto-occipital joint. `share` solves ONE
;; equality, so each of those was solved where it was primary and the moment it
;; simultaneously exerted at its other joint was computed and REPORTED as an
;; unfed load — 3.63 N·m at the hip in a deep squat, and at the head an
;; over-supply of 3.57 N·m whose absorption would have cost 1.85 times every
;; newton the modelled upper cervical flexors can produce.
;;
;; THE MATHEMATICS. Minimise Σ (F_i/a_i)³ over F ≥ 0 subject to m linear
;; equalities C F = T. The objective is convex and SEPARABLE and the constraints
;; are linear, so the Lagrangian splits per muscle:
;;
;;     L(F, λ) = Σ_i (F_i/a_i)³ − Σ_k λ_k ( Σ_i C_ki F_i − T_k )
;;
;; Write s_i = Σ_k λ_k C_ki — one number per muscle, the price its own
;; coefficients fetch at the current multipliers. Stationarity in F_i is
;;
;;     3 F_i² / a_i³ = s_i        ⇒       F_i = a_i^{3/2} √(s_i/3)
;;
;; and the KKT condition for the bound F_i ≥ 0 is that a muscle whose price is
;; non-positive is at the bound: at F_i = 0 the objective's slope is 0, so the
;; multiplier on the bound is −s_i, and −s_i ≥ 0 means s_i ≤ 0. Both branches are
;; one expression:
;;
;;     F_i(λ) = a_i^{3/2} √( max(s_i, 0) / 3 )                              (★)
;;
;; THE ACTIVE SET IS THEREFORE FOUND AND NOT ASSUMED — it is exactly
;; {i : s_i(λ) > 0}, and it is read off (★) at every iterate rather than guessed
;; and corrected. A muscle whose price goes negative is not pulling backwards; it
;; is off, and it comes back on by itself if the multipliers move. There is no
;; combinatorial search over subsets and no pivoting, which is what makes this a
;; solve in m unknowns rather than in n.
;;
;; THE DUAL, which is what is actually iterated on. Substituting (★) back gives a
;; concave function of λ alone,
;;
;;     q(λ) = λ·T − (2/(3√3)) Σ_i a_i^{3/2} (s_i)_+^{3/2}
;;     ∇q(λ) = T − C F(λ)                    (the residual, negated)
;;     ∇²q(λ) = − Σ_{s_i>0} (a_i^{3/2} / (2√3 √s_i)) C_·i C_·iᵀ     (⪯ 0)
;;
;; so maximising q is an UNCONSTRAINED concave problem in m variables — two for
;; the neck, three for one leg — and its stationary point is the primal optimum.
;; q is C¹ everywhere (the derivative of (x)_+^{3/2} is (3/2)(x)_+^{1/2}, which is
;; continuous); its Hessian is not, and blows up like s^{-1/2} where a muscle
;; enters the active set. Newton is therefore damped and line-searched rather than
;; taken raw. See `solve`.
;;
;; CHECKED AGAINST THE ONE-CONSTRAINT CASE, which is the whole reason to trust it.
;; With m = 1, s_i = λ c_i, and for the muscles with c_i > 0
;;
;;     F_i = a_i^{3/2} √(c_i) √(λ/3)   and   Σ c_i F_i = T
;;     ⇒ √(λ/3) = T / Σ (a_j c_j)^{3/2}
;;     ⇒ F_i = a_i^{3/2} √(c_i) · T / Σ (a_j c_j)^{3/2}
;;
;; which is `share`, term for term, and the muscles with c_i ≤ 0 get s_i ≤ 0 and
;; F_i = 0, which is `share`'s `:acts-the-wrong-way`. `recruit-test` asserts the
;; agreement numerically over a set of cases INCLUDING ones started away from the
;; closed-form multiplier, so that the iteration and not only the initial guess is
;; what is being checked.
;;
;; WHAT IT COSTS. A single-constraint optimum is a LOWER BOUND on the cost of the
;; coupled one: every feasible point of the coupled problem is feasible for each
;; constraint taken alone, so satisfying more constraints can only raise
;; Σ (F_i/a_i)³. %MVC therefore generally RISES when a task joins a coupled group.
;; That is the correct direction and it is not tuned away.

(def ^:private sqrt3 (Math/sqrt 3.0))

(def max-iterations
  "Newton iterations before the solver stops claiming to know.

  It refuses rather than returning the last iterate: a multiplier vector that has
  not converged produces forces that satisfy no equilibrium, and those are
  indistinguishable from solved ones once they are in a table."
  60)

(def tolerance
  "Relative residual at which the coupled equilibrium counts as satisfied.

  Relative to the largest load in the group, so a 200 N·m hip and a 0.3 N·m
  atlanto-occipital joint are held to the same PROPORTION rather than to the same
  newton-metre — an absolute floor would be slack for one and unreachable for the
  other."
  1.0e-11)

(defn- dot [a b]
  (loop [i 0 acc 0.0]
    (if (< i (count a)) (recur (inc i) (+ acc (* (nth a i) (nth b i)))) acc)))

(defn- inf-norm [v] (reduce (fn [m x] (max m (math/abs* x))) 0.0 v))

(defn- solve-linear
  "Gaussian elimination with partial pivoting; nil when the matrix is singular.

  Written out rather than pulled in because this repo's numeric floor is stdlib
  only and portable — the same doubles and the same pivot choices on the JVM and
  in the browser, which is what makes `nbb_test` able to check that the two hosts
  take the same number of iterations."
  [a b]
  (let [n (count b)
        m (mapv (fn [row x] (conj (vec row) x)) a b)]
    (loop [m m k 0]
      (if (= k n)
        (loop [i (dec n) x (vec (repeat n 0.0))]
          (if (neg? i)
            x
            (let [row (nth m i)
                  s (reduce + 0.0 (for [j (range (inc i) n)] (* (nth row j) (nth x j))))
                  d (nth row i)]
              (if (zero? d) nil (recur (dec i) (assoc x i (/ (- (nth row n) s) d)))))))
        (let [piv (reduce (fn [best i]
                            (if (> (math/abs* (nth (nth m i) k))
                                   (math/abs* (nth (nth m best) k)))
                              i best))
                          k (range k n))
              pv (nth (nth m piv) k)]
          (if (< (math/abs* pv) 1.0e-300)
            nil
            (let [m (if (= piv k) m (assoc m k (nth m piv) piv (nth m k)))
                  pr (nth m k)
                  m (reduce (fn [m i]
                              (let [f (/ (nth (nth m i) k) (nth pr k))]
                                (assoc m i (mapv (fn [aij akj] (- aij (* f akj)))
                                                 (nth m i) pr))))
                            m (range (inc k) n))]
              (recur m (inc k)))))))))

;; --- the primal map (★) and the dual it comes from ---------------------------

(defn- price
  "s_i = Σ_k λ_k C_ki — the coefficient vector's value at the current multipliers."
  [cvec lambda]
  (dot cvec lambda))

(defn- force-at
  "F_i from (★): zero when the price is non-positive, which IS the active set."
  [a32 s]
  (if (pos? s) (* a32 (Math/sqrt (/ s 3.0))) 0.0))

(defn- forces
  [actives lambda]
  (mapv (fn [{:keys [a32 cvec]}] (force-at a32 (price cvec lambda))) actives))

(defn- residual-vec
  "C F(λ) − T."
  [actives loads lambda]
  (let [f (forces actives lambda)]
    (mapv (fn [k tk]
            (- (reduce + 0.0 (map-indexed (fn [i {:keys [cvec]}] (* (nth cvec k) (nth f i)))
                                          actives))
               tk))
          (range (count loads)) loads)))

(defn- dual-objective
  "q(λ). Maximised, concave, and its gradient is the negated residual — so an
  ascent on it is a descent on the equilibrium error, which is what makes the line
  search below able to be monotone in something."
  [actives loads lambda]
  (- (dot lambda loads)
     (reduce + 0.0
             (map (fn [{:keys [a32 cvec]}]
                    (let [s (price cvec lambda)]
                      (if (pos? s) (* (/ 2.0 (* 3.0 sqrt3)) a32 s (Math/sqrt s)) 0.0)))
                  actives))))

(defn- hessian-neg
  "−∇²q = Σ_{s_i>0} (a_i^{3/2} / (2√3 √s_i)) C_·i C_·iᵀ, positive semidefinite."
  [actives m lambda]
  (reduce (fn [h {:keys [a32 cvec]}]
            (let [s (price cvec lambda)]
              (if (pos? s)
                (let [w (/ a32 (* 2.0 sqrt3 (Math/sqrt s)))]
                  (mapv (fn [k row]
                          (mapv (fn [l x] (+ x (* w (nth cvec k) (nth cvec l))))
                                (range m) row))
                        (range m) h))
                h)))
          (vec (repeat m (vec (repeat m 0.0))))
          actives))

(defn- initial-lambda
  "Each constraint solved ALONE by the closed form, ignoring the coupling.

  It is the exact answer when m = 1 — which is why `solve` reproduces `share`
  without iterating there — and a good enough start when it is not, because the
  coupling perturbs the independent solve rather than replacing it. `solve` also
  takes an explicit start, so a test can prove that the ITERATION and not the
  guess is what lands on the closed form."
  [actives loads]
  (mapv (fn [k tk]
          (if (zero? tk)
            0.0
            (let [sgn (if (neg? tk) -1.0 1.0)
                  d (reduce + 0.0
                            (for [{:keys [a cvec]} actives
                                  :let [c (* sgn (nth cvec k))]
                                  :when (pos? c)]
                              (pow32 (* a c))))]
              (if (pos? d)
                (let [r (/ (math/abs* tk) d)] (* sgn 3.0 r r))
                0.0))))
        (range (count loads)) loads))

(defn- newton
  "Damped Newton ascent on the concave dual. Returns
  {:lambda :iterations :converged? :residual}.

  DAMPED AND LINE-SEARCHED, for a reason that is in the mathematics rather than in
  caution. The Hessian is Σ a^{3/2}/(2√3 √s) C Cᵀ, so it grows without bound as a
  muscle approaches the edge of the active set (s → 0⁺) and is merely singular
  when the active columns do not span. Levenberg–Marquardt damping handles the
  second — a step is always defined — and the Armijo backtrack on q handles the
  first, because q is concave and every damped Newton direction is an ascent
  direction, so a short enough step always improves it. That is what makes this
  terminate rather than oscillate across the kink where the active set changes."
  [actives loads lambda0]
  (let [m (count loads)
        scale (max 1.0 (inf-norm loads))
        tol (* tolerance scale)
        independent (initial-lambda actives loads)
        nothing-on? (fn [lambda]
                      (not-any? (fn [{:keys [cvec]}] (pos? (price cvec lambda))) actives))]
    (loop [lambda lambda0 iter 0 mu 0.0]
      (let [g (residual-vec actives loads lambda)]
        (cond
          (<= (inf-norm g) tol)
          {:lambda lambda :iterations iter :converged? true :residual g}

          (>= iter max-iterations)
          {:lambda lambda :iterations iter :converged? false :residual g}

          ;; NOTHING IS ACTIVE AND THE LOAD IS NOT PLACED. The Hessian is then
          ;; identically zero, so the Newton step is whatever the damping says and
          ;; the damping has no scale to read — the iterate carries a direction and
          ;; no magnitude, and a line search on a step of the wrong order stalls
          ;; rather than fails. Restart from the independent closed form, which is
          ;; the one point in this space whose scale is known without iterating.
          ;; Each restart costs an iteration, so a solve that keeps landing back
          ;; where nothing is on runs out of `max-iterations` and REFUSES rather
          ;; than cycling.
          (and (nothing-on? lambda) (not= lambda independent))
          (recur independent (inc iter) 0.0)

          :else
          (let [h (hessian-neg actives m lambda)
                tr (reduce + 0.0 (map-indexed (fn [k row] (nth row k)) h))
                ;; a floor under the damping so a step exists even where nothing is
                ;; active (H = 0) — there the step is along the residual, which is
                ;; exactly the direction that switches a muscle on
                mu (max mu (* 1.0e-10 (/ (max tr 1.0e-12) m)) 1.0e-12)
                dl (loop [mu mu tries 0]
                     (if (> tries 40)
                       nil
                       (if-let [d (solve-linear
                                   (mapv (fn [k row]
                                           (mapv (fn [l x] (if (= k l) (+ x mu) x))
                                                 (range m) row))
                                         (range m) h)
                                   (mapv - g))]
                         d
                         (recur (* mu 10.0) (inc tries)))))]
            (if (nil? dl)
              {:lambda lambda :iterations iter :converged? false :residual g}
              (let [q0 (dual-objective actives loads lambda)
                    slope (dot (mapv - g) dl)      ; ∇q · Δ, positive for an ascent
                    step (loop [alpha 1.0 n 0]
                           (let [cand (mapv (fn [x d] (+ x (* alpha d))) lambda dl)
                                 q1 (dual-objective actives loads cand)]
                             (cond
                               (>= q1 (+ q0 (* 1.0e-4 alpha slope))) [cand alpha]
                               (> n 60) nil
                               :else (recur (* 0.5 alpha) (inc n)))))]
                (if (nil? step)
                  ;; no ascent along a direction that is provably an ascent
                  ;; direction means the step is below what the doubles can
                  ;; resolve — the iterate is as converged as it is going to get,
                  ;; and whether that is good enough is the residual's answer and
                  ;; not this loop's
                  {:lambda lambda :iterations iter
                   :converged? (<= (inf-norm g) tol) :residual g}
                  (recur (first step) (inc iter)
                         ;; the damping tracks how hard the last step was to take,
                         ;; and is capped against the curvature actually present so
                         ;; a run of backtracks cannot compound it into a step of
                         ;; zero length that then reports itself as convergence
                         (min (if (< (second step) 1.0) (* 4.0 mu) (* 0.25 mu))
                              (* 1.0e8 (/ (max tr 1.0e-12) m)))))))))))))

(defn- coeff-vec
  "One candidate's row of C, over the group's joints in order. A joint the muscle
  does not span contributes 0 — not a small number, ZERO: a muscle that does not
  cross a joint exerts no moment about it, and reading a straight-line arm off a
  joint the line does not span would invent one."
  [coeffs joints]
  (mapv (fn [j] (let [c (get coeffs j)] (if (number? c) c 0.0))) joints))

(defn solve
  "Distribute `loads` across `candidates` that may span more than one of them, by
  the same cubed-stress criterion `share` uses, satisfying EVERY constraint at
  once.

  `candidates` — each {:name :f-max-n :coeffs {joint coefficient} :passive-n}.
  `loads`      — a VECTOR of [joint load] pairs. A vector because the order is the
                 order of the constraint matrix and a map's seq order is not a
                 promise on both hosts.

  Returns
    {:entries      per candidate, in order, the same shape `share` returns
     :joints       the joint keys, in constraint order
     :loads        the loads after passive tension was subtracted
     :multipliers  {joint λ}
     :residual     {joint (Σ c F − T)}
     :iterations   Newton steps taken
     :converged?   whether the equilibrium was actually satisfied
     :refused      a group-level reason, or absent}

  NON-NEGATIVITY, which is the part that needs care. A muscle whose price
  s_i = Σ_k λ_k C_ki turns negative is NOT pulling backwards; it is switched off,
  and the KKT conditions say exactly that (see the block comment above). So the
  active set is read off the multipliers at every iterate — {i : s_i > 0} — and a
  muscle can leave it and come back as the multipliers move. Nothing is assumed
  about which muscles will be on. An inactive muscle is reported with
  `:active-n 0.0` and `:inactive? true` rather than as a REFUSAL, because the
  model did not decline to answer for it: the optimum's answer is that it is off.
  Its passive tension is still reported, because that does not need permission.

  REFUSAL, group-level, in two kinds:
    :no-usable-synergist    a joint in this group has a load and not one candidate
                            with usable leverage there. Nothing can satisfy it.
    :coupled-solve-did-not-converge
                            the Newton iteration did not reach `tolerance` in
                            `max-iterations`. The last iterate is NOT returned as
                            an answer — forces that satisfy no equilibrium look
                            exactly like forces that do, once they are in a table.

  Per-candidate refusal keeps `share`'s two GEOMETRIC kinds, because they are
  properties of the line of action and not of the solve: `:no-line-of-action` when
  the primary coefficient is nil, and `:coefficient-below-floor` when every joint
  this muscle spans gives it less than `min-coeff` of leverage in magnitude.
  `:acts-the-wrong-way` does NOT survive into a coupled group and could not: with
  more than one constraint the direction a muscle helps in is not readable off one
  coefficient, and that judgement now belongs to the solve, which expresses it as
  `:inactive?`."
  ([candidates loads] (solve candidates loads nil))
  ([candidates loads lambda0]
   (let [joints (mapv first loads)
         m (count joints)
         raw (mapv second loads)
         ;; PASSIVE TENSION IS NOT DISTRIBUTED, exactly as in `share` and for the
         ;; same reason — it is determined by length, not chosen — but now it is
         ;; subtracted from EVERY constraint it touches, through that constraint's
         ;; own coefficient. A ligament spanning one joint of a coupled pair used
         ;; to be subtracted from one load and invisible at the other.
         passive-moments
         (mapv (fn [k]
                 (reduce + 0.0
                         (for [{:keys [coeffs passive-n]} candidates
                               :let [c (get coeffs (nth joints k))]
                               :when (and (number? c) (number? passive-n))]
                           (* c passive-n))))
               (range m))
         t (mapv - raw passive-moments)
         usable? (fn [{:keys [coeffs f-max-n]}]
                   (and (number? f-max-n) (pos? f-max-n)
                        (some (fn [j] (let [c (get coeffs j)]
                                        (and (number? c) (>= (math/abs* c) min-coeff))))
                              joints)))
         usable (filterv usable? candidates)
         actives (mapv (fn [{:keys [f-max-n coeffs]}]
                         {:a f-max-n :a32 (pow32 f-max-n)
                          :cvec (coeff-vec coeffs joints)})
                       usable)
         ;; a constraint with a load and nothing that can act on it is not a slow
         ;; convergence, it is an unanswerable question, and saying so before
         ;; iterating is the difference between a reason and a timeout
         unreachable (filterv (fn [k]
                                (and (> (math/abs* (nth t k)) 1.0e-12)
                                     (not-any? #(pos? (math/abs* (nth (:cvec %) k))) actives)))
                              (range m))
         base {:joints joints :loads (zipmap joints t)}]
     (cond
       (seq unreachable)
       (assoc base
              :refused :no-usable-synergist
              :unreachable-joints (mapv #(nth joints %) unreachable)
              :entries (mapv (fn [c]
                               {:name (:name c) :refused :no-usable-synergist
                                :passive-n (or (:passive-n c) 0.0)
                                :note (str "no muscle in this coupled group has usable "
                                           "leverage at "
                                           (pr-str (mapv #(nth joints %) unreachable)))})
                             candidates))

       :else
       (let [l0 (or lambda0 (initial-lambda actives t))
             {:keys [lambda iterations converged? residual]} (newton actives t l0)
             by-name (into {} (map-indexed (fn [i c] [(:name c) (nth actives i)]) usable))]
         (if-not converged?
           (assoc base
                  :refused :coupled-solve-did-not-converge
                  :iterations iterations
                  :multipliers (zipmap joints lambda)
                  :residual (zipmap joints residual)
                  :entries (mapv (fn [c]
                                   {:name (:name c)
                                    :refused :coupled-solve-did-not-converge
                                    :passive-n (or (:passive-n c) 0.0)
                                    :note (str "the coupled equilibrium over "
                                               (pr-str joints)
                                               " did not converge in " iterations
                                               " Newton steps; the last iterate is not "
                                               "offered as an answer")})
                                 candidates))
           (assoc base
                  :multipliers (zipmap joints lambda)
                  :residual (zipmap joints residual)
                  :iterations iterations
                  :converged? true
                  :entries
                  (mapv (fn [{:keys [name coeffs f-max-n primary] :as c}]
                          (let [pc (get coeffs primary)
                                spanned (filterv #(number? (get coeffs %)) joints)
                                best (reduce max 0.0
                                             (map #(math/abs* (get coeffs %)) spanned))
                                passive (or (:passive-n c) 0.0)]
                            (cond
                              (and primary (not (number? pc)))
                              {:name name :refused :no-line-of-action
                               :note "the muscle's line of action is degenerate at this posture"}

                              (empty? spanned)
                              {:name name :refused :no-line-of-action
                               :note "the muscle's line of action is degenerate at this posture"}

                              (not (and (number? f-max-n) (pos? f-max-n)))
                              ;; a ligament: no contractile machinery to distribute
                              ;; to, and its passive term is already in the loads
                              {:name name :coeff pc :coeffs coeffs :f-max-n f-max-n
                               :active-n 0.0 :passive-n passive :force-n passive}

                              (< best min-coeff)
                              {:name name :refused :coefficient-below-floor :coeff pc
                               :coeffs coeffs
                               :note (str "leverage " (math/fmt-fixed best 5)
                                          " at every joint this muscle spans is below the "
                                          "floor " min-coeff
                                          " — a straight-line model has no wrapping "
                                          "surface here")}

                              :else
                              (let [{:keys [a32 cvec]} (get by-name name)
                                    s (price cvec lambda)
                                    active (force-at a32 s)]
                                (cond-> {:name name :coeff pc :coeffs coeffs
                                         :f-max-n f-max-n
                                         :active-n active :passive-n passive
                                         :force-n (+ active passive)
                                         :price s}
                                  (not (pos? s))
                                  (assoc :inactive? true
                                         :note (str "the coupled optimum switches this "
                                                    "muscle off here: its price at the "
                                                    "solved multipliers is "
                                                    (math/fmt-fixed s 6)
                                                    ", so activating it would raise the "
                                                    "cost without helping any of "
                                                    (pr-str joints))))))))
                        candidates))))))))

(defn coupled-residual
  "Σ c_ki F_i − T_k per joint, over the entries that got a force. Zero to floating
  point when the solve converged — which is the point of it: `share`'s `residual`
  could only ever be zero for the one constraint it solved, and the moment at the
  other joint was reported as unfed."
  [{:keys [entries joints loads]}]
  (into {}
        (for [j joints]
          [j (- (reduce + 0.0
                        (keep (fn [{:keys [coeffs force-n]}]
                                (when (and coeffs force-n (number? (get coeffs j)))
                                  (* (get coeffs j) force-n)))
                              entries))
                (get loads j 0.0))])))
