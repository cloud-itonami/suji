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
                       (a function of head tilt                          — but only along trunk = 0,
                        from VERTICAL = trunk + head)                     which is how Hansraj measured
                   ──▶ muscle moment arms             attachment.cljc ← from the anatomy,
                       (geometric, angle-dependent)                    not a constant table
                   ──▶ load sharing between synergists recruit.cljc  ← Crowninshield-Brand
                       (minimum cubed stress, closed form)              min sum (F/Fmax)^3
                   ──▶ muscle %MVC  (緊張 / tension)   muscle.cljc
                   ──▶ stiffness index (強張り)        strain.cljc   ← sustained-isometric dose,
                                                                      ANSWERS TO Frey Law & Avin 2010
                   ──▶ A/B/C ergonomic comparison      analyze.cljc
```

The **skeleton** is a sagittal articulated segment chain — head → cervical → thorax → lumbar,
with an arm branch (shoulder → elbow → wrist) and, since 2026-09-07, a leg branch
(hip → knee → ankle) on each side — built from Winter (4e) Table 4.1 anthropometry — exactly the `PlanarChain` articulation kami-genesis
solves (ADR-2605311500/1800). The **bones load** is the static special case of Featherstone RNEA
(the gravity term), computable in stdlib and independently checkable. The **muscles** are a Hill-type
moment-arm model (force = moment / arm; %MVC = force / F_max). **強張り** is a
sustained-isometric dose accumulated over a work session, from a power-law endurance
curve of the family Rohmert's belongs to — *not* Rohmert's own equation, and since
2026-09-07 it answers to a published meta-analysis of measured endurance times.

### The answer (`clojure -M -m suji.methods.analyze`)

| workstation | head tilt from vertical | neck load | ×head-weight | worst-muscle stiffness |
|---|---|---|---|---|
| laptop-on-lap | 64° | **27.9 kgf** | 4.9× | cervical-extensors **1.00** (very-high) |
| laptop-on-desk | 32° | 19.8 kgf | 3.5× | cervical-extensors 1.00 (very-high) |
| external-monitor + keyboard @ eye level | 10° | **10.5 kgf** | 1.9× | erector-spinae **0.98** (very-high) |

→ raising the screen to eye level cuts the cervical compressive load **−62%**.
(Self-referenced Wellbecoming, G3 — the same body across setups, not a ranking of people.
Mechanism only; a clinician owns any health interpretation.)

⚠ **This table is a transcript of one run, and it has gone stale twice** — first the last
column said `anterior-deltoid 0.05` where the report said `erector_spinae 0.04`, which went
unnoticed because until 2026-09-07 `clojure -M -m suji.methods.analyze` **threw** and nobody
was reading its output; then, later the same day, every figure in it moved when
`lumbosacral-moment` and `cervical-load` were corrected. Regenerate rather than trusting it.

**THE COLUMN IS HEAD TILT FROM VERTICAL, not head flexion** (changed 2026-09-07). The cervical
model is a function of the head's angle from vertical, which is trunk flexion plus head flexion,
and every workstation here leans the trunk: laptop-on-lap is 43.5° at the neck and 63.5° from
vertical. Until 2026-09-07 this column printed the neck angle beside a load computed as though
the trunk were upright — see **Two joint moments that computed their own answer** below.

**THIS TABLE NO LONGER SAYS "and drops every muscle to low stiffness".** It used to, and the
sentence is now false: with the lumbar spine correctly carrying the head and both arms, an
upright supported sit at an eye-level monitor still asks the erector spinae for 11.3 %MVC
held for two hours, and the dose reads 0.98 rather than 0.04. That is a knife-edge and it
is worth saying so: the endurance curve is very steep through 10 %MVC, so a few newton-metres at
L5/S1 move the dose from *low* to *very-high* while the underlying moment goes only from
7.6 N·m to 12.2 N·m. **The screen height still does what it did to the NECK**; the change is that
the trunk was never as cheap as this table said.

## Empirical anchor — Hansraj (2014)

The cervical leg reproduces the published forward-head-posture loads of Hansraj, *Surgical
Technology International* 25 (the "60-lb tech-neck" study): neutral ≈ head weight, rising to ~5× at
60° flexion. `test/suji/methods/load_test.cljc`'s `test-reproduces-hansraj-table` asserts the
multipliers track the published table (0°→1× … 60°→5×) within 10%, and
`test-the-hansraj-multipliers-are-unchanged-to-the-bit` pins them at full precision, because a
10% band would not notice the anchor moving.

⚠ **HANSRAJ'S TABLE IS MEASURED WITH THE TRUNK UPRIGHT**, so it constrains this model along one
line only — `trunk = 0` — and says nothing about a leaning trunk. That matters because the
2026-09-07 correction below changes which angle the cervical model is handed everywhere ELSE, and
the anchor is the reason it could not simply be re-fitted. The multipliers at trunk = 0 are
unchanged to the last bit (1.000 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 /
4.830127018922192, measured on the commit before the correction and again after it).

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

## The dose layer against the endurance literature — and it disagrees

The stiffness index (強張り) is what a reader of this app actually sees: it is the
number behind the *very-high* / *low* verdict in the comparison table. Until
2026-09-07 it was graded **力学的だが例示的** — mechanically motivated but
illustrative — and had nothing behind it at all. It now answers to a published
meta-analysis of **measured** endurance times, the same way the lumbar profile
answers to Wilke. **The answer is mixed, and this section exists to say so rather
than to announce a validation.**

### What the model actually implements

`strain/model-form` states it exactly:

```
T_end(f) = 0.2 · f^-2.32   minutes        (f = %MVC / 100)
ET(f)    = 12.0 · f^-2.32  seconds        the same curve, the literature's units
```

plus a hard floor: **below 8 %MVC it returns ∞**.

**It is not Rohmert's equation.** Rohmert's own 1960 curve is usually reproduced as
a polynomial in *(f − 0.15)* with a pole at 15 %MVC — a curve that goes to infinity
*at a stated intensity*. This is a plain power law with no pole, and the floor is
bolted on to supply the asymptote the functional form does not have. Calling it
*Rohmert-type* is fair as a statement of family; calling it *Rohmert's* is not, and
the docstring no longer does.

**The provenance of `0.2` and `−2.32` could not be obtained.** They match no
published fit that could be read (searched 2026-09-07). `model-form` carries
`:provenance :could-not-obtain` and `unobtained-references` lists what was looked
for and not found: El ahrache et al. 2006 (paywalled shell), Rohmert 1960 (not
online in any form), Sato et al. 1984 (PDF mirror returned zero bytes), Monod &
Scherrer 1965 (paywalled). **No number from any of those appears anywhere in this
repo**, and the widely repeated "15 %MVC can be held indefinitely" is *not* quoted
here, because no source stating it was read.

### What the measurement is

**Frey Law LA & Avin KG, "Endurance time is joint-specific: a modelling and
meta-analysis investigation", *Ergonomics* 2010;53(1):109–129.** Full text read at
<https://pmc.ncbi.nlm.nih.gov/articles/PMC2891087/>. **194 publications, 369 data
points**, fitted to seven power laws — one pooled, one per joint region. Table 2's
caption, verbatim: *"Power ( Time = bo\*(MVC) b1 ) … coefficients by joint, where
intensity (% MVC) values are between 0.0 and 1.0; time is in seconds."*

| region | b₀ | b₁ | R² |  | region | b₀ | b₁ | R² |
|---|---|---|---|---|---|---|---|---|
| **general** | 21.92 | −1.98 | 0.814 | | grip | 33.55 | −1.61 | 0.748 |
| ankle | 34.71 | −2.06 | 0.884 | | knee | 19.38 | −1.88 | 0.789 |
| trunk | 22.69 | −2.27 | 0.885 | | shoulder | 14.86 | −1.83 | 0.897 |
| elbow | 17.98 | −2.21 | 0.915 | | | | | |

What counted as an endurance time, verbatim: *"isometric tasks performed until
volitional failure … single-joint involvement (per fatigue task)"*. A held posture
is not a contraction held to volitional failure, and this repo is not claiming it
is — what is compared is one endurance-time curve against another.

**The reference's own spread** is quoted from the same first author's follow-up
(**Frey-Law, Looft & Heitsman, *J Biomech* 2012;45(10):1803–1808**, full text at
<https://pmc.ncbi.nlm.nih.gov/articles/PMC3397684/>): *"the percent difference
between the 95% PI curve and the mean expected curve for ET … ranged from 29–47%."*
That is a **range, not a number**, so both readings are carried and neither is
presented as the answer — `:within-reference-spread?` uses ±47%,
`:within-narrow-reference-spread?` uses ±29%. The provenance is stated split: the
sentence is in the 2012 paper, describing the 2010 one.

**One directly measured point**, so the comparison is not entirely against other
people's regressions: **Heinzl et al., *Scientific Reports* 2025;15:1250**, full
text at <https://www.nature.com/articles/s41598-024-83939-7> — handgrip held to
task failure at 15 %MVC by 14 healthy young men, **455.9 ± 34.1 s (7.60 min)** with
the forearm at heart level. The same paper measured **389.6 s** at the same
intensity with the forearm raised 27.5 cm, which bounds how precisely *any* curve
can be expected to predict a held posture.

### The disagreement

`strain/endurance-cross-check` — same shape as `spine/lumbar-cross-check`, same
asymmetry: `:validated :reference`, `:model-validated? false`.

**Against the pooled curve the model looks good.** Over 8–47 %MVC it sits inside
even the tight ±29% reading of the reference's own prediction interval, and it
stays inside the wide reading across the whole fitted range, running short at the
top (0.20 min against 0.37 at 100 %MVC).

**Against the joint-specific curves it disagrees, and the sign depends on the
muscle.** Measured 2026-09-07 on `laptop-on-lap`, of the four muscle groups whose
%MVC lands inside the fitted range, **two fall outside the reference's own wide
interval and they fall out on opposite sides**:

| muscle | region | %MVC | model ÷ reference |
|---|---|---|---|
| anterior deltoid | shoulder | 27.7 | **1.51×** — model says it can be held longer |
| wrist extensors | grip *(nearest region)* | 10.4 | **1.78×** — same direction |
| erector spinae | trunk | 57.4 | **0.54×** — model says shorter |
| biceps brachii | elbow | 12.1 | 0.84× |

and the groups just below the fitted range go further still — upper trapezius
2.59×, levator scapulae 2.73×.

(The erector spinae row was **25.7 %MVC / 0.57×** when this table was first
measured, hours earlier the same day. It moved because `lumbosacral-moment` stopped
omitting the head's own lever and both arms — see **Two joint moments that computed
their own answer**. The bucket counts below did not move, and neither did any other
row: this is the one muscle whose load that correction changed. `session-cross-check`
re-measured 2026-09-07 after it: 13 of 48 compared, same four refusal reasons, same
counts.)

**That is not a calibration error, it is the shape of the model.** The reference's
own between-joint spread at 20 %MVC runs from **4.7 min (shoulder) to 15.9 min
(ankle), a factor of 3.4** — larger than the model's disagreement with the pooled
curve anywhere in the fitted range. One curve for every muscle in the body cannot
be simultaneously right for a deltoid and an erector spinae, whatever its
coefficients are. `strain/joint-spread` computes that, so the limit is a number
rather than a caveat.

**The model was not tuned to close any of this.** The tests pin the *disagreement*
— `against-the-shoulder-curve-the-model-disagrees-and-says-which-way` asserts the
ratio is above the reference's wide band — so making the layer agree by moving a
coefficient fails a test and has to be argued for.

### The two extrapolation boundaries

This is the class of error this kind of model usually dies of: a curve fitted
between 10% and 100% will happily answer at 5% or at 120%, and the answer can be
absurd. This repo has shipped one exponential that extrapolated to 52,312 N before
it was clamped. So both boundaries are now **named in the value**, not in a
comment — `strain/endurance` returns `:position` and `:extrapolated?`, and
`muscle-strain` carries them through onto every dose.

| `:position` | when | what the model does |
|---|---|---|
| `:below-endurance-floor` | ≤ 8 %MVC | returns **∞**; no acute dose accrues |
| `:below-fitted-range` | 8–10 %MVC | a finite number, but nothing measured covers it |
| `:within-fitted-range` | 10–100 %MVC | the only regime the reference checks |
| `:above-maximum-voluntary-contraction` | > 100 %MVC | still answers, and says it is past meaning |

**The low end is where a desk posture lives, and the ∞ is a modelling choice.**
Below 8 %MVC the model says the load can be held forever; the published fit returns
**54 min at 8 %MVC and 138 min at 5 %MVC**. Neither side is measured there — the
fit's own data starts at 10 %MVC and the paper says it is *"concentrated above 25%
MVC"* — so the cross-check returns `:could-not-obtain
:model-returns-no-finite-endurance` with `:direction
:model-unbounded-reference-finite` rather than dividing an infinity into a report.
It matters: in `laptop-on-lap` **twenty of this actor's forty-eight muscle entries
sit under the floor** and accrue no acute dose at all.

**The high end arrives because `muscle` deliberately does not clamp %MVC above
100** — a posture demanding more force than the muscle can give is a mechanical
fact worth reporting. So this layer receives 111.6 %MVC and a power law prices it
at 9 seconds. The number is still produced, because clamping would erase the
finding, but `:above-maximum-voluntary-contraction` says what it is: an endurance
time is how long a *sub-maximal* load is held, and above maximum there is no such
quantity to extrapolate to.

### What could not be checked at all

**The muscle that produces this app's headline verdict has no published curve
here.** The reference's six regions are ankle, knee, trunk, shoulder, elbow and
hand/grip — **none of them is the neck** — and the *very-high* band in the
comparison table is produced by the **cervical extensors**. `endurance-cross-check`
returns `:could-not-obtain :no-published-curve-for-this-region` for them rather
than quietly holding them to the pooled curve as though it were their own; the
pooled curve is dominated by the limb data that *is* in the meta-analysis.

The **hip** is missing from the reference too — the paper lists `hip` among its
search terms and fits no hip curve — but unlike the neck it has a defensible
nearest region: the reference's own discussion reports the prior review grouping
models into *"general fatigue models, upper limb (shoulder, elbow, hand) models,
and trunk/hip models"* and calls that grouping *"consistent with our power ET
models"*. So `:hip-extension` routes to `:trunk` with `:basis :nearest-region`,
while `:knee-extension` and `:ankle-plantarflexion` route to regions the reference
fitted by name.

`session-cross-check` reports the buckets so a reader cannot skim forty rows and
conclude everything was checked. On `laptop-on-lap` (measured 2026-09-07, after the
lower limb landed): **13 of 48 entries produced a ratio**; the rest declined for one
of four distinct, non-interchangeable reasons — no published curve for the neck (3),
no %MVC because the entry is a ligament (2), the model's own floor (20 — a seated
posture asks almost nothing of the leg), and a muscle `recruit` refused (10).

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
clojure -M:test                                   # JVM   — 242 tests / 9322 assertions
nbb --classpath src:test scripts/nbb_test.cljs    # cljs  — 215 tests / 1639 assertions
clojure -M:lint                                   # 0 errors (13 pre-existing warnings)
clojure -M -m suji.methods.analyze                # the laptop-posture report (works again)
```

