(ns suji.methods.recruit-test
  "The load-sharing criterion: that it is exact, that it prefers the muscle with
  the better leverage and the larger cross-section, and that it refuses rather
  than dividing by a coefficient near zero."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [kotoba.lang.text :as str]
            [suji.methods.math :as math]
            [suji.methods.recruit :as recruit]))

(defn- c [name f-max coeff] {:name name :f-max-n f-max :coeff coeff})

(deftest carried-is-not-all-placed
  ;; `complete?` used to mean "every candidate got a force", which is false by
  ;; construction for a mirror-paired task: exactly one side resists and the other
  ;; is its antagonist, which a static optimum does not co-contract. Counting the
  ;; antagonist as an unanswered load made every frontal-plane posture look
  ;; unanswerable the moment the frontal muscles were added.
  (let [r (recruit/share [(c "resisting" 600.0 0.05) (c "antagonist" 400.0 -0.04)] 20.0)]
    (is (recruit/carried? r) "the load WAS placed")
    (is (not (recruit/all-placed? r)) "and one candidate still got nothing")
    (is (math/nearly= 0.0 (recruit/residual r 20.0) 1e-9)))
  (let [none (recruit/share [(c "a" 600.0 -0.05) (c "b" 400.0 -0.04)] 20.0)]
    (is (not (recruit/carried? none)) "nobody could take it, so it was not placed")))

(deftest the-shared-forces-balance-the-load-exactly
  ;; The closed form is derived, not fitted, so equilibrium must hold to floating
  ;; point for every input — not approximately, and not only for the cases someone
  ;; happened to try.
  (doseq [load [0.5 5.0 30.0 250.0 1e4]
          cands [[(c "a" 600.0 0.05)]
                 [(c "a" 600.0 0.05) (c "b" 200.0 0.03)]
                 [(c "a" 600.0 0.05) (c "b" 200.0 0.03) (c "d" 90.0 0.012)]]]
    (let [r (recruit/share cands load)]
      (is (recruit/complete? r))
      (is (math/nearly= 0.0 (recruit/residual r load) (* 1e-9 (max 1.0 load)))
          (str "Σ c·F must equal the load: " load " / " (count cands) " muscles")))))

