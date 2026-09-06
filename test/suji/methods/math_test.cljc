(ns suji.methods.math-test
  "suji (筋) — the portable numeric floor, checked on the host that is running.

  These tests earn their keep only when the suite runs on BOTH hosts: the golden
  vectors below are the same literals on the JVM and in ClojureScript, so a value
  that rounds or converts differently fails on exactly one of them. Run under JVM
  alone they assert almost nothing."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.math :as math]))

(deftest test-radians-matches-known-values
  (is (math/nearly= 0.0 (math/radians 0.0)))
  (is (math/nearly= (/ math/pi 2.0) (math/radians 90.0)))
  (is (math/nearly= math/pi (math/radians 180.0)))
  (is (math/nearly= (- math/pi) (math/radians -180.0)))
  ;; round-trip
  (doseq [d [0.0 1.0 27.0 43.5 60.0 90.0 179.9]]
    (is (math/nearly= d (math/degrees (math/radians d)) 1e-9))))

(deftest test-infinity-is-recognised-on-this-host
  (is (math/infinite? math/inf))
  (is (math/infinite? (- math/inf)))
  (is (not (math/infinite? 0.0)))
  (is (not (math/infinite? 1e308)))
  ;; the JVM's clojure.core/infinite? throws for a non-number; ours answers.
  (is (not (math/infinite? nil)))
  (is (not (math/infinite? "120"))))

(deftest test-round-to-agrees-across-hosts
  ;; THE REGRESSION THIS PINS. Before 2026-09-06 the ClojureScript copy of this
  ;; function was (/ (Math/round (* v 10^n)) 10^n), which multiplies before it
  ;; rounds; 2.675 * 100 is 267.50000000000006 in binary, so JS answered 2.68
  ;; where the JVM's exact BigDecimal answered 2.67. Four namespaces held a copy.
  ;; Each pair below is [input decimals expected]; the expectation is the exact
  ;; value's correct rounding, so it is the SAME literal on both hosts.
  (doseq [[v n expected] [[2.675      2  2.67]
                          [23.6277249 2  23.63]
                          [0.1235     3  0.123]
                          [1.005      2  1.0]
                          [231.708828 4  231.7088]
                          [-2.675     2  -2.67]
                          [0.0        2  0.0]
                          ;; 1.005 and 8.105 land on opposite sides, and neither
                          ;; is the side the decimal literal suggests: the nearest
                          ;; double to 1.005 is 1.00499999999999989, below the tie,
                          ;; and the nearest to 8.105 is 8.10500000000000043, above
                          ;; it. Rounding the EXACT value is what makes both hosts
                          ;; agree; rounding the literal a reader "meant" would not.
                          [8.105      2  8.11]]]
    (is (math/nearly= expected (math/round-to v n) 1e-9)
        (str "round-to " v " " n))))

(deftest test-round-to-is-idempotent
  (doseq [v [231.7088284352797 26.58 0.0 -14.125 3.14159265358979]]
    (let [once (math/round-to v 3)]
      (is (math/nearly= once (math/round-to once 3) 1e-12)))))

(deftest test-vector-helpers
  (is (= [4.0 6.0 8.0] (mapv double (math/v+ [1 2 3] [3 4 5]))))
  (is (= [-2.0 -2.0 -2.0] (mapv double (math/v- [1 2 3] [3 4 5]))))
  (is (= [2.0 4.0 6.0] (mapv double (math/v* [1 2 3] 2))))
  (is (math/nearly= 5.0 (math/vlen [3 4 0])))
  (is (math/nearly= 0.0 (math/vlen [0 0 0])))
  (is (= [2.0 3.0 4.0] (mapv double (math/vmid [1 2 3] [3 4 5])))))

(deftest test-clamp-and-fmt
  (is (= 5 (math/clamp 5 0 10)))
  (is (= 0 (math/clamp -3 0 10)))
  (is (= 10 (math/clamp 99 0 10)))
  (is (= "23.63" (math/fmt-fixed 23.6277 2)))
  (is (= "0.0" (math/fmt-fixed 0.0 1))))