**Why two hosts.** Until 2026-09-06 every namespace here was named `.cljc` and four
of them called JVM-only interop (`Math/toRadians`, `Double/POSITIVE_INFINITY`,
`Double/isInfinite`), while the tests referred `clojure.test` directly. The suite was
green and the library did not load in a browser at all — a JVM-only suite returns the
same green for portable `.cljc` and for `.cljc` that only claims to be. The numeric
floor is now `suji.methods.math` and the cljs runner exists to fail. Three test
namespaces read repo files off disk and stay `.clj`, named for what they are.

## Corrections

**`:arms-supported` reached the sagittal moments and nothing else (2026-09-07).** The flag means
the forearms rest on a desk, so the desk carries the forearm and the hand and the body carries
only the upper arm. It is one of the three reference workstations' distinguishing features and it
is what the advice line in `analyze`'s report points at. It was read at five call sites in
`load.cljc` with the two-element answer **written out by hand at each**, and `frontal-moments` did
not read it at all.

Measured on the 70 kg / 1.70 m body at shoulder 20° / elbow 90° / **abduction 40°**, before and
after:

| quantity | unsupported | supported (was) | supported (now) |
|---|---|---|---|
| shoulder moment, sagittal | 9.922588 N·m | 1.812620 | 1.812620 |
| shoulder moment, **frontal** (left) | −3.9184149037422804 N·m | **−3.9184149037422804** | **−1.600583792781923** |
| `middle_deltoid/left` | 21.56177777831644 %MVC | **21.56177777831644** | **8.843874638150753** |
| L5/S1 weight-above (`spine`) | 367.945508 N | **367.945508** | **367.945508** — see below |

