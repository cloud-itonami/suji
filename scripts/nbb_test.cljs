(ns nbb-test
  "suji (筋) — run the portable half of the suite on ClojureScript.

  WHY. Every namespace in this repo is named `.cljc`, and until 2026-09-06 four of
  them called JVM-only interop (`Math/toRadians`, `Double/POSITIVE_INFINITY`,
  `Double/isInfinite`) while the tests referred `clojure.test` directly. The suite
  was green and the library did not load in a browser. A JVM-only test suite cannot
  discriminate that: it returns the same green for `.cljc` that is portable and for
  `.cljc` that only claims to be.

  So this runner exists to fail. Run it with:

      nbb --classpath src:test scripts/nbb_test.cljs

  SCOPE — three test namespaces are deliberately NOT here. `datoms-test`,
  `bridge-consistency-test` and `charter-invariants-test` read repo files off disk
  (`slurp`, `clojure.java.io`); they check repo invariants, not physics, and are
  JVM-only by nature rather than by accident. Everything that computes a load, a
  tension or a dose runs on both hosts. If you add a physics namespace, add its test
  to `namespaces` below — a test that is never run is not coverage."
  (:require [cljs.test]
            [suji.cells.state-machines-test]
            [suji.methods.articulation-dict-test]
            [suji.methods.attachment-test]
            [suji.methods.load-sensitivity-test]
            [suji.methods.load-test]
            [suji.methods.math-test]
            [suji.methods.moment-balance-test]
            [suji.methods.muscle-strain-test]
            [suji.methods.pose-test]
            [suji.methods.recruit-test]
            [suji.methods.spine-test]
            [suji.murakumo-test]))

(def namespaces
  '[suji.cells.state-machines-test
    suji.methods.articulation-dict-test
    suji.methods.attachment-test
    suji.methods.load-sensitivity-test
    suji.methods.load-test
    suji.methods.math-test
    suji.methods.moment-balance-test
    suji.methods.muscle-strain-test
    suji.methods.pose-test
    suji.methods.recruit-test
    suji.methods.spine-test
    suji.murakumo-test])

;; An evidence floor: a runner that loads no namespace, or that silently stops
;; finding vars, must not be able to print a pass. Cf. the workspace rule that a
;; check which could not run has to be distinguishable from a check that passed.
(def ^:private min-tests 60)

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (let [{:keys [test pass fail error]} m]
    (println (str "\nRan " test " tests containing " (+ pass fail error) " assertions."))
    (println (str fail " failures, " error " errors."))
    (cond
      (< test min-tests)
      (do (println (str "REFUSING to report a pass: ran " test " tests, floor is " min-tests
                        " — the runner is not reaching the suite."))
          (js/process.exit 2))
      (or (pos? fail) (pos? error)) (js/process.exit 1)
      :else (js/process.exit 0))))

(apply cljs.test/run-tests namespaces)
