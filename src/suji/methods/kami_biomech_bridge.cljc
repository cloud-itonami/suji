(ns suji.methods.kami-biomech-bridge
  "suji (筋) ↔ kami-genesis / Isaac-Sim articulation bridge. 1:1 Clojure port of
  `src/suji/methods/kami_biomech_bridge.cljc` (ADR-2606061900). Stdlib only.

  Maps the suji sagittal segment chain to the articulation spec that a kami-genesis
  `PlanarChain` (Featherstone RNEA/CRBA, ADR-2605311500/1800) — exposed through the
  nv-compat Isaac-Sim `ArticulationView` / `ArticulationBatch` surface (ADR-2606010030)
  — would load. The data contract here is the WIT in `wire/wit/kami-biomech.wit`.

  Two functions:
    - `to-articulation(body posture)` builds the link/joint/gravity spec (the thing you
      hand to `isaacsim.core.api` `Articulation` or kami-genesis `PlanarChain::from_spec`).
    - `solve-static(body posture)` returns the per-joint gravity moments — the SAME quantity
      a kami-genesis backend returns from its full RNEA, computed here via the closed-form
      statics in `load.cljc` so the contract is exercised without the (unpopulated) submodule.

  HONEST INTEGRATION STATE (G7): the kami-genesis Rust crate is absent in this checkout;
  this is the WIT contract + reference behaviour, not a compiled backend (noroshi pattern).
  NO live actuation (the body model is passive; a powered exosuit/robot driven from these
  moments would be a different actor under the tazuna force-class + Council gate).

  NUMERICS: Python `round(v, 4)` is round-half-EVEN; `py-round` reproduces it via exact
  BigDecimal.(double) + HALF_EVEN → nearest double (same helper as datoms.cljc). The Python
  @dataclasses become kebab-keyword maps; links/joints kept as ordered vectors (list order)."
  (:require [suji.methods.segment :as segment]
            [suji.methods.math :as math]
            [suji.methods.load :as load]))

;; KamiLink / KamiJoint / KamiArticulation @dataclasses → kebab-keyword maps.

(defn- kami-link [name mass-kg length-m com-frac]
  {:name name :mass-kg mass-kg :length-m length-m :com-frac com-frac})

(defn- kami-joint [name parent-link child-link angle-deg]
  {:name name :parent-link parent-link :child-link child-link :angle-deg angle-deg})

(defn articulation->dict
  "KamiArticulation.to_dict() — the WIT/Isaac string-keyed spec (links/joints/gravity)."
  [art]
  {"links" (mapv (fn [l] {"name" (:name l) "mass_kg" (:mass-kg l)
                          "length_m" (:length-m l) "com_frac" (:com-frac l)})
                 (:links art))
   "joints" (mapv (fn [j] {"name" (:name j) "parent_link" (:parent-link j)
                           "child_link" (:child-link j) "angle_deg" (:angle-deg j)})
                  (:joints art))
   "gravity_mps2" (:gravity-mps2 art)})

;; The sagittal kinematic order: pelvis(base) → lumbar → thorax → cervical → head, plus
;; the upper-limb branch thorax → shoulder → upper_arm → elbow → forearm → wrist → hand.
(def ^:private chain-joints
  [["lumbosacral" "pelvis" "thorax_abdomen" :trunk-flexion-deg]
   ["cervicothoracic" "thorax_abdomen" "head_neck" :head-flexion-deg]
   ["shoulder" "thorax_abdomen" "upper_arm" :shoulder-flexion-deg]
   ["elbow" "upper_arm" "forearm" :elbow-flexion-deg]])

(defn to-articulation
  "Build the kami-genesis / Isaac articulation spec for a posed body."
  [body posture]
  (let [;; body.segments.values() iteration order = the segment insertion order.
        links (mapv (fn [s]
                      (kami-link (:name s) (math/round-to (:mass-kg s) 4)
                                 (math/round-to (:length-m s) 4) (:com-frac s)))
                    (vals (:segments body)))
        ;; pelvis is the base link (seat support); thorax connects above it.
        angles {:trunk-flexion-deg (:trunk-flexion-deg posture)
                :head-flexion-deg (:head-flexion-deg posture)
                :shoulder-flexion-deg (:shoulder-flexion-deg posture)
                :elbow-flexion-deg (:elbow-flexion-deg posture)}
        joints (mapv (fn [[name parent child ang-key]]
                       (kami-joint name parent child (get angles ang-key)))
                     chain-joints)]
    {:links links :joints joints :gravity-mps2 segment/gravity}))

(defn solve-static
  "Per-joint static gravity moments — the quantity a kami-genesis RNEA returns.

  Reference implementation via the closed-form statics (load.cljc). A live backend
  would instead call kami-genesis `PlanarChain::inverse_dynamics` with zero velocity
  and acceleration (the gravity term)."
  [body posture]
  (let [loads (load/solve-posture-loads body posture)
        head (conj [{"joint" "cervicothoracic"
                     "moment_nm" (math/round-to (:extensor-moment-nm (:cervical loads)) 4)}])]
    (into head
          (comp (remove #(= (:joint %) "cervicothoracic"))
                (map (fn [j] {"joint" (:joint j) "moment_nm" (math/round-to (:moment-nm j) 4)})))
          (:joints loads))))