The supported column was **byte-identical** to the unsupported one in the frontal plane. In the
sagittal plane a forearm rested on a desk; in the frontal plane the same forearm, of the same arm,
in the same posture, was still hanging in mid-air. `middle_deltoid` is fed entirely by
`:frontal :shoulder-per-side`, so the abduction equilibrium never heard about the desk at all.

With a laterally-bent trunk the same defect appears one joint down: at 25° of bend the frontal
L5/S1 moment was −54.91992161403256 N·m in both states and is now −48.67019441729147 N·m
supported, and `quadratus_lumborum/right` goes 27.882735608784447 → 24.577900226844537 %MVC.

**One place decides now.** `load/body-carries?` answers "does the body still carry this segment,
or has the desk taken it", `load/desk-borne-bases` is `#{"forearm" "hand"}`, and
`arm-moment-about`, `elbow-moment`, `wrist-moment`, `lumbar-borne-bases` and `frontal-moments` all
route through it. `lumbar-borne-bases` **was a map keyed by support state** — a second
hand-written copy of the same two-element answer — and is derived now; the frontal L5/S1 term
asks it for its segment list rather than concatenating its own. The idealisation is stated once,
in `body-carries?`, and therefore holds identically everywhere: *the desk's upward reaction is
taken to act at the forearm and hand centres of mass, so those segments drop out of the free body
entirely rather than leaving the small residual couple a real forearm resting on its ulnar border
at one point leaves.*

