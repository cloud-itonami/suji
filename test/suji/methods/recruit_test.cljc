(ns suji.methods.recruit-test
  "The load-sharing criterion: that it is exact, that it prefers the muscle with
  the better leverage and the larger cross-section, and that it refuses rather
  than dividing by a coefficient near zero."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [clojure.string :as str]
            [suji.methods.math :as math]
            [suji.methods.recruit :as recruit]))

(defn- c [name f-max coeff] {:name name :f-max-n f-max :coeff coeff})

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
    (is (not (recruit/complete? r)) "and the result must not read as complete")
    (is (some? (:force-n good)) "the muscles that can carry it still do")
    ;; the surviving muscle carries the whole load — the answer is incomplete in
    ;; its accounting of WHO carries it, not in the equilibrium
    (is (math/nearly= 0.0 (recruit/residual r 30.0) 1e-9))))

(deftest a-missing-line-of-action-is-refused-too
  (let [r (recruit/share [(c "a" 600.0 0.05) (c "nowhere" 200.0 nil)] 30.0)]
    (is (= :no-line-of-action (:refused (second r))))
    (is (not (recruit/complete? r)))))

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
    (is (not (recruit/complete? r)))
    ;; equilibrium still holds over what was placed
    (is (math/nearly= 0.0 (recruit/residual r 30.0) 1e-9))))

(deftest a-zero-coefficient-is-wrong-way-not-below-floor
  ;; exactly zero is the boundary and belongs on the wrong-way side: a muscle with
  ;; no component along the task does not have "a little" leverage, it has none
  (let [r (recruit/share [(c "a" 600.0 0.05) (c "zero" 200.0 0.0)] 10.0)]
    (is (= :acts-the-wrong-way (:refused (second r))))))
