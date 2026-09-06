# suji 筋 — musculoskeletal posture-load biomechanics simulator

> **ADR-2606061900 · R0 · Tier-B · L4 Care (instrument/simulation layer)**
> DID: `did:web:etzhayyim.com:actor:suji`
> 筋 = muscle / sinew **and** line-of-force / reasoning — the double meaning of a force-balance engine.

Answers **「ノートパソコンの姿勢が人体にどういった緊張・強張りを作るか」** — what tension (緊張)
and stiffness (強張り) a laptop posture builds in the body — with **runnable, validated physics**,
using `kami-engine` (the kami-genesis articulation solver) and `kotoba` (the Datom log) under the
constitutional **non-diagnostic** boundary.

It is the **physics-simulation sibling of `kizashi` 兆** (which *senses* the body) and **upstream of
`mitate`** (which *diagnoses*):

```
kizashi senses  →  suji simulates the loads  →  mitate diagnoses  →  iyashi treats
```

## What it computes

```
laptop workstation ──▶ posture (joint angles)        posture.cljc
     OR a standing   ──▶ + SUPPORT MODE                posture.cljc  ← seated or standing:
        posture              (seated / standing)                       what is under the pelvis
                   ──▶ forward kinematics (3-D)       pose.cljc     ← world-space chain,
                                                                      shared by physics + renderer
                                                                      head · trunk · both arms
                                                                      · BOTH LEGS · the ground
                   ──▶ static inverse dynamics        load.cljc     ← kami-genesis PlanarChain
                       (RNEA gravity term)                          Featherstone statics
                   ──▶ hip / knee / ankle moment      load.cljc     ← + the ground reaction,
                       + the weight each transmits                    at the line of gravity
                   ──▶ cervical compressive load      load.cljc     ← VALIDATED vs Hansraj 2014
                   ──▶ muscle moment arms             attachment.cljc ← from the anatomy,
                       (geometric, angle-dependent)                    not a constant table
                   ──▶ load sharing between synergists recruit.cljc  ← Crowninshield-Brand
                       (minimum cubed stress, closed form)              min sum (F/Fmax)^3
                   ──▶ muscle %MVC  (緊張 / tension)   muscle.cljc
                   ──▶ stiffness index (強張り)        strain.cljc   ← Rohmert sustained dose
                   ──▶ A/B/C ergonomic comparison      analyze.cljc
```

The **skeleton** is a sagittal articulated segment chain — head → cervical → thorax → lumbar,
with an arm branch (shoulder → elbow → wrist) and, since 2026-09-07, a leg branch
(hip → knee → ankle) on each side — built from Winter (4e) Table 4.1 anthropometry — exactly the `PlanarChain` articulation kami-genesis
solves (ADR-2605311500/1800). The **bones load** is the static special case of Featherstone RNEA
(the gravity term), computable in stdlib and independently checkable. The **muscles** are a Hill-type
moment-arm model (force = moment / arm; %MVC = force / F_max). **強張り** is the Rohmert
sustained-isometric dose accumulated over a work session.

### The answer (`bb -m suji.methods.analyze`)

| workstation | head flexion | neck load | ×head-weight | worst-muscle stiffness |
|---|---|---|---|---|
| laptop-on-lap | 44° | **23.6 kgf** | 4.2× | cervical-extensors **1.00** (very-high) |
| laptop-on-desk | 27° | 17.9 kgf | 3.2× | cervical-extensors 1.00 |
| external-monitor + keyboard @ eye level | 5° | **8.1 kgf** | 1.4× | anterior-deltoid **0.05** (low) |

→ raising the screen to eye level cuts the cervical compressive load **−66%** and drops every
muscle to *low* stiffness. (Self-referenced Wellbecoming, G3 — the same body across setups, not a
ranking of people. Mechanism only; a clinician owns any health interpretation.)

## Empirical anchor — Hansraj (2014)

The cervical leg reproduces the published forward-head-posture loads of Hansraj, *Surgical
Technology International* 25 (the "60-lb tech-neck" study): neutral ≈ head weight, rising to ~5× at
60° flexion. `src/suji/methods/test_load.cljc::test_reproduces_hansraj_table` asserts the multipliers track the
published table (0°→1× … 60°→5×) within 10%.

## The lumbar spine against the literature — and it disagrees

The lumbar spine is the most-measured structure in this field, so unlike almost
everything else this actor computes, the per-level profile can be made to answer
to a number somebody actually recorded. `spine/lumbar-cross-check` does that.
**The answer is that the model disagrees with the measurement, and this section
exists to say so rather than to announce a validation.**

### What the measurement is