(deftest better-leverage-and-more-cross-section-carry-more
  (let [equal-arm (recruit/share [(c "strong" 600.0 0.04) (c "weak" 150.0 0.04)] 40.0)
        equal-pcsa (recruit/share [(c "long-arm" 300.0 0.06) (c "short-arm" 300.0 0.02)] 40.0)
        f (fn [r n] (:force-n (first (filter #(= n (:name %)) r))))]
    (is (> (f equal-arm "strong") (f equal-arm "weak"))
        "at equal leverage the bigger muscle takes more")
    (is (> (f equal-pcsa "long-arm") (f equal-pcsa "short-arm"))
        "at equal size the better-levered muscle takes more")))

(deftest sharing-lowers-the-peak-stress
  ;; the point of a synergist: two muscles doing one job are each less loaded than
  ;; either would be alone
  (let [alone (recruit/share [(c "a" 600.0 0.05)] 40.0)
        together (recruit/share [(c "a" 600.0 0.05) (c "b" 300.0 0.04)] 40.0)
        stress (fn [r] (apply max (map #(/ (:force-n %) (:f-max-n %)) r)))]
    (is (< (stress together) (stress alone)))))

(deftest a-load-of-zero-is-an-answer-not-a-refusal
  (let [r (recruit/share [(c "a" 600.0 0.05) (c "b" 200.0 0.03)] 0.0)]
    (is (recruit/complete? r))
    (is (every? #(zero? (:force-n %)) r))))

(deftest it-refuses-rather-than-dividing-through-a-vanishing-arm
  ;; THE FAILURE THIS PREVENTS. A straight-line muscle's line of action can pass
  ;; through the joint it acts about — this model's anterior deltoid does, at 90°
  ;; of shoulder flexion. Dividing by that arm yields an arbitrarily large force
  ;; that is indistinguishable, in the output, from a real finding.
  (let [r (recruit/share [(c "ok" 600.0 0.05) (c "degenerate" 200.0 0.0001)] 30.0)
        bad (first (filter #(= "degenerate" (:name %)) r))
        good (first (filter #(= "ok" (:name %)) r))]
    (is (= :coefficient-below-floor (:refused bad)))
    (is (nil? (:force-n bad)) "a refused muscle must carry NO force, not a large one")
    (is (not (recruit/all-placed? r)) "and the result must not read as fully placed")
    (is (recruit/carried? r) "though the load itself was still carried, by the other muscle")
    (is (some? (:force-n good)) "the muscles that can carry it still do")
    ;; the surviving muscle carries the whole load — the answer is incomplete in
    ;; its accounting of WHO carries it, not in the equilibrium
    (is (math/nearly= 0.0 (recruit/residual r 30.0) 1e-9))))

(deftest a-missing-line-of-action-is-refused-too
  (let [r (recruit/share [(c "a" 600.0 0.05) (c "nowhere" 200.0 nil)] 30.0)]
    (is (= :no-line-of-action (:refused (second r))))
    (is (not (recruit/all-placed? r)))
    (is (recruit/carried? r))))

(deftest with-no-usable-synergist-nothing-is-invented
  (let [r (recruit/share [(c "a" 600.0 0.0001) (c "b" 200.0 0.0002)] 30.0)]
    (is (every? :refused r))
    (is (not-any? :force-n r))))

(deftest abs-normalises-negative-zero
  ;; -0.0 is not negative, so a plain `neg?` guard passes it through and every
  ;; report downstream prints "-0.0" for a quantity that is zero.
  (is (= "0.00" (math/fmt-fixed (math/abs* -0.0) 2)))
  (is (= "0.00" (math/fmt-fixed (math/abs* 0.0) 2)))
  (is (math/nearly= 3.5 (math/abs* -3.5))))

(deftest the-three-refusals-are-told-apart
  ;; They have three different fixes, so collapsing them into one reason sends the
  ;; reader to the wrong repair. A non-positive coefficient in particular is NOT a
  ;; wrapping problem: no surface makes a muscle that is pulling the wrong way pull
  ;; the right way. Before 2026-09-06 `suspension-effectiveness` clamped its cosine
  ;; at zero, which turned every wrong-way posture into "below the leverage floor —
  ;; a straight-line model has no wrapping surface here", which is simply untrue.
  (let [r (recruit/share [(c "fine" 600.0 0.05)
                          (c "too-little" 200.0 0.0001)
                          (c "wrong-way" 200.0 -0.4)
                          (c "no-line" 200.0 nil)] 30.0)
        by (into {} (map (juxt :name identity)) r)]
    (is (some? (:force-n (by "fine"))))
    (is (= :coefficient-below-floor (:refused (by "too-little"))))
    (is (= :acts-the-wrong-way (:refused (by "wrong-way"))))
    (is (= :no-line-of-action (:refused (by "no-line"))))
    (is (nil? (:force-n (by "wrong-way"))) "a wrong-way muscle carries nothing")
    ;; and the notes must not tell the reader to look for a wrapping surface when
    ;; that is not the problem
    (is (not (str/includes? (:note (by "wrong-way")) "wrapping surface here")))
    (is (str/includes? (:note (by "too-little")) "wrapping surface"))
    (is (not (recruit/all-placed? r)))
    ;; equilibrium still holds over what was placed
    (is (math/nearly= 0.0 (recruit/residual r 30.0) 1e-9))))

(deftest a-zero-coefficient-is-wrong-way-not-below-floor
  ;; exactly zero is the boundary and belongs on the wrong-way side: a muscle with
  ;; no component along the task does not have "a little" leverage, it has none
  (let [r (recruit/share [(c "a" 600.0 0.05) (c "zero" 200.0 0.0)] 10.0)]
    (is (= :acts-the-wrong-way (:refused (second r))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; THE COUPLED SOLVE
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- one-constraint
  "The same candidates `share` takes, restated for `solve` with a single joint."
  [cands]
  (mapv (fn [c] (-> c (assoc :coeffs {:j (:coeff c)} :primary :j) (dissoc :coeff)))
        cands))

(deftest the-coupled-solver-reproduces-the-closed-form
  ;; THE FIRST TEST, and the one that makes the rest trustworthy. With one
  ;; equality constraint the stationarity condition F_i = a_i^{3/2} √(s_i/3) with
  ;; s_i = λ c_i collapses to `share`'s closed form term for term, so the iterative
  ;; solver has an exact answer to be checked against — one this repo already
  ;; trusts, derived independently and asserted elsewhere to satisfy Σ c_i F_i = T.
  ;;
  ;; A muscle with c_i ≤ 0 gets s_i ≤ 0 and therefore F_i = 0, which is `share`'s
  ;; `:acts-the-wrong-way` refusal expressed as an active set. That is compared as
  ;; "no active force" rather than as an equal `:force-n`, because the two report
  ;; it differently on purpose — see `the-coupled-solve-does-not-refuse-what-it-can-answer`.
  ;;
  ;; ⚠ WHAT THIS TEST CANNOT SEE, measured 2026-09-08 rather than reasoned about.
  ;; The constant 3 in F_i = a_i^{3/2} √(s_i/3) is a REPARAMETERISATION of λ: change
  ;; it and every multiplier scales to compensate, so no force moves. Breaking
  ;; `(/ s 3.0)` to `(/ s 2.0)` leaves this test green, and the only assertion in
  ;; the suite that notices is `the-iteration-count-is-the-same-on-both-hosts`
  ;; (4 → 16 iterations, and the answer moves in the 10th digit). That is not a
  ;; hole in this test — the constant genuinely cannot be wrong — but it is worth
  ;; knowing before treating a green here as covering the whole expression. What
  ;; this test does discriminate is the exponent and the √c: replacing a_i^{3/2}
  ;; with a_i fails it at once (638.477 → 589.201 N on the first case).
  (doseq [[label cands load]
          [["two synergists" [(c "a" 600.0 0.05) (c "b" 300.0 0.04)] 40.0]
           ["one antagonist" [(c "a" 600.0 0.05) (c "b" 400.0 -0.04)] 20.0]
           ["three, one wrong-way, cervical-shaped"
            [(c "a" 720.0 0.02) (c "b" 511.2 0.012) (c "c" 446.4 -0.036)] 14.0]
           ["a big load on a short arm" [(c "a" 2040.0 0.03) (c "b" 4344.0 0.045)] 190.0]
           ["a tiny load" [(c "a" 600.0 0.05) (c "b" 300.0 0.04)] 1.0e-4]]]
    (let [closed (recruit/share cands load)
          solved (recruit/solve (one-constraint cands) [[:j load]])]
      (is (:converged? solved) (str label ": the solve must converge"))
      (doseq [[x y] (map vector closed (:entries solved))]
        (is (= (:name x) (:name y)) (str label ": same order"))
        (if (:refused x)
          (is (math/nearly= 0.0 (:active-n y) 1e-12)
              (str label " " (:name x) ": `share` refused it, so `solve` must give "
                   "it no active force, got " (:active-n y)))
          (is (math/nearly= (:force-n x) (:force-n y) (* 1e-9 (max 1.0 (:force-n x))))
              (str label " " (:name x) ": closed form " (:force-n x)
                   " vs coupled solve " (:force-n y))))))))

(deftest the-iteration-and-not-the-guess-is-what-lands-on-the-closed-form
  ;; THE HALF THE TEST ABOVE CANNOT REACH. `solve`'s initial multiplier is each
  ;; constraint solved alone by the closed form, which IS the exact answer when
  ;; there is one constraint — so the comparison above passes at iteration zero
  ;; and says nothing about the Newton loop. Started somewhere else, the loop has
  ;; to find its way back to the same numbers.
  ;;
  ;; The four starts are chosen to exercise different paths: far below, far above,
  ;; exactly zero (where nothing is active and the Jacobian vanishes, which is what
  ;; the restart in `newton` exists for) and negative (where every candidate is
  ;; switched off).
  (let [cands [(c "a" 600.0 0.05) (c "b" 300.0 0.04)]
        closed (recruit/share cands 40.0)]
    (doseq [start [[1.0e-6] [1.0e6] [0.0] [-5.0]]]
      (let [solved (recruit/solve (one-constraint cands) [[:j 40.0]] start)]
        (is (:converged? solved) (str "start " start ": must converge"))
        (is (pos? (:iterations solved))
            (str "start " start ": must actually iterate, or this test is the "
                 "previous one again"))
        (doseq [[x y] (map vector closed (:entries solved))]
          (is (math/nearly= (:force-n x) (:force-n y) 1e-6)
              (str "start " start " " (:name x) ": " (:force-n x) " vs "
                   (:force-n y))))))))

(deftest a-two-joint-muscle-satisfies-both-constraints
  ;; The smallest problem `share` cannot do: three muscles, two joints, one of them
  ;; spanning both. Solved as two independent tasks the shared muscle's force is
  ;; whatever its own joint wants and the other joint is out by that muscle's
  ;; moment there; solved together, both hold.
  (let [cands [{:name "one-joint-a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
               {:name "two-joint"   :f-max-n 400.0 :primary :j1
                :coeffs {:j1 0.04 :j2 -0.03}}
               {:name "one-joint-b" :f-max-n 500.0 :primary :j2 :coeffs {:j2 0.045}}]
        r (recruit/solve cands [[:j1 20.0] [:j2 8.0]])
        by (into {} (map (juxt :name identity)) (:entries r))
        resid (recruit/coupled-residual r)]
    (is (:converged? r) (str "must converge: " (dissoc r :entries)))
    (is (math/nearly= 0.0 (:j1 resid) 1e-9) (str "j1 must balance: " resid))
    (is (math/nearly= 0.0 (:j2 resid) 1e-9) (str "j2 must balance: " resid))
    ;; the discriminating half: the two-joint muscle is actually recruited, so
    ;; both residuals are not zero merely because it was switched off
    (is (pos? (:active-n (by "two-joint")))
        (str "the two-joint muscle must be doing something: " (by "two-joint")))
    ;; and its force is NOT what a solve of j1 alone would give it — that is the
    ;; whole content of the coupling
    (let [alone (recruit/share [(c "one-joint-a" 600.0 0.05) (c "two-joint" 400.0 0.04)]
                               20.0)
          alone-f (:force-n (second alone))]
      (is (not (math/nearly= alone-f (:active-n (by "two-joint")) 1e-6))
          (str "solving j1 alone gives the two-joint muscle " alone-f
               " N; with j2 in the problem it must differ, got "
               (:active-n (by "two-joint")))))))

(deftest coupling-cannot-lower-the-cost
  ;; THE INEQUALITY THAT SAYS THE %MVC RISE IS CORRECT AND NOT A DEFECT. Every
  ;; point feasible for the coupled problem is feasible for each of its
  ;; constraints taken alone, so the single-constraint minimum is a LOWER BOUND on
  ;; the coupled one and Σ (F_i/a_i)³ can only go up. It is asserted here rather
  ;; than argued, because "the numbers went up" and "the solve is wrong" look the
  ;; same from outside.
  (let [cands [{:name "one-joint-a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
               {:name "two-joint"   :f-max-n 400.0 :primary :j1
                :coeffs {:j1 0.04 :j2 -0.03}}
               {:name "one-joint-b" :f-max-n 500.0 :primary :j2 :coeffs {:j2 0.045}}]
        coupled (recruit/solve cands [[:j1 20.0] [:j2 8.0]])
        ;; j1 alone, over exactly the muscles that span j1
        j1-only (recruit/solve (filterv #(contains? (:coeffs %) :j1) cands) [[:j1 20.0]])
        j2-only (recruit/solve (filterv #(contains? (:coeffs %) :j2) cands) [[:j2 8.0]])]
    (is (:converged? coupled))
    (is (>= (recruit/cost (:entries coupled))
            (recruit/cost (:entries j1-only)))
        (str "the coupled cost " (recruit/cost (:entries coupled))
             " cannot be below the j1-alone lower bound "
             (recruit/cost (:entries j1-only))))
    (is (>= (recruit/cost (:entries coupled))
            (recruit/cost (:entries j2-only)))
        (str "nor below the j2-alone one " (recruit/cost (:entries j2-only))))
    ;; and it is STRICTLY above at least one of them here, or the inequality is
    ;; being satisfied by a coupling that does not bind
    (is (or (> (recruit/cost (:entries coupled)) (recruit/cost (:entries j1-only)))
            (> (recruit/cost (:entries coupled)) (recruit/cost (:entries j2-only))))
        "the coupling must actually bind in this problem")))

(deftest the-coupled-solve-does-not-refuse-what-it-can-answer
  ;; THE REFUSAL SEMANTICS, which are deliberately narrower than `share`'s.
  ;; `:acts-the-wrong-way` cannot survive into a coupled group: with more than one
  ;; constraint the direction a muscle helps in is not readable off one
  ;; coefficient, and a muscle with good leverage the right way at one joint can
  ;; still be off because helping there costs more at another. So that judgement
  ;; belongs to the solve and comes back as `:inactive?` with the KKT price beside
  ;; it — a computed zero, not a declined answer. The two GEOMETRIC refusals stay,
  ;; because they are properties of the line of action and not of the solve.
  (let [r (recruit/solve
           [{:name "fine"      :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
            {:name "wrong-way" :f-max-n 400.0 :primary :j1 :coeffs {:j1 -0.04}}
            {:name "too-little" :f-max-n 200.0 :primary :j1 :coeffs {:j1 0.0001}}
            {:name "no-line"   :f-max-n 200.0 :primary :j1 :coeffs {:j1 nil}}]
           [[:j1 30.0]])
        by (into {} (map (juxt :name identity)) (:entries r))]
    (is (:converged? r))
    (is (some? (:force-n (by "fine"))))
    ;; the wrong-way muscle is ANSWERED, at zero, with the reason in the price
    (is (nil? (:refused (by "wrong-way")))
        (str "a coupled group does not refuse a muscle it can price: "
             (by "wrong-way")))
    (is (:inactive? (by "wrong-way")))
    (is (neg? (:price (by "wrong-way")))
        (str "and the price is the reason: " (:price (by "wrong-way"))))
    (is (math/nearly= 0.0 (:active-n (by "wrong-way")) 1e-12))
    ;; the geometric refusals keep their reason literals, which is what a consumer
    ;; branches on to know whether a wrapping surface would fix it
    (is (= :coefficient-below-floor (:refused (by "too-little"))))
    (is (str/includes? (:note (by "too-little")) "wrapping surface"))
    (is (= :no-line-of-action (:refused (by "no-line"))))
    ;; and the equilibrium holds over what was answered
    (is (math/nearly= 0.0 (:j1 (recruit/coupled-residual r)) 1e-9))))

(deftest the-coupled-solve-refuses-rather-than-returning-an-unconverged-iterate
  ;; A multiplier vector that has not converged produces forces that satisfy no
  ;; equilibrium, and those are indistinguishable from solved ones once they are in
  ;; a table. So the whole group is refused and NO force is returned.
  ;;
  ;; Driven here by capping `max-iterations` at 1 through the same code path
  ;; production uses, on a problem that is known to need more than one step (the
  ;; two-joint problem above takes several).
  (let [cands [{:name "one-joint-a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
               {:name "two-joint"   :f-max-n 400.0 :primary :j1
                :coeffs {:j1 0.04 :j2 -0.03}}
               {:name "one-joint-b" :f-max-n 500.0 :primary :j2 :coeffs {:j2 0.045}}]
        r (with-redefs [recruit/max-iterations 1]
            (recruit/solve cands [[:j1 20.0] [:j2 8.0]]))]
    (is (= :coupled-solve-did-not-converge (:refused r)))
    (is (not-any? :force-n (:entries r))
        (str "no entry may carry a force: " (:entries r)))
    (doseq [e (:entries r)]
      (is (= :coupled-solve-did-not-converge (:refused e))
          (str (:name e) " must say why: " e)))))

(deftest a-constraint-nothing-can-act-on-is-refused-before-it-is-iterated
  ;; A joint with a load and not one candidate with usable leverage there is not a
  ;; slow convergence, it is an unanswerable question — and saying so before
  ;; iterating is the difference between a reason and a timeout.
  (let [r (recruit/solve
           [{:name "a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}]
           [[:j1 20.0] [:j2 8.0]])]
    (is (= :no-usable-synergist (:refused r)))
    (is (= [:j2] (:unreachable-joints r))
        (str "and it names WHICH joint: " (:unreachable-joints r)))
    (is (not-any? :force-n (:entries r)))))

(deftest passive-tension-is-subtracted-from-every-constraint-it-touches
  ;; `share` subtracts passive tension from the one load it distributes. A ligament
  ;; or a stretched muscle spanning two joints of a coupled group produces a moment
  ;; at BOTH, and subtracting it from one and not the other would leave the second
  ;; equilibrium quietly wrong by that muscle's moment there.
  (let [cands [{:name "active-a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
               {:name "active-b" :f-max-n 500.0 :primary :j2 :coeffs {:j2 0.045}}
               ;; a passive-only structure spanning both: no contractile force
               {:name "band" :f-max-n 0.0 :primary :j1 :passive-n 120.0
                :coeffs {:j1 0.03 :j2 0.02}}]
        r (recruit/solve cands [[:j1 20.0] [:j2 8.0]])
        by (into {} (map (juxt :name identity)) (:entries r))]
    (is (:converged? r))
    ;; the band's moment is taken off both loads, not one
    (is (math/nearly= (- 20.0 (* 0.03 120.0)) (:j1 (:loads r)) 1e-12)
        (str "j1's load after passive: " (:loads r)))
    (is (math/nearly= (- 8.0 (* 0.02 120.0)) (:j2 (:loads r)) 1e-12)
        (str "j2's load after passive: " (:loads r)))
    ;; and the residual against the ORIGINAL loads, counting the band's force, is
    ;; zero at both — which is the mechanical statement worth checking
    (let [resid (recruit/coupled-residual r)]
      (is (math/nearly= 0.0 (:j1 resid) 1e-9) (str "j1: " resid))
      (is (math/nearly= 0.0 (:j2 resid) 1e-9) (str "j2: " resid)))
    (is (math/nearly= 120.0 (:force-n (by "band")) 1e-12)
        "the band transmits its passive force and nothing else")
    (is (math/nearly= 0.0 (:active-n (by "band")) 1e-12)
        "it has no contractile machinery to be given any")))

(deftest the-iteration-count-is-the-same-on-both-hosts
  ;; THE POINT OF THE nbb RUNNER, made explicit. The solver's stopping rule is a
  ;; relative residual, so a host whose arithmetic differed — or whose `Math/sqrt`
  ;; or matrix pivoting did — would take a different number of steps and land
  ;; somewhere else. Pinning the COUNT and not only the answer is what discriminates
  ;; a genuine agreement from two runs that both happened to be inside a tolerance.
  ;;
  ;; Measured 2026-09-08 on the JVM and under nbb: identical.
  (let [cands [{:name "one-joint-a" :f-max-n 600.0 :primary :j1 :coeffs {:j1 0.05}}
               {:name "two-joint"   :f-max-n 400.0 :primary :j1
                :coeffs {:j1 0.04 :j2 -0.03}}
               {:name "one-joint-b" :f-max-n 500.0 :primary :j2 :coeffs {:j2 0.045}}]
        r (recruit/solve cands [[:j1 20.0] [:j2 8.0]])]
    (is (= 4 (:iterations r)) (str "iterations: " (:iterations r)))
    (is (math/nearly= 331.71995875716937 (:force-n (first (:entries r))) 1e-10)
        (str "and the answer to the last bits: " (:force-n (first (:entries r)))))))