**Why nothing noticed.** All three reference workstations are purely sagittal — no abduction, no
lateral bend — so every frontal term is 0.0 in both support states and the defect is invisible at
exactly the three postures this README publishes. `clojure -M -m suji.methods.analyze` produces
**byte-identical output** before and after this change (diffed against `origin/main`'s `src`).
The tests were the same shape: every test of the flag was a sagittal test. There are seven new
ones, and `test-every-quantity-the-support-flag-reaches-responds-to-it` is a coverage test rather
than a physics test — it asserts that each of six quantities MOVES when the flag flips, at a
posture chosen so that all six are non-zero. Three of the six did not move by a single bit.

**Neither published cross-check moved.** `lumbar-cross-check` holds Wilke's posture, which is
`:arms-supported false` (model force 350.88684032499987 N, ratio 0.6356645658061592,
`:within-reference-spread? false` — unchanged to the bit). `cervical-cross-check` at the three
workstations is unchanged too (ratios 2.379664 / 1.666824 / 1.766411), because the arms load
trunk levels only and never reached a cervical one.

### What the spine still owes the desk, and the function it can call

`spine/above-fraction` decides how much of each segment sits above a level, and its "a segment the
rank table does not know is an ARM, which hangs from the girdle and therefore loads every trunk
level" branch is **right and incomplete**: an arm hangs from the girdle *unless it is lying on a
desk*. `weight-above-n` is therefore identical in both support states — 367.945508 N at the
posture above — and `spine.cljc` is the last place in this model where the desk does not exist.

`spine.cljc` already `:require`s `load`, so the call is available with no new dependency:

```clojure
(load/body-carries? posture (:base seg))   ;; false for "forearm"/"hand" when :arms-supported
```

It needs the POSTURE, which `above-fraction` and `weight-above-n` do not currently take.
`profile` has it, and threading it through `level-compression` → `weight-above-n` →
`above-fraction` is the whole change. Measured with that thread in place and then reverted
byte-identical, on the same 70 kg / 1.70 m body:

| posture | level | weight-above now | with the flag honoured | force-n now | with the flag |
|---|---|---|---|---|---|
| audit, supported | L5/S1 | 367.9455 N | **337.7410** | 400.1093 N | **369.9048** |
| audit, supported | L4/L5 | 350.8868 | **320.6824** | 383.0506 | **352.8461** |
| `laptop-on-desk` | L5/S1 | 366.5454 | **336.4558** | 660.4416 | **630.3521** |
| `laptop-on-desk` | L4/L5 | 349.5516 | **319.4621** | 643.4479 | **613.3583** |

The drop is the same at every lumbar level, because `above-fraction` gives an arm 1.0 at all of
them: **30.2045 N** at the audit posture and **30.0896 N** at `laptop-on-desk`. Both are the
weight of two forearms and two hands — 30.204482 N — times the level axis's vertical component,
which is 1.0 for an upright trunk and cos 5° for that workstation's. Both cross-checks are unmoved by
it: `lumbar-cross-check` because Wilke's posture is unsupported, `cervical-cross-check` because no
cervical level ever counted an arm. That is measured, not predicted: 350.8868 N / 0.6357 and
2.3797 / 1.6668 / 1.7664 with the thread in and with it out.

**An audit finding that did not reproduce.** The same audit reported that
`muscle/suspended-weight-n` read the flag from `(meta p)` and that `pose` never calls `with-meta`,
so supported and unsupported both returned 34.32 N. That is fixed on `main` already — the flag is
an argument and `attachment-test`'s `supporting-the-forearms-unloads-the-girdle` pins
34.323274999999995 N against 19.221034 N. Putting the metadata read back makes that test fail with
`resting the forearms transfers two segments to the desk: 34.323274999999995 N`, which is the
audit's own number, so the guard discriminates for the reason it names.

**Two joint moments that computed their own answer (2026-09-07).** `pose/gravitational-moment`
is the RNEA gravity term read off the placed chain — the honest answer — and two functions in
`load.cljc` derived their own instead, by hand, and got it wrong. They are the same defect twice,
and both were understatements, so nothing downstream ever looked alarming enough to check.

*`lumbosacral-moment` omitted the arms and the head's own lever.* It placed the head's weight
**at C7** (`L_thorax × sin(trunk)`), so a head flexed on an upright trunk contributed exactly
nothing, and it had **no arm term at all** and never read `:arms-supported` — although its
docstring said "head-arm load". Measured on the 70 kg / 1.70 m body, head/trunk/shoulder/elbow:

| posture | was | now | thorax | head | arms |
|---|---|---|---|---|---|
| 0/0/0/0 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |
| 45/0/0/0 | **0.000** | **6.691** | 0.000 | 6.691 | 0.000 |
| 0/60/0/0 | 75.240 | 112.541 | 51.664 | 31.771 | 29.107 |
| 43.5/20/15/90 | 29.715 | 58.500 | 20.404 | 17.779 | 20.317 |

Row 2 is the control that names its own reason: trunk upright, head flexed 45°, the head's centre
of mass 12 cm anterior of L5/S1, and the model returned zero. The last row is `laptop-on-lap`,
understated by 49%. **The internal contradiction that makes this a defect and not a modelling
choice**: `spine/above-fraction` already counted the arms as loading every trunk level. One model,
two answers, and nothing that compared them.

This moment is the ENTIRE load of the `:trunk-extension` equilibrium, so it set erector spinae
%MVC and, through `spine`, every lumbar compression here. At L5/S1 in `laptop-on-lap` the total
went **950 N → 1,542 N** (0.53 → 0.86 MPa), of which the muscle term is 483 → 1,075 N.

*`cervical-load` was blind to trunk flexion.* It took `head-flexion-deg` and computed `sin` of
it — but gravity is world-fixed and `pose` places the head at trunk + head, so the head's tilt
from vertical is the SUM. Three postures that place the head identically (pose-derived moment
about C7 = 8.194 N·m in all three):

| head / trunk | was | now |
|---|---|---|
| 60 / 0 | 4.815 | 4.815 |
| 30 / 30 | 2.780 | 4.815 |
| **0 / 60** | **0.000** | 4.815 |

A person bent 60° at the waist with the head in line has a head hanging well in front of C7, and
the cervical extensors were given a load of exactly zero. All three reference workstations have
non-zero trunk flexion, so every cervical number this actor ever produced was understated. The
moment-ARM side was already correct and rotation-invariant; only the load side was wrong.
`solve-posture-loads` now passes `load/head-tilt-from-vertical-deg`, read off the placed segment
so it cannot drift from `pose`, and the `:cervical` map's `:head-flexion-deg` key is **renamed
`:head-tilt-deg`** — deliberately rather than silently, because a consumer printing it under the
heading "head flexion" would now be printing a different quantity.

**The model still reads 4.815 N·m where the placed head exerts 8.194.** That gap is the Hansraj
calibration — `head-com-lever-m` is a fitted 0.10 m where the head's own CoM sits 0.170 m along
the segment — and closing it would break the one quantity in this library that answers to a
published measurement. `spine/cervical-cross-check` computes the same disagreement from the other
end rather than hiding it.

**What let both of these survive.** `pose-test`'s "the pose and the moment solver are the same
geometry" test called `lumbosacral-moment` with a synthetic `{:head-weight-n 0.0}` and compared it
against a **thorax-only** pose lever, so it checked the one term that was already right.
`moment_balance_test` had the same shape and tested monotonicity, which cannot see a missing
segment: a model that drops the head's lever and both arms is still monotone in trunk flexion, it
just answers 75.2 where the chain says 112.5. Both now compare against the full pose-derived
moment, with the list of carried segments written out **in the test** rather than read from the
implementation.

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

**The cervical leg was untouched by that change** and still reproduces the Hansraj (2014) table.
(It was NOT untouched on 2026-09-07 — see the entry above — but the table it reproduces is,
because Hansraj measured with the trunk upright and that is where the anchor lives.)

**Moment arms, redundancy and refusal (2026-09-06).** Every moment arm used to be a
constant in `muscle/specs`, which asserts that a muscle's leverage does not change
when the joint moves — false for every muscle in the body. `attachment.cljc` places
each muscle's attachments on the bones `pose` placed and derives the perpendicular
distance; the old constants are the calibration anchor (the neutral arms reproduce
them to within 0.2%) and everything away from neutral is now geometry. Measured
consequence: the cervical extensor arm falls from 20.0 mm at neutral to 6.1 mm at
60° of head flexion, so the same neck moment costs three times the muscle force —
which is why laptop-on-lap's cervical extensors read 50 %MVC now and 27 %MVC before.
(**56 %MVC** since later on 2026-09-07, when `cervical-load` stopped ignoring the
20° of trunk flexion that posture carries — the arm shortening and the load rising
are independent corrections that happen to push the same way.)

**Wrapping surfaces (2026-09-06).** A straight chord between two attachment points
can pass through the joint it acts about; the arm goes to zero and the force needed
to hold any moment diverges. Real muscles lie ON the bone and wrap over it, and every
tangent to a circle of radius R is R from its centre — so the arm floors at R instead
of vanishing. The anterior deltoid (humeral head, R = 20 mm) and the cervical
extensors (the cervical column, R = 12 mm) declare one. Measured across 3,240
postures, this removed **every** `:coefficient-below-floor` refusal.

**A floor is only a floor if the chord can beat it (2026-09-07).** The middle
deltoid declared R = 22 mm, which is not a radius of anything: it was the top of
the `~20-25 mm` moment-arm range its own `:source` quoted, used as a floor. A floor
taken from the arm's own target sits above the arm and binds everywhere — measured
over 3,072 postures the wrap was in force **3,072 times**, against 2,432 for the
anterior deltoid and 1,560 for the biceps, and the reported arm was ±22 mm at every
one of them. A moment arm that never moves is a table, and a table is what this
whole layer replaced.

Two things were wrong. The radius is the humeral head's, and the anterior deltoid
wraps the same bone at 20 mm — one bone, one radius. And the acromion sat at
exactly the height `pose` gives the shoulder joint (both at y = 0.4896 m at
neutral), which makes the chord's abduction leverage LARGEST at 0° and carries it
through zero near 78° into adduction: the principal abductor running backwards
through the top half of its own range, invisible because the floor was above all of
it. The acromion arches *over* the head — the gap is the subacromial space — so its
superior offset is at least one head radius, and one head radius is what it now has:
the same 0.020 m, not a second number. The arm is 21.7 mm at 0°, peaks near 28.5 mm
at 45°, and reaches the floor near 86°; the wrap now binds 2,000 of those 3,072
postures instead of all of them.

**One muscle here has both ends on one segment, and it is allowed to.**
`middle_trapezius` runs from the thoracic spinous processes to the acromion, and
this model places both on `thorax_abdomen` because it has no scapula. For a MOMENT
that shape is the bug — a line whose ends both ride on a segment rotates rigidly
with the joint that segment carries, so the arm cannot move. For a SUSPENSION it is
not: the coefficient is a direction cosine against the world vertical, which the
thorax does not carry, and measured over 4,608 postures it spans −0.18 to +0.49.
What it does cost is the LENGTH, which is 1.0000 × optimal at every one of those
postures — the only muscle in the set for which that is true. So its force–length
factor is always 1 and its passive tension always 0, and its reported %MVC is a
lower bound. Adding a scapulothoracic degree of freedom is what would change that;
until then the test forbids the shape for every task except suspension, so a
*moment* muscle that acquires it fails rather than showing up as a flat column.

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

**Flexion-relaxation is PARTLY reproduced, and the part that was advertised was an
artefact (revised 2026-09-07).** This paragraph used to read "Flexion-relaxation is
now reproduced. Erector spinae active force across trunk flexion: 473 N at 20°,
267 N at 40°, **0 N at 60°**." Those three numbers were real outputs of the model
and the zero was not the phenomenon: `lumbosacral-moment` omitted the head's own
lever and both arms (see **Two joint moments that computed their own answer**), so
the demand at 60° came out **75.24 N·m** where the placed chain says **121.36**.
The posterior ligamentous system's moment there is **108.18 N·m** — *more than the
whole understated demand* — so the remainder went negative and `recruit` clamped
the muscle at exactly 0.0. It was the model running out of load, not the tissue
taking over.

With the corrected demand, erector spinae ACTIVE force and %MVC:

| trunk | was | now | %MVC now | ligament |
|---|---|---|---|---|
| 20° | 473 N | 977 N | 51.7% | 124 N |
| 40° | 267 N | 1,296 N | 80.6% | 1,068 N |
| 60° | **0 N** | **441 N** | 40.8% | 3,989 N |
| 61° | — | 393 N | 38.0% | 4,196 N ← the minimum |
| 62° | — | 604 N | 53.2% | 4,200 N (clamped) |

**So the relaxation survives and the silence does not.** The muscle peaks near 40°
and gives up about 70% of its force by 61° while the ligament force triples — that
is the shape of flexion-relaxation and it is what `passive-test/flexion-relaxation`
now asserts, together with an explicit assertion that the muscle is NOT silent, so
a future change that restores the zero has to come here and say why.

⚠ **The ligament was deliberately not re-tuned.** `:force-at-ref 4200.0` was
calibrated when the demand was understated, and it is very likely that the 60°
crossover it produced was fitted to that understatement — the crossover now lands
at 61°, one degree before the calibration clamp, which is a suspicious coincidence
rather than a result. Re-fitting it to restore a 0 would be fitting the tissue to a
bug. **Flexion-relaxation is a real, measured phenomenon in humans, and this model
now reproduces its shape but not its endpoint.** Deciding whether a correct model
of THIS body should reach silence needs surface EMG of a real erector spinae across
trunk flexion, which this repository does not have and does not pretend to.

Without ligaments at all the model reports the muscle working hardest exactly where
it is measured to be working least, which is why they were added and remains true.

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
151 N of the 5,450 N the posture demands, about 3%. (Re-measured 2026-09-07 against
the corrected L5/S1 moment; the passive force is unchanged because it is a function
of length, and the denominator grew from 3,379 N, so the share fell from 4% to 3% —
the paragraph's point got stronger, not weaker.) Real flexion-relaxation is the
posterior ligamentous system taking over — supraspinous and interspinous ligaments,
thoracolumbar fascia — and those are separate structures this model does not have.
`the-passive-term-is-small-here-and-the-model-says-so` pins the fraction, so making
the passive term large enough to explain the phenomenon fails a test and has to be
argued for.

⚠ **That paragraph used to say the per-level profile's attachment steps were GONE,
and it was wrong (corrected 2026-09-07).** What passive tension removed was the
exact ZERO, not the step. `attachment-steps` required `:muscle-n` to be exactly
0.0, so once no level was ever empty the predicate could not fire — and its unit
test went on exercising it against constructed rows that do reach zero, which the
model no longer produces. The profile stepped the whole time: measured at
laptop-on-lap, the muscle term falls **1074.7 N → 4.2 N between L2/L3 and L1/L2**,
99.6% of it in one level, where the erector spinae's point insertion at 0.25 of the
trunk lies. The detector reported `[]`, and this document published the silence.
See **The spine is resolved level by level** below for what replaced the zero.

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

The tissue term dominates: at L5/S1 in the laptop-on-lap posture it is 1,196 N —
1,075 N of muscle and 122 N of ligament — against 346 N of weight, because an
extensor works at a short moment arm and all of the force it needs presses the
joint together. (Measured 2026-09-07. This paragraph said "the muscle term … is
612 N", which was the figure from before the tissue term was split; the split
made the sentence name the wrong structure as well as the wrong number. The
numbers moved AGAIN later the same day, 605 → 1,196 N of tissue and 950 → 1,542 N
in total, when `lumbosacral-moment` stopped omitting the head's own lever and both
arms — the weight term is untouched, because `above-fraction` was already counting
what the moment was not.)

⚠ **The level profile is NOT validated and disagrees with the leg that is.** At the
cervical spine it disagrees with the Hansraj-calibrated lumped model by roughly a
factor of two, because it uses the muscle's geometric moment arm rather than an
effective lever fitted to the published table. At the lumbar spine it now has a
published measurement to answer to, and it disagrees with that too — in the other
direction. See **The lumbar spine against the literature** below.

**The ratio is not written here on purpose.** It moves whenever the muscle set
moves — it was 2.24 when the level profile landed, 2.44 after the ligaments, and
2.38 after the cervical load stopped ignoring trunk flexion —
and a number in a standing document gets quoted with its date dropped. Ask for it:

```clojure
(spine/cervical-cross-check body posture tensions (:cervical loads))
;; => {:level-force-n … :lumped-force-n … :ratio … :validated :lumped}
```

It names which of the two is validated, so the profile cannot be read as though it
inherited the validation. It did not.

`attachment-steps` reports the levels where a muscle's WHOLE contribution
disappears between neighbours. A real muscle attaches over a range of vertebrae;
this one attaches at a point, so the force steps rather than tapering. Naming the
steps is the difference between a reader seeing an artefact and a reader believing
a spine.

**There is no threshold in it, and that is the fix (2026-09-07).** It used to ask
whether the muscle term was exactly 0.0, which passive tension made unreachable.
Replacing an unreachable constant with an invented percentage would have been the
same mistake twice, and it is not necessary: `crosses?` is all-or-nothing, because
a point attachment is either above a level or below it. So the model already knows
*which* muscles stop crossing. Each row now carries `:muscle-crossing` — the
instances contributing and the newtons each contributes — and a step is the loss of
a whole muscle, reported with its size rather than judged against one:

```clojure
{:after "L2/L3" :at "L1/L2" :lost ["erector_spinae"]
 :lost-n 1070.5 :muscle-n-before 1074.7 :muscle-n-after 4.2}
```

Steps are reported only WITHIN a region. L1/L2 and C7/T1 are adjacent in the vector
and are not neighbours in a spine — this model has no thoracic levels — and pairing
them would report the missing region as an attachment artefact. And rows with no
`:muscle-crossing` are REFUSED rather than reported as having no steps: a detector
that cannot see its input must not return the value of one that looked and found
nothing.

⚠ **`:muscle-crossing` also makes a second defect visible, which is named and not
fixed.** `crosses?` is a half-space test on HEIGHT along the spine, so a muscle
nowhere near the spine counts whenever its two ends straddle a level's height:
at laptop-on-lap the whole C3/C4 row is carried by the two wrist extensors, and at
60° of trunk flexion `vasti` and `tibialis_anterior` appear at L1/L2. A wrist
extensor transmits its force to the forearm, not through somebody's neck. Fixing it
moves every number in this namespace and both published cross-checks, so it is
written down in `crosses?` as a known gap rather than silently changed.

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
in this model carries. (That 7.9 N·m is both shoulders' whole arms; it was computed
before `frontal-moments` read `:arms-supported`, and at a desk — where the forearms
are rested — the same posture now reports 3.2 N·m, carried by the middle deltoids.) The alternative, which this actor did until 2026-09-06, is
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

**The dose layer was calling itself Rohmert's, and it is not (2026-09-07).** This
README said "**強張り** is the Rohmert sustained-isometric dose" and `strain.cljc`'s
docstring said "Rohmert-type". Rohmert's own curve has a pole at 15 %MVC; this one is
a plain power law with a floor bolted on at 8 %MVC to supply an asymptote the
functional form does not have — and the coefficients `0.2` and `−2.32` could not be
traced to any published fit that was obtainable. `model-form` now carries
`:provenance :could-not-obtain`, `unobtained-references` names the four papers that
were looked for and not found, and `the-constants-are-not-claimed-to-be-published`
asserts the honest state, so a later `:provenance :full-text` has to arrive with a
citation rather than on its own.

**The endurance number answered outside its own range without saying so
(2026-09-07).** `endurance-minutes` is a power law, so it answers everywhere: ∞ at
5 %MVC, where the published fit returns 138 minutes, and a confident 9 seconds at
111.6 %MVC — a holding time for a load the muscle cannot produce, which `muscle`
deliberately does not clamp. Neither answer changed; both are now labelled
(`:endurance-position`, `:endurance-extrapolated?`, `:unbounded-endurance?`) so a
consumer can tell an answer from a guess, which was previously impossible from the
value alone. Twenty of the forty-eight muscle entries in `laptop-on-lap` are on
the unbounded side of that line.

**The published lexicons described data the emitter had stopped producing
(2026-09-07).** `data/lex/muscleTension.edn` and `strainReport.edn` declared a
five-value `group` enum. Measured on the reference scenarios that day, the emitter
produced **48 distinct group values, none of them in that enum** — every paired
group carried a `/left` or `/right` suffix, two ligaments had been added, and even
the four midline groups were written as EDN keyword literals (`:erector-spinae`)
against an enum of bare strings. It emitted a `:not-computed` band the enum did not
list, omitted two `required` properties on 28 records each, and emitted four
properties in `postureScenario` plus one in `jointLoad` that records declaring
`additionalProperties false` say are structurally unrepresentable.

None of that was detectable, because **nothing compared an emitted value against a
lexicon.** `charter-invariants-test` had a test whose message called any difference
"drift", but what it compared was the lexicon file against a five-element set typed
into the test file; the emitter was not one of its inputs. Worse, the assertion was
an EQUALITY against that snapshot, so correcting the lexicon to describe the real
model FAILED the test whose stated purpose was to catch the lexicon being wrong.
That is why the drift was never fixed.

`lexicon-conformance-test` replaces it with the invariant worth holding: **every
value the emitter produces is admitted by the lexicon**, checked by emitting from
the reference scenarios and validating against the file
(`datoms/validate-datoms`, portable `.cljc`; only the slurping is JVM). It also
pins the closed vocabularies in the other direction, because an enum that admits
everything emitted can still be a stale superset — which is exactly how a five-name
list survived a twenty-six-group model. What stayed in the charter test is the half
a conformance check cannot hold: the enum may contain only MECHANICAL names (G10 —
no 経絡/気/波動). If a meridian were added to `attachment/instances`, conformance
would report "not admitted" and the obvious fix — add it to the enum — would turn
conformance green with the charter gone.

**The structure and the side are two facts.** `:muscle/group` used to be
`:upper-trapezius/left`. That made the published enum a list of modelled INSTANCES
rather than a vocabulary of mechanical structures: it grew by two every time a
muscle was made bilateral, so a purely mechanical change became a breaking change
to a published enum, and the field's grammar varied by value, because the four
midline groups carried no suffix and nothing in the schema said when to expect one.
The anatomy layer had already separated them — `attachment/instances` sets `:group`
and `:side` as distinct keys — and the emitter was re-joining what it had taken
apart. `group` now names the structure, `side` is `:left`/`:right`/`:midline`, and
"compare left against right" is an equality instead of string surgery.

**The `-1.0` sentinel is gone.** `:strain/endurance-min` wrote `-1.0` for two
OPPOSITE facts: 101 of 144 records where the %MVC is below the model's endurance
floor and the fit returns ∞ (endurance effectively unlimited — the safest case),
and 28 where the model refused the muscle and there is no dose at all. A consumer
sorting that column numerically ranks the least-loaded muscles next to the ones
nobody solved: the same failure as a band function returning the LOWEST band for a
nil. `:strain/stiffness` wrote `-1.0` too, outside the `minimum 0, maximum 1` its
own lexicon declared. Both fields are now **absent** when there is no number, and
`:strain/endurance-limit` (`:finite` / `:unbounded` / `:not-computed`) says which
kind of absence it is, in a field that cannot be sorted or averaged —
`:strain/band` already did this for stiffness with `:not-computed`, and this is the
same idiom at the third site to learn it. `:strain/endurance-position` carries the
caveat next to the finite numbers, six of which are extrapolated below the fitted
range.

**Still open:** `suji.cells.strain-accumulate.state-machine` is a SECOND producer
of the same published `strainReport` record, and it emits a different shape —
bare keys (`"strain/id"`, not `":strain/id"`), bare band and group values, no
`side`, no `enduranceLimit`, and it still builds a `-1.0` for an infinite endurance
at `transition-rohmert-dose`. `validate-datoms` rejects all of it as
`:no-identity-attribute`. Its `solve` throws (R0 scaffold), so it is not a live
producer, but two projections of one published record disagreeing is the same class
of defect one layer over.

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
the deep squat that is 3.6 N·m of hip flexion the hip's equilibrium was never told
about — and this sentence carried 4.2 N·m for a few hours after the squat's trunk
angle moved, which is the reason to ask the model rather than the README:
`(:two-joint-unfed-nm (muscle/tension-summary tensions loads))`. A test asserts it is non-zero somewhere, because a reported approximation
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

**The report did not run (fixed 2026-09-07).** `clojure -M -m suji.methods.analyze`,
the command this README advertises, threw a `NullPointerException` out of `fmt-f`.
`render-report` reached straight for `(:mvc-pct s)` and `(:stiffness-index s)` and
handed them to a formatter that calls `.doubleValue`; a REFUSED muscle has neither,
and a LIGAMENT has no %MVC at all because it cannot contract. It had been throwing
since before 2026-09-06 and **nothing in the suite called `render-report`**, so the
suite was green and the entry point was dead — the same shape as `.cljc` that only
claims to be portable, one layer up. The lower limb made it worse rather than
causing it: every seated scenario now has antagonist refusals in its table.

The fix is the idiom `muscle/numeric-mvc?` was written for, at what is now the
fourth emit site to learn it: **branch on whether the number is there, not on why
it is not.** A refused muscle stays in the table with a dash and the
`not-computed` band, because dropping it would make a muscle the model could not
solve read as a muscle that was fine. A second unreachable-input path in the same
function is fixed with it: the comparison baseline was looked up by the literal
name `laptop-on-lap`, so rendering any other set of results returned nil and threw
two lines later. `report-test` renders the report and asserts on the parsed table
cells — not on `includes?` of words the prose above the table also uses.

**Honest R0**: design + runnable physics + a cervical model validated **along one line**.
Anthropometry / muscle / endurance parameters are `:representative` (G7); the cervical leg is
validated at `trunk = 0`, which is how Hansraj measured it and therefore all the anchor can say —
its response to trunk flexion is geometry the anchor does not constrain (2026-09-07). The muscle
%MVC leg is mechanistically grounded but illustrative. **The strain / dose leg is no longer "illustrative":
since 2026-09-07 it answers to Frey Law & Avin's meta-analysis of measured endurance times, and the
answer is that it agrees with the pooled curve and disagrees with the joint-specific ones — see
"The dose layer against the endurance literature" above.** The per-level spinal profile is
**not** validated, and since 2026-09-07 that is a measurement rather than a disclaimer: it disagrees
with the Hansraj-calibrated cervical model by about a factor of two, and with Wilke's in-vivo lumbar
pressure by about a factor of two thirds in the other direction. Both disagreements are computed by
`cervical-cross-check` / `lumbar-cross-check` and asserted by tests, so neither can quietly stop
being true. No hardware, no live member scan, no live kami-genesis backend. Cells `.solve()` raise
at R0; `load_solve` transitions are unit-tested.
