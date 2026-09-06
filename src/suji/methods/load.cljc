(ns suji.methods.load
  "suji (筋) — static inverse dynamics: posture → joint moments + spinal load. 1:1 Clojure
  port of `src/suji/methods/load.cljc` (ADR-2606061900). Stdlib only.

  The bones half. Given the sagittal joint angles (posture) and the segment masses
  (segment), it solves the STATIC inverse-dynamics problem: the gravitational moment each
  joint must resist to hold the posture against gravity. This is the gravity term of the
  Featherstone RNEA reduced to statics — the closed-form static special case.

  EMPIRICAL ANCHOR (G7/G10): the cervical (neck) model reproduces Hansraj (2014) forward-
  head-posture loads: neutral ≈ head weight, rising to ~5× head weight at 60° flexion.

  NON-DIAGNOSTIC (G1, 医師法 §17): every output is a mechanical quantity — a moment (N·m),
  a muscle force (N), a compressive load (N / kg-force). None is a diagnosis.

  Numerics: sin/cos resolve on both hosts; degree conversion goes through
  suji.methods.math (Math/toRadians is JVM-only — see that ns).
  Records are kebab-keyword maps; joints kept as an ordered vector (Python list order)."
  (:require [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

;; --- Cervical lever model (Hansraj-calibrated) -------------------------------
(def head-com-lever-m 0.10)     ;; effective horizontal lever of head CoM at full flexion
(def cervical-ext-arm-m 0.02)   ;; cervical extensor moment arm

(defn cervical-load
  "Forward-head-posture cervical load. Reproduces Hansraj (2014) (G7 anchor)."
  ([head-flexion-deg head-weight-n]
   (cervical-load head-flexion-deg head-weight-n head-com-lever-m cervical-ext-arm-m))
  ([head-flexion-deg head-weight-n head-com-lever-m extensor-arm-m]
   (when (<= head-weight-n 0)
     (throw (ex-info "head_weight_n must be positive" {:type :value-error})))
   (when (<= extensor-arm-m 0)
     (throw (ex-info "extensor_arm_m must be positive" {:type :value-error})))
   (let [theta (math/radians head-flexion-deg)
         rho (/ head-com-lever-m extensor-arm-m)
         moment (* head-weight-n head-com-lever-m (Math/sin theta))
         ext-force (/ moment extensor-arm-m)
         compressive (* head-weight-n (+ (* rho (Math/sin theta)) (Math/cos theta)))]
     {:head-flexion-deg head-flexion-deg
      :head-weight-n head-weight-n
      :extensor-moment-nm moment
      :extensor-force-n ext-force
      :compressive-load-n compressive
      :compressive-load-kgf (/ compressive segment/gravity)
      :multiplier-vs-head (/ compressive head-weight-n)})))

(defn cervical-load-sensitivity
  "Marginal cervical compressive load per degree of forward head flexion AT a given posture — the
  local slope d(compressive-load)/d(flexion) of the Hansraj forward-head-posture model, by central
  finite difference. A purely MECHANICAL quantity (N of compressive load per additional degree;
  G1 non-diagnostic — no clinical key, never a prescription) and self-referenced to the SAME posture
  (G3 — the model's local derivative here, never a population rank). It makes a small posture
  change's MODELLED load effect legible: because the load curve is concave, the first degrees off
  neutral cost the most per degree. Returns {:head-flexion-deg :compressive-load-n :d-load-per-deg-n}."
  ([head-flexion-deg head-weight-n] (cervical-load-sensitivity head-flexion-deg head-weight-n 0.5))
  ([head-flexion-deg head-weight-n delta-deg]
   (let [load-at (fn [a] (:compressive-load-n (cervical-load a head-weight-n)))]
     {:head-flexion-deg head-flexion-deg
      :compressive-load-n (load-at head-flexion-deg)
      :d-load-per-deg-n (/ (- (load-at (+ head-flexion-deg delta-deg))
                              (load-at (- head-flexion-deg delta-deg)))
                           (* 2.0 delta-deg))})))

;; --- Generic static joint moment (RNEA gravity term) -------------------------
(defn- ->joint-load
  ([joint moment-nm] (->joint-load joint moment-nm "" nil))
  ([joint moment-nm note] (->joint-load joint moment-nm note nil))
  ([joint moment-nm note per-side]
   (cond-> {:joint joint :moment-nm moment-nm :note note}
     per-side (assoc :per-side per-side))))

(defn- horizontal-lever
  "Horizontal moment arm of a flexed segment's CoM about its proximal joint:
  length * com-frac * sin(flexion). (Pure-vertical segment → zero lever.)"
  [length-m com-frac flexion-deg]
  (* length-m com-frac (Math/sin (math/radians flexion-deg))))

(defn- arm-moment-about
  "Sagittal moment about one shoulder from the arm segments hanging off it."
  [body p side arms-supported]
  (let [w (pose/segment-weights body p)
        carried (if arms-supported
                  ;; forearm + hand rest on the desk; the girdle carries the upper arm only
                  ["upper_arm"]
                  ["upper_arm" "forearm" "hand"])
        joint (get-in p [:joints (keyword "shoulder" (name side))])]
    (pose/gravitational-moment
     joint
     (for [seg (pose/segments-on p carried side)] [seg (get w (:name seg))]))))

(defn shoulder-moment
  "Gravitational moment about the glenohumeral joints from the held-out arm(s).

  BILATERAL since 2026-09-06: the two sides are computed and SUMMED, where this
  used to compute one and multiply by two. Those agree exactly for a symmetric
  posture and disagree for every asymmetric one — and lateral bend is asymmetric
  by definition, so doubling one side could not represent it.

  GEOMETRY CORRECTION (2026-09-06, earlier the same day). This used to place the
  forearm and hand at `(90° − elbow-flexion)` from vertical, which is inverted at
  both ends of the range: a straight arm came out HORIZONTAL and the 90° elbow of
  a typing posture came out VERTICAL. The chain is placed by `pose/solve-pose` and
  the moment read off it as Σ weight × anterior lever, which is the definition of
  the RNEA gravity term rather than a re-derivation of it."
  ([body shoulder-flexion-deg elbow-flexion-deg arms-supported]
   (shoulder-moment body {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0
                          :shoulder-flexion-deg shoulder-flexion-deg
                          :elbow-flexion-deg elbow-flexion-deg
                          :arms-supported arms-supported}
                    :both))
  ([body posture _mode]
   (let [p (pose/solve-pose body posture)
         sup (:arms-supported posture)
         per-side (into {} (for [side [:left :right]]
                             [side (arm-moment-about body p side sup)]))]
     (->joint-load "shoulder" (+ (:left per-side) (:right per-side))
                   (if sup "forearms supported" "arms unsupported (hanging)")
                   per-side))))

(defn elbow-moment
  "Gravitational moment about each elbow from the forearm and hand hanging off it.

  The chain has placed an elbow since `pose` existed and nothing took a moment
  about it: the forearm and hand were carried by the shoulder's equilibrium and by
  nothing else, as though the elbow were welded. A typing posture holds them out
  at 90° all day, so this is not a small term — it is the one a desk worker is
  actually asking about.

  Resting the forearms transfers them to the desk, and then the elbow carries
  nothing, which is the whole of the `arms-supported` effect here."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        carried (if (:arms-supported posture) [] ["forearm" "hand"])]
    (into {}
          (for [side [:left :right]]
            [side (pose/gravitational-moment
                   (get-in p [:joints (keyword "elbow" (name side))])
                   (for [seg (pose/segments-on p carried side)]
                     [seg (get w (:name seg))]))]))))

(defn wrist-moment
  "Gravitational moment about each wrist from the hand.

  The last joint this chain placed and did not solve. The hand is small — about
  0.6% of body mass — but a keyboard posture holds it out horizontally for hours,
  and the wrist extensors holding it there are the muscles a typist complains
  about. Resting the forearms rests the hands with them.

  THE HIP USED TO BE EXCLUDED HERE, and this paragraph used to explain why: there
  was no thigh segment, so there was nothing for a hip moment to be a moment of.
  That was true and it is no longer — see `lower-limb-loads`. The exclusion was
  never about the hip being uninteresting; it was about an absent segment, which is
  exactly the kind of gap that gets read as a decision if nobody comes back."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        carried (if (:arms-supported posture) [] ["hand"])]
    (into {}
          (for [side [:left :right]]
            [side (pose/gravitational-moment
                   (get-in p [:joints (keyword "wrist" (name side))])
                   (for [seg (pose/segments-on p carried side)]
                     [seg (get w (:name seg))]))]))))

(defn lumbosacral-moment
  "Gravitational moment about L5/S1 from the leaned trunk + head-arm load above it."
  [body trunk-flexion-deg head]
  (let [thorax (segment/seg body "thorax_abdomen")
        m0 (* (segment/weight-n thorax)
              (horizontal-lever (:length-m thorax) (:com-frac thorax) trunk-flexion-deg))
        head-x (* (:length-m thorax) (Math/sin (math/radians trunk-flexion-deg)))
        m (+ m0 (* (:head-weight-n head) head-x))]
    (->joint-load "lumbosacral" m "trunk lean + carried head")))

(defn frontal-moments
  "The FRONTAL-plane (about X) gravitational moments this posture creates.

  Zero for a symmetric posture — the two arms hang at ±half the biacromial breadth
  from the midline and their moments about the spine cancel exactly — and non-zero
  as soon as the trunk bends laterally or the arms abduct unequally. Before the
  model was bilateral it placed ONE arm and doubled it, so there was nothing for
  that arm's frontal moment to cancel against and this number was an artefact of
  the modelling rather than of the posture.

  These are reported, not solved, until a muscle exists that can carry them: see
  `muscle/tension-summary`, which refuses to call an answer complete while any of
  this is unassigned."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        frontal (fn [joint-point segs]
                  (nth (pose/gravitational-moment-vec
                        joint-point (for [seg segs] [seg (get w (:name seg))]))
                       0))
        arm-bases ["upper_arm" "forearm" "hand"]]
    {;; PER SIDE, not summed. The two shoulders are different joints with
     ;; different muscles; a symmetric abduction gives each of them a real frontal
     ;; moment, and adding them cancels to zero and reports that abducting both
     ;; arms asks nothing of either shoulder. The lumbosacral term IS a sum,
     ;; because there is one spine and the two arms load it about the same axis.
     :shoulder-per-side (into {} (for [side [:left :right]]
                                   [side (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                  (pose/segments-on p arm-bases side))]))
     :shoulder-nm (reduce max 0.0
                          (for [side [:left :right]]
                            (math/abs* (frontal (get-in p [:joints (keyword "shoulder" (name side))])
                                                (pose/segments-on p arm-bases side)))))
     :cervical-nm (frontal (get-in p [:joints :c7])
                           (pose/segments-on p ["head_neck"]))
     :lumbosacral-nm (frontal (get-in p [:joints :l5s1])
                              (concat (pose/segments-on p ["thorax_abdomen" "head_neck"])
                                      (pose/segments-on p arm-bases)))}))

