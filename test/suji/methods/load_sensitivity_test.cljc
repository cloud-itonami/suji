;; suji 筋 — tests for the marginal cervical-load sensitivity.
(ns suji.methods.load-sensitivity-test
  "Tests for cervical-load-sensitivity — the marginal cervical compressive load per degree of forward
  head flexion (the local slope of the Hansraj model). Pins the finite-difference slope against the
  ANALYTICAL derivative of the model, and the G1 mechanical-only / non-diagnostic output shape."
  (:require [suji.methods.load :as l]
            [suji.methods.math :as math]
            #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])))

(def ^:private W 50.0)

(defn- analytical-d-per-deg
  "d(compressive)/d(deg) for compressive = W·(ρ·sinθ + cosθ), ρ = head-com-lever / extensor-arm —
  the closed-form derivative of the Hansraj compressive-load formula."
  [theta-deg]
  (let [rho (/ l/head-com-lever-m l/cervical-ext-arm-m)
        th (math/radians theta-deg)]
    (* W (- (* rho (Math/cos th)) (Math/sin th)) (/ math/pi 180.0))))

(deftest reports-the-actual-cervical-load-at-the-posture
  (doseq [a [0.0 30.0 60.0]]
    (is (= (:compressive-load-n (l/cervical-load a W))
           (:compressive-load-n (l/cervical-load-sensitivity a W)))
        "the lens reports the model's actual compressive load at the angle")))

(deftest finite-difference-matches-the-analytical-derivative
  (doseq [a [5.0 15.0 30.0 45.0 60.0]]
    (is (< (Math/abs (- (:d-load-per-deg-n (l/cervical-load-sensitivity a W))
                        (analytical-d-per-deg a))) 0.05)
        (str "the finite-difference slope matches the Hansraj model's analytical d/dθ at " a "°"))))

(deftest marginal-load-is-highest-near-neutral-concave-curve
  ;; the load curve is concave: each added degree costs less the more flexed you already are
  (let [s (fn [a] (:d-load-per-deg-n (l/cervical-load-sensitivity a W)))]
    (is (> (s 0.0) (s 30.0) (s 60.0)) "marginal load per degree decreases as flexion increases")
    (is (pos? (s 45.0)) "load still rises with flexion through the working range")))

(deftest output-is-mechanical-only-non-diagnostic-g1
  (is (= #{:head-flexion-deg :compressive-load-n :d-load-per-deg-n}
         (set (keys (l/cervical-load-sensitivity 30.0 W))))
      "only mechanical quantities — no diagnosis/prescription/condition key (G1)"))

