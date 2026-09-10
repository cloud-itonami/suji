(ns suji.test-runner
  (:require [clojure.test :as test]
            [suji.cells.state-machines-test]
            [suji.methods.articulation-dict-test]
            [suji.methods.bridge-consistency-test]
            [suji.methods.charter-invariants-test]
            [suji.methods.datoms-test]
            [suji.methods.load-test]
            [suji.methods.load-sensitivity-test]
            [suji.methods.moment-balance-test]
            [suji.methods.muscle-strain-test]
            [suji.murakumo-test]))
(def suites '[suji.cells.state-machines-test suji.methods.articulation-dict-test
              suji.methods.bridge-consistency-test suji.methods.charter-invariants-test
              suji.methods.datoms-test suji.methods.load-test suji.methods.load-sensitivity-test
              suji.methods.moment-balance-test suji.methods.muscle-strain-test suji.murakumo-test])
(defn -main [& _]
  (let [r (apply test/run-tests suites)]
    (when-not (zero? (+ (:fail r) (:error r))) (throw (ex-info "suji tests failed" r)))))