(def lower-limb-chain
  "Each lower-limb joint, the segments DISTAL to it, and the name it is reported
  under. Proximal to distal.

  The list is the same for standing and for sitting, which is the point: the two
  support modes do not disagree about what the leg is made of. They disagree about
  whether the floor is pushing on the end of it."
  [{:joint :hip   :name "hip"   :distal ["thigh" "shank" "foot"]}
   {:joint :knee  :name "knee"  :distal ["shank" "foot"]}
   {:joint :ankle :name "ankle" :distal ["foot"]}])

(defn- lower-limb-forces
  "Every external force on the free body distal to one lower-limb joint, as
  `[point force-vector]` pairs — the segment weights, plus the ground reaction when
  the body is standing on that foot."
  [body pose-data posture w joint-spec side]
  (let [standing (posture/standing? posture)
        seated-hip-is-empty (and (= :hip (:joint joint-spec))
                                 (posture/thigh-supported? posture))
        weights (when-not seated-hip-is-empty
                  (for [seg (pose/segments-on pose-data (:distal joint-spec) side)]
                    [(:com seg) [0.0 (- (get w (:name seg))) 0.0]]))
        cop (when (and standing (not seated-hip-is-empty))
              (pose/centre-of-pressure body pose-data side))]
    (cond-> (vec weights)
      cop (conj [cop [0.0 (* 0.5 (:weight-n (pose/whole-body-com body pose-data))) 0.0]]))))

