(ns suji.methods.muscle
  "suji (筋) — muscle tension: share each equilibrium's load across the muscles
  that can carry it, and report the result as %MVC. Stdlib only, no I/O.

  Force available is F_max = PCSA × specific tension; %MVC is force ÷ F_max.

  WHAT CHANGED 2026-09-06. Three things, all of which used to be stated as
  constants or as expressions with no stated intent:

  1. **Moment arms are computed, not tabulated.** They come from
     `suji.methods.attachment`, which places each muscle's attachments on the
     bones `suji.methods.pose` placed and derives the perpendicular distance. A
     constant arm asserts that leverage does not change when the joint moves,
     which is false for every muscle in the body. The constants this actor used
     are still the anchor — the attachment offsets are calibrated so the derived
     arm reproduces them at the neutral posture — but the variation away from
     neutral is now geometry rather than absence.

  2. **Redundancy is resolved by a stated criterion.** Upper trapezius and levator
     scapulae both suspend the shoulder girdle, so the equilibrium does not
     determine their forces on its own. They used to be assigned by two unrelated
     hand-written expressions. They are now shared by `suji.methods.recruit`
     (Crowninshield–Brand minimum cubed stress), which is a closed form, is exact,
     and moves when the anatomy moves.

  3. **The model refuses postures it cannot answer for.** A straight-line muscle
     has no wrapping surface, so its line of action can pass through the joint it
     acts about; near there the required force diverges. Such a muscle comes back
     with `:refused` and no `:mvc-pct`, and consumers must show that rather than a
     number. See `recruit/min-coeff`.

  A CORRECTION MADE ALONG THE WAY. The old upper-trapezius expression added a term
  proportional to head weight × sin(head flexion). The head's extension load is
  already carried by `cervical_extensors` in the cervical-extension equilibrium,
  so that term charged the same load twice. It is gone.

  A GAP LEFT OPEN. `:shoulder-elevation-deg` (scapular elevation) is carried in
  the posture and is NOT an input to any equilibrium here. The old expressions
  used it as a multiplier with no stated meaning; rather than keep an unaccountable
  term, this model states that it does not yet resolve the elevation demand.

  NON-DIAGNOSTIC (G1): %MVC is a force ratio, not a diagnosis.
  G10 anti-pseudoscience: Hill-model muscles only; NO 経絡/気/波動."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.math :as math]
            [suji.methods.pose :as pose]
            [suji.methods.recruit :as recruit]
            [suji.methods.segment :as segment]))

(def specific-tension-n-cm2 60.0)

(defn peak-force-n
  "The most this muscle could ever produce: PCSA × specific tension. It is the
  force available only AT the optimal length — see `available-force-n`."
  [spec]
  (* (:pcsa-cm2 spec) specific-tension-n-cm2))

;; kept as the old name, because it is what `specs` documents
(def f-max-n peak-force-n)

(defn force-length-factor
  "Hill-type active force–length scaling: a multiplier in [0,1], 1.0 at the optimal
  length and reaching 0 outside [0.5, 1.5] × optimal.

  DELIBERATE DUPLICATION, stated so it is not mistaken for a failure to look.
  `kotoba.biomech.muscle/force-length-factor` in kotoba-lang/biomech is this same
  closed form. suji does not depend on it: biomech's deps.edn pulls kotoba-lang/fea,
  kotoba-lang/kami-vehicle and kotoba-lang/kami-engine-cfd — three solver repos —
  and this actor is compiled into a browser bundle. Three heavy git dependencies
  for one three-line function is a cost the consumer pays and the function does not
  justify. `muscle-test` pins the values, so the two can be compared by hand; it
  cannot notice biomech changing, and that is the price of the duplication."
  [length optimal-length]
  (if (or (nil? length) (nil? optimal-length) (<= optimal-length 0.0))
    1.0
    (let [ratio (/ (double length) (double optimal-length))]
      (max 0.0 (- 1.0 (* 4.0 (- ratio 1.0) (- ratio 1.0)))))))