Wilke, Neef, Caimi, Hoogland & Claes, "New in vivo measurements of pressures in
the intervertebral disc in daily life", *Spine* 1999;24(8):755–762 — a pressure
transducer implanted in the **L4/L5 nucleus of one living volunteer**, 45 years
old, **70 kg, 1.68 m**, disc cross-sectional area **1800 mm²**, telemetered across
a day of ordinary postures. Full text read at
<https://www.fonar.com/pdf/spine_vol_24.No.8.pdf>.

Four of its Table 1 entries are carried in `spine/lumbar-references`. **Only one of
them is comparable**, and the other three say why not:

| Wilke Table 1 (p.757) | pressure | comparable? |
|---|---|---|
| sitting relaxed, without backrest | **0.46 MPa** (0.45–0.50, p.758) | **yes** — p.758 also states the posture: "Relaxed sitting on a stool with a **normally straight back**" |
| sitting with maximum flexion | 0.83 MPa | no — the paper gives the pressure but **not the trunk angle** |
| standing, bent forward | 1.10 MPa | no — same, no angle |
| relaxed standing | 0.50 MPa (0.48–0.50) | no — but **the reason changed on 2026-09-07 and is worth reading.** It used to be "this model cannot stand": no thigh segment, no support mode. It has both now. It still returns **the same 351 N at L4/L5 for standing and for sitting** — measured, not assumed — because in this model sitting and standing differ only BELOW L5/S1, and the lumbar spine cannot tell. What separates Wilke's two figures (0.50 vs 0.46) is pelvic tilt and the lordosis that goes with it, and this model's pelvis does not rotate. A missing segment became a missing degree of freedom; the entry stays refused either way |

Refusing the last three is the point. To compare against "maximum flexion" the
model would have to **choose** a trunk angle, and choosing it is exactly the move
that turns a validation into a fit. Those entries keep the published pressure —
a real number, worth having — and return `:could-not-obtain` with the reason.

### A pressure is not a force

Wilke measured **megapascals in a nucleus**. This model computes **newtons through
a joint**. Getting from one to the other requires a conversion, and the conversion
is a modelling assumption, not a measurement:

> **Nachemson's pressure index.** Nachemson, "Measurement of Intradiscal Pressure",
> *Acta Orthopaedica Scandinavica* XXVIII:269–289 (full text read at
> <https://actaorthop.org/actao/article/download/30762/35650/84307>) measured, in
> vitro, the quotient between the pressure recorded in the nucleus and the pressure
> applied to the whole disc, and named it the pressure index: `I = Pn / (P / Ad)`,
> hence **`force = pressure × disc area ÷ I`** (definition p.282, formula p.285).
> For **normal** lumbar discs Table 5 (p.281) gives **L1 1.6, L2 1.7, L3 1.5,
> L4 1.7** — so **1.5 to 1.7**, and the prose at p.285 uses 1.5.

**It is an assumption.** The index was measured on cadaver discs under pure axial
load; using it on an in-vivo pressure assumes the same proportionality holds in a
living spine that is also being squeezed by its own muscles. Wilke's paper performs
no such conversion and does not endorse one. So the 1.5–1.7 spread is propagated
into the reference's own spread rather than hidden behind the single number, and
`nachemson-pressure-index` is a separate, documented value that a caller can
disagree with by passing a different one.

### The disagreement

Reference, from the two published sources above and nothing else:

```
0.46 MPa × 1800 mm² ÷ 1.5  =  552 N        (the point value)
0.45 MPa × 1800 mm² ÷ 1.7  =  476 N        (low: Wilke's low pressure, Nachemson's high index)
0.50 MPa × 1800 mm² ÷ 1.5  =  600 N        (high: the other way round)
```

**The model reads about 351 N** at that posture on Wilke's own body — **below the
reference's own spread, at roughly two thirds of the measured value** (measured
2026-09-07; the model side moves whenever the muscle set moves, which is why
`spine-test` pins the ratio into 0.5–0.8 rather than to a number, and why you
should ask rather than quote):

```clojure
(spine/lumbar-cross-check)
;; => {:model-force-n … :reference-force-n … :reference-force-range-n […]
;;     :ratio … :within-reference-spread? false :direction :model-below-reference
;;     :validated :reference :model-validated? false}
```

`:validated :reference` is the counterpart of `cervical-cross-check`'s
`:validated :lumped`, and it points the other way: **there the validated side was
this actor's own model; here it is the literature.** The profile is the unvalidated
side in both.

### Why it is short, named rather than fixed

