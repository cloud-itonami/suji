(ns suji.methods.datoms
  "suji (筋) — emit analyze results as kotoba EAVT Datoms (G9). 1:1 Clojure port of
  `src/suji/methods/datoms.cljc` (ADR-2606061900). Stdlib only.

  Projects the physics analyze run into the canonical kotoba Datom-log shape (schema.edn):
  a body / posture / joint-load / muscle-tension / strain is a replayable, as-of Datom.

  NON-DIAGNOSTIC (G1): every emitted attribute is a mechanical quantity declared in the
  schema; test-datoms drift-locks the emitted attribute set against schema.edn.

  NUMERICS: Python `round(v, n)` is round-half-EVEN; `render_edn` then writes floats via
  Python `str()` (shortest round-trip repr). `py-round` reproduces round() via exact
  BigDecimal.(double) + HALF_EVEN → nearest double, and `fmt` renders a double via
  Double/toString — byte-identical to Python's repr for these rounded magnitudes.

  KEY ORDER: each datom is an ordered (array-map) map so the rendered `k v` pairs come out
  in the exact Python dict-literal order. as-of / session-minutes carry their original
  numeric type (idx is a Long, session-minutes stays the float passed in)."
  (:require [clojure.string :as str]
            [suji.methods.analyze :as analyze]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.strain :as strain]
            #?(:clj [clojure.java.io :as io])))

(def ^:private joint-kw
  ;; A joint missing from this map used to emit a nil keyword into the datom and
  ;; then fail two lines later on the arithmetic. Adding a joint to `load` and
  ;; forgetting it here is the obvious mistake, so the fallback derives the
  ;; keyword instead of returning nothing.
  {"cervicothoracic" ":cervicothoracic" "shoulder" ":shoulder"
   "elbow" ":elbow" "wrist" ":wrist" "lumbosacral" ":lumbosacral"
   "hip" ":hip" "knee" ":knee" "ankle" ":ankle"})

(defn- kw-str
  "A vocabulary term as the datom log writes it: an EDN keyword literal in a string.
  Every controlled-vocabulary value this emitter writes goes through here, so the
  lexicon enums can be literal sets of exactly these strings with no normalisation
  step in between. A validator that normalises before comparing cannot tell
  `\"low\"` from `\":low\"`, and a contract check that cannot tell two spellings
  apart is not checking the spelling."
  [s]
  (str ":" (str/replace (str s) "_" "-")))

(defn instance-group+side
  "Split a muscle instance's identity into the two facts it is made of: WHICH
  STRUCTURE, and WHICH SIDE OF THE BODY.

  WHY THIS IS TWO FIELDS AND NOT ONE. Until 2026-09-07 this emitter wrote
  `:muscle/group :upper-trapezius/left` — the group name and the side concatenated
  into a single vocabulary term. That is two facts in one string, and it breaks the
  `group` enum in the published lexicon in a specific way: the enum's stated job
  (G10) is to say WHICH ANATOMICAL STRUCTURES this model may name, so that no
  経絡/気/波動 term can enter it. Folding the side in turns that vocabulary into a
  list of INSTANCES — it grows by two every time a muscle is modelled bilaterally,
  a purely mechanical change becomes a breaking change to a published enum, and the
  enum stops answering \"is this a mechanical structure?\" and starts answering
  \"have we modelled this instance yet?\". Only the first question is the charter's.

  It was also a field whose grammar varied by value: `cervical-extensors`,
  `erector-spinae`, `nuchal-ligament` and `posterior-lumbar-ligaments` are midline
  and carried no suffix, so a consumer parsing `group` could not know from the
  schema whether a suffix was coming. `side` with an explicit `:midline` makes
  every record the same shape, and makes \"compare left against right\" an equality
  rather than string surgery.

  The model had already separated them: `attachment/instances` sets `:group` and
  `:side` as distinct keys on every instance (`attachment.cljc`, `mirror`/`midline`).
  This emitter was re-joining what the anatomy layer had taken apart. Where those
  keys are present they are used verbatim; `strain/muscle-strain` carries only
  `:name` forward, so for a strain record the pair is recovered by splitting the
  name.

  THE STRING FALLBACK IS GONE (2026-09-07). Until then, a record without `:group`
  had its pair recovered by looking for a `/` in `:name` — a parser for a grammar
  nothing declared, which silently produced `[name :midline]` for any instance
  whose name happened not to contain one. `strain/muscle-strain` was the only
  producer that needed it, because it dropped the two keys its input already
  carried; it now carries them, so the fallback has no callers and every way of
  reaching it would be a bug.

  It REFUSES rather than guessing. A record that reaches here without `:group` is
  a producer that has not been updated — and there is one in the repo:
  `suji.cells.strain-accumulate.state-machine` builds the same published
  `strainReport` record with bare keys, no side, and no `:group`. Its `solve`
  throws today (R0 scaffold) so it is not a live producer, but if it becomes one
  this must say so rather than emit a plausible record with the side guessed."
  [m]
  (when-not (:group m)
    (throw (ex-info "record has no :group; a muscle instance must carry the structure and the side as separate facts"
                    {:type :value-error :missing :group :name (:name m)})))
  [(kw-str (:group m)) (kw-str (name (:side m)))])

(def ^:private omit
  "Marker for a field that is NOT EMITTED, as distinct from a field emitted with a
  stand-in value. See `ordered-datom`."
  ::omit)

(defn- ordered-datom
  "Build a datom from ordered [k v] pairs, dropping any pair whose value is `omit`.

  `array-map` and not `assoc`: a PersistentArrayMap promotes to a hash map on the
  ninth `assoc` and silently loses insertion order, and `render-edn` renders the
  map in iteration order. The strain datom is now long enough to cross that line."
  [pairs]
  (apply array-map (mapcat identity (remove #(= omit (second %)) pairs))))

(defn body-datom [body-id total-mass-kg stature-m]
  (array-map
   ":body/id" body-id
   ":body/total-mass-kg" (math/round-to total-mass-kg 2)
   ":body/stature-m" (math/round-to stature-m 3)
   ":body/representative" true))

(defn scenario-datoms
  "All datoms for one workstation scenario: posture + loads + tensions + strains."
  [result body-id idx]
  (let [pid (str "p-" (:workstation result))
        p (:posture result)
        cerv (get-in result [:loads :cervical])
        posture-d (array-map
                   ":posture/id" pid ":posture/body" body-id
                   ":posture/workstation" (:workstation result)
                   ":posture/head-flex-deg" (math/round-to (:head-flexion-deg p) 2)
                   ":posture/trunk-flex-deg" (math/round-to (:trunk-flexion-deg p) 2)
                   ":posture/shoulder-flex-deg" (math/round-to (:shoulder-flexion-deg p) 2)
                   ":posture/arms-supported" (:arms-supported p)
                   ;; THE SUPPORT MODE IS PART OF THE POSTURE'S IDENTITY, not a note
                   ;; about it. Two postures with these same upper-body angles load
                   ;; the hip, knee and ankle by a factor of about thirty apart
                   ;; depending on it, so a datom log without it records two
                   ;; different bodies as the same one.
                   ":posture/support" (str (posture/support-mode p))
                   ":posture/hip-flex-deg" (math/round-to (or (:hip-flexion-deg p) 0.0) 2)
                   ":posture/knee-flex-deg" (math/round-to (or (:knee-flexion-deg p) 0.0) 2)
                   ":posture/ankle-dorsiflex-deg" (math/round-to (or (:ankle-dorsiflexion-deg p) 0.0) 2)
                   ":posture/as-of" idx)
        cerv-d (array-map
                ":load/id" (str pid "-load-cerv") ":load/posture" pid
                ":load/joint" ":cervicothoracic"
                ":load/moment-nm" (math/round-to (:extensor-moment-nm cerv) 4)
                ":load/compressive-kgf" (math/round-to (:compressive-load-kgf cerv) 2)
                ":load/mult-vs-head" (math/round-to (:multiplier-vs-head cerv) 2))
        joint-ds (->> (get-in result [:loads :joints])
                      (remove #(= (:joint %) "cervicothoracic"))
                      (mapv (fn [j]
                              (cond-> (array-map
                                       ":load/id" (str pid "-load-" (:joint j))
                                       ":load/posture" pid
                                       ":load/joint" (or (joint-kw (:joint j))
                                                         (str ":" (:joint j)))
                                       ":load/moment-nm" (math/round-to (:moment-nm j) 4))
                                ;; the lower limb carries one more quantity than a
                                ;; moment, and it is the one the support mode decides
                                (:supported-weight-n j)
                                (assoc ":load/supported-weight-n"
                                       (math/round-to (+ (:left (:supported-weight-n j))
                                                         (:right (:supported-weight-n j)))
                                                      2))))))
        muscle-ds (mapv (fn [t]
                          ;; a REFUSED muscle has no force and no %MVC; rounding
                          ;; a nil threw here the moment a refusal could reach
                          ;; this far. The datom carries the reason instead —
                          ;; omitting the muscle would make an unanswered load
                          ;; indistinguishable from an absent one.
                          ;; branch on whether the number is THERE, not on why
                          ;; it is not — a ligament has no %MVC and is not refused
                          (let [[grp side] (instance-group+side t)
                                has-mvc? (muscle/numeric-mvc? t)]
                            (ordered-datom
                             [[":muscle/id" (str pid "-musc-" (:name t))]
                              [":muscle/posture" pid]
                              [":muscle/group" grp]
                              [":muscle/side" side]
                              [":muscle/force-n" (if has-mvc? (math/round-to (:force-n t) 2) omit)]
                              [":muscle/mvc-pct" (if has-mvc? (math/round-to (:mvc-pct t) 2) omit)]
                              ;; `:none` is not a refusal — it is a LIGAMENT, which
                              ;; cannot contract and so has no maximum voluntary
                              ;; contraction to be a fraction of. The lexicon says so.
                              [":muscle/refused" (if has-mvc? omit
                                                     (if (:refused t)
                                                       (kw-str (name (:refused t)))
                                                       ":none"))]
                              [":muscle/antagonist" (if has-mvc? omit (boolean (:antagonist? t)))]])))
                        (:tensions result))
        strain-ds (mapv (fn [st]
                          ;; THE SENTINEL IS GONE (2026-09-07). Until today both of
                          ;; these fields wrote `-1.0` when there was no number, and
                          ;; `:strain/endurance-min` wrote it for TWO OPPOSITE
                          ;; reasons: the model refused this muscle (no endurance to
                          ;; report) and the load is below the endurance floor, where
                          ;; the model returns ∞ (endurance effectively unlimited —
                          ;; the SAFEST case). Measured on the reference scenarios:
                          ;; 101 of 144 were the ∞ case and 28 were the refusal, and
                          ;; both were the same -1.0. A consumer sorting the column
                          ;; numerically puts the least-loaded muscles at the bottom
                          ;; next to the ones nobody solved — the identical failure
                          ;; the browser band function had when a nil fell into the
                          ;; LOWEST band. `-1.0` in `:strain/stiffness` was worse
                          ;; still: the lexicon declares that field `minimum 0,
                          ;; maximum 1`, so the sentinel was out of its own contract.
                          ;;
                          ;; A number is emitted only when there IS one. Which kind
                          ;; of absence it is goes in `:strain/endurance-limit`, a
                          ;; vocabulary term, where it cannot be sorted or averaged;
                          ;; `:strain/band` already did this for stiffness with its
                          ;; `:not-computed` value, and this is the same idiom.
                          (let [[grp side] (instance-group+side st)
                                mins (:endurance-minutes st)
                                unbounded? (and mins (math/infinite? mins))
                                finite? (and mins (not unbounded?))
                                stiff (:stiffness-index st)]
                            (ordered-datom
                             [[":strain/id" (str pid "-strain-" (:name st))]
                              [":strain/posture" pid]
                              [":strain/group" grp]
                              [":strain/side" side]
                              [":strain/session-min" (:session-minutes st)]
                              [":strain/endurance-limit" (cond finite? ":finite"
                                                               unbounded? ":unbounded"
                                                               :else ":not-computed")]
                              [":strain/endurance-min" (if finite? (math/round-to mins 2) omit)]
                              ;; the caveat travels with the number, the way
                              ;; `strain/endurance` and the analyze report already
                              ;; carry it: 6 of the 15 finite endurance times on the
                              ;; reference scenarios are extrapolated below the
                              ;; fitted range, and nothing in a bare number says so.
                              [":strain/endurance-position" (if-let [p (:endurance-position st)]
                                                              (kw-str (name p))
                                                              omit)]
                              [":strain/stiffness" (if stiff (math/round-to stiff 4) omit)]
                              [":strain/band" (kw-str (strain/stiffness-band stiff))]
                              [":strain/saturated" (boolean (:saturated? st))]
                              [":strain/as-of" idx]])))
                        (:strains result))]
    (-> [posture-d cerv-d]
        (into joint-ds)
        (into muscle-ds)
        (into strain-ds))))

(defn results-to-datoms
  "Project a full analyze run into a flat vector of kotoba Datoms (body shared)."
  ([results] (results-to-datoms results 70.0 1.70 "ref-adult-70-170"))
  ([results total-mass-kg stature-m body-id]
   (into [(body-datom body-id total-mass-kg stature-m)]
         (mapcat (fn [[idx r]] (scenario-datoms r body-id idx))
                 (map-indexed vector results)))))

;; --- the published contract --------------------------------------------------
;;
;; `data/lex/*.edn` are the PUBLISHED records. This emitter is their only producer,
;; and until 2026-09-07 nothing in the repo compared the two: the charter test
;; checked a lexicon enum against a five-element set typed into the test file, so
;; the emitter could grow from 5 muscle groups to 26 (48 instances), start writing a
;; `:not-computed` band the enum did not list, and stop writing two `required`
;; fields, with every test green. A snapshot test cannot detect drift between a
;; contract and its producer, because the producer is not one of its inputs.
;;
;; The binding below is what makes the real check possible: which lexicon each
;; emitted datom belongs to, and which declared property each emitted attribute is
;; the datom projection of. It is data and not a naming convention on purpose —
;; `:body/id` → `bodyId` but `:posture/body` → `bodyId` too, and `:strain/session-min`
;; → `sessionMinutes`, so no mechanical camel-casing gets all of them right, and one
;; that got most of them right would fail silently on the rest.

(def lexicon-bindings
  "datom kind → {:lexicon <data/lex stem> :attrs {<emitted attribute> <lexicon property>}}.

  Every attribute this namespace can emit appears here exactly once. An attribute
  absent from its kind's map is a violation, not a pass — see `validate-datoms`."
  {"body"
   {:lexicon "bodyModel"
    :attrs {":body/id" :bodyId ":body/total-mass-kg" :totalMassKg
            ":body/stature-m" :statureM ":body/representative" :representative
            ":body/encrypted-cid" :encryptedPayloadCid}}
   "posture"
   {:lexicon "postureScenario"
    :attrs {":posture/id" :postureId ":posture/body" :bodyId
            ":posture/workstation" :workstation
            ":posture/head-flex-deg" :headFlexDeg ":posture/trunk-flex-deg" :trunkFlexDeg
            ":posture/shoulder-flex-deg" :shoulderFlexDeg
            ":posture/arms-supported" :armsSupported ":posture/support" :support
            ":posture/hip-flex-deg" :hipFlexDeg ":posture/knee-flex-deg" :kneeFlexDeg
            ":posture/ankle-dorsiflex-deg" :ankleDorsiflexDeg ":posture/as-of" :asOf}}
   "load"
   {:lexicon "jointLoad"
    :attrs {":load/id" :loadId ":load/posture" :postureId ":load/joint" :joint
            ":load/moment-nm" :momentNm ":load/compressive-kgf" :compressiveKgf
            ":load/mult-vs-head" :multVsHead
            ":load/supported-weight-n" :supportedWeightN}}
   "muscle"
   {:lexicon "muscleTension"
    :attrs {":muscle/id" :muscleId ":muscle/posture" :postureId
            ":muscle/group" :group ":muscle/side" :side
            ":muscle/force-n" :forceN ":muscle/mvc-pct" :mvcPct
            ":muscle/refused" :refused ":muscle/antagonist" :antagonist}}
   "strain"
   {:lexicon "strainReport"
    :attrs {":strain/id" :strainId ":strain/posture" :postureId
            ":strain/group" :group ":strain/side" :side
            ":strain/session-min" :sessionMinutes
            ":strain/endurance-limit" :enduranceLimit
            ":strain/endurance-min" :enduranceMinutes
            ":strain/endurance-position" :endurancePosition
            ":strain/stiffness" :stiffnessIndex ":strain/band" :band
            ":strain/saturated" :saturated ":strain/as-of" :asOf}}})

(defn datom-kind
  "The kind of a datom, from its `:…/id` attribute. `nil` if it has none — a datom
  with no identity cannot be checked against a record contract, and saying so is
  better than skipping it silently.

  The leading colon is required, not stripped optimistically: `suji.cells.
  strain-accumulate.state-machine` emits a SECOND projection of the same
  `strainReport` record with bare keys (`\"strain/id\"`), and stripping the first
  character regardless turned that into the kind `\"train\"` — a wrong answer in
  the violation report rather than an honest `nil`."
  [d]
  (some (fn [k]
          (when (and (str/starts-with? k ":") (str/ends-with? k "/id"))
            (subs k 1 (- (count k) 3))))
        (keys d)))

(defn- type-ok? [declared v]
  (case declared
    "string" (string? v)
    "number" (number? v)
    "integer" (integer? v)
    "boolean" (boolean? v)
    true))

(defn validate-datoms
  "Check emitted datoms against the published lexicon records. Returns a vector of
  violation maps (empty = conformant); never throws on a bad datom, because a
  validator that dies on the first problem reports one of them.

  `records` is {<lexicon stem> <the :main :record map>} — the caller supplies it,
  so this function stays portable and the file reading stays on the host that has
  a filesystem.

  WHAT IT CHECKS, and why each one is a defect this repo actually shipped:

  - `:undeclared-property` — an attribute whose lexicon property is not declared.
    Every record sets `additionalProperties false`, i.e. claims such a field is
    STRUCTURALLY unrepresentable; four of them were being emitted anyway.
  - `:unbound-attribute` — an emitted attribute with no entry in `lexicon-bindings`.
    Without this, adding an attribute and forgetting the binding would make the
    check quietly stop covering it, which is the failure mode this whole file is
    about.
  - `:missing-required` — a `required` property no datom of that kind carries.
    `mvcPct` and `stiffnessIndex` were both required and both routinely absent.
  - `:value-not-in-enum` — the drift the charter test was meant to catch and could
    not, because it never looked at an emitted value.
  - `:type-mismatch`, `:below-minimum` / `:above-maximum` — the `-1.0` stiffness
    sentinel was outside the field's own declared `[0,1]`."
  [datoms records]
  (into []
        (mapcat
         (fn [d]
           (let [kind (datom-kind d)
                 binding (get lexicon-bindings kind)
                 rec (get records (:lexicon binding))
                 props (:properties rec)
                 base {:kind kind :id (get d (str ":" kind "/id"))}]
             (cond
               (nil? kind) [(assoc base :violation :no-identity-attribute :datom d)]
               (nil? binding) [(assoc base :violation :unknown-kind)]
               (nil? rec) [(assoc base :violation :lexicon-not-supplied
                                  :lexicon (:lexicon binding))]
               :else
               (concat
                ;; every emitted attribute is bound, declared, and in contract
                (mapcat
                 (fn [[a v]]
                   (let [prop (get (:attrs binding) a)
                         spec (get props prop)
                         at (assoc base :attribute a :property prop :value v)]
                     (cond
                       (nil? prop) [(assoc at :violation :unbound-attribute)]
                       (nil? spec) [(assoc at :violation :undeclared-property)]
                       :else
                       (cond-> []
                         (not (type-ok? (:type spec) v))
                         (conj (assoc at :violation :type-mismatch :expected (:type spec)))
                         (and (:enum spec) (not (contains? (set (:enum spec)) v)))
                         (conj (assoc at :violation :value-not-in-enum :enum (:enum spec)))
                         (and (:minimum spec) (number? v) (< v (:minimum spec)))
                         (conj (assoc at :violation :below-minimum :minimum (:minimum spec)))
                         (and (:maximum spec) (number? v) (> v (:maximum spec)))
                         (conj (assoc at :violation :above-maximum :maximum (:maximum spec)))))))
                 d)
                ;; every required property is actually there
                (let [emitted (into #{} (keep (:attrs binding)) (keys d))]
                  (for [r (:required rec)
                        :let [rk (keyword r)]
                        :when (not (contains? emitted rk))]
                    (assoc base :violation :missing-required :property rk))))))))
        datoms))

(defn emitted-vocabulary
  "The distinct values the emitter actually produced for one attribute, as a set.
  The other half of the contract check: an enum that admits everything emitted can
  still be a stale superset, and for the closed vocabularies (`group`, `side`,
  `joint`, `band`, `enduranceLimit`) the lexicon should list exactly what exists."
  [datoms attr]
  (into #{} (keep #(get % attr)) datoms))

(defn- py-float-repr
  "Python str(float): shortest round-trip decimal. For the magnitudes this actor emits
  (HALF_EVEN-rounded to ≤4 decimals, 1e-4 ≤ |v| < 1e16, or 0.0) Python never uses
  scientific notation, so expand Double/toString's shortest digits to plain decimal,
  preserving the always-present `.0` on integral floats."
  [d]
  #?(:clj
     (let [s (Double/toString d)]
       (if (or (str/includes? s "E") (str/includes? s "e"))
         ;; expand the shortest repr to plain form (no scientific in our range)
         (let [bd (java.math.BigDecimal. s)
               plain (.toPlainString bd)
               plain (if (str/includes? plain ".")
                       (let [stripped (str/replace plain #"0+$" "")]
                         (if (str/ends-with? stripped ".") (str stripped "0") stripped))
                       (str plain ".0"))]
           plain)
         s))
     :cljs (str d)))

(defn- fmt
  "Port of _fmt: bool → true/false; \":…\" kept literal; other string → quoted; double →
  Python str() shortest repr; else str()."
  [v]
  (cond
    (true? v) "true"
    (false? v) "false"
    (and (string? v) (str/starts-with? v ":")) v
    (string? v) (str "\"" v "\"")
    (double? v) (py-float-repr v)
    :else (str v)))

(defn render-edn
  "Render datoms as seed-style EDN (one map per line)."
  [datoms]
  (let [lines (transient
               [";; suji (筋) analyze → kotoba Datoms (G9; generated by src/suji/methods/datoms.cljc)."
                ";; NON-DIAGNOSTIC (G1): mechanical attributes only. Self-referenced (G3). 非終末論."
                "["])]
    (doseq [d datoms]
      (let [body (str/join " " (map (fn [[k v]] (str k " " (fmt v))) d))]
        (conj! lines (str " {" body "}"))))
    (conj! lines "]")
    (str/join "\n" (persistent! lines))))

#?(:clj
   (defn -main
     [& _argv]
     (let [results (analyze/analyze-all 70.0 1.70 120.0)
           datoms (results-to-datoms results)
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getParentFile .getParentFile))
                    (io/file "."))
           out-dir (io/file here "out")
           path (io/file out-dir "posture-datoms.edn")]
       (.mkdirs out-dir)
       (spit path (str (render-edn datoms) "\n"))
       (println (str "[wrote " (count datoms) " datoms → " path "]"))
       0)))
