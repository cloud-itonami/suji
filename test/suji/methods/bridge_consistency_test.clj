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
            [clojure.string :as str]
            [suji.methods.kami-biomech-bridge]
            [suji.methods.math]
            [suji.methods.pose]
            [suji.methods.posture]
            [suji.methods.segment]))

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

(deftest the-articulation-joins-links-that-exist
  ;; THE GAP THIS FILLS, found on 2026-09-07. `to-articulation` builds `links` from
  ;; the body's segment table and `joints` from a hand-written table in the same
  ;; namespace, and nothing compared the two. When `head_neck` was split into three
  ;; segments the joint table went on naming it as a child link, so the exported
  ;; spec had a joint attached to a link that was not in the spec — which an Isaac
  ;; `Articulation` or a kami-genesis `PlanarChain::from_spec` would refuse to
  ;; build, and which every test in this repo passed.
  ;;
  ;; It is the same shape as the two hand-written copies of the skeleton `spine`
  ;; removed on the same day: a topology stated twice is one place to correct it and
  ;; one place to forget it.
  (let [body (suji.methods.segment/build-body 70.0 1.70)
        art (suji.methods.kami-biomech-bridge/to-articulation
             body (suji.methods.posture/posture-from-workstation
                   suji.methods.posture/laptop-on-lap))
        links (into #{} (map :name) (:links art))]
    (is (seq (:joints art)) "the spec must have joints, or this checks nothing")
    (doseq [j (:joints art)]
      (is (contains? links (:parent-link j))
          (str (:name j) " hangs off " (:parent-link j) ", which is not a link: " links))
      (is (contains? links (:child-link j))
          (str (:name j) " carries " (:child-link j) ", which is not a link: " links)))
    ;; and the cervical joints it exports are the ones the solver's chain has, with
    ;; the same angles — a second copy of the partition would drift from the first
    (let [by-name (into {} (map (juxt :name identity)) (:joints art))
          head-flex 43.5]
      (doseq [[jn share] [["cervicothoracic" (:lower suji.methods.pose/cervical-partition)]
                          ["c2c3" (:upper suji.methods.pose/cervical-partition)]
                          ["atlanto-occipital" (:head suji.methods.pose/cervical-partition)]]]
        (is (suji.methods.math/nearly= (* share head-flex) (:angle-deg (by-name jn)) 1e-9)
            (str jn " must carry its share of the head angle: " (by-name jn))))
      (is (neg? (:angle-deg (by-name "atlanto-occipital")))
          "and the exported occiput extends on the atlas, as the solver's does"))))