At zero trunk flexion this model's extensor moment is zero, so its tissue term is
**exactly zero** and the whole 351 N is the weight stacked above L4/L5 — nothing
else. A real spine at rest is not unloaded: it has lordosis, resting muscle tone
and intra-abdominal pressure, and `spine.cljc`'s own docstring already says it has
none of the three ("There is no curvature: the model's spine is two straight
segments"). `the-disagreement-is-the-absent-tissue-term-not-the-weight` asserts
that decomposition, so the explanation is a computation rather than a story.

**The model was not tuned to close the gap.** A fudge factor of 1.57 would make
this section read like a validation and would be worth nothing.

### The NIOSH scale

`spine/niosh-compression-comparison` places an L5/S1 force on the published
occupational **design** scale. Not a validation, and not about anybody — it is one
newton compared to another (G1).

NIOSH states these in **kilogram-force**, not newtons. *Work Practices Guide for
Manual Lifting*, DHHS (NIOSH) 81-122, 1981, p.36 (full text read at
<https://stacks.cdc.gov/view/cdc/209417/cdc_209417_DS1.pdf>; scanned, no text
layer): "jobs which place more than **650 kg** compressive force on the low-back
are hazardous to all but the healthiest of workers. In terms of a specification
for design a much lower level of **350 kg** or lower should be viewed as an upper
limit." The familiar **3400 N and 6400 N are those two figures times g**
(3432 N and 6374 N), and the 1993 revision quotes the lower one directly as
"3·4 kN (770 lbs)" and keeps it (Waters, Putz-Anderson, Garg & Fine, *Ergonomics*
1993;36(7):749–776, Table 1 p.751 and §3.4 p.755; full text read at
<https://stacks.cdc.gov/view/cdc/205542/cdc_205542_DS1.pdf>). The labels "action
limit" and "maximum permissible limit" are commonly attached to these two numbers;
the pages read here do not use those words for them, so this repo does not either.

### What could not be obtained

**Schultz, Andersson, Örtengren, Haderspeck & Nachemson (1982), "Loads on the
lumbar spine", *J Bone Joint Surg Am* 64(5):713–720** — the EMG-and-model lumbar
compression estimates. PubMed, Europe PMC, Semantic Scholar and the publisher all
returned a consent page, a navigation shell or a paywall rather than the abstract.
A search engine returned a *paraphrase* of the abstract; **that is not a source
that was read, so none of its numbers appear anywhere in this repo.** The entry is
missing, not filled in.

## Isaac Sim / kami-genesis

`wire/wit/kami-biomech.wit` is the articulation contract a kami-genesis `PlanarChain` / nv-compat
`isaacsim.core.api` `Articulation` would implement; `src/suji/methods/kami_biomech_bridge.cljc` builds the
link/joint/gravity spec and returns the same static joint moments the full RNEA backend would.
**Honest R0**: the `40-engine/kami-engine` submodule is unpopulated here, so this is the WIT
contract + Python reference, not a compiled backend (the `noroshi` pattern). No live actuation — the
body model is passive.

## Constitutional discipline (CRITICAL)

- **G1 NON-DIAGNOSTIC (医師法 §17)** — every output is a *mechanical* quantity (moment, force, %MVC,
  kgf, stiffness dose). No `diagnosis`/`disease`/`prescription`/`treatment`/`condition` field is
  representable in the schema, the lexicons, **or** the `load_solve` cell (`assert_nondiagnostic`
  refuses a clinical key by construction — the nusa/tazuna/kamado pattern). A licensed clinician
  (`mitate`/`iyashi`) owns any diagnosis.
- **G2 simulation-only / not-a-medical-device (薬機法/SaMD)** — no sensing hardware, no biometric
  capture; inputs are posture *parameters* (`kizashi` owns sensing).
- **G3 self-referenced Wellbecoming** — `as-of` stiffness trajectory, same-member comparison only;
  no population ranking (非終末論, no final state).
- **G4 encrypted envelope on real scan** — a body built from a real `kizashi` scan carries 要配慮 PII
  → `encryptedPayloadCid`. suji's own bodies are `:representative` averages.
- **G5 Murakumo-only · G6 no-server-key · G7 sourcing-honest · G8 outward-gated · G9 kotoba-EAVT ·
  G10 anti-pseudoscience** (no 経絡/気/波動 — Hill-model muscles only).

## Layout

```
src/suji/methods/   math · segment · posture · pose · load · muscle · strain · analyze · datoms · kami_biomech_bridge  (+ tests)
data/cells/     segment_build · posture_resolve · load_solve(coded) · strain_accumulate(coded) · ergonomic_compare
data/lex/       bodyModel · postureScenario · jointLoad · muscleTension · strainReport · ergonomicComparison
kotoba/    schema.edn · seed.edn      wit/  kami-biomech.wit      out/  posture-report.md · posture-datoms.edn
```

## Run

`bb` is retired in this workspace (ADR-2607173000); the suite runs on two hosts.

```bash
clojure -M:test                                   # JVM   — 200 tests / 6302 assertions
nbb --classpath src:test scripts/nbb_test.cljs    # cljs  — 182 tests / 1253 assertions
clojure -M:lint                                   # 0 errors (14 pre-existing warnings)
clojure -M -m suji.methods.analyze                # the laptop-posture report
```

**Why two hosts.** Until 2026-09-06 every namespace here was named `.cljc` and four
of them called JVM-only interop (`Math/toRadians`, `Double/POSITIVE_INFINITY`,
`Double/isInfinite`), while the tests referred `clojure.test` directly. The suite was
green and the library did not load in a browser at all — a JVM-only suite returns the
same green for portable `.cljc` and for `.cljc` that only claims to be. The numeric
floor is now `suji.methods.math` and the cljs runner exists to fail. Three test
namespaces read repo files off disk and stay `.clj`, named for what they are.

## Corrections

**Shoulder geometry (2026-09-06).** `shoulder-moment` placed the forearm and hand at
`(90° − elbow-flexion)` from vertical. That is inverted at both ends of the range: a
straight hanging arm came out horizontal, and the 90° elbow of a typing posture came
out vertical. Measured consequence — a straight arm hanging at the side, whose mass is
directly beneath the joint and can therefore exert **exactly zero** moment, reported
**5.15 N·m**; the unsupported laptop-on-lap shoulder moment was understated by 130%
(3.84 → 8.82 N·m). The two supported reference postures are unchanged, because only
the upper arm loads the girdle there. The chain is now placed once by `pose/solve-pose`
and the moment read off it as Σ weight × anterior lever. `pose-test` states the control
that names its own reason: a mass under its joint has no lever.

**The cervical leg is untouched** and still reproduces the Hansraj (2014) table.

**Moment arms, redundancy and refusal (2026-09-06).** Every moment arm used to be a
constant in `muscle/specs`, which asserts that a muscle's leverage does not change
when the joint moves — false for every muscle in the body. `attachment.cljc` places
each muscle's attachments on the bones `pose` placed and derives the perpendicular
distance; the old constants are the calibration anchor (the neutral arms reproduce
them to within 0.2%) and everything away from neutral is now geometry. Measured
consequence: the cervical extensor arm falls from 20.0 mm at neutral to 6.1 mm at
60° of head flexion, so the same neck moment costs three times the muscle force —
which is why laptop-on-lap's cervical extensors read 50 %MVC now and 27 %MVC before.

**Wrapping surfaces (2026-09-06).** A straight chord between two attachment points
can pass through the joint it acts about; the arm goes to zero and the force needed
to hold any moment diverges. Real muscles lie ON the bone and wrap over it, and every
tangent to a circle of radius R is R from its centre — so the arm floors at R instead
of vanishing. The anterior deltoid (humeral head, R = 20 mm) and the cervical
extensors (the cervical column, R = 12 mm) declare one. Measured across 3,240
postures, this removed **every** `:coefficient-below-floor` refusal.

The condition is `sign × straight < R`, not `|straight| < R`. The second looks right
and is not: once the chord swings far enough to the wrong side its magnitude exceeds
R again, wrapping switches off, and the model hands back a straight-line arm with the
sign flipped — the anterior deltoid became an *extensor* at 130° of shoulder flexion.

A straight line goes wrong in two ways, and both are in the tests. It can cross to
the far side of the joint as the joint flexes, at which point the model reports the
extensors as flexors — the cervical group did this at 30° and erector spinae near
55° before the insertions were moved. And its line of action can pass *through* the
joint, where the required force diverges: this model's anterior deltoid does at 90°
of shoulder flexion, because a straight line has no wrapping surface. `recruit`
**refuses** below a stated leverage floor rather than returning the large number.

Upper trapezius and levator scapulae both suspend the girdle, so the equilibrium
does not determine their forces; they used to be assigned by two unrelated
hand-written expressions. `recruit` shares them by minimum cubed stress
(Crowninshield & Brand 1981) in closed form — exact, and it moves when the anatomy
moves. The old trapezius expression also charged the head's extension load a second
time, on top of the cervical group; that term is gone.

**Passive tension (2026-09-06).** A stretched muscle produces force without being
activated and without costing anything metabolically — it is the tissue resisting
being stretched. This actor assigned the whole of every load to active contraction
and therefore overstated the effort of any posture that lengthens a muscle. The
passive term is now subtracted from the load first, through each muscle's own
coefficient, and only the remainder is shared: it is determined by length, so the
criterion must not get to choose it. Passive tissue can carry the whole load, and
then nothing is asked of the contractile machinery at all.

**The posterior ligamentous system (2026-09-06), and flexion-relaxation.** The
previous wave measured its own claim, found it false, and named what was missing;
this adds it. The posterior lumbar band (supraspinous and interspinous ligaments
plus thoracolumbar fascia) and the nuchal ligament are NOT muscles — they cannot
contract, they have no %MVC, and they are slack until the joint has already carried
past them. They are stated as a force at a stretch rather than a cross-section,
because a ligament has no contractile machinery for a specific tension to describe.

**Flexion-relaxation is now reproduced.** Erector spinae active force across trunk
flexion: 473 N at 20°, 267 N at 40°, **0 N at 60°** — while the ligament goes
124 N → 1,068 N → 3,989 N and takes the load. Without ligaments the model reports
3,228 N of active force at 60°, i.e. the muscle working hardest exactly where it is
measured to be working least.

Each structure is calibrated over its OWN stretch range. They do not stretch alike:
60° of trunk flexion takes the lumbar band to 1.25× its neutral length, while 15° of
head flexion already takes the short nuchal ligament to 1.18× and 60° to 1.60×.
Calibrating both at 1.25× put the entire cervical load on a ligament and reported
the extensors doing nothing, in the one posture this actor exists to describe.

Beyond the calibrated range the force is CLAMPED and `at-limit?` says so —
extrapolating the exponential gave the nuchal ligament 52,312 N at an ordinary
forward-head posture. A real ligament stiffens further and then fails; this model
has no failure law and holds the last value it can defend.

⚠ **A muscle's own passive tension is not, by itself, flexion-relaxation** — and the first draft of the
docstring said it was. Measured after writing it: at 60° of trunk flexion the
erector spinae reaches 1.27× its optimal length and its own passive tissue supplies
151 N of the 3,379 N the posture demands, about 4%. Real flexion-relaxation is the
posterior ligamentous system taking over — supraspinous and interspinous ligaments,
thoracolumbar fascia — and those are separate structures this model does not have.
`the-passive-term-is-small-here-and-the-model-says-so` pins the fraction, so making
the passive term large enough to explain the phenomenon fails a test and has to be
argued for.

A measured side effect: the per-level spinal profile's attachment steps are GONE.
A stretched muscle now contributes across levels where it previously contributed
exactly zero. `attachment-steps` is still there and still correct — what changed is
that this model no longer produces the artefact, and its test now checks the
detector on constructed input rather than asserting the model still has one.

**%MVC's denominator is no longer a constant (2026-09-06).** It divided by
PCSA × specific tension, which assumes a muscle can produce its maximum at every
length. It cannot — at half or one and a half times its optimal length it produces
nothing — and the posture decides its length. The optimal length is now DERIVED,
as each muscle's line length at anatomical neutral, from the same geometry as the
moment arms; the available force is that peak scaled by the Hill force–length
parabola; and `recruit` weighs available force, so load is not handed to a muscle
too short to use its cross-section.

Measured where it is worst: at 90° of shoulder flexion with a straight elbow the
anterior deltoid sits at 0.75 of its optimal length. The constant denominator
reported **83.4 %MVC**; the length-aware one reports **111.6 %MVC** — above maximum
voluntary contraction, which is a different statement about that posture and the
true one. The old number understated the effort by 28 points at exactly the
posture where the muscle is most compromised.

`force-length-factor` deliberately duplicates
`kotoba.biomech.muscle/force-length-factor`, and says so: biomech's deps.edn pulls
kotoba-lang/fea, kotoba-lang/kami-vehicle and kotoba-lang/kami-engine-cfd — three
solver repos — and this actor compiles into a browser bundle. `force-length-test`
pins the values so the two can be compared by hand; it cannot notice biomech
changing, and that is the price.

**Superseded on 2026-09-07 by the lower limb: the hip is solved.** The paragraph
below was true when it was written, and its last sentence is why it is still here —
it named the gap as an absent segment rather than as a decision, and the segment
arrived. `every-placed-joint-except-the-hip-has-an-equilibrium` is now
`every-placed-joint-has-an-equilibrium-or-is-named-as-a-gap`, and its pending set
is empty.

**Every placed joint is solved, except the hip on purpose (2026-09-06).** The
wrist was the last one the kinematics placed and the kinetics did not, and it had
the opposite problem from the elbow: no wrist ANGLE existed, so the hand continued
the forearm rigidly and its muscles' moment arms could not change with a joint that
could not move. `:wrist-extension-deg` is an input now, the workstation model
derives it from keyboard height, and wrist extensors and flexors share the
equilibrium — the extensors carrying it in every palm-down posture, which is the
group a typist complains about.

The hip stays unsolved deliberately: this is a SEATED model whose base is the
pelvis, and a hip moment would need a thigh segment `segment/build-body` does not
have. An absent segment, not a forgotten equilibrium, and
`every-placed-joint-except-the-hip-has-an-equilibrium` asserts the whole claim
against the data rather than a comment.

**A retinaculum is not a wrapping surface.** A surface the tendon passes over puts
a floor under the moment arm and lets the chord win where the chord gives more; a
retinaculum straps the tendon against the bone, so the arm is pinned in BOTH
directions. That is why the wrist's arms are near-constant through its range where
the elbow's are not. Without the distinction the wrist extensor's arm grew from
11 mm to 29 mm across 45° of extension, which no retinaculum would allow.

**The elbow has an equilibrium (2026-09-06).** `pose` has placed an elbow since
the pose layer existed and no muscle acted about it — the forearm and hand hung
off a joint whose equilibrium nobody solved, as though it were welded, while a
typing posture holds them out at 90° all day. Biceps brachii, brachialis and
triceps brachii now share it. Every joint the kinematics places except the wrist
now has kinetics, and `the-elbow-has-an-equilibrium-at-all` asserts it by looking
at what the muscle set actually acts about rather than at a comment.

**The spine is resolved level by level (2026-09-06).** This actor used to report
ONE spinal number, the lumped cervical compressive load. `spine.cljc` computes, at
each of ten intervertebral levels, the weight above it plus the axial component of
every muscle force crossing it, and divides by that level's disc area — because a
disc's tolerance is a stress, and 500 N through a cervical disc and 500 N through
a lumbar one are not the same event.

The tissue term dominates: at L5/S1 in the laptop-on-lap posture it is 605 N —
483 N of muscle and 122 N of ligament — against 346 N of weight, because an
extensor works at a short moment arm and all of the force it needs presses the
joint together. (Measured 2026-09-07. This paragraph said "the muscle term … is
612 N", which was the figure from before the tissue term was split; the split
made the sentence name the wrong structure as well as the wrong number.)

⚠ **The level profile is NOT validated and disagrees with the leg that is.** At the
cervical spine it disagrees with the Hansraj-calibrated lumped model by roughly a
factor of two, because it uses the muscle's geometric moment arm rather than an
effective lever fitted to the published table. At the lumbar spine it now has a
published measurement to answer to, and it disagrees with that too — in the other
direction. See **The lumbar spine against the literature** below.

**The ratio is not written here on purpose.** It moves whenever the muscle set
moves — it was 2.24 when the level profile landed and 2.44 after the ligaments —
and a number in a standing document gets quoted with its date dropped. Ask for it:

```clojure
(spine/cervical-cross-check body posture tensions (:cervical loads))
;; => {:level-force-n … :lumped-force-n … :ratio … :validated :lumped}
```

It names which of the two is validated, so the profile cannot be read as though it
inherited the validation. It did not.

`attachment-steps` reports the levels where the muscle term drops to zero between
neighbours. A real muscle attaches over a range of vertebrae; this one attaches at
a point, so the force steps rather than tapering. Naming the steps is the
difference between a reader seeing an artefact and a reader believing a spine.

**The frontal plane is CARRIED (2026-09-06).** Six muscle groups were added for
it — middle deltoid and latissimus dorsi at each shoulder, quadratus lumborum and
obliques at L5/S1, scalenes at C7, plus middle trapezius for the girdle in postures
where the upper fibres' occipital origin has swung below the acromion. Swept over
5,760 postures spanning the ranges this app's sliders offer, **every one now has
its whole load placed**; before, 3,264 of them had a load nobody could carry.

Extreme postures outside that range (trunk 60° and head 60° and 40° of bend and 90°
of abduction at once) still run out, and the model says which muscle and why rather
than producing a number.

A mirror-paired task refuses its antagonist by construction: exactly one side
resists at any instant and a static optimum does not co-contract. That is reported
as `:antagonist?` and is NOT counted as an unanswered load — counting it as one
made every frontal-plane posture look unanswerable the moment these muscles landed.

**%MVC above 100 is reported, not clamped.** It says the posture asks the modelled
muscles for more force than they can produce, which means a body holding it is
being held by something this model does not contain — ligaments, passive tissue,
flexion-relaxation. Clamping would erase the finding.

### Superseded: the frontal plane as unassigned load `pose` accepts
abduction, lateral bend and head rotation; `load/frontal-moments` computes the
moments they create. This actor has no frontal-plane musculature — no scalenes,
no latissimus, no gluteus medius — so there is nobody to assign them to, and
`muscle/tension-summary` reports `:unassigned-frontal-nm` and refuses to call the
answer complete. 40° of shoulder abduction at a desk creates 7.9 N·m that nothing
in this model carries. The alternative, which this actor did until 2026-09-06, is
to accept the input, move the picture with it, and quietly leave the load out of
every number on the page.

Kinds of incompleteness, reported separately because they have different fixes:

| | what it means | what fixes it |
|---|---|---|
| `:coefficient-below-floor` | real leverage, too little for a straight line to resolve | a wrapping surface |
| `:acts-the-wrong-way` | the line would ADD to the load at this posture | nothing here — the posture has left the range this line represents |
| `:no-line-of-action` | degenerate geometry | the attachment data |
| `:unassigned-frontal-nm` | no muscle in this model can carry that load at all | more muscles |

The second used to be reported as the first, because `suspension-effectiveness`
clamped its cosine at zero — so a muscle pulling the girdle *down* was described as
"below the leverage floor — a straight-line model has no wrapping surface here",
which sends the reader to a repair that cannot help.

⚠ **This section used to end by claiming the remaining refusals are all of that
kind. Do not read it that way** — it was a count of one sweep on one day, and two
waves have landed since. The kinds are a taxonomy; which of them a given posture
produces is a measurement, and the way to get it is to run the posture and read
`muscle/tension-summary`.

There is one entry that is NOT a refusal and belongs in the table anyway:

| `:two-joint-unfed-nm` | a two-joint muscle is pulling on a second joint whose equilibrium was not told about it | a solver with more than one equality constraint — see the lower limb, below |

**The stiffness index saturates and now says so.** It is mathematically in [0,1) but
reaches exactly 1.0 in double precision once the dose passes ~37 — roughly 50 %MVC
held for two hours, which is an ordinary posture. Two postures, one twice as bad as
the other, both read 1.00. `:saturated?` marks them.

**The lower limb (2026-09-07), and the support mode that is the whole of it.**
Thigh, shank and foot, bilateral, with muscles at the hip, knee and ankle. The
model covered head, neck, trunk, shoulder, elbow and wrist; for standing, and for
getting out of a chair, the joints it did not have are the ones carrying the most.

The load at a lower-limb joint does not follow from the posture. Those three joints
sit BETWEEN the mass and the thing holding the mass up, so what they carry depends
on what the body is resting on. Seated, the chair takes the trunk through the
ischial tuberosities and that load never reaches them: each carries only what hangs
below it, a few kilograms of limb. Standing, there is nothing under the pelvis and
each carries everything above it. Measured on a 70 kg body: **333 N through a
standing ankle against 10 N through a seated one**, and 301 N against 42 N at the
knee.

In the statics the two modes differ by **exactly one force**. The free body below
each joint holds the same segments either way; standing adds the ground reaction,
which is about thirty times the weight of the foot it acts on. It is added by a
`cond->` and not by a second code path, and a test asserts that standing minus
seated IS that term and nothing else, at all three joints. A model that silently
put body weight through a seated knee would report a plausible 300 N at every desk
posture it has ever been asked about, and the only way to notice would be to
already know the answer.

**Where the ground pushes is not a parameter.** Static equilibrium puts the centre
of pressure under the line of gravity, so `pose/centre-of-pressure` computes it
there rather than taking it as a fraction along the foot. Stating it as anatomy
looks like more anatomy and is less physics: it would let the model report a
plantarflexor moment for a body that, on its own numbers, is toppling. The model
does not refuse such a posture — a body outside its base of support is a step, or a
fall — but it reports `:cop-inside-base?`, so nobody reads the statics of a posture
nobody can hold as the statics of one somebody is holding.

The consequence is the checkable prediction: **in quiet standing soleus works and
is never silent.** The line of gravity passes 3.6 cm anterior to the ankle, the
plantarflexors hold 12 N·m per ankle, and soleus reads **5.1 %MVC / 159 N**. The
control is the perfect vertical stack, where soleus reads **0 N** — because a body
balanced exactly over its ankles asks nothing of its calves. Without that control
the test would pass against a model that gave soleus a floor, which is the cheapest
way to make the number non-zero and the one that would mean nothing. (Verified by
putting a floor in: the control fails, at 152 N.)

**PCSA is measured here, and the specimens were 83 years old.** Ward, Eng,
Smallwood & Lieber (2009), *Clin Orthop Relat Res* 467(4), Table 3, from 21 human
lower extremities — the first values in this actor that are not representative.
Where a group is several of their muscles the sum is written out: vasti =
35.1 + 20.6 + 16.7 = 72.4 cm², hamstrings = 18.4 + 11.3 + 4.8 = 34.5, iliopsoas =
7.7 + 9.9 = 17.6. Their age makes every lower-limb %MVC here an **overstatement**
of a young body's effort. That is the right direction to be wrong in for a model
that reports load, and it is stated rather than corrected by a factor nobody
measured. The moment arms are representative and calibrated as the upper limb's
were.

**The two-joint muscles are the part this model cannot do.** Rectus femoris, the
hamstrings and gastrocnemius each span two joints, so they appear in two equilibria
at once and the two are coupled. `recruit`'s Crowninshield–Brand form is the closed
solution for ONE equality constraint, and there is no closed form of that shape for
two. So each is solved where it is the primary actor, and the moment it is
simultaneously exerting at its other joint is computed and reported — per muscle as
`:secondary-moment-nm`, per joint as `tension-summary`'s `:two-joint-unfed-nm`. In
the deep squat that is 4.2 N·m of hip flexion the hip's equilibrium was never told
about. A test asserts it is non-zero somewhere, because a reported approximation
that is always zero means a coupled model and an uncoupled one produce identical
output.

Which joint is primary is anatomy, not preference. The hip has two one-joint
muscles and fills both sides of its equilibrium. The knee has an extensor and **no
one-joint flexor**, because the body does not have one of any size — so the
hamstrings go to the knee.

**The patella is a wrapping surface**, the existing kind: a sesamoid the extensor
tendon passes over, floor and not pin, radius 42 mm. Adding the hip turned up the
same defect one joint up. Gluteus maximus's straight chord falls from 60 mm of
extension arm at neutral through **zero near 55° of flexion** and is +32 mm at 85°,
so a straight-line model reports the principal hip extensor as a **flexor** in the
posture the muscle exists for; nobody carried the squat's hip moment and `recruit`
declined the equilibrium. It wraps the ischium now. Tibialis anterior takes the
other kind — the extensor retinacula pin its arm at 35 mm where the bare chord
would give 53, which is exactly the bowstringing a retinaculum prevents.

Measured in the deep squat: vasti **26.6 %MVC**, gluteus maximus **55.5 %**; and in
quiet standing the vasti are reported as the **antagonist with no %MVC at all**,
because the ground reaction passes in front of the knee there. Swept over 240
lower-limb postures, every load is carried.

**A defect the legs exposed, in `spine.cljc`.** `above-fraction` decides how much of
a segment sits above a spinal level from a rank table of the two *spinal* segments,
and treated anything it did not recognise as sitting above every trunk level. Right
for the arms, which hang from the girdle; wrong for the legs the moment they
existed. A third of body mass was being added to L5/S1 in every posture and the
only symptom was a number 220 N too large.

**And one that could not fire at all.** `muscle/suspended-weight-n` read
`:arms-supported` from `(meta p)`, and `pose` calls `with-meta` nowhere and never
has — so the lookup returned nil at every call, and the desk documented in that
function's own docstring as taking two segments took none: supported and
unsupported both gave 34.32 N, the unsupported answer. Nothing downstream was
wrong, because `solve-muscle-tensions` used a private twin that took the flag as an
argument — and that duplicate body is the other half of the defect, since it is what
let the public one rot unnoticed. One body now, 34.32 N against 19.22 N.

**What the lower limb does not do.** No hip abduction and no frontal-plane
lower-limb muscle, so `:frontal-per-side` is reported and carried by nobody —
exactly as the upper limb's frontal moments were before muscles for them existed.
The ground reaction is split equally between the two feet, so single-leg stance is
out of range. The hip, knee and ankle joint centres are stacked on one vertical
line with no anterior-posterior offsets, so the small characteristic knee and hip
moments of quiet standing come out near zero where a real body has them. A seated
person's feet are unloaded.

**Honest R0**: design + runnable physics + a validated cervical model. Anthropometry / muscle /
endurance parameters are `:representative` (G7); the cervical leg is validated, the muscle %MVC and
Rohmert strain legs are mechanistically grounded but illustrative. The per-level spinal profile is
**not** validated, and since 2026-09-07 that is a measurement rather than a disclaimer: it disagrees
with the Hansraj-calibrated cervical model by about a factor of two, and with Wilke's in-vivo lumbar
pressure by about a factor of two thirds in the other direction. Both disagreements are computed by
`cervical-cross-check` / `lumbar-cross-check` and asserted by tests, so neither can quietly stop
being true. No hardware, no live member scan, no live kami-genesis backend. Cells `.solve()` raise
at R0; `load_solve` transitions are unit-tested.
