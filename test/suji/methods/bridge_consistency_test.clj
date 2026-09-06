(ns suji.methods.bridge-consistency-test
  "suji (筋) — SSoT-consistency tests. Partial 1:1 Clojure port of
  src/suji/methods/test_bridge_consistency.cljc.

  DEFERRED (kami_biomech_bridge is NOT part of this Python→Clojure closure — it is the
  gated kami-genesis/Isaac articulation bridge, the noroshi-pattern WIT contract): the two
  bridge-solver tests `test_articulation_spec_well_formed` and
  `test_bridge_static_matches_load_solver` are not ported here. The latter's invariant —
  that the bridge's static moments equal load.cljc's closed-form moments — is the SAME single
  solver that this closure already exercises in test-load / test-datoms (the byte-identical
  cervical / shoulder / lumbosacral moments).

  The three SSoT drift-lock tests below need no bridge module and ARE ported: manifest cells
  ↔ disk, manifest lexicons ↔ disk, and seed reference resolution. Read with clojure.edn."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private root (clojure.java.io/file "."))

(defn- edn-stems [dir]
  (into #{} (comp (map #(.getName %))
                  (filter #(str/ends-with? % ".edn"))
                  (map #(subs % 0 (- (count %) 4))))
        (seq (.listFiles (clojure.java.io/file root dir)))))

(deftest test-manifest-cells-match-cell-files
  (let [manifest (edn/read-string (slurp (str (clojure.java.io/file root "manifest.edn"))))
        declared (into #{} (map :cell/id) (:actor/cells manifest))
        on-disk (edn-stems "data/cells")]
    (is (= declared on-disk) (str "manifest cells " declared " != disk " on-disk))))

(deftest test-manifest-lexicons-match-lex-files
  (let [manifest (edn/read-string (slurp (str (clojure.java.io/file root "manifest.edn"))))
        declared (set (:actor/lexicons manifest))
        on-disk (into #{} (map #(str "com.etzhayyim.suji." %)) (edn-stems "data/lex"))]
    (is (= declared on-disk))))

(deftest test-seed-references-resolve
  (let [seed (edn/read-string (slurp (str (clojure.java.io/file root "data" "seed.edn"))))
        bodies (into #{} (keep :body/id) seed)
        postures (into #{} (keep :posture/id) seed)]
    (doseq [e seed]
      (when (contains? e :posture/body)
        (is (contains? bodies (:posture/body e))))
      (doseq [ref [:load/posture :muscle/posture :strain/posture]]
        (when (contains? e ref)
          (is (contains? postures (get e ref)) (str "dangling " ref "=" (get e ref))))))))
