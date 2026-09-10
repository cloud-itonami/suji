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
            [suji.methods.pose :as pose]
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

;; The sagittal kinematic order: pelvis(base) → lumbar → thorax → lower cervical →
;; upper cervical → head, plus
;; the upper-limb branch thorax → shoulder → upper_arm → elbow → forearm → wrist → hand.
(def ^:private chain-joints
  "Each joint the articulation exports: name, parent link, child link, the posture
  key its angle comes from, and the SHARE of that angle it takes.

  THE SHARE IS NEW ON 2026-09-07 and it exists because the neck stopped being one
  link. `head_neck` was split into `lower_cervical` + `upper_cervical` + `head`, so
  a `chain-joints` row naming `head_neck` as a child would export a joint attached
  to a link that is not in `links` — a spec an Isaac `Articulation` would refuse to
  build, and one nothing in this repo would have noticed, because `to-articulation`
  builds `links` from the body and `joints` from this table and never compared them.
  `the-articulation-joins-links-that-exist` does compare them now.

  The three cervical shares are `pose/cervical-partition`, so the exported
  articulation bends the same way the solver's chain does rather than carrying a
  second copy of the rule.

  A ROW CARRIES SEVERAL TERMS SINCE 2026-09-08, and it had to. When the trunk was
  split at T12/L1 the lumbosacral joint stopped being a function of one posture
  input: the lumbar spine's tilt is `trunk-flexion + pelvic-tilt/2` and the
  pelvis it hangs off is itself rotated by `-pelvic-tilt`, so the angle BETWEEN
  them is `trunk-flexion + 1.5 x pelvic-tilt`. With one `[key share]` per row the
  only way to export that would have been to drop the pelvic term, which is the
  shape of defect this file already caught once — an exported articulation that
  bends differently from the solver's chain and nothing comparing them. A row is
  now a seq of `[key share]` pairs summed, so the export follows
  `pose/lumbar-chord-tilt-deg` instead of approximating it."
  [["lumbosacral" "pelvis" "lumbar" [[:trunk-flexion-deg 1.0]
                                     [:lumbar-lordosis-deg 1.5]]]
   ;; the thorax relative to the lumbar spine: MINUS half the pelvic tilt, because
   ;; the lumbar chord takes half of it and the thorax takes none. This is the
   ;; lordosis, seen from the joint that carries it.
   ["thoracolumbar" "lumbar" "thorax" [[:lumbar-lordosis-deg -0.5]]]
   ["cervicothoracic" "thorax" "lower_cervical"
    [[:head-flexion-deg (:lower pose/cervical-partition)]]]
   ["c2c3" "lower_cervical" "upper_cervical"
    [[:head-flexion-deg (:upper pose/cervical-partition)]]]
   ["atlanto-occipital" "upper_cervical" "head"
    [[:head-flexion-deg (:head pose/cervical-partition)]]]
   ["shoulder" "thorax" "upper_arm" [[:shoulder-flexion-deg 1.0]]]
   ["elbow" "upper_arm" "forearm" [[:elbow-flexion-deg 1.0]]]])

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
                :elbow-flexion-deg (:elbow-flexion-deg posture)
                ;; optional, and defaulted HERE rather than at the row, so that a
                ;; posture written before the pelvis could rotate exports exactly
                ;; the articulation it exported before.
                :lumbar-lordosis-deg (or (:lumbar-lordosis-deg posture) 0.0)}
        joints (mapv (fn [[name parent child terms]]
                       (kami-joint name parent child
                                   (reduce (fn [a [k share]]
                                             (+ a (* share (get angles k))))
                                           0.0 terms)))
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