(defn lower-limb-loads
  "Hip, knee and ankle: the moment each has to hold and the axial force each has to
  transmit, per side.

  THE SUPPORT MODE IS THE WHOLE ANSWER. `posture/support-mode` explains why in
  words; here is what it costs. The free body distal to each joint holds the same
  segments in both modes. Standing adds ONE force — the ground reaction, half the
  body's weight, pushing up at the centre of pressure — and that force is about
  thirty times the weight of the foot it acts on. Every difference between the
  statics of standing and the statics of sitting in this model is that one term,
  which is why it is added by a `cond->` rather than by a second code path: a
  second code path is a place for the two to drift.

  WHERE THE GROUND REACTION ACTS is not a parameter of this function. Static
  equilibrium puts the centre of pressure under the line of gravity, and
  `pose/centre-of-pressure` computes it there. The consequence is that a
  perfectly-stacked standing posture reports almost no ankle moment (true — a body
  balanced over its ankles asks nothing of its calves) and a posture that leans
  reports one proportional to the lean (also true, and it is what makes soleus work
  in quiet standing).

  WHAT IT DOES NOT DO. It does not refuse a posture whose centre of pressure falls
  outside the feet; it reports `:cop-inside-base?` and leaves the judgement to the
  consumer, because a body outside its base of support is a real thing — it is a
  step, or a fall — and a static model can describe the instant without claiming
  the body can hold it. It splits the ground reaction equally between the two feet,
  so single-leg stance is out of range. And it solves the SAGITTAL plane only;
  `:frontal-per-side` is reported and no muscle in this model carries it, exactly
  as the upper limb's frontal moments were reported before muscles for them
  existed.

  Returns
  `{:joints [{:joint :moment-nm :per-side :frontal-per-side :supported-weight-n}…]
    :support {…}}`."
  [body posture]
  (let [p (pose/solve-pose body posture)
        w (pose/segment-weights body p)
        {com-point :point body-weight :weight-n} (pose/whole-body-com body p)
        base (pose/base-of-support p)
        standing (posture/standing? posture)
        per-joint
        (fn [spec]
          (let [f (into {} (for [side [:left :right]]
                             [side (lower-limb-forces body p posture w spec side)]))
                vecs (into {} (for [side [:left :right]]
                                [side (pose/external-moment-vec
                                       (get-in p [:joints (keyword (name (:joint spec))
                                                                   (name side))])
                                       (get f side))]))]
            {:name (name (:joint spec))
             :moment-nm (+ (nth (:left vecs) 2) (nth (:right vecs) 2))
             :per-side (into {} (for [side [:left :right]] [side (nth (side vecs) 2)]))
             :frontal-per-side (into {} (for [side [:left :right]] [side (nth (side vecs) 0)]))
             ;; the axial force the joint transmits: the joint reaction balances the
             ;; net vertical external force on the free body below it
             :supported-weight-n
             (into {} (for [side [:left :right]]
                        [side (math/abs* (reduce + 0.0 (map #(second (second %))
                                                            (get f side))))]))}))]
    {:joints (mapv per-joint lower-limb-chain)
     :support {:mode (posture/support-mode posture)
               :thigh-supported (posture/thigh-supported? posture)
               :body-weight-n body-weight
               :com-x com-point
               :ground-reaction-per-foot-n (if standing (* 0.5 body-weight) 0.0)
               :base-of-support base
               :cop-inside-base?
               (when (and standing base)
                 (<= (:back base) (first com-point) (:front base)))}}))

