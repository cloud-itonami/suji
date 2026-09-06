;; suji 筋 — validation of the KamiArticulation → WIT/Isaac dict serializer.
;; Run:  bb test
(ns suji.methods.articulation-dict-test
  "Validation of articulation->dict — the kami-biomech bridge that serializes a KamiArticulation
  into the string-keyed (WIT / Isaac) spec the kami-engine consumes. It was ISOLATED. Pins the
  kebab→snake key rename and the per-link / per-joint field mapping, so a regression that dropped or
  mis-keyed a field — which would silently feed the physics solver the wrong geometry — is caught."
  (:require [suji.methods.kami-biomech-bridge :as b]
            #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])))

(def ^:private art
  {:links [{:name "thorax" :mass-kg 25.0 :length-m 0.3 :com-frac 0.5}
           {:name "head"   :mass-kg 5.0  :length-m 0.2 :com-frac 0.5}]
   :joints [{:name "l5s1" :parent-link "pelvis" :child-link "thorax" :angle-deg 20.0}]
   :gravity-mps2 9.81})

(deftest serializes-each-link-to-the-snake-keyed-spec
  (let [d (b/articulation->dict art)]
    (is (= 2 (count (get d "links"))))
    (is (= {"name" "thorax" "mass_kg" 25.0 "length_m" 0.3 "com_frac" 0.5}
           (first (get d "links")))
        "a link maps to exactly the snake-keyed WIT fields")))

(deftest serializes-each-joint-to-the-snake-keyed-spec
  (is (= {"name" "l5s1" "parent_link" "pelvis" "child_link" "thorax" "angle_deg" 20.0}
         (first (get (b/articulation->dict art) "joints")))))

(deftest carries-gravity-and-handles-empty-chains
  (is (= 9.81 (get (b/articulation->dict art) "gravity_mps2")))
  (let [empty (b/articulation->dict {:links [] :joints [] :gravity-mps2 9.81})]
    (is (= [] (get empty "links")))
    (is (= [] (get empty "joints")))
    (is (= 9.81 (get empty "gravity_mps2")))))

(deftest output-is-fully-string-keyed
  ;; the whole point of the bridge: no keyword keys leak into the WIT/Isaac spec
  (let [d (b/articulation->dict art)]
    (is (every? string? (keys d)))
    (is (every? string? (mapcat keys (get d "links"))))
    (is (every? string? (mapcat keys (get d "joints"))))))