(def passive-slack-frac
  "Below this fraction of optimal length a muscle's passive tissue is slack and
  carries nothing. A muscle at or under its optimal length does not pull on its
  own — the elastic elements are not stretched."
  1.0)

(def passive-at-stretch
  "Passive force as a fraction of PEAK active force at 1.5x optimal length, where
  the active curve has already reached zero. Representative: passive tension is
  the reason a fully stretched muscle still resists at all."
  0.8)

(defn ligament-force-n
  "Force a LIGAMENT produces at its current length.

  Not a muscle: it cannot contract, it has no %MVC, and it is slack until the
  joint has already carried past it. Its stiffness is stated as a force at a
  STATED stretch rather than derived from a cross-section, because a ligament has
  no contractile machinery for a specific tension to describe.

  The stretch each structure is calibrated at is its own (`:ref-stretch`), because
  they do not stretch alike: measured 2026-09-06, 60 degrees of trunk flexion takes
  the lumbar band to 1.25x its neutral length, while 15 degrees of head flexion
  already takes the nuchal ligament to 1.18x and 60 degrees to 1.60x — it is short
  and the head turns through a large angle. Calibrating both at 1.25x put the whole
  cervical load on a ligament and reported the extensors doing nothing, in the one
  posture this actor exists to describe.

  This is the structure the previous wave named as missing when it measured its
  own claim: a muscle's own passive tension supplies about 4% of the demand at 60
  degrees of trunk flexion, and flexion-relaxation is these taking over."
  [spec length neutral-length]
  (if (or (nil? length) (nil? neutral-length) (<= neutral-length 0.0))
    0.0
    (let [ratio (/ (double length) (double neutral-length))
          slack (or (:slack-frac spec) 1.0)]
      (if (<= ratio slack)
        0.0
        ;; CLAMPED AT 1.25x. The exponential is calibrated between slack and 1.25
        ;; and says nothing beyond it: extrapolating gave the nuchal ligament
        ;; 52,312 N at an ordinary forward-head posture, because the head flexes
        ;; far enough to stretch a short ligament well past the calibrated range.
        ;; A real ligament stiffens and then FAILS; this model has no failure law,
        ;; so it holds the force at the last value it can defend and
        ;; `at-limit?` says the posture has left the range.
        (let [ref-stretch (or (:ref-stretch spec) 1.25)
              x (math/clamp (/ (- ratio slack) (- ref-stretch slack)) 0.0 1.0)
              k 4.0]
          (* (or (:force-at-ref spec) 0.0)
             (/ (- (Math/exp (* k x)) 1.0) (- (Math/exp k) 1.0))))))))

(defn ligament-at-limit?
  "True when the posture has stretched this ligament past the range its stiffness
  is calibrated over (`:ref-stretch`, per structure). The force it reports there is the last defensible value, not
  a prediction — a real ligament stiffens further and then fails, and this model
  has no failure law."
  [spec length neutral-length]
  (boolean
   (and (:ligament? spec) length neutral-length (pos? neutral-length)
        (> (/ (double length) (double neutral-length))
           (or (:ref-stretch spec) 1.25)))))

