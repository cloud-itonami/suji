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
                   ──▶ forward kinematics (3-D)       pose.cljc     ← world-space chain,
                                                                      shared by physics + renderer
                   ──▶ static inverse dynamics        load.cljc     ← kami-genesis PlanarChain
                       (RNEA gravity term)                          Featherstone statics
                   ──▶ cervical compressive load      load.cljc     ← VALIDATED vs Hansraj 2014
                   ──▶ muscle moment arms             attachment.cljc ← from the anatomy,
                       (geometric, angle-dependent)                    not a constant table
                   ──▶ load sharing between synergists recruit.cljc  ← Crowninshield-Brand
                       (minimum cubed stress, closed form)              min sum (F/Fmax)^3
                   ──▶ muscle %MVC  (緊張 / tension)   muscle.cljc
                   ──▶ stiffness index (強張り)        strain.cljc   ← Rohmert sustained dose
                   ──▶ A/B/C ergonomic comparison      analyze.cljc
```

The **skeleton** is a sagittal articulated segment chain (head → cervical → thorax → lumbar + arm)
built from de Leva / Winter anthropometry — exactly the `PlanarChain` articulation kami-genesis
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
clojure -M:test                                   # JVM   — 149 tests / 4205 assertions
nbb --classpath src:test scripts/nbb_test.cljs    # cljs  — 133 tests /  736 assertions
clojure -M:lint                                   # 0 errors
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

The muscle term dominates: at L5/S1 in the laptop-on-lap posture it is 612 N
against 346 N of weight, because an extensor works at a short moment arm and all
of the force it needs presses the joint together.

⚠ **The level profile is NOT validated and disagrees with the leg that is.** At the
cervical spine it gives 519 N where the Hansraj-calibrated lumped model gives 232 N
— a ratio of 2.2, because it uses the muscle's geometric moment arm rather than an
effective lever fitted to the published table. `cervical-cross-check` computes that
ratio and names which of the two is validated, so the profile cannot be read as
though it inherited the validation. It did not.

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
which sends the reader to a repair that cannot help. The remaining refusals in this
model are all of that kind, and they occur where the head folds past horizontal
(combined trunk + head flexion beyond about 100°), where a one-sided schematic
suspension line genuinely stops representing the anatomy.

**The stiffness index saturates and now says so.** It is mathematically in [0,1) but
reaches exactly 1.0 in double precision once the dose passes ~37 — roughly 50 %MVC
held for two hours, which is an ordinary posture. Two postures, one twice as bad as
the other, both read 1.00. `:saturated?` marks them.

**Honest R0**: design + runnable physics + a validated cervical model. Anthropometry / muscle /
endurance parameters are `:representative` (G7); the cervical leg is validated, the muscle %MVC and
Rohmert strain legs are mechanistically grounded but illustrative. No hardware, no live member scan,
no live kami-genesis backend. Cells `.solve()` raise at R0; `load_solve` transitions are unit-tested.
