(ns suji.cells.state-machines-test
  "suji (筋) — cell state-machine tests (load_solve + strain_accumulate coded cells + R0
  scaffold gating). 1:1 Clojure port of data/cells/test_state_machines.cljc (ADR-2606061900).

  pytest assertions → clojure.test; ValueError/RuntimeError raises → thrown? ExceptionInfo.
  The cell.cljc R0-gating tests are folded onto each state machine's `solve` (the sanae pattern;
  cell.cljc is deleted in this port)."
  (:require [clojure.set :as set]
            #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.cells.load-solve.state-machine :as load-sm]
            [suji.cells.strain-accumulate.state-machine :as strain-sm]))

(def ^:private lap-posture
  {"postureId" "p-lap" "headFlexDeg" 44.0 "trunkFlexDeg" 20.0
   "shoulderFlexDeg" 15.0 "armsSupported" false})

(defn- run-to-emit []
  (-> (load-sm/load-state {"posture" lap-posture})
      load-sm/transition-static-inverse-dynamics
      load-sm/transition-muscle-distribute
      load-sm/transition-assert-nondiagnostic
      load-sm/transition-emit))

(deftest test-full-pipeline-reaches-emit
  (let [s (run-to-emit)
        emitted (get s "emitted")]
    (is (= load-sm/phase-emitted (get s "phase")))
    (is (some #(= (get % "load/joint") "cervicothoracic") emitted))
    (is (some #(contains? % "muscle/mvc-pct") emitted))))

(deftest test-cervical-load-present-and-large-for-lap
  (let [s (-> (load-sm/load-state {"posture" lap-posture})
              load-sm/transition-static-inverse-dynamics)
        cerv (first (filter #(= (get % "joint") "cervicothoracic") (get s "joint_loads")))]
    (is (> (get cerv "compressiveKgf") 15.0))   ; deep flexion → heavy neck load
    (is (> (get cerv "multVsHead") 3.0))))

(deftest test-phase-order-enforced
  (let [s (load-sm/load-state {"posture" lap-posture})]
    (doseq [bad [load-sm/transition-muscle-distribute
                 load-sm/transition-assert-nondiagnostic
                 load-sm/transition-emit]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (bad s))))))

(deftest test-nondiagnostic-gate-refuses-clinical-key
  (let [s (-> (run-to-emit)
              (assoc "phase" load-sm/phase-distributed)
              (update "muscle_tensions" conj
                      {"group" "x" "mvcPct" 1.0 "diagnosis" "cervicalgia"}))]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"non-diagnostic"
                          (load-sm/transition-assert-nondiagnostic s)))))

(deftest test-forbidden-set-covers-core-clinical-terms
  (is (set/subset? #{"diagnosis" "prescription" "treatment"}
                           load-sm/forbidden-clinical-keys)))

(deftest test-solve-is-r0-gated
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"R0 scaffold"
                        (load-sm/solve {"posture" lap-posture}))))

;; --- strain_accumulate cell (second coded cell) ---------------------------------

(def ^:private tensions
  [{"group" "cervical-extensors" "mvcPct" 27.0}
   {"group" "upper-trapezius" "mvcPct" 5.0}])

(defn- run-strain
  ([] (run-strain 120.0))
  ([session]
   (-> (strain-sm/strain-state {"posture_id" "p-lap" "session_minutes" session
                                "tensions" (mapv #(into {} %) tensions)})
       strain-sm/transition-rohmert-dose
       strain-sm/transition-band
       strain-sm/transition-assert-self-referenced
       strain-sm/transition-emit)))

(deftest test-strain-pipeline-reaches-emit-with-bands
  (let [s (run-strain)
        emitted (get s "emitted")
        high (first (filter #(= (get % "strain/group") "cervical-extensors") emitted))]
    (is (= strain-sm/phase-emitted (get s "phase")))
    (is (every? #(and (contains? % "strain/stiffness") (contains? % "strain/band")) emitted))
    (is (> (get high "strain/stiffness") 0.9))))   ; 27% MVC held 2h → very-high

(deftest test-strain-higher-load-more-stiffness
  (let [s (run-strain)
        by-group (into {} (map (fn [d] [(get d "strain/group") (get d "strain/stiffness")])
                               (get s "emitted")))]
    (is (> (get by-group "cervical-extensors") (get by-group "upper-trapezius")))))

(deftest test-strain-phase-order-enforced
  (let [s (strain-sm/strain-state {"tensions" [(into {} (first tensions))]})]
    (doseq [bad [strain-sm/transition-band
                 strain-sm/transition-assert-self-referenced
                 strain-sm/transition-emit]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (bad s))))))

(deftest test-strain-refuses-ranking-key-g3
  (let [s (-> (run-strain)
              (assoc "phase" strain-sm/phase-banded)
              (update "strains" (fn [v] (update v 0 assoc "percentile" 88))))]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"self-referenced"
                          (strain-sm/transition-assert-self-referenced s)))))

(deftest test-strain-refuses-clinical-key-g1
  (let [s (-> (run-strain)
              (assoc "phase" strain-sm/phase-banded)
              (update "strains" (fn [v] (update v 0 assoc "diagnosis" "myalgia"))))]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"non-diagnostic"
                          (strain-sm/transition-assert-self-referenced s)))))

(deftest test-strain-rohmert-requires-tensions
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (strain-sm/transition-rohmert-dose (strain-sm/strain-state {"tensions" []})))))

(deftest test-ranking-denylist-covers-core-terms
  (is (set/subset? #{"percentile" "rank" "cohort"}
                           strain-sm/forbidden-ranking-keys)))

(deftest test-strain-solve-is-r0-gated
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"R0 scaffold"
                        (strain-sm/solve {"tensions" tensions}))))