(defn passive-force-n
  "Force this muscle's PASSIVE elastic tissue produces at its current length.

  WHY THIS IS NOT OPTIONAL IN A STATIC MODEL. Passive tension needs no activation
  and costs nothing metabolically — it is the tissue itself resisting being
  stretched. In a static posture it is carried by whichever muscles the posture has
  lengthened, and the nervous system only has to supply the REST. Ignoring it, as
  this actor did until 2026-09-06, assigns the whole load to active contraction and
  therefore overstates the effort of every posture that stretches a muscle.

  IT IS NOT, BY ITSELF, FLEXION-RELAXATION — and the first draft of this docstring
  said it was. Measured after writing it: at 60 deg of trunk flexion the erector
  spinae reaches 1.27x its optimal length and its own passive tissue supplies
  151 N of the 3,379 N the posture demands, about 4%. Real flexion-relaxation is
  the POSTERIOR LIGAMENTOUS SYSTEM taking over — the supraspinous and
  interspinous ligaments and the thoracolumbar fascia — and those are separate
  structures this model does not contain. A muscle's own passive tension is one
  term of that story and the smaller one; claiming it explains the phenomenon
  would be claiming a mechanism the numbers do not support.

  Exponential above slack, zero below, and expressed relative to the PEAK active
  force rather than the available one — passive tissue does not care what the
  contractile machinery can do at this length."
  [spec length optimal-length]
  (if (or (nil? length) (nil? optimal-length) (<= optimal-length 0.0))
    0.0
    (let [ratio (/ (double length) (double optimal-length))]
      (if (<= ratio passive-slack-frac)
        0.0
        ;; normalised so that ratio = 1.5 gives `passive-at-stretch` of peak
        (let [x (/ (- ratio passive-slack-frac) (- 1.5 passive-slack-frac))
              k 5.0]
          (* (peak-force-n spec) passive-at-stretch
             (/ (- (Math/exp (* k x)) 1.0) (- (Math/exp k) 1.0))))))))

(defn available-force-n
  "What this muscle can actually produce AT THIS POSTURE.

  %MVC used to divide by the peak force, which assumes a muscle can produce its
  maximum at every length. It cannot: at half or one and a half times its optimal
  length it produces nothing at all, and the posture decides its length. Dividing
  by a constant therefore UNDERSTATES the effort of any posture that stretches or
  shortens a muscle away from its optimum — which is most of the interesting ones."
  [spec length optimal-length]
  (* (peak-force-n spec) (force-length-factor length optimal-length)))

(def specs
  "Kept as the public description of each group. `:moment-arm-m` is the NEUTRAL
  arm the attachment geometry is calibrated against — it is documentation of the
  calibration target, not the value used in the solve. `attachment-test` pins the
  agreement, so this table cannot drift away from the anatomy."
  (array-map
   "cervical_extensors" {:name "cervical_extensors" :pcsa-cm2 12.0 :moment-arm-m 0.020}
   "upper_trapezius"    {:name "upper_trapezius"    :pcsa-cm2 9.0  :moment-arm-m 0.025}
   "levator_scapulae"   {:name "levator_scapulae"   :pcsa-cm2 5.0  :moment-arm-m 0.020}
   "anterior_deltoid"   {:name "anterior_deltoid"   :pcsa-cm2 10.0 :moment-arm-m 0.030}
   "erector_spinae"     {:name "erector_spinae"     :pcsa-cm2 34.0 :moment-arm-m 0.055}
   ;; added 2026-09-06 — no legacy constant to calibrate against, because this
   ;; actor had no muscle for the frontal plane or for the girdle in a folded
   ;; posture, which is why both were reported as carried by nobody.
   "middle_trapezius"   {:name "middle_trapezius"   :pcsa-cm2 8.0}
   "middle_deltoid"     {:name "middle_deltoid"     :pcsa-cm2 12.0}
   "latissimus_dorsi"   {:name "latissimus_dorsi"   :pcsa-cm2 14.0}
   "quadratus_lumborum" {:name "quadratus_lumborum" :pcsa-cm2 8.0}
   "obliques"           {:name "obliques"           :pcsa-cm2 16.0}
   "scalenes"           {:name "scalenes"           :pcsa-cm2 5.0}
   ;; the elbow, added 2026-09-06 — the chain placed it and nothing solved it
   "biceps_brachii"     {:name "biceps_brachii"     :pcsa-cm2 9.0}
   "brachialis"         {:name "brachialis"         :pcsa-cm2 12.0}
   "triceps_brachii"    {:name "triceps_brachii"    :pcsa-cm2 20.0}
   ;; the wrist, added 2026-09-06 — the last joint the kinematics placed and the
   ;; kinetics did not
   "wrist_extensors"    {:name "wrist_extensors"    :pcsa-cm2 5.0}
   "wrist_flexors"      {:name "wrist_flexors"      :pcsa-cm2 8.0}))