(defn solve-posture-loads
  "Full static inverse-dynamics solve for a posture (the RNEA gravity term).

  `:frontal` carries the frontal-plane moments, which no muscle in this model
  carries — see `frontal-moments`."
  [body posture]
  (let [head-w (* (segment/head-mass-kg (:total-mass-kg body)) segment/gravity)
        cerv (cervical-load (:head-flexion-deg posture) head-w)
        joints [(->joint-load "cervicothoracic" (:extensor-moment-nm cerv)
                              "cervical extensor moment")
                (shoulder-moment body posture :both)
                (let [per-side (elbow-moment body posture)]
                  (->joint-load "elbow" (+ (:left per-side) (:right per-side))
                                (if (:arms-supported posture)
                                  "forearms rest on the desk"
                                  "forearm + hand held out")
                                per-side))
                (let [per-side (wrist-moment body posture)]
                  (->joint-load "wrist" (+ (:left per-side) (:right per-side))
                                (if (:arms-supported posture)
                                  "hands rest with the forearms"
                                  "hand held out")
                                per-side))
                (lumbosacral-moment body (:trunk-flexion-deg posture) cerv)]
        lower (lower-limb-loads body posture)
        ;; the lower-limb entries carry two keys the upper-limb ones do not
        ;; (`:supported-weight-n` and `:frontal-per-side`) and are otherwise the
        ;; same shape, so a consumer that walks `:joints` reading `:joint` and
        ;; `:moment-nm` — which is every consumer this actor has — needs no special
        ;; case for them. That was the requirement.
        lower-joints (mapv (fn [j]
                             (-> j
                                 (assoc :joint (:name j)
                                        :note (if (= :standing (get-in lower [:support :mode]))
                                                "standing: the ground reaction under this foot"
                                                "seated: only what hangs below this joint"))
                                 (dissoc :name)))
                           (:joints lower))]
    {:cervical cerv
     :joints (into (vec joints) lower-joints)
     :frontal (frontal-moments body posture)
     :support (:support lower)}))