;; emission order — midline groups, then each side, matching `attachment/instances`
(def emit-order (mapv :name attachment/instances))

(defn passive-of
  "Passive force for one instance at this posture — a ligament's whole force, or a
  muscle's elastic term."
  [inst lengths optimals]
  (if (:ligament? inst)
    (ligament-force-n inst (get lengths (:name inst)) (get optimals (:name inst)))
    (passive-force-n (get specs (:group inst))
                     (get lengths (:name inst))
                     (get optimals (:name inst)))))

(defn f-max-of
  "Force available to one instance at this posture. `lengths` and `optimals` are
  `attachment/lengths` / `attachment/optimal-lengths`; passing neither falls back
  to the peak force, which is what this returned before the force–length relation
  existed."
  ([inst] (if (:ligament? inst) 0.0 (peak-force-n (get specs (:group inst)))))
  ([inst lengths optimals]
   (if (:ligament? inst)
     ;; a ligament cannot contract, so it offers the criterion nothing to
     ;; distribute; its force is already subtracted from the load as a passive term
     0.0
     (available-force-n (get specs (:group inst))
                        (get lengths (:name inst))
                        (get optimals (:name inst))))))

(defn suspended-weight-n
  "Weight ONE shoulder girdle has to suspend: the arm segments hanging from it.
  Resting the forearms on a desk transfers those two segments to the desk, so the
  girdle carries the upper arm only — that is the whole of the `arms-supported`
  effect, stated as which segments are hanging rather than as a multiplier.

  Per side since the model became bilateral: this used to be `2 ×` one arm, which
  is the same number for a symmetric posture and cannot represent any other."
  [body p side]
  (let [w (pose/segment-weights body p)
        hanging (if (:arms-supported (meta p)) ["upper_arm"] ["upper_arm" "forearm" "hand"])]
    (reduce + 0.0 (map #(get w (:name %)) (pose/segments-on p hanging side)))))

(defn- girdle-load-n [body p side arms-supported]
  (let [w (pose/segment-weights body p)
        hanging (if arms-supported ["upper_arm"] ["upper_arm" "forearm" "hand"])]
    (reduce + 0.0 (map #(get w (:name %)) (pose/segments-on p hanging side)))))

(defn- candidates
  "Muscle instances for one task, optionally restricted to one side.

  `:f-max-n` is the force AVAILABLE at this posture, not the peak — so the
  criterion weighs what each muscle can actually contribute here. Weighing the
  peak would hand load to a muscle that is too short or too stretched to take it."
  [task side coeffs lengths optimals]
  (for [m attachment/instances
        :when (and (= task (:task m))
                   (or (nil? side) (= side (:side m))))]
    {:name (:name m) :f-max-n (f-max-of m lengths optimals) :coeff (get coeffs (:name m))
     :passive-n (passive-of m lengths optimals)}))

(defn- share-signed
  "Share a load whose SIGN says which side has to resist it.

  A frontal-plane moment can go either way, and the muscles that oppose it are a
  mirror pair: at any instant one side's coefficient is positive and the other's is
  negative. `recruit` only distributes across positive coefficients — correctly, a
  muscle cannot push — so the load and the coefficients are flipped together when
  the load is negative. The muscles on the resisting side then come out positive
  and carry it, and the muscles on the other side come out negative and are refused
  as acting the wrong way, which at that instant they are: their antagonist does
  not co-contract in a static optimum."
  [cands load]
  ;; A load that is zero to within rounding has no side. Reading its sign off the
  ;; last bits — a symmetric posture computes -1e-16, not 0.0 — picks a resisting
  ;; side at random and refuses the other as acting the wrong way, for a load that
  ;; is not there. Measured 2026-09-06: symmetric postures reported one deltoid
  ;; refused and the other carrying 0.0 N.
  (let [zero? (< (math/abs* load) 1e-9)
        flip (if (neg? load) -1.0 1.0)]
    (if zero?
      (recruit/share (map #(update % :coeff (fn [c] (when c (math/abs* c)))) cands) 0.0)
      (recruit/share (map #(update % :coeff (fn [c] (when c (* flip c)))) cands)
                     (math/abs* load)))))

(defn- ->tension
  "Attach %MVC to one shared result. A refusal stays a refusal — there is no
  %MVC for a force this model declined to compute.

  `:over-mvc?` marks a demand ABOVE maximum voluntary contraction. It is not an
  error and it is not clamped: it says the posture asks the modelled muscles for
  more force than they can produce, which is a real mechanical statement — a body
  in that posture is being held by something this model does not contain
  (ligaments, passive tissue, the spine in flexion-relaxation, or a different
  strategy altogether). Clamping it at 100 would erase exactly the finding."
  [shared]
  (cond
    (:refused shared) (assoc shared :mvc-pct nil)
    ;; a ligament has no maximum voluntary contraction, because it cannot contract
    (or (nil? (:f-max-n shared)) (zero? (:f-max-n shared)))
    (assoc shared :mvc-pct nil :over-mvc? false)
    :else
    (let [pct (/ (* 100.0 (max 0.0 (:force-n shared))) (:f-max-n shared))]
      (assoc shared :mvc-pct pct :over-mvc? (> pct 100.0)))))

(defn solve-muscle-tensions
  "Map a posture's joint loads onto per-muscle force and %MVC.

  BILATERAL. Each side's shoulder and girdle equilibrium is solved on its own, so
  an asymmetric posture — which is any posture with lateral bend, and any posture
  where the two arms differ — loads the two sides differently. The midline groups
  (cervical extensors, erector spinae) are solved once; their PCSA is already the
  bilateral sum.

  Returns a vector in `emit-order`. An entry is either
  {:name :group :side :force-n :f-max-n :mvc-pct :coeff} or
  {:name :refused :note :mvc-pct nil}."
  [body posture loads]
  (let [p (pose/solve-pose body posture)
        coeffs (attachment/arms p (:stature-m body))
        lens (attachment/lengths p (:stature-m body))
        opts (attachment/optimal-lengths
              (pose/solve-pose body attachment/reference-posture) (:stature-m body))
        candidates (fn [task side coeffs] (candidates task side coeffs lens opts))
        sup (:arms-supported posture)
        joint (fn [n] (first (filter #(= n (:joint %)) (:joints loads))))
        by-name (fn [xs] (into {} (map (juxt :name identity)) xs))
        shoulder-per-side (:per-side (joint "shoulder"))
        task-list
        (concat
         [[[:cervical-extension :midline]
           (recruit/share (candidates :cervical-extension nil coeffs)
                          (get-in loads [:cervical :extensor-moment-nm]))]
          [[:trunk-extension :midline]
           (recruit/share (candidates :trunk-extension nil coeffs)
                          (:moment-nm (joint "lumbosacral")))]
          [[:trunk-lateral-flexion :midline]
           (share-signed (candidates :trunk-lateral-flexion nil coeffs)
                         (get-in loads [:frontal :lumbosacral-nm] 0.0))]
          [[:cervical-lateral-flexion :midline]
           (share-signed (candidates :cervical-lateral-flexion nil coeffs)
                         (get-in loads [:frontal :cervical-nm] 0.0))]]
         (for [side [:left :right]
               entry [[[:shoulder-flexion side]
                       (recruit/share (candidates :shoulder-flexion side coeffs)
                                      (get shoulder-per-side side 0.0))]
                      [[:scapular-suspension side]
                       (recruit/share (candidates :scapular-suspension side coeffs)
                                      (girdle-load-n body p side sup))]
                      [[:elbow-flexion side]
                       (share-signed (candidates :elbow-flexion side coeffs)
                                     (get-in (into {} (map (juxt :joint identity) (:joints loads)))
                                             ["elbow" :per-side side] 0.0))]
                      [[:wrist-flexion side]
                       (share-signed (candidates :wrist-flexion side coeffs)
                                     (get-in (into {} (map (juxt :joint identity) (:joints loads)))
                                             ["wrist" :per-side side] 0.0))]
                      [[:shoulder-abduction side]
                       (share-signed (candidates :shoulder-abduction side coeffs)
                                     (get-in loads [:frontal :shoulder-per-side side] 0.0))]]]
           entry))
        task-results (into {} task-list)
        results (into {} (for [[_ shared] task-list, x shared] [(:name x) x]))
        instance-by (into {} (map (juxt :name identity)) attachment/instances)
        ;; a task whose load WAS placed has no unanswered load; its refused
        ;; members are antagonists, and marking them says so rather than leaving a
        ;; consumer to count them as gaps.
        ;;
        ;; Keyed by instance NAME, not by [task side]: a mirror-paired task like
        ;; lateral flexion is solved once with both sides as candidates, so its
        ;; members do not share the side its key would carry. Keying by task+side
        ;; matched nothing, every refusal looked like a gap, and the sweep reported
        ;; all 3,240 postures incomplete.
        carried-names (into #{}
                            (for [[_ shared] task-results
                                  :when (recruit/carried? shared)
                                  x shared]
                              (:name x)))]
    (mapv (fn [n]
            (let [inst (instance-by n)
                  t (->tension (get results n))]
              (merge (select-keys inst [:group :side :task :ligament?])
                     (when (:ligament? inst)
                       {:at-limit? (ligament-at-limit?
                                    inst (get lens (:name inst)) (get opts (:name inst)))})
                     t
                     (when (and (:refused t) (contains? carried-names n))
                       {:antagonist? true}))))
          emit-order)))

(defn over-mvc
  "Instances demanding more than maximum voluntary contraction at this posture."
  [tensions]
  (filterv :over-mvc? tensions))

(defn tension-summary
  "Which parts of the load this solve could place, for a consumer that must not
  present an incomplete answer as a complete one.

  Two independent ways an answer can be incomplete, and they are reported
  separately because they have different fixes:

    :refused         a muscle exists but its leverage is unresolvable HERE — a
                     straight-line model with no wrapping surface. Fixed by
                     wrapping surfaces, not by more muscles.
    :antagonists     a mirror-paired task's other side, refused because a static
                     optimum does not co-contract. NOT a gap.
    :over-mvc        the load was placed, and placing it needs more force than the
                     muscle can produce. Also not a gap — a finding.

  Frontal-plane loads used to appear here as `:unassigned-frontal-nm`, because
  this actor had no frontal-plane musculature at all. It has since 2026-09-06."
  ([tensions] (tension-summary tensions nil))
  ([tensions loads]
   (let [frontal (:frontal loads)]
     (cond-> {:total (count tensions)
              ;; an antagonist is not a gap: for a mirror-paired task exactly one
              ;; side resists and the other is refused, which is the static
              ;; optimum rather than an unanswered load
              :refused (count (remove :antagonist? (filter :refused tensions)))
              :antagonists (count (filter :antagonist? tensions))
              :over-mvc (count (over-mvc tensions))
              :complete? (not-any? #(and (:refused %) (not (:antagonist? %))) tensions)
              :max-mvc-pct (let [xs (keep :mvc-pct tensions)] (when (seq xs) (apply max xs)))}
       frontal (assoc :frontal frontal)))))

(defn numeric-mvc?
  "Does this entry have a %MVC at all?

  THE QUESTION EVERY EMIT SITE SHOULD ASK, and the one three of them got wrong in
  a row by asking `:refused` instead. There are now two ways to have no %MVC — the
  model declined to compute a force, and the entry is a LIGAMENT, which cannot
  contract and therefore has no maximum voluntary contraction to be a fraction of.
  A site that branches on the reason has to be revisited every time a new reason
  appears; a site that branches on whether the number is there does not."
  [t]
  (number? (:mvc-pct t)))

(defn fmt-mvc
  "Display helper: a %MVC, or the reason there is not one."
  [t]
  (if (numeric-mvc? t) (str (math/fmt-fixed (:mvc-pct t) 1) " %") "—"))
