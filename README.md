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

## The nearest repos, and where the boundary runs (2026-09-07)

**`kotoba-lang/biomech` covers the same subject at a different resolution, and this
README had never named it.** The naming rule (ADR-2608040100) allows one subject on two
planes only if each README states the boundary with the nearest repo; neither of ours
did, for a month.

| | resolution | what it holds |
|---|---|---|
| `kotoba-lang/biomech` | **tissue** | tissue material properties, Euler–Bernoulli beams, FEM, softbody, hemodynamics, and a lumped Hill muscle with activation dynamics, eccentric enhancement and a series-elastic tendon. Solver backends: `fea`, `kami-vehicle`, `kami-engine-cfd` |
| **here** | **whole body** | static inverse dynamics, Crowninshield–Brand over several equilibria at once, geometric moment arms, per-level disc compression, Rohmert dose. Every muscle is collapsed to one line of action |
| `kotoba-lang/kami-app-suji` | **the visible face** | the browser app. It holds no physics and generates no geometry |

`筋` / `biomechanics` / `%MVC` / `moment arm` reach all three through
`manifest/concept-vocabulary.edn` in the superproject (`:posture-load-biomechanics`).

### The two Hill implementations, measured

Both `force-length-factor` functions were loaded into one JVM and evaluated on the same
17-point grid from 0.40 to 1.60 × optimal (2026-09-07, biomech `7832c4a`):

- **The active curve is the same closed form** — `1 − 4(L/L₀ − 1)²` clamped at 0 — with a
  worst absolute difference of **6.7 × 10⁻¹⁶**, i.e. double rounding. The duplication was
  already deliberate and this file's `muscle.cljc` docstring already said so.
- **The passive elements are different models, up to 121× apart, and below optimal they
  disagree in sign.** biomech is a linear *bidirectional* spring about rest length; ours
  is tension-only and exponential above optimal, normalised to 80% of peak active force
  at 1.5×. As a fraction of each model's own peak: at 0.70 L₀ biomech −0.0090 against our
  0.0000; at 1.50 L₀ 0.0150 against 0.8000 (**53×**); at 1.60 L₀ ours returns **2.18× its
  own peak**, extrapolating past the stretch its exponential was calibrated at.
- **Force–velocity, activation dynamics and the tendon exist only in biomech.** Static
  inverse dynamics has no velocity and no activation state, so there is nothing here to
  compare them against — that is a gap in comparability, not a disagreement.

**Neither repo depends on the other, and this comparison is not automated.** Automating
it would put this code on biomech's classpath, which is the thing both repos declined.
biomech pins the same 17 points on its side and its comment says it cannot notice a
change here. **Which passive model is right is not decided** — that needs a third source,
and Hansraj and Wilke are not it.


## What it computes

```
laptop workstation ──▶ posture (joint angles)        posture.cljc
     OR a standing   ──▶ + SUPPORT MODE                posture.cljc  ← seated or standing:
        posture              (seated / standing)                       what is under the pelvis
                   ──▶ forward kinematics (3-D)       pose.cljc     ← world-space chain,
                                                                      shared by physics + renderer
                                                                      SKULL · upper + lower
                                                                      CERVICAL · trunk · both
                                                                      arms · BOTH LEGS · ground
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
                       (minimum cubed stress; closed form                min sum (F/Fmax)^3
                        for one constraint, a COUPLED SOLVE              s.t. C.F = T, F >= 0
                        for joints that share a muscle)
                   ──▶ muscle %MVC  (緊張 / tension)   muscle.cljc
                   ──▶ stiffness index (強張り)        strain.cljc   ← sustained-isometric dose,
                                                                      ANSWERS TO Frey Law & Avin 2010
                   ──▶ A/B/C ergonomic comparison      analyze.cljc
```

The **skeleton** is a sagittal articulated segment chain — head → cervical → thorax → lumbar
→ pelvis (the trunk was one segment until 2026-09-08 and is two now, hinged at T12/L1),
with an arm branch (shoulder → elbow → wrist) and, since 2026-09-07, a leg branch
(hip → knee → ankle) on each side — built from Winter (4e) Table 4.1 anthropometry — exactly the `PlanarChain` articulation kami-genesis
solves (ADR-2605311500/1800). The **bones load** is the static special case of Featherstone RNEA
(the gravity term), computable in stdlib and independently checkable. The **muscles** are a Hill-type
moment-arm model (force = moment / arm; %MVC = force / F_max). **強張り** is a
sustained-isometric dose accumulated over a work session, from a power-law endurance
curve of the family Rohmert's belongs to — *not* Rohmert's own equation, and since
2026-09-07 it answers to a published meta-analysis of measured endurance times.

### The answer (`kbb -M -m suji.methods.analyze`)

| workstation | head tilt from vertical | neck load | ×head-weight | worst-muscle stiffness |
|---|---|---|---|---|
| laptop-on-lap | 64° | **27.9 kgf** | 4.9× | erector-spinae **1.00** (very-high) |
| laptop-on-desk | 32° | 19.8 kgf | 3.5× | **cervical-extensors** 1.00 (very-high) |
| external-monitor + keyboard @ eye level | 10° | **10.5 kgf** | 1.9× | erector-spinae **0.98** (very-high) |

→ raising the screen to eye level cuts the cervical compressive load **−62%**.
(Self-referenced Wellbecoming, G3 — the same body across setups, not a ranking of people.
Mechanism only; a clinician owns any health interpretation.)

⚠ **This table is a transcript of one run, and it has gone stale twice** — first the last
column said `anterior-deltoid 0.05` where the report said `erector_spinae 0.04`, which went
unnoticed because until 2026-09-07 `kbb -M -m suji.methods.analyze` **threw** and nobody
was reading its output; then, later the same day, every figure in it moved when
`lumbosacral-moment` and `cervical-load` were corrected. Regenerate rather than trusting it.

⚠ **It went stale a third time, in the last column, later on 2026-09-07.** The first two rows
said `cervical-extensors`, and what was really happening is that cervical-extensors and
erector-spinae were BOTH at exactly 1.00 — `analyze/worst-stiffness` returns the first maximum
on ties, so the winner was decided by emit order rather than by load. Adding the muscles that
hold the head up (see **The upper cervical spine had no muscles** below) split the cervical
extensor moment three ways, cervical-extensors fell from 56.2 %MVC to 22.2 %MVC, and
erector-spinae became the sole maximum. **The neck-load columns did not move at all** — those
come from `load/cervical-load`, which this change does not touch.

⚠ **And a fourth time, on 2026-09-08, in the same column and for the opposite reason.** The
coupled neck solve (see **The coupled solve** below) stops loading the two capitis muscles to
hold C7 — they are better levered about the joint *above* it — and puts the work on
`cervical_extensors` at its shorter arm: 22.08 → **39.18 %MVC** at `laptop-on-lap`, 11.56 →
**19.48 %** at `laptop-on-desk`. At `laptop-on-desk` that makes it the sole maximum and the
column changes. At `laptop-on-lap` it merely ties erector-spinae at exactly 1.00 again, which
is why `analyze/worst-stiffness` now breaks ties **by dose** instead of by emit order — the
same tie-break this note flagged as a hazard on 2026-09-07, fixed rather than re-recorded.
**The neck-load columns still did not move**: they come from `load/cervical-load`, which does
not go through `recruit` at all, and that is now asserted by
`spine-test/the-model-reports-where-it-disagrees-with-the-validated-leg` rather than assumed.

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
60° flexion. `test/suji/methods/load_test.kotoba`'s `test-reproduces-hansraj-table` asserts the
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
| relaxed standing | 0.50 MPa (0.48–0.50) | **yes, since 2026-09-08** — and it was refused twice before that, each time for a reason that was true when it was written. First "this model cannot stand": no thigh segment, no support mode. Then "it returns **the same 351 N** for standing and for sitting", because sitting and standing differed only BELOW L5/S1 and the lumbar spine could not tell. What separates Wilke's two figures is pelvic tilt and the lordosis that goes with it; **the pelvis rotates now and the lumbar spine tilts with it**, so the model can hold the two apart. It carries `:parameter-not-in-source` — the lordosis is Cho's, not Wilke's — and it **overshoots**. See "The pelvis had no rotation" below |

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

**The model reads about 349 N** at that posture on Wilke's own body — **below the
reference's own spread, at roughly two thirds of the measured value** (measured
2026-09-08; the model side moves whenever the muscle set moves, which is why
`spine-test` pins the ratio into 0.5–0.8 rather than to a number, and why you
should ask rather than quote. It read 350.887 N until the trunk was split at
T12/L1 and Winter's own non-uniform mass rows replaced an assumed uniform trunk;
that took 2.025 N off it and moved it **further from** Wilke):

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
**exactly zero** and the whole 349 N is the weight stacked above L4/L5 — nothing
else. A real spine at rest is not unloaded: it has lordosis, resting muscle tone
and intra-abdominal pressure. **One of those three arrived on 2026-09-08**, and it
does not close this gap, because Cho measures the lordosis of *this* posture —
relaxed sitting on a stool — at **0.6° ± 3.6°**, i.e. straight. The model's
neutral IS this posture, so the lordosis term is zero here for a measured reason
rather than a missing one. Resting tone and intra-abdominal pressure are still
absent. `the-disagreement-is-the-absent-tissue-term-not-the-weight` asserts that
decomposition, so the explanation is a computation rather than a story.

**The crossing repair of 2026-09-07 did not move this number, and it could not.**
When `crosses?` stopped being a half-space test on height, every cervical row fell
and the lumbar rows at laptop-on-lap did not. At Wilke's posture the model force is
**350.887 N before and 350.887 N after, to every digit** — and the reason is
stronger than the arithmetic: at zero trunk flexion the crossing SET at L4/L5 was
already empty under both rules. The twelve muscles carrying force at that posture
are the girdle trio and three leg muscles per side, and none of the six ever passed
either test. So this cross-check is untouched by the repair, and reporting it as
unchanged is the honest answer rather than a lucky one.

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
lumbar spine. Validation of a biomechanical analysis by measurements of
intradiscal pressures and myoelectric signals", *J Bone Joint Surg Am*
64(5):713–720, DOI `10.2106/00004623-198264050-00008` (PMID 7085696)** —
the EMG-and-model lumbar compression estimates. The first attempt (2026-09-07)
returned a consent page, a navigation shell or a paywall from PubMed, Europe PMC
(the **HTML article page**), Semantic Scholar and the publisher; a search engine
returned a *paraphrase* of the abstract, which is not a source that was read, so
at that point none of its numbers appeared anywhere in this repo.

**Updated 2026-09-09: the abstract was obtained** from the Europe PMC **REST API**,
resolved through Crossref to the DOI and PMID above. It states, of a model validated
against its own measurements: mean compressive loads on the spine of as much as
**2400 N**, posterior back-muscle contraction forces of as much as **1800 N**, and
intradiscal pressures of as much as **1600 kPa**, from 25 isometric tasks performed
by 4 healthy volunteers with myoelectric activity measured at 12 trunk sites and disc
pressure in the third lumbar disc. **The full text is still `:could-not-obtain`**
(no PMC record, JBJS paywalled, no PDF), so no full-text table has been read and no
per-task value is used here — only the abstract’s own maxima, which is the level
this entry is allowed to claim.

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
counts. Re-measured again later that day, after the muscles that hold the head up
landed: still 13 compared and this table is unchanged row for row — the new muscles
are cervical, and the reference has no neck curve, so all three land in
`:no-published-curve-for-this-region` or `:acts-the-wrong-way`.)

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

### The headline band carries one bit, and the discontinuity is why

**`moderate` and `high` are not rare — at this app's session lengths they are
unreachable.** The band is the column the answer table above and the browser
comparison view are read through, and it distinguishes two states: *is this
muscle above 8 %MVC or not*.

The cause is the endurance floor. `endurance-minutes` returns ∞ at or below
8 %MVC and **70.1 min** immediately above it, so the acute term does not grow
from zero — it switches on at full size. At a 120 minute session that is 1.71 of
dose appearing in the width of a rounding error, and the index steps from
**0.053 to 0.829 across 0.001 %MVC**. That step is wider than `moderate` and
`high` put together (0.20 → 0.70), so nothing can land in either.

Measured 2026-09-07 on the three reference workstations, 144 muscle entries:

| session | band histogram |
|---|---|
| 30 min | `{low 99, moderate 2, high 9, very-high 6, not-computed 28}` |
| **120 min** (the default) | `{low 99, very-high 17, not-computed 28}` |
| 480 min (a working day) | `{low 99, very-high 17, not-computed 28}` |

Sweeping %MVC uniformly instead of running the postures gives the same answer:
at 120 and 480 min only `low` and `very-high` are reachable; at 30 min all four
are. `strain/band-resolution` computes this by **inverting the model**, and
`band-resolution-inversion-agrees-with-sampling-the-model` requires the inversion
and a 0.05 %MVC sweep to agree band by band, so a bug in one cannot make the
finding look smaller.

**The session lengths at which each band dies are solved, not chosen** — from
`chronic-weight`, `chronic-threshold-pct`, `endurance-floor-pct` and the
power-law constants: `moderate` becomes unreachable at **40.6 min** and comes
back at 495.9 (when the chronic term below the floor finally reaches 0.20);
`high` dies at **81.8 min** and returns at 1328.5. The app's default is 120 min,
so both are dead across everything it is used for.

**The top is blind too, by a different mechanism.** The bottom loses resolution
to a *gap*; the top loses it to a *ceiling*. `strain/band-resolution` reports
`:saturation-onset-pct` — the %MVC above which the index is exactly 1.0 in double
precision: **30.2 %MVC at 120 min**, 16.5 at 480. Above it the index is not
coarse, it is constant: 70 of the 100 points on the axis carry no information at
all at the default session, and `index-at 50 == index-at 100` is an equality, not
an approximation. `:saturated?` already marked individual rows; what was missing
was the size of the region.

**What was NOT done.** No smoothing constant. Fairing the step away needs a blend
width, and there is nowhere to get one: below 8 %MVC neither the model nor the
reference has measured anything, so any width would be invented — the exact thing
this repo has spent days removing. No thresholds were moved and no coefficient
was touched: re-cutting the bands cannot help, because the defect is in the index
and no threshold set can put a value inside a gap. And the floor itself was left
alone, for a reason that cuts **against** it and is now computed rather than
argued: at the floor the model's 70.1 min and the pooled published fit's 54.3 min
**agree**, ratio 1.29, inside the reference's own wide (±47%) prediction interval
and outside the tight (±29%) one. The ∞ is switched on while the power law was
still inside the literature's spread. That is a reason to *report* the step; it is
not on its own a reason to delete the floor, because deleting it replaces one
unmeasured claim (*indefinitely*) with another (*54 minutes*).

**What is reported instead.** `muscle-strain` now emits two things it was
throwing away or leaving to be reassembled:

| key | what it is |
|---|---|
| `:dose` | the sum `acute + chronic` the index is a saturating transform of. It still has range where the index has none: the 17 `very-high` rows at 120 min span 1.93 to 165.95 — a factor of **86** — and at the two decimals the report prints they take only **7** distinct values (1.00, 0.99, 0.98, 0.96, 0.94, 0.92, 0.85) |
| `:index-resolution` | `:distinguishing` / `:saturated` / `:below-endurance-floor` / `:not-computed` — which regime of the index the row is in, as one keyword instead of three flags |

`:index-resolution` is the **G3** key. This actor exists to compare one member's
posture against their own other posture. `0.04` against `0.85` reads as a factor
of twenty and is produced by 0.02 %MVC across the floor; `1.00` against `1.00`
reads as a tie and can be a factor of two in load. Both are misreadings of a
correct number, and both are avoidable if the render knows which side of the
discontinuity each row is on.

**What the consumers should now show** (`analyze/render-report`, the answer table
above, and the browser comparison view — none of them changed here):

- Do not print `very-high` as though it were the top of a four-rung scale.
  At 120 min it is one of **two** reachable values. Either print the pair
  (`above / below the endurance floor`) or print the band together with the dose.
- Print `:dose` beside the index in the per-muscle table. It is the column that
  orders the rows the index ties.
- Render `:index-resolution :saturated` as `≥ 1.00`, and
  `:index-resolution :below-endurance-floor` with a marker saying the acute term
  is switched off there — an 0.04 and an 0.85 are not twenty-fold apart in load.
- Do not compute a ratio of two indices across the floor. It is a ratio of two
  numbers on either side of a step.

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
lower limb landed, and re-measured after the upper cervical muscles did): **13 of 51
entries produced a ratio**; the rest declined for one of four distinct,
non-interchangeable reasons — no published curve for the neck (5), no %MVC because
the entry is a ligament (2), the model's own floor (20 — a seated posture asks almost
nothing of the leg), and a muscle `recruit` refused (11). The three neck muscles
added on 2026-09-07 moved two of those counts (3 → 5 and 10 → 11) and none of the
ratios: **there is still no published endurance curve for the neck**, which is the
one bucket this actor's headline muscles have always been in.

## Isaac Sim / kami-genesis

`wire/wit/kami-biomech.wit` is the articulation contract a kami-genesis `PlanarChain` / nv-compat
`isaacsim.core.api` `Articulation` would implement; `src/suji/methods/kami_biomech_bridge.kotoba` builds the
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
kbb -M:test                                   # JVM   — 242 tests / 9322 assertions
kbb --backend sci --classpath src:test scripts/nbb_test.kotoba    # cljs  — 215 tests / 1639 assertions
kbb -M:lint                                   # 0 errors (13 pre-existing warnings)
kbb -M -m suji.methods.analyze                # the laptop-posture report (works again)
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
| L5/S1 weight-above (`spine`) | 367.945508 N | **367.945508** | **337.741026** — see below |

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
route through it — and since later the same day so does `spine/above-fraction`, which was the
last holdout (see **The spine has paid the desk** below; that is what moved the fourth row of the
table from byte-identical to 337.741026 N). `lumbar-borne-bases` **was a map keyed by support state** — a second
hand-written copy of the same two-element answer — and is derived now; the frontal L5/S1 term
asks it for its segment list rather than concatenating its own. The idealisation is stated once,
in `body-carries?`, and therefore holds identically everywhere: *the desk's upward reaction is
taken to act at the forearm and hand centres of mass, so those segments drop out of the free body
entirely rather than leaving the small residual couple a real forearm resting on its ulnar border
at one point leaves.*

**Why nothing noticed.** All three reference workstations are purely sagittal — no abduction, no
lateral bend — so every frontal term is 0.0 in both support states and the defect is invisible at
exactly the three postures this README publishes. `kbb -M -m suji.methods.analyze` produces
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

### The spine has paid the desk (2026-09-07)

`spine/above-fraction` decides how much of each segment sits above a level, and its "an
unrecognised segment is an ARM, which hangs from the girdle and therefore loads every trunk level"
branch was **right and incomplete**: an arm hangs from the girdle *unless it is lying on a desk*.
`weight-above-n` was byte-identical in both support states, so `spine.cljc` was the last place in
this model where the desk did not exist. It exists now: `above-fraction` asks
`load/body-carries?`, the same function every other equilibrium asks, and the posture is threaded
`profile` → `level-compression` → `weight-above-n` → `above-fraction`. `level-compression` gained
a parameter; `profile` is its only caller in this tree.

| posture | level | weight-above before | after | force-n before | after |
|---|---|---|---|---|---|
| `laptop-on-desk` | L5/S1 | 366.545364 N | **336.455819** | 660.441627 N | **630.352082** |
| `laptop-on-desk` | L4/L5 | 349.551610 | **319.462065** | 643.447872 | **613.358328** |
| `external-monitor+keyboard` | L5/S1 | 366.545364 | **336.455819** | 590.956342 | **560.866797** |
| `external-monitor+keyboard` | L4/L5 | 349.551610 | **319.462065** | 573.962588 | **543.873043** |

The drop is **30.089545 N** and it is the same at every lumbar level, because the cut gives an arm
1.0 at all of them. `the-desk-takes-the-forearms-off-the-lumbar-spine` derives that number rather
than typing it — two forearms and two hands, projected on the level axis — and asserts that **no
cervical level moves at all**, because an arm hangs below every one of them. `laptop-on-lap` is
untouched: it is `:arms-supported false`.

Neither published cross-check moved: `lumbar-cross-check` because Wilke's posture is unsupported
(350.886840 N, ratio 0.635665, unchanged to the bit), `cervical-cross-check` because no cervical
level ever counted an arm.

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
cervical spine it disagrees with the Hansraj-calibrated lumped model, because it
uses the muscle's geometric moment arm rather than an effective lever fitted to the
published table. Until 2026-09-07 that sentence was the whole explanation and it
was not the whole cause: the C7/T1 row also contained 186 N of anterior deltoid and
wrist extensor, admitted by a crossing rule that compared heights. Removing them
took the ratio from 2.38 to 1.70 — **closer to the validated leg, and that is not
evidence of anything.** Agreement bought by deleting a defect somewhere else is not
a validation; what the profile inherited from the repair is a smaller number, not a
measurement. Later the same day the muscles that reach the skull were added and the
ratio went **1.70 → 1.72**, i.e. *away* from 1 — which is not evidence of anything
either, in the other direction. The lumped side is 273.62 N and has not moved
through any of this; it is the side Hansraj anchors, and the level profile is the
side that is unvalidated whatever the ratio happens to be. At the lumbar spine it has a published measurement to answer to, and
it disagrees with that too — in the other direction. See **The lumbar spine against
the literature** below.

**The ratio is not written here on purpose.** It moves whenever the muscle set
moves — it was 2.24 when the level profile landed, 2.44 after the ligaments, 2.38
after the cervical load stopped ignoring trunk flexion, 1.70 once the crossing
rule stopped admitting arms, and 1.72 once the head got the muscles that hold it
up — and a number in a standing document gets quoted with
its date dropped. Ask for it:

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

**Which muscles load a level is now a path through the skeleton, not a height
(2026-09-07).** `:muscle-crossing` made a second defect visible and this is the
commit that removed it. `crosses?` used to project both attachment points onto the
spine's local axis at the level and ask whether they straddled it, so anything
whose two ends sat at different heights counted — whether or not its line of force
went near a spine. Measured at laptop-on-lap, 70 kg / 1.70 m, before the repair:

| level | muscle term | who was carrying it |
|---|---|---|
| C3/C4 | 61.4 N | `wrist_extensors/left` + `/right`, **100% of it** |
| C4/C5 | 96.0 N | 64% wrist extensor, 36% levator scapulae |
| C5/C6 | 96.0 N | same |
| C6/C7 | 254.9 N | 49% anterior deltoid, 24% wrist extensor |
| C7/T1 | 587.8 N | 57% cervical extensors, 21% anterior deltoid, 10% wrist extensor |

and at 60° of trunk flexion `vasti` and `tibialis_anterior` appeared at L1/L2 while
`vasti` carried 117.7 N of the 186.0 N at C3/C4. A wrist extensor transmits its
force to the forearm and a vastus transmits it to the tibia; neither passes through
anybody's neck.

A level is now a **cut of the skeleton**: take the point out and the body falls
into two pieces, and a muscle crosses the level when its two attachments land in
different pieces. Walk from each attachment toward L5/S1, carrying the fraction at
which each segment hangs on the next; if the walk reaches the level's own segment,
the answer is whether it arrived past the level, and if it reaches the root without
ever entering that segment, the level was never between the site and the root. A
hand and a forearm both reach the root through `upper_arm → thorax_abdomen (1.0)`
and never enter the neck, so no muscle of the arm chain can load a cervical
level at any height in any posture. (This section was written while the neck was
one segment called `head_neck`; it is three segments as of later the same day — see
*The neck had one joint* — and the rule is unchanged, because it never named one.)

**It is derived, not listed.** There is no set of spinal muscles anywhere in
`spine.cljc` and no name is tested. The answer comes from two things the model
already states: which bone each attachment rides on and how far along it
(`attachment/muscles`), and which bone hangs from which and where — `pose`'s
`:attaches-to`, **a pure addition made in the same wave**, because `solve-pose` had
always known the skeleton's shape and then discarded it. Ask the rule about a
muscle it has never heard of:

```clojure
(spine/levels-crossed pose-data {:origin    {:segment "forearm" :along 0.10}
                                 :insertion {:segment "hand"    :along 0.25}
                                 :side :left})
;; => []                                   ; the same map, sites moved:
(spine/levels-crossed pose-data {:origin    {:segment "thorax_abdomen"  :along 0.97}
                                 :insertion {:segment "lower_cervical" :along 0.667}})
;; => C7/T1 C6/C7 C5/C6 C4/C5
```

**What moved, and what did not.** At laptop-on-lap the lumbar rows are unchanged —
the muscles that were crossing them already belonged there. The cervical rows fell:
C7/T1 from 651.1 N to 464.9 N, C3/C4 from 80.3 N to 18.9 N. The **peak stress moved
from C7/T1 (1.302 MPa) to L2/L3 (0.996 MPa)**, which is the more consequential
change: the level this actor named as the most stressed one was being named by
wrist extensors and deltoids.

⚠ **C3/C4 then carried no muscle at all**, and that was reported rather than filled.
This model's most cranial muscle attachment was `levator_scapulae` at 0.22 of the
then-single head-and-neck segment; C3/C4 sits at 0.24, so nothing in the set spanned it and the level
reported the weight above it and nothing else. A real upper cervical spine is
spanned by muscles that reach the skull and this set had none of them. That was a
gap in the model's anatomy, not a statement about a neck — and it is exactly what
the old rule was hiding, because a wrist extensor was standing in for the muscles
that are missing. **It is closed as of later the same day; see the next section.**

## The upper cervical spine had no muscles (2026-09-07)

The finding above is what this section answers. Nothing in this model reached the
skull, so the region a forward-head posture actually loads was carried by nobody:

| level | muscle force before | after | crossed by, after |
|---|---|---|---|
| C7/T1 | 401.56 N | **407.38 N** | cervical extensors, semispinalis capitis, splenius capitis, levator scapulae ×2, upper trapezius ×2 |
| C6/C7 | 68.66 N | **276.11 N** | semispinalis capitis, splenius capitis, levator scapulae ×2, upper trapezius ×2 |
| C5/C6 | 34.51 N | **241.96 N** | semispinalis capitis, splenius capitis, levator scapulae ×2 |
| C4/C5 | 34.51 N | **241.96 N** | semispinalis capitis, splenius capitis, levator scapulae ×2 |
| C3/C4 | **0.00 N** | **207.44 N** | semispinalis capitis, splenius capitis |

(laptop-on-lap, 70 kg / 1.70 m. The lumbar rows are byte-identical before and after,
and so is `lumbar-cross-check` at 350.887 N — nothing added here reaches a lumbar
level, and `spine/levels-crossed` is asked rather than told.)

**Three muscles, all running thorax → skull.** `semispinalis_capitis` and
`splenius_capitis` on the extensor side and `sternocleidomastoid` on the flexor
side. Each has its two ends on *different* segments, so its moment arm changes with
head tilt — which is the whole difference between a muscle and a table.

**PCSA here is MEASURED**, the second measured column in this actor after the lower
limb's. Kamibayashi LK & Richmond FJR, *Morphometry of human neck muscles*, Spine
23(12):1314–1323, 1998 — 14 neck muscles from 10 human cadavers — read as Table 3-3
of Vasavada's chapter 3 of *Rothman-Simeone The Spine*, which reprints it with the
attribution under the table. Full text obtained 2026-09-07 from
<https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf> and read in full, not
as an abstract. Per-side means with the reported range: semispinalis capitis 5.40
(3.93–7.32) cm², splenius 4.26 (2.57–5.48) cm², sternocleidomastoideus 3.72
(1.81–5.26) cm². A midline group here carries the bilateral sum, so each is doubled.
The moment arms are *not* measured — they are representative targets calibrated the
way the rest of `attachment` is — but their **order** is sourced: the same chapter
(p.68) states that "the semispinalis capitis has shorter fascicle lengths, but also
a smaller moment arm than the splenius capitis", so splenius gets 38 mm and
semispinalis 30 mm rather than the other way round.

### The double-counting decision: added, not carved out

`cervical_extensors` is a lumped group and `load/cervical-load` is calibrated
against it, so adding named extensors beside it is exactly where a model
double-counts. The decision is **add only what the lump excludes**, and the evidence
is the lump's own geometry rather than its name:

- its insertion is at 0.05 of the old `head_neck` span = **15.5 mm above C7**
  (`lower_cervical` 0.1667 since the split), which is *below*
  C6/C7 at 18.6 mm, so it crosses **exactly one** intervertebral level;
- an occipital insertion would have to reach 0.42 — continue `spine/levels`' own
  0.06-per-level spacing past C3/C4 and C2/C3 is 0.30, C1/C2 is 0.36 and the
  occipito-atlantal joint is 0.42, i.e. 130 mm above C7. **That derivation became
  the segmentation later the same day**: `segment` now cuts the neck at exactly
  those two fractions;
- its 0.020 m calibration target is `load/cervical-ext-arm-m`, the **Hansraj
  effective lever**, not a measured muscle moment arm.

So what it places is the deep cervical group spanning the cervicothoracic junction —
semispinalis cervicis, multifidus, longissimus and spinalis cervicis — and Kamibayashi
& Richmond do not measure any of those, which is why it keeps its representative
12.0 cm² rather than being carved up against numbers that do not cover it.

**The arithmetic, and what it costs.** Cervical extensor cross-section goes
12.00 → 12.00 + 10.80 + 8.52 = **31.32 cm²**, a factor of 2.61. *If* a reader's view
is that the 12.00 was always meant as the whole posterior neck, then this model now
overstates neck extensor capacity by 12.00 cm² and every cervical %MVC it reports is
correspondingly low. **That cannot be settled from inside the model**, because the
12.00 has no provenance to check — it is `:representative` with no citation. The
one-line change that would settle it the other way is `muscle/specs`
`"cervical_extensors" :pcsa-cm2 12.0 → 3.356` — that is 12.0 × 7.50/26.82, the
suboccipital residual's share of the measured bilateral total 10.80 + 8.52 + 7.50 =
26.82 cm². It is **not** made here: `muscle.cljc` is landed, and this is reported
instead of quietly done.

**What did move because of it.** `cervical_extensors` fell from 56.2 %MVC to
22.2 %MVC at laptop-on-lap and stopped saturating the 120-minute stiffness index
(dose 158.35 → 18.41), which is what changed the worst-muscle column of the headline
table. The *moment* is not double-counted at any capacity: the equilibrium
Σ cᵢFᵢ = T holds exactly whatever the split, so C7/T1 moved only 401.56 → 407.38 N.
All the change is in the levels above it, which is the point.

### What this still cannot express

- ~~**The suboccipitals are absent on purpose.**~~ **Closed later the same day —
  see *The neck had one joint* below.** The paragraph here said *"what is missing is
  a joint, and no attachment can supply one"*, and that was right: the neck was split
  into three segments and three of the four suboccipitals now exist. Obliquus capitis
  inferior still does not, because it runs C2 → C1 and this model puts the atlas and
  the axis in one segment.
- **The sternocleidomastoid is refused in every posture this actor reports**, and
  that is the correct answer: about C7 it is a flexor, `:cervical-extension` shares
  an unsigned load, so it comes back `:refused :acts-the-wrong-way` and is marked
  `:antagonist?` rather than counted as a gap. But a refused instance carries no
  `:force-n`, so **it contributes nothing to the compression at levels its line
  crosses** — a real sternocleidomastoid holding a head against a headrest
  compresses the neck and this model cannot say so.
- **It is modelled midline, so its lateral flexion and axial rotation are absent.**
  Those would need it paired and in `:cervical-lateral-flexion`, and a muscle
  belongs to one task here.
- **The wrap radius is the cervical column's own, 0.012 m**, shared with
  `cervical_extensors` because it is the same column. A muscle lying further out
  from the bone has a larger effective radius and this model does not know how much
  larger, so the floor is a *lower* bound on the leverage — and therefore an
  **overstatement** of the force and of the compression that follows from it. It is
  needed rather than decorative: the unwrapped chord of `semispinalis_capitis` is
  +29.8 mm at neutral and **−10.3 mm at 45° of head flexion**, so without it the
  model's principal head extensor would be reported as a flexor in the posture this
  actor exists to describe.
- **The splenius entry is capitis + cervicis.** The source gives one mass and one
  PCSA for both and separates them only by fascicle length, so this entry puts a
  share of splenius cervicis's cross-section on a cranial insertion it does not have
  (cervicis runs to the C1–C3 transverse processes). It crosses the same six levels
  either way, so the error is in *where on the skull* the force is applied.
- **The specimens were cadavers.** Kamibayashi & Richmond's own N is 9 or 10 per
  muscle and the ranges are wide — semispinalis capitis spans 3.93 to 7.32 cm²,
  nearly a factor of two.

## The neck had one joint (2026-09-07)

`head_neck` ran from C7 to the vertex as **one rigid body with one joint at its
base**. Three things followed and none of them was a decision:

- `spine` reported five cervical intervertebral levels and **all five had the same
  orientation**, because there was only one cervical joint to orient them with. Five
  samples of one rigid body.
- **The suboccipitals could not be written down at all** — see the crossed-out bullet
  above. Both ends of each would have ridden on the same bone.
- **A forward-head posture is lower cervical flexion with upper cervical extension.**
  The chin tucks under while the head tips back to keep the eyes level. One block can
  only tilt.

### Three segments

| segment | spans | length (1.70 m) | mass (70 kg) |
|---|---|---|---|
| `lower_cervical` | C7/T1 disc → C2/C3 disc | 92.8 mm | 0.810 kg |
| `upper_cervical` | C2/C3 disc → occipital condyles (atlas + axis) | 37.1 mm | 0.324 kg |
| `head` | occipital condyles → vertex (the skull) | 179.5 mm | 4.536 kg |

Two new joints, `:c2c3` and `:atlanto-occipital`. New `:base` names for a renderer:
**`lower_cervical`, `upper_cervical`, `head`** — `head_neck` is gone.

**Why not two.** `head` + one cervical column, hinged at the atlanto-occipital joint,
is the minimum change and buys the *same three* suboccipitals, because all three cross
that joint. What it does not buy is the forward-head shape: with one cervical body
there is no way for the bottom of the neck to flex while the top extends.

**Why not per-vertebra.** It needs a mass and a centre of mass for each vertebra,
which none of the three anthropometric tables checked below publishes, and an angle
for each of seven joints where the posture input supplies **one number**. Seven
invented numbers dressed as anatomy.

**What a fourth segment would have bought.** Splitting `upper_cervical` into atlas and
axis makes the atlanto-axial joint real and lets **obliquus capitis inferior** be
written down — the one suboccipital that still cannot be, and 2.58 cm² of the 7.50.
It needs a per-vertebra mass, and the joint it opens is principally a *rotation* joint
(40.5° axial against ~10° sagittal), so a sagittal model pays the cost and collects
almost none of the benefit.

### Where the mass split came from: nowhere. It is representative.

Three standard tables were checked and **none of them divides head from neck**:

| source | what it says |
|---|---|
| Winter 4e, Table 4.1 | one row, `Head and neck`, 0.081. No head row, no neck row. |
| de Leva 1996, Table 4 | his `Head` segment is **vertex→cervicale (C7), this same span**, 6.94% for males. He also lists it vertex→mid-gonion with the *same* 6.94% and a different length — the endpoints move and the mass does not, because he never divided it either. Full text read 2026-09-07 from <https://ebm.ufabc.edu.br/wp-content/uploads/2013/12/Leva-1996.pdf>. |
| Plagenhoef, Evans & Abdelnour 1983 | their dissection protocol *does* cut here — p.170, *"For the head: (1) decapitate the skull from the atlas"* — and their tables still report only `Head and neck`, 8.26% for men. Full text read 2026-09-07 from <https://courses.grainger.illinois.edu/me481/sp2021/Anthro1.pdf>. |

So `segment/head-share-of-complex` is **0.80, representative and not measured**. At
70 kg it puts 4.54 kg in the skull and 1.13 kg in the neck; the neck that leaves is a
cylinder about 130 mm long and 110 mm across, which at soft-tissue density is roughly a
kilogram — a plausibility check, not a measurement. A heavier skull raises the moment
about C7 and about the condyles in a flexed posture, and 0.80 is at the high end of the
plausible range, so these demands are more likely over- than under-stated.

Everything else in the split is **derived from what the model already stated**: the two
boundaries are its own 0.06-per-level cervical spacing continued upward (C2/C3 at 0.30
of C7→vertex, the condyles at 0.42 = 130 mm above C7 — a derivation `attachment` wrote
down that morning, before there was a segment to use it); the two neck masses divide the
remainder by length; and the head's `:com-frac` (0.3707) is **solved** from the
condition that the three together keep the centre of mass the one segment had.

### The posture input did not change

`:head-flexion-deg` still means the head's angle relative to the trunk. The rule that
turns one number into three joint angles is `pose/cervical-partition`, and its **three
coefficients sum to 1.0 by construction**, so the skull still lands at exactly
`trunk-flexion + head-flexion`:

| joint | share of `:head-flexion-deg` | at 43.5° (laptop-on-lap) |
|---|---|---|
| C7 (thorax → lower cervical) | **+1.0103** | 43.95° |
| C2/C3 | **+0.1384** | 6.02° |
| atlanto-occipital | **−0.1487** | −6.47° (extension) |

The **negative** share is the forward-head shape: the column below flexes by more than
the head does and the occiput extends on the atlas. Its *direction* is sourced —
Bogduk & Mercer 2000, from van Mameren's cineradiography of 10 normal subjects, p.643:
flexion is initiated in the lower cervical spine and in the final phase *"C0–C2
typically exhibits a reversal of motion (i.e. extension)"*. Its *size* is
representative: the reversal is given the atlanto-occipital joint's own share of the
cervical sagittal range (14.5° of 97.5°, about 15%) and the remainder is split between
the two flexing joints in proportion to the ranges the same paper tabulates
(C3/C4–C6/C7 = 73°, C2/C3 = 10°; Table 5, Dvorak et al., N=28). Full text read
2026-09-07 from <https://squareonephysio.com.au/wp-content/uploads/2021/08/Bogduk-2000-Biomechanics-Cervical-Spine.pdf>.

⚠ **A fixed proportion is a linear approximation to a motion that is not linear.** The
same paper says *"the total range of motion of the neck is not the arithmetic sum of
its intersegmental ranges of motion"*, and van Mameren's subjects move the lower
cervical spine first, then the upper, then the lower again — some of them *reversing*
C6/C7 mid-excursion. This model has one number per posture and cannot represent a
sequence. What it can now represent, and could not before, is the shape at the end of
it. At the largest head-flexion input the model takes, 60°, the derived
atlanto-occipital extension is **8.9°, inside the 14.5° the joint has** —
`the-derived-atlanto-occipital-angle-stays-inside-its-published-range` asserts it.

### The suboccipitals

Three of four, PCSA measured from the same Kamibayashi & Richmond table as the capitis
muscles (per side, mean, range; doubled below because a midline group here carries the
bilateral sum):

| muscle | PCSA/side | bilateral | modelled length | measured length |
|---|---|---|---|---|
| rectus capitis posterior major | 0.93 cm² (0.44–1.45) | 1.86 | 41.5 mm | 30–48 mm ✓ |
| rectus capitis posterior minor | 0.50 cm² (0.48–0.83) | 1.00 | 23.0 mm | 26–31 mm — **3.0 mm short** |
| obliquus capitis superior | 1.03 cm² (0.29–1.59) | 2.06 | 39.5 mm | 43–57 mm — **3.5 mm short** |
| *obliquus capitis inferior* | *1.29 cm² (0.69–1.73)* | *2.58* | — | **not modelled** |

**They are calibrated against a measured LENGTH, not against an invented moment arm.**
Everywhere else in `attachment` the offsets are chosen so the neutral arm reproduces a
constant this actor already used; there is no such constant here, and inventing a
moment-arm target to calibrate to would be a number pretending to be an anchor. The
sites are placed from bony landmarks and the resulting line length is compared against
the cadaver measurement, which is **reported and not tuned**: both shortfalls have the
same cause and it is not these muscles — this model's `upper_cervical` is 37 mm where
an atlas plus axis is nearer 50, because `segment` cuts the neck at the model's own
uniform 18.6 mm level spacing and C2 with its dens is taller than a typical vertebra.

**The moment arms vary with posture**, which is the thing a muscle with both ends on
one bone can never do (−15° to 60° of head flexion):

| muscle | arm at −15° | at 0° | at 60° |
|---|---|---|---|
| rectus capitis posterior major | 27.39 mm | 27.65 mm | 28.46 mm |
| rectus capitis posterior minor | 22.55 mm | 22.76 mm | 23.51 mm |
| obliquus capitis superior | 12.66 mm | 12.88 mm | 13.63 mm |

Monotonic, positive throughout, and **moved by head flexion only** — leaning the trunk
carries the whole chain rigidly and does not move an atlanto-occipital arm. It is a 4%
swing and not more, because the joint rotates only 8.9° over the model's whole input
range. Break the split and the arm goes flat at 26.72 mm at every angle, which is what
`the-suboccipital-moment-arms-vary-with-posture` was verified against.

### And then they carry nothing at a desk, which is a result

> ⚠ **SUPERSEDED 2026-09-08 by the coupled solve.** Everything in this subsection describes
> the *uncoupled* decomposition — `:residual-nm`, `:over-supplied-nm` and the third argument
> to `load/atlanto-occipital-moment` that produced them are **gone**, along with
> `load/capitis-groups`. The joint's demand (2.648 N·m at `laptop-on-lap`) is unchanged,
> because it is gravity on the skull and no muscle force enters it; what changed is that the
> muscle side is now chosen with this joint in the problem, so there is no leftover to hand
> anybody. The suboccipitals still carry 0 N at a desk and the reason is different — see
> **The coupled solve** below. The paragraph is kept because the measurement in it is what
> motivated the change.

The atlanto-occipital equilibrium is given the **residual**, not the demand:

```
laptop-on-lap, 70 kg / 1.70 m
  skull about the condyles                     2.648 N·m   demanded
  semispinalis + splenius capitis, already     6.223 N·m   supplied   (×2.35)
  → residual for the suboccipitals             0.000 N·m
  → surplus nobody in this model balances      3.575 N·m
```

Semispinalis capitis and splenius capitis run thorax → **occiput**, so they pull on
this joint too; they are solved at C7 because that is where they are the principal
actors, and `recruit`'s closed form takes one constraint, so a muscle belongs to one
task. Handing the suboccipitals the whole 2.648 N·m charges them for work those two are
already doing — and it did: it made **rectus capitis posterior major the worst-loaded
muscle in the entire report at 51% MVC**, a headline manufactured by a decomposition.

**The surplus being positive is the finding.** The big superficial extensors, sized by
the load at C7, over-extend the joint above them; what a real neck balances that with is
its upper cervical **flexors** — longus capitis, rectus capitis anterior and lateralis.
That is also why the sign is robust rather than an artefact of solving the two
constraints in sequence: the suboccipitals act *only* at the atlanto-occipital joint, and
that joint can be satisfied by muscles that also serve C7, so a minimum-cubed-stress
optimum over both constraints together would still prefer the large capitis muscles.
`:over-supplied-nm` is reported at every posture.

> ⚠ **That last prediction was made in 2026-09-07 and tested on 2026-09-08. It is half
> right.** The coupled optimum does still prefer the capitis muscles at the
> atlanto-occipital joint and the suboccipitals still take 0 N at a desk — but it does **not**
> load them as heavily as the uncoupled solve did, because it now pays for what they do at
> the joint above: splenius capitis falls 87.29 → 3.81 N and semispinalis 125.25 → 101.56 N,
> and the work moves to `cervical_extensors`. The direction was right; the forces were not.
> There was no way to know which without running it.

> **This paragraph ended *"and this model has none of them"* until 2026-09-08.** It has
> two now — and the surplus is still not balanced, for a reason the flexors made
> measurable rather than removed. See *The upper cervical spine had no flexors* below.

Swept over head −60…60° × trunk 0…75° in 5° steps, **17 of 400 postures have a positive
residual**, all of them head −40…−60° on a trunk flexed 60…75° — the head held back to
look forward from a deep bend, which is the posture a suboccipital is for. At head −55°
/ trunk 75° the three carry 1.30 N, 1.10 N and 0.68 N (1.21%, 1.98% and 0.55% MVC). At a
laptop posture they carry **0 N and are not refused** — a zero load is a placed load, and
the difference between *"nothing is asked of it here"* and *"this model could not answer"*
is the whole point of the distinction.

### C2/C3 exists now, and nothing holds it

The split added one intervertebral level — **C2/C3, the most cranial disc there is**.
There is no disc between C1 and C2 or between C1 and the occiput, which is why those two
appear in `pose` as joints and not in `spine/levels`.

**No muscle in this model is solved at the C2/C3 joint.** Four cross it, and none of
them is *at* it: `awaiting-muscles` in `attachment-test` names it, which is the mechanism
this repo uses instead of a paragraph. Its compression is therefore a **lower bound**.

> ⚠ **The reason stated here was wrong until 2026-09-08.** It said filling C2/C3 needs
> *"rectus capitis anterior and lateralis, longus capitis, the semispinalis and multifidus
> cervicis fascicles that end on C2 — none of which exists here"*, i.e. that the blocker
> was a shortage of anatomy. Two of those muscles were added on 2026-09-08 and the joint
> is still unsolved, because they act about the **atlanto-occipital** joint. A name on
> that list was not a muscle at this one. The corrected verdict is in
> *C2/C3: expressible, and blocked by provenance* below.

### What the split did to the numbers

The per-level cervical profile at `laptop-on-lap`, 70 kg / 1.70 m:

| level | force before | force after | crossed by, after |
|---|---|---|---|
| C7/T1 | 470.741 N | **470.299 N** | cervical extensors, semispinalis capitis, splenius capitis, levator scapulae ×2, upper trapezius ×2 |
| C6/C7 | 337.985 N | **339.472 N** | semispinalis capitis, splenius capitis, levator scapulae ×2, upper trapezius ×2 |
| C5/C6 | 263.790 N | **264.398 N** | semispinalis capitis, splenius capitis, levator scapulae ×2 |
| C4/C5 | 262.301 N | **263.700 N** | semispinalis capitis, splenius capitis, levator scapulae ×2 |
| C3/C4 | 226.300 N | **228.463 N** | semispinalis capitis, splenius capitis |
| C2/C3 | — | **216.962 N** | semispinalis capitis, splenius capitis |

> This table records what *the split* did and its "after" column is that day's. Since
> 2026-09-08 the bottom three rows also carry `longus_capitis` — C4/C5 263.713 N, C3/C4
> 228.476 N, C2/C3 216.975 N, each **+0.013 N**, which is that muscle's passive term at a
> posture where its active force is zero. The three rows above them are byte-identical.

The lumbar rows moved by 1.9 N each (L5/S1 1542.139 → 1544.053 N) for one reason: the
cervical column now flexes **more** than the head does, which carries the mass above C7 a
little further anterior of L5/S1. The suboccipitals appear in **no** row, and that is
correct — both their ends are above every level this model has, so there is nothing for
them to cross. Splitting the neck gave them a joint, not a level.

**Both cross-checks, before and after:**

| | before | after | what it means |
|---|---|---|---|
| `cervical-cross-check` ratio | 1.7204 | **1.7188** | −0.09%. It moved *toward* 1 by a hair and **that is not a validation** — it is arithmetic. The lumped side (273.619 N) did not move at all, because the split preserved the head's tilt exactly; the level side moved because the weight term did (24.810 → 24.420 N). `:validated :lumped` still says which side carries one. |
| `lumbar-cross-check` | 350.887 N, ratio 0.6357 | **350.887 N, ratio 0.6357** | **byte-identical.** Nothing in the neck reaches L4/L5, and at Wilke's zero-flexion posture the crossing set there was already empty. Still below Wilke's spread; `:within-reference-spread? false` as before. |

**The Hansraj multipliers are unchanged to the bit** — 1.0 / 2.260021051801672 /
3.366025403784438 / 4.242640687119285 / 4.830127018922192. They depend only on the ratio
`head-com-lever-m / cervical-ext-arm-m`, which the split does not touch. Neither did the
two inputs `load/cervical-load` is actually handed at a posture, and that took work:

- **the head's tilt from vertical.** The partition sums to 1.0, and `cervical-chain`
  *sets* the skull's tilt to `trunk + head-flexion` rather than accumulating it —
  accumulating puts a head asked for 63.5° at 63.49999999999999.
- **the head weight.** It is still the **whole complex above C7** (5.670 kg = Winter's
  0.081), not the skull. Hansraj's model is fitted with a ~12 lb head and Winter's
  head-and-neck row is 5.43 kg at 67 kg; this actor has always identified the two.
  Handing it the skull alone would have cut the validated quantity by a fifth and looked
  like nothing.

Verified against `origin/main` itself, out of `git archive`, at all three reference
workstations: 273.61858521664936 / 194.4819901245166 / 103.0363709306259 N, identical.

### What the neck still cannot express

- **Obliquus capitis inferior**, 2.58 cm² bilateral — C2 spinous → C1 transverse, both
  ends on `upper_cervical`. It needs the atlas and the axis to be separate bodies.
- **The atlanto-axial joint**, whose cardinal motion is 40.5° of axial rotation. A
  sagittal model has nowhere to spend it.
- ~~**Any upper cervical flexor**~~ **Closed 2026-09-08** — `longus_capitis` (PCSA
  measured) and `rectus_capitis_anterior` (PCSA representative) act about the
  atlanto-occipital joint. ~~What is *not* closed is the surplus: carrying it would take
  185% and 116% of what those two can produce.~~ **The surplus is closed too, later the
  same day**: `recruit/solve` satisfies C7 and this joint together, so the surplus is not
  smaller — it does not exist. The flexors now carry 9.94% and 6.81% MVC at
  `laptop-on-lap`, which is a coupled force rather than a decomposition error charged to a
  muscle. See **The coupled solve**.
- **Any muscle solved AT C2/C3.** Still true — but *not* because the segmentation cannot
  express one. See *C2/C3: expressible, and blocked by provenance*.
- **Rectus capitis lateralis**, the third upper cervical flexor named in the source, is
  not here. It runs from the transverse process of the atlas to the jugular process of
  the occiput and its function is **lateral bending**, not sagittal flexion; a midline
  sagittal model has nowhere to put it, and Kamibayashi & Richmond do not measure it
  either.
- ~~**A coupled solve over the C7 and atlanto-occipital constraints.**~~ **Closed
  2026-09-08** — `recruit/solve`, one dual variable per constraint, active set read off
  the KKT prices. The gap the flexors turned from a suspicion into a measurement is the
  gap that measurement closed. See **The coupled solve**.
- **A motion sequence.** The partition is a fixed proportion; real cervical flexion moves
  the lower column, then the upper, then the lower again.
- **Lateral bend within the neck.** `pose` gives all three cervical segments the same
  lateral-bend angle, so there is no side-bending rhythm and no coupled axial rotation —
  which is most of what the atlanto-axial joint does.
- **The three suboccipitals are modelled midline**, so their lateral flexion and their
  role in steadying the head in rotation are absent. That costs most for obliquus capitis
  superior, which really runs from a transverse process 25 mm off the midline.
- **`upper_cervical` is 37 mm where an atlas plus axis is nearer 50**, because the model
  cuts the neck at its own uniform level spacing. Everything spanning that joint comes out
  short, and a short muscle changes length by a larger fraction for the same rotation — so
  the force–length term falls off faster than it should, biasing these three toward *less*
  available force and a *higher* %MVC than a correctly scaled model would report.
- **The five lower cervical levels still share one orientation.** `lower_cervical` is C3
  to C7 and is still one rigid body; C7/T1 … C3/C4 are five samples of it. What is no
  longer true is that the *skull* shares that frame.

## The upper cervical spine had no flexors (2026-09-08)

The section above created the gap and named it: the atlanto-occipital joint had three
suboccipital **extensors** and nothing on the other side, so it could not express
co-contraction, could not balance an over-supply, and could not represent a head held
back against a headrest. `load/atlanto-occipital-moment` said so in its own docstring —
*"what a real neck balances that with is its upper cervical **flexors** — longus capitis,
rectus capitis anterior and lateralis — and this model has none of them"*.

Reproduced before anything changed, on a 70 kg / 1.70 m body:

```
muscles with :acts-about :atlanto-occipital     3, all :atlanto-occipital-extension
tasks in the whole model carrying a flexion
  moment about any cervical joint               0
at laptop-on-lap:  demand 2.648 N·m · capitis 6.223 N·m · residual 0.000 · surplus 3.575
                   all three suboccipitals: 0.000 N
```

### Two muscles, one measured and one not

| group | PCSA (bilateral) | provenance | acts about | task |
|---|---|---|---|---|
| `longus_capitis` | **1.84 cm²** | **measured** — Kamibayashi & Richmond 1998 Table 3-3, 0.92 (0.35) cm² per side, range 0.54–1.63, N=7, ×2 sides | `:atlanto-occipital` | `:atlanto-occipital-flexion` |
| `rectus_capitis_anterior` | 1.00 cm² | **representative, not measured** — 0.50 cm² per side taken to equal the *measured* value of its direct posterior counterpart, rectus capitis posterior minor, in the same table | `:atlanto-occipital` | `:atlanto-occipital-flexion` |

The source is Kamibayashi LK, Richmond FJR, *Morphometry of human neck muscles*, Spine
23(12):1314–1323, 1998, read as Table 3-3 of Vasavada AN, *Architectural Design and
Function of Human Back Muscles*, Rothman-Simeone The Spine ch.3 p.65 —
<https://nmbl.stanford.edu/publications/pdf/Vasavada2010.pdf>, **fetched 2026-09-08 and
read in full text** with `pdftotext -layout`, not as an abstract. Its longus capitis row
also gives mass 3.7 (1.2) g, muscle length 7.8–11.1 cm mean 9.2 (1.4), NF length 3.8 (1)
cm. **Rectus capitis anterior is not one of its fifteen muscle rows** — checked in the fetched
text rather than assumed — and Vasavada names it only qualitatively: *"On the ventral
side, the rectus capitis anterior and rectus capitis lateralis are very small muscles
that connect the skull to C1, presumably with (small) moment arms for flexion and lateral
bending"* (p.66).

**What the representative number costs**, stated where it is spent: `longus_capitis`'s
PCSA is measured, and once the two share the flexion task by Crowninshield–Brand, the
measured muscle's force depends on the unmeasured one. The one-line change that removes
that dependence is to delete the `rectus_capitis_anterior` entry; the joint then has one
flexor, and that flexor also crosses C2/C3, so the model would have no way to express
purely atlanto-occipital flexion at all. That is why it is in rather than out.

**The moment arms are not calibrated to anything**, for the same reason the suboccipitals'
are not: there is no constant this actor already used about this joint and no published
moment arm was obtained, so a target would be a number pretending to be an anchor. The
sites are placed from bony landmarks and the resulting **length** is checked against the
measurement:

| | modelled at neutral | measured | |
|---|---|---|---|
| `longus_capitis` | **91.75 mm** | 78–111 mm, mean 92 (14) | inside, on the mean |
| `rectus_capitis_anterior` | 16.06 mm | — | the table does not contain the muscle |

### The arms move, and they move the other way from the extensors

Moment arm about `:atlanto-occipital`, 70 kg / 1.70 m, negative = flexion:

| head flexion | −15° | 0° | 15° | 30° | 45° | 60° |
|---|---|---|---|---|---|---|
| `longus_capitis` | −14.687 | −14.538 | −14.384 | −14.226 | −14.064 | −13.898 |
| `rectus_capitis_anterior` | −10.937 | −10.783 | −10.625 | −10.464 | −10.298 | −10.128 |
| `rectus_capitis_posterior_major` (extensor) | +27.39 | +27.65 | +27.89 | +28.11 | +28.30 | +28.46 |

Same rotation, opposite directions — the atlanto-occipital joint *extends* as the head
flexes on the trunk, which carries a posterior insertion further behind the joint centre
and an anterior one closer to it. Leaning the **trunk** moves neither (identical to
1e-12), because the joint is between the skull and the atlas and trunk flexion carries
the whole chain rigidly. That is the test that would fail for a muscle with both ends on
one bone, and it is the reason this could not be written before the neck was split.

### Sternocleidomastoid was the obvious candidate and the geometry says no

It is this model's existing neck flexor. Re-tasking it was considered and rejected on the
measurement, not on the name: it inserts on the **mastoid process**, which is *behind* the
occipital condyles, so about the atlanto-occipital joint it is an **extensor** —

| head flexion | −15° | 0° | 15° | 30° | 45° | 60° |
|---|---|---|---|---|---|---|
| SCM arm about `:atlanto-occipital` | +4.90 | +4.54 | +4.24 | +4.06 | +4.05 | +4.27 mm |

— positive at every posture, and **below `recruit/min-coeff` (5 mm) at all of them**. So
tasked as a flexor it is refused `:acts-the-wrong-way`, and tasked as an extensor there it
is refused `:coefficient-below-floor`. Both refusal literals are pinned by
`the-sternocleidomastoid-is-an-upper-cervical-extensor-not-a-flexor`, because a test that
asserted only *refused* would pass on a degenerate line of action, which is a different
defect with a different fix. This is also the mechanism of the forward-head posture the
muscle is famous for: lower cervical flexion with upper cervical **extension**, which is
exactly the shape `pose/cervical-partition` produces.

### The load is gravity. The surplus is reported, not assigned.

> ⚠ **SUPERSEDED 2026-09-08.** This subsection is the record of a decision that was right
> while the solve took one constraint, and the keys it describes — `:surplus-force-n`,
> `:surplus-mvc-pct`, `:atlanto-occipital-surplus-mvc-pct`, `:gravitational-flexion-nm`,
> `:decomposition-surplus-nm` — have all been **removed** rather than reinterpreted, because
> a key whose meaning inverts is worse than one that is gone. The 184.82% headline it exists
> to keep out of the report is still out of it: the flexors carry **9.94%** at
> `laptop-on-lap` now, and that is a force chosen by an optimisation over both constraints
> rather than a surplus divided by a muscle. Kept for the reasoning, which is the part that
> generalises.

`:over-supplied-nm` turned out to be **two different things added together**, and
`load/atlanto-occipital-moment` now splits them:

```
:gravitational-flexion-nm    max(0, −M)            what gravity asks of the flexors
:decomposition-surplus-nm    the rest              what the uncoupled solve leaves
                             (they sum to :over-supplied-nm, asserted not assumed)
```

Gravity asks the flexors for something exactly when the skull's centre of mass sits
**behind** the occipital condyles — a head tipped back, or held against a headrest. The
surplus is what the two capitis muscles exert here in excess of that, because they were
sized by the equilibrium at C7 and `recruit`'s closed form takes one constraint.

**Assigning the surplus was the first draft and it was rejected on the measurement.**

| | at `laptop-on-lap` | at `laptop-on-desk` | at `external-monitor` |
|---|---|---|---|
| `longus_capitis` if the surplus is assigned | **184.82% MVC** | 97.23% | 13.47% |
| `rectus_capitis_anterior` if assigned | **115.93% MVC** | 61.55% | 8.59% |
| worst muscle in the whole report, then | `longus_capitis` | `longus_capitis` | `longus_capitis` |
| `longus_capitis` as landed | **0.012%** | 0.009% | 0.002% |
| worst muscle in the whole report, as landed | `erector_spinae` 57.49% | `erector_spinae` 14.88% | `erector_spinae` 11.29% |

A muscle cannot be shown carrying 1.85 times what it can produce as though that were a
finding about a posture — it is the same headline-manufactured-by-a-decomposition that
`atlanto-occipital-moment` refused when it declined to charge the suboccipitals this
joint's whole demand. So the surplus is **reported**, and for the first time in units that
make its size legible: `:surplus-force-n` and `:surplus-mvc-pct` on every flexor row, and
`:atlanto-occipital-surplus-mvc-pct` in `tension-summary` — what *this* muscle would have
to produce if the surplus were assigned, through the same criterion, deliberately not
folded into `:mvc-pct` so nothing downstream ranks or doses a body by a number the model
does not claim.

**The result is stronger than the moment was.** The surplus is not a small residual that a
missing muscle was hiding: it is **1.85× the entire capacity of the anatomy that would
have to absorb it**. Adding the flexors did not close the joint. It measured how far from
closing it is, and named what would close it — a solve over both constraints together,
which `recruit` does not have and which no further muscle supplies.

> **`recruit` has one now** (2026-09-08). The named thing was built and the surplus went to
> zero — not smaller, absent, because the two constraints are satisfied by one choice of
> forces. `:coupled-residual-nm` is 0 to floating point at both joints at every reference
> workstation, and `load-test/the-coupled-solve-balances-the-atlanto-occipital-joint`
> asserts it along with the discriminating half: semispinalis capitis is still recruited,
> at **less** than the 125.249 N the uncoupled solve gave it. Break the coupling — hand the
> neck group only the `:c7` constraint — and that number returns to
> **125.24866077557766 N** exactly, which is how the test knows it is measuring the coupling
> and not something else.

### The sweep: they carry where gravity flexes the head, and nowhere else

> ⚠ **The equivalence in this heading is FALSE after 2026-09-08, and the reason is
> mechanical rather than a regression.** It was true of the uncoupled model *by
> construction* — the flexors' task load was the gravitational flexion moment, which is zero
> unless the head is tipped back — so what this sweep checked was the wiring. Under the
> coupled solve they also **co-contract** wherever the head is forward, because the capitis
> muscles are better levered about the atlanto-occipital joint than about C7 and any force
> they produce for C7 over-extends it. The claim is now about **size**, and the size is the
> finding: 11.23 %MVC worst forward-head co-contraction against 38.06 % worst genuine
> flexion demand. See `attachment-test/the-flexors-co-contract-and-the-size-of-it-is-the-finding`.

Over 365 postures (head −15…60° × trunk 0…45° × shoulder 0…90° × arms supported/not,
plus the three reference workstations and two standing references), 70 kg / 1.70 m:

| | |
|---|---|
| postures where a flexor takes **active** force | **40 of 365** |
| …and in every one of them the head's tilt from vertical is | **negative** |
| postures where gravity demands upper cervical flexion | 40 — *the same 40* |
| postures where a flexor is **refused** | **0** — a placed load of zero is not a refusal |
| peak `longus_capitis` | **37.59% MVC** at head −15° / trunk 0° (41.50 N) |
| peak `rectus_capitis_anterior` | 23.88% MVC at the same posture (14.29 N) |
| postures where the surplus alone would exceed 100% MVC | 89 of 365, peaking at **214.3%** |
| **suboccipital** active force, anywhere in the sweep | **0 N — unchanged** |

The equivalence is the claim, not the count: the flexors carry in exactly the postures
where the skull's centre of mass is behind the condyles. `laptop-on-lap`,
`laptop-on-desk` and `external-monitor+keyboard` are all head-forward, so at all three
they carry nothing and are not refused.

**The suboccipitals did not move.** They carried 0 N in all 365 postures before this wave
and 0 N in all 365 after it, because `:residual-nm` is `max(0, M − capitis)` and the
capitis surplus is positive everywhere the head is forward. `:task-over-supplied-nm` is
unchanged too — 3.575 N·m at `laptop-on-lap`, 4.2045 N·m peak across the sweep, before
and after. The flexors did not take anything off the extension side; they gave the
flexion side a number.

### C2/C3: expressible, and blocked by provenance

`awaiting-muscles` said the blocker was a shortage of muscles. Two of the muscles it
named were added and the joint is still unsolved, so the reason was re-derived from the
model rather than from the list — and it is **not the segmentation**.

`spine/levels-crossed` and `attachment/moment-arm` both take a *muscle map* rather than a
name, so a candidate that is not in `attachment/instances` can be handed to them and the
model answers about it. All three muscles that act at C2/C3 in anatomy were written in
this model's attachment language and asked:

| candidate (anatomy from Vasavada ch.3, p.66) | ends ride on | crosses | arm about `:c2c3`, head −15° → 60° |
|---|---|---|---|
| semispinalis cervicis — thoracic transverse processes → C2 spinous, *"with the bulk of its mass inserting on C2"* | `thorax_abdomen` → `upper_cervical` | all six levels | +19.02 → +11.82 mm (−38%) |
| cervical multifidus — *"span one or two vertebral segments"*, C3 articular process → C2 spinous | `lower_cervical` → `upper_cervical` | **C2/C3 only** | +16.74 → +15.81 mm |
| longus colli, superior oblique part — *"fibers run superomedially from transverse processes to the anterior vertebral bodies"*, to the anterior tubercle of the atlas | `lower_cervical` → `upper_cervical` | C4/C5, C3/C4, C2/C3 | −7.68 → −11.21 mm |

**None of them has both ends on one segment**, so none is the shape
`a-muscle-with-both-ends-on-one-bone-cannot-have-an-angle-dependent-arm` names as the
known error, and each has an arm about `:c2c3` that moves when the joint moves. The
one-level multifidus crosses **C2/C3 and nothing else** — a muscle can be placed that acts
at this joint and at no other level in the model.

> The test asserts a **spread**, not `(count (distinct arms))`. Verified 2026-09-08 by
> moving the multifidus candidate's origin onto `upper_cervical`: the arm is then constant
> in exact arithmetic and differs in the last bits in a double, so the distinct count
> still returned 6 — for exactly the shape the test exists to reject. The spread was
> 8.3e-17 against a 1.88e-4 threshold.

**What actually blocks it is provenance, in two parts.**

1. Kamibayashi & Richmond 1998 Table 3-3 — the source every measured PCSA in this model
   comes from — has fifteen muscle rows and **not one of the three is among them**. Checked in
   the fetched full text. Their cross-sections would be invented.
2. The lumped `cervical_extensors` **already declares that it stands for two of them**.
   Its `:source` says it places *"semispinalis cervicis, multifidus, longissimus and
   spinalis cervicis"*, and its 12.0 cm² is unprovenanced — so carving them out would
   need a number to divide that does not exist, and adding them alongside would
   double-count against a lump nobody can check. That sentence moved out of a comment
   and into the `:source` on 2026-09-08 so a test can hold it.

So **`:c2c3` stays in `awaiting-muscles`**, and what would close it is a source with a
cross-section for the deep cervical extensors — not a segmentation change, not a wrapping
surface, and not another muscle from the list.

### Searched for that source, did not find it (2026-09-10)

The one thing that would close the provenance block — a measured cross-section for
cervical semispinalis cervicis or one-level cervical multifidus — was searched for on
2026-09-10 and **not found in a form this model can use**:

- **Elliott J, Jull G, Noteboom JT, Darnell R, Galloway G, Gibbon WW, *Magnetic resonance
  imaging study of cross-sectional area of the cervical extensor musculature in an
  asymptomatic cohort*, Clin Anat 20:35–40, 2007** (doi:10.1002/ca.20252) — measures
  rectus capitis posterior minor/major, multifidus, semispinalis cervicis/capitis and
  splenius capitis by MRI in 42 asymptomatic women. Abstract obtained. What the abstract
  reports is *reliability and side/level differences* (P-values), not per-muscle mean CSA
  values, so no number for `cervical_extensors`' constituents is in it as far as the
  abstract goes. `:could-not-obtain` — full text not fetched.
- **Fortin M, Dobrescu O, Jarzem P, Ouellet J, Weber MH, *Quantitative Magnetic Resonance
  Imaging Analysis of the Cervical Spine Extensor Muscles: Intrarater and Interrater
  Reliability of a Novice and an Experienced Rater*, Asian Spine J 12(1):94–102, 2018**
  (doi:10.4184/asj.2018.12.1.94; author string and DOI from the Europe PMC REST record for
  PMCID PMC5821939) — measures cervical multifidus and semispinalis cervicis **as one ROI**
  at C2–C3, "due to the large amount of periarticular fat and lack of identifiable muscle
  boundaries at this level" (mean bilateral MF & SCER CSA 231.77 ± 83.37 mm² at C2–C3 in
  a 10-patient pathological sample). The joint this model cannot solve is the one level
  where the in-vivo literature itself declines to separate the two muscles. `:could-not-obtain`
  for any separated per-muscle value; the combined value is also from a pathological
  sample, so using it would load a patient population's atrophy into an asymptomatic model
  with the error direction **under**-stating the healthy cross-section.
- The lumped group also mixes in longissimus and spinalis cervicis, which none of the MRI
  studies above measures at all (they are absent from every ROI list found).

So the block is confirmed to be **a source that does not exist in vivo either**, not a
source nobody has looked for. The candidate moment arms in the table above (+16.74 mm at
neutral for the one-level multifidus) remain usable the day a measured PCSA appears; the
PCSA does not.

What *did* change at C2/C3: `longus_capitis` runs up the front of the column to the
basiocciput, so the level now carries an **anterior** line for the first time. Its
`:secondary-moment-nm` about `:c2c3` is reported (`tension-summary`'s
`:two-joint-unfed-nm` gained a `:c2c3` key), which is the same confession
`attachment/secondary-arm` makes for the two-joint muscles of the leg.

### What did not move

| | before | after |
|---|---|---|
| Hansraj multipliers | 1.0 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 / 4.830127018922192 | **identical to the bit** |
| `cervical-cross-check` ratio, `laptop-on-lap` | 1.718812573109 | **1.718812573109** |
| …`laptop-on-desk` / `external-monitor` | 1.412019204359 / 1.222043863200 | **identical to 12 dp** |
| `lumbar-cross-check` | 350.88684032499987 N, ratio 0.6356645658061592 | **byte-identical** |
| C7/T1, C6/C7, C5/C6 compression | 470.2991 / 339.4723 / 264.3976 N | **byte-identical** |
| `:task-over-supplied-nm` at `laptop-on-lap` | 3.5746 N·m | **3.5746 N·m** |
| suboccipital forces, all 365 swept postures | 0 N | **0 N** |
| worst muscle in the generated report | `erector_spinae` | **`erector_spinae`** |

> ⚠ **This table is that wave's, not today's.** Four of its rows moved on 2026-09-08 when
> the neck became a coupled group: the cervical cross-check ratios (1.7188 → 1.7036,
> 1.4120 → 1.3716, 1.2220 → 1.2494), the C7/T1 compression (470.2991 → 466.1425 N), the
> `:task-over-supplied-nm` row (**the key is gone**), and the worst muscle at
> `laptop-on-desk` (`erector_spinae` → `cervical_extensors`). The Hansraj multipliers and
> `lumbar-cross-check` are the two that are still byte-identical, and *why* they cannot move
> is now checked rather than stated. See **The coupled solve**.

The cervical cross-check ratio not moving is the point: neither flexor crosses C7/T1
(`longus_capitis` originates halfway up `lower_cervical`, above the level;
`rectus_capitis_anterior` runs C1 → skull and crosses no disc at all). The only rows that
moved are C4/C5, C3/C4 and C2/C3, by **+0.013 N each** at `laptop-on-lap` — the longus
capitis passive term at a posture where its active force is zero.

### What the upper cervical spine still cannot express

- ~~**The surplus.** 1.85× the flexors' capacity. It needs a coupled solve, not a muscle.~~
  **Closed 2026-09-08.** The coupled solve was built; there is no surplus.
- **Rectus capitis lateralis** — a lateral bender, and a midline sagittal model has
  nowhere to put it. Also unmeasured by the source.
- **Longus colli**, all three parts. Its superior oblique part is expressible (above); its
  vertical part runs within `lower_cervical` at both ends and is the known error shape;
  none of the three has a published PCSA here.
- **`rectus_capitis_anterior`'s length** is checked against nothing, because the source
  does not contain the muscle.
- **Both flexors are modelled midline**, so longus capitis's ipsilateral rotation — which
  Vasavada attributes to its superomedial fascicle orientation — is absent, as is rectus
  capitis anterior's lateral bending.
- **`upper_cervical` is still 37 mm where an atlas plus axis is nearer 50**, so
  `rectus_capitis_anterior`, which spans it, is short for the same reason
  `rectus_capitis_posterior_minor` is.

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

| `:two-joint-unfed-nm` | a two-joint muscle is pulling on a second joint whose equilibrium was not told about it | ~~a solver with more than one equality constraint~~ — **built 2026-09-08**, so this now contains only `:c2c3`, the one joint with no equilibrium at all |

**The stiffness index saturates and now says so.** It is mathematically in [0,1) but
reaches exactly 1.0 in double precision once the dose passes ~37 — roughly 50 %MVC
held for two hours, which is an ordinary posture. Two postures, one twice as bad as
the other, both read 1.00. `:saturated?` marks them.

**And it is blind at the bottom too, which nothing said (2026-09-07).** The
saturation above is a ceiling; the fault at the other end is a *gap*, and it does
more damage because it lands on the band — the column a reader actually reads.
The index steps from 0.053 to 0.829 across 0.001 %MVC at the 8 %MVC endurance
floor, which is wider than `moderate` and `high` put together, so **neither band
can be reached at all** at 120 or 480 minutes. Measured on the three reference
workstations at 120 min the 144 entries come out `{low 99, very-high 17,
not-computed 28}`, and the 17 `very-high` rows span 8.3 to 57.4 %MVC — a factor
of 6.9 in load and 86 in dose — under one word. The band function, its
thresholds and every coefficient are unchanged; what is new is
`strain/band-resolution` and `strain/floor-discontinuity`, which compute the
damage by inverting the model, plus `:dose` and `:index-resolution` on every row.
`lexicon_conformance_test`'s comment that *"`moderate` and `high` are legitimate
and no reference posture lands in them"* is now false in its second half and
should be corrected: nothing can land in them. See **The headline band carries
one bit** above.

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

> ⚠ **CLOSED 2026-09-08 — this is now the part the model *does*.** *"There is no closed
> form of that shape for two"* is true and was never the obstacle: the coupled problem has
> one **dual** variable per constraint, and the forces are still closed-form in the
> multipliers. `muscle/coupled-groups` solves the hip, the knee and the ankle of one leg
> together, so `:two-joint-unfed-nm` no longer contains any lower-limb joint — the deep
> squat's 3.6348762211480548 N·m at each hip is **fed**, and
> `:coupled-residual-nm` is 0 to floating point at all six lower-limb joints. What is left
> in that map is `:c2c3` alone. See **The coupled solve**. The test that asserted the unfed
> moment is non-zero has been rewritten to assert the opposite, with the discriminating half
> the inversion needs: the hamstrings must actually be **recruited** in a squat (456.81 N,
> where the uncoupled solve refused them as acting the wrong way at the knee), and the
> gluteus maximus must be doing **less** than the 1072.30 N it did without them.

Which joint is primary is anatomy, not preference — and since 2026-09-08 it decides no
force, only which task a muscle is emitted under and therefore which of its two joints the
row calls *secondary*. The hip has two one-joint
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

> ⚠ **Re-measured 2026-09-08 under the coupled solve.** Deep squat: vasti **36.4 %MVC**
> (1528.95 N, up from 1116.12), gluteus maximus **43.3 %** (836.02 N, down from 1072.30),
> hamstrings **23.00 %** (456.81 N, from a refusal), rectus femoris **`:inactive?`** (from
> 86.54 N). In quiet standing the vasti are no longer an antagonist: the coupled optimum
> gives them **1.10 %MVC**, a co-contraction the per-joint solve could not express, and the
> assertion is now that it is *small* rather than that it is absent — a large one would be
> a finding about the model. The 240-posture sweep still carries every load.

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

**The report did not run (fixed 2026-09-07).** `kbb -M -m suji.methods.analyze`,
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

## The coupled solve (2026-09-08)

Three separate waves hit the same wall and each recorded it rather than working around it.

| where | what was reported | how big |
|---|---|---|
| the lower limb | `:two-joint-unfed-nm` — rectus femoris, the hamstrings and gastrocnemius each span two joints, were solved at one, and pulled on the other | **3.6348762211480548 N·m at each hip in a deep squat** |
| the atlanto-occipital joint | `:task-over-supplied-nm` — the two capitis muscles, sized at C7, exerted 6.223 N·m where the joint above demands 2.648 (×2.35) | **3.5746 N·m**, and absorbing it would cost **204.04 N against 110.40 N available — 1.85×** |
| `longus_capitis` | a `:c2c3` entry in `:two-joint-unfed-nm` | 1.9e-4 N·m at `laptop-on-lap` |

Each of them ends in the same sentence, written by a different agent: *a coupled solve over
both constraints, which `recruit`'s closed form does not have and which no further muscle
supplies.* `recruit/solve` is that solve.

### The derivation, and how it was checked

Minimise Σ (F_i/a_i)³ over **F ≥ 0** subject to **m** linear equalities **C·F = T**, with
a_i the force available to muscle *i* at this posture. The objective is convex and
**separable** and the constraints are linear, so the Lagrangian splits per muscle. Write
**s_i = Σ_k λ_k C_ki** — one number per muscle, the price its own coefficients fetch at the
current multipliers. Stationarity in F_i is

```
3 F_i² / a_i³ = s_i        ⇒        F_i = a_i^{3/2} √(s_i / 3)
```

and the KKT condition for the bound F ≥ 0 is that a muscle whose price is non-positive sits
at the bound — at F_i = 0 the objective's slope is 0, so the bound's multiplier is −s_i, and
−s_i ≥ 0 means s_i ≤ 0. Both branches are **one expression**:

```
F_i(λ) = a_i^{3/2} √( max(s_i, 0) / 3 )                              (★)
```

so there is **one dual variable per constraint** — two for the neck, three for one leg — and
not one per muscle. Substituting (★) back gives a concave function of λ alone whose gradient
is the negated residual and whose Hessian is −Σ_{s_i>0} (a_i^{3/2}/(2√3 √s_i)) C_·i C_·iᵀ.

**Non-negativity is the part that needs care, and (★) is how it is handled.** The active set
is exactly {i : s_i(λ) > 0}, read off the multipliers at every iterate rather than guessed
and corrected; a muscle can leave it and come back as λ moves. There is no combinatorial
search over subsets and no pivoting. A muscle whose price goes negative **is not pulling
backwards — it is switched off**, and the row says `:inactive?` with the price beside it
rather than `:refused`, because the model did not decline to answer: it computed the force
and the force is zero. That distinction is the same one this actor already keeps between *a
placed load of zero* and *a load this model could not place*.

**Checked against the one-constraint case, which is the first test.** With m = 1,
s_i = λ c_i, and for c_i > 0

```
F_i = a_i^{3/2} √(c_i) √(λ/3)   and   Σ c_i F_i = T
⇒ F_i = a_i^{3/2} √(c_i) · T / Σ (a_j c_j)^{3/2}
```

which is `recruit/share` term for term; muscles with c_i ≤ 0 get s_i ≤ 0 and F_i = 0, which
is `share`'s `:acts-the-wrong-way`. `recruit-test/the-coupled-solver-reproduces-the-closed-form`
asserts the agreement over five one-constraint cases, and
`the-iteration-and-not-the-guess-is-what-lands-on-the-closed-form` re-runs it from four
deliberately wrong starting multipliers — because `solve`'s own initial guess **is** the
closed form when there is one constraint, so without that second test the first would pass
at iteration zero and say nothing about the loop.

⚠ **The `3` in (★) is not a number that can be wrong**, and that is worth knowing before
trusting a test that appears to check it. Replacing `s/3` with `s/2` is exactly a rescaling
of λ, so every force is unchanged; measured 2026-09-08, the reproduction test above passes
with it broken and only `the-iteration-count-is-the-same-on-both-hosts` notices (4 → 16
iterations, and the answer moves in the 10th digit).

### The iteration: Levenberg–Marquardt, and why not on the dual

q is concave and C¹, so the textbook move is a damped Newton ascent with an Armijo backtrack
on q itself. That was the first implementation and it **stalls at a relative residual near
1e-9**: q is a difference of two comparable quantities of order 1e-2, so the improvements
that remain once the equilibrium holds to nine digits are below what a double can represent
in q, and the line search can no longer tell an improving step from a worse one. Measured at
`laptop-on-desk`: the residual sat at **2.574e-9 N·m and did not move again in 2,000 further
iterations**. Levenberg–Marquardt on ‖g‖² — the quantity being driven to zero rather than a
functional of it — reaches **1.8e-15 in 19 steps**.

**A Jacobian force floor, because dF/ds is unbounded where F is not.** dF_i/ds_i = F_i/(2 s_i)
grows without bound as a price approaches zero from above even though the force goes to zero
with it, so a muscle producing *nothing* can set the conditioning of the whole linear system.
Measured seated with the hip at 90° and the knee straight: the ankle moment is 1.2e-15 N·m,
tibialis anterior comes out at a price of 1.2e-35 and a force of 3e-14 N, and its Jacobian
entry is **1e17 times the vasti's**; the solve spent 60 iterations crawling from a residual
of 9.94 N·m to 0.023 and refused. Muscles below 1e-12 of the largest force in the group are
left out of the **derivative** and not out of the answer; with the floor it converges in
**five**.

### Convergence across the posture space

Swept 3,000 postures — seated and standing × head −15…60° × trunk 0…45° × hip 0…120° ×
knee 0…120° × ankle −20…20°:

| | |
|---|---|
| groups that did not converge | **0** |
| worst `:coupled-residual-nm` over every joint of every posture | **1.70e-9 N·m**, which is **1.91e-11** of the largest load in its group |
| postures with a refusal that is not an antagonist | 250 (500 rows) — all of them the **scalenes** at `:coefficient-below-floor` in `:cervical-lateral-flexion`, which is not a coupled task and did not change |

Before the Jacobian floor the same sweep had **one** family of non-convergent postures (hip
90°, knee 0°, seated — 15 of 3,000), and what it did there is what it still does anywhere it
cannot converge: it **refuses the whole group** with `:coupled-solve-did-not-converge` and
returns **no force for any of its muscles**. A multiplier vector that has not converged
produces forces that satisfy no equilibrium, and those are indistinguishable from solved ones
once they are in a table. `recruit-test/the-coupled-solve-refuses-rather-than-returning-an-unconverged-iterate`
drives that path by capping `max-iterations` at 1.

### What is coupled, and what is deliberately not

```clojure
muscle/coupled-groups
  :neck        [:c7 :atlanto-occipital]        cervical-extension, atlanto-occipital-
                                               extension, atlanto-occipital-flexion
  :lower-limb  [:hip :knee :ankle]  per side   hip-extension, knee-extension,
                                               ankle-plantarflexion
```

Everything else stays on `share`. **That is not a gap**: a task whose muscles cross exactly
one joint has no coupling to represent, and the closed form is the exact optimum for it — the
same optimum `solve` finds, which is what the reproduction test checks. The shoulder, the
girdle, the elbow, the wrist, the trunk and the two lateral-flexion tasks are unchanged **to
the bit**.

### %MVC goes UP, and that is correct

A single-constraint optimum is a **lower bound** on the cost of the coupled one: every point
feasible for the coupled problem is feasible for each of its constraints taken alone, so
satisfying more constraints cannot lower Σ(F_i/a_i)³. `recruit/cost` reports the criterion's
value and `recruit-test/coupling-cannot-lower-the-cost` asserts the inequality, because *the
numbers went up* and *the solve is wrong* look the same from outside. Nothing here is tuned
back down.

### What moved, at `laptop-on-lap`, 70 kg / 1.70 m

| | before | after |
|---|---|---|
| `:two-joint-unfed-nm`, deep squat | `{:hip/left 3.6348762211480548, :knee/left -0.9496, :hip/right …, :c2c3 0.0}` | **`{:c2c3 -0.0585, :c7 -1.8428}`** — see the two survivors below; every lower-limb joint is gone from it |
| `:coupled-residual-nm`, every joint of every group | — (did not exist) | **0 to floating point** |
| `:task-over-supplied-nm` at the atlanto-occipital joint | 3.5746 N·m | **key removed with the decomposition that produced it** |
| `:atlanto-occipital-surplus-mvc-pct` | 184.82 | **key removed** |
| `cervical_extensors` | 22.0757 %MVC (138.3152 N) | **39.1799 %MVC (245.4814 N)** |
| `semispinalis_capitis` | 19.3959 % (125.2487 N) | **15.7275 % (101.5604 N)** |
| `splenius_capitis` | 17.6027 % (87.2923 N) | **0.7692 % (3.8142 N — passive only)** |
| `sternocleidomastoid` | `:refused :acts-the-wrong-way` | **0.0 %MVC, `:inactive?`** |
| `longus_capitis` | 0.0120 % (0.0133 N) | **9.9405 % (10.9741 N)** |
| `rectus_capitis_anterior` | 0.6102 % (0.3580 N) | **6.8055 % (3.9934 N)** |
| `iliopsoas` (each side) | 0.0 % | **5.9125 % (55.4863 N)** |
| `hamstrings` (each side) | `:refused :acts-the-wrong-way` | **2.9676 % (59.8430 N)** |
| `soleus`, `gastrocnemius` (each side) | `:refused :acts-the-wrong-way` | **0.0 % / 0.1620 %** |
| `max-mvc-pct` | 57.4931 (`erector_spinae`) | **57.4931 (`erector_spinae`) — unchanged** |
| antagonists / inactive | 11 / — | **4 / 11** |
| worst muscle in the report, `laptop-on-desk` | `erector_spinae` dose 7.33 | **`cervical_extensors` dose 13.64** |
| deep squat, `gluteus_maximus/left` | 1072.3012 N (55.53 %MVC) | **836.0213 N** — the hamstrings are helping at the hip |
| deep squat, `hamstrings/left` | `:refused :acts-the-wrong-way` | **456.8078 N (23.00 %MVC)** |
| deep squat, `rectus_femoris/left` | 86.5447 N | **0.0 N, `:inactive?`** |
| deep squat, `vasti/left` | 1116.1159 N | **1528.9518 N** |

The lower-limb pattern is the one a squat actually has: the hamstrings extend the hip **and**
flex the knee at once, which lets the gluteus maximus do less and makes the vasti do more.
The uncoupled solve could not represent it — it refused the hamstrings for acting the wrong
way at the knee, which taken alone they do.

### The cross-checks

| | before | after | |
|---|---|---|---|
| `cervical-cross-check` ratio, `laptop-on-lap` | 1.7188125731089365 | **1.7036215508461914** | level force 470.2990645067 → **466.1425184871**; lumped **273.6185852166 unchanged** |
| …`laptop-on-desk` | 1.4120192044 | **1.3715734227** | |
| …`external-monitor` | 1.2220438632 | **1.2494140835** | it moved the **other way** here |
| `lumbar-cross-check` | 350.88684032499987 N, ratio 0.6356645658061592 | **byte-identical** | Wilke's posture is upright standing, where `:model-muscle-n` is 0.0 — no muscle force enters it, so nothing `recruit` does can reach it. `:within-reference-spread? false` as before. |
| Hansraj multipliers | 1.0 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 / 4.830127018922192 | **identical to the bit** | verified rather than assumed: `load/cervical-load` does not call `recruit` |
| `strain/session-cross-check` | `:compared 13`, `:acts-the-wrong-way 11`, `:model-returns-no-finite-endurance 20`, `:no-published-curve-for-this-region 10`, `:no-mvc 2` | **`:compared 13`**, `:acts-the-wrong-way 4`, `:model-returns-no-finite-endurance 26`, `:no-published-curve-for-this-region 11`, `:no-mvc 2` | the **disagreement itself did not move** — the same 13 rows are compared against Frey Law & Avin and give the same answer. What changed is the bucket of rows that could not be compared: seven muscles stopped being refused and became lightly loaded instead, which puts them under the endurance floor. |

**Two of the three cervical ratios moved toward 1 and one moved away, and none of that is a
validation.** The lumped side is the one Hansraj anchors. A profile that agreed with it
exactly would still be unvalidated, and this repo has now recorded that four times.

### What the coupled solve still cannot express

- **`:c2c3`.** `longus_capitis` crosses it and **no equilibrium in this model covers it**, so
  its moment there is still reported as `:two-joint-unfed-nm` and still fed to nobody. The
  blocker is unchanged and is **provenance, not the solver**: see *C2/C3: expressible, and
  blocked by provenance*. This is the one survivor of the original finding.
- **`:c7`, and this one is new — found by asking what else the closed solve was hiding.**
  Upper trapezius and levator scapulae run past the cervicothoracic junction (occiput and
  nuchal line → lateral clavicle; upper cervical transverse processes → scapula), so both
  exert a moment about C7. `spine/levels-crossed` has always put them across C7/T1 and their
  FORCE has always been in that level's compression; the MOMENT was reported by nobody.
  Measured at `laptop-on-lap`: **−0.3786 N·m against a C7 demand of 4.9762, i.e. 7.6%**;
  −1.8428 N·m in a deep squat, where the arms are held out. They now declare
  `:crosses {:joint :c7}`, so it is a number at every posture rather than this paragraph.
  **They are not in the `:neck` group and the obstacle is the solver's INPUTS, not the
  solver:** their own equilibrium is a suspension balance — a force, with a dimensionless
  direction cosine for a coefficient — and the neck group's rows are moments.
  `recruit/solve` can take rows in different units, because each multiplier carries the
  reciprocal of its own row's; `attachment/coupled-arms` supplies moment arms and has
  nothing to say about a suspension coefficient. And even with the plumbing, this model
  would decline: **both arms are below `recruit/min-coeff`** (−3.22 mm and −1.13 mm against
  a 5 mm floor), which is the floor that says a straight line has no business claiming
  leverage that close to a joint. See
  `muscle-test/the-girdle-suspension-muscles-load-c7-and-are-not-in-its-group`.
  ⚠ Declaring that crossing also turned up a latent bug in `attachment/mirror`: it
  side-qualified **every** crossed joint, so a paired muscle crossing a MIDLINE joint got
  `:c7/left`, which `pose` has no joint for — the arm came back nil and the moment was
  silently not reported. It now uses the same paired-joint set `:acts-about` does.
- **Co-contraction at the atlanto-occipital joint is now predicted, and its size depends on a
  `:representative` number.** Semispinalis and splenius capitis have a *larger* arm about the
  atlanto-occipital joint than about C7 — 26.8 and 32.9 mm against 12.0 mm, where the C7
  chord is sitting on the cervical column's wrapping radius — so any force they produce for
  C7 over-extends the joint above, and the cheapest way to close both is a little flexor
  activity. That is what a coupled Crowninshield–Brand optimum says given these arms, and it
  is a real phenomenon; but the 12.0 mm radius is `:representative` and the effect is
  sensitive to it. Measured over 36 postures: **11.23 %MVC** is the worst forward-head
  co-contraction and **38.06 %** the worst genuine flexion demand (head tipped back), against
  the **184.82 %** that assigning the old surplus would have produced.
- **The suboccipitals still carry nothing at a desk**, and the reason is better than it was.
  It used to be that their load was floored at zero by a decomposition; now the joint *has* a
  load, the load is *met*, and the coupled optimum simply prefers the muscles that were going
  to cross that joint anyway. They do take force where the optimum wants them — 0.32 / 0.11 /
  0.26 N at head −55° on a trunk flexed 75° — which is what makes the desk zero a measurement
  rather than a constant.
- **A muscle cannot be in two coupled groups.** `coupled-groups` is a partition. Nothing in
  this anatomy needs to be in two, but a trunk model that coupled the lumbar spine to the hip
  would.
- **Nothing is coupled across the midline.** The two legs are solved separately because they
  share no muscle. A model with a muscle spanning the midline would need one group for both.
- **It is still a static optimum.** No co-contraction for stability, no history, no
  activation dynamics — a coupled static optimum predicts *less* co-contraction than a body
  produces, not more.

## The pelvis had no rotation (2026-09-08)

`spine/lumbar-cross-check` refused Wilke's `relaxed standing` entry, and the reason
recorded here was precise: the model returned **the same 351 N at L4/L5 for standing
and for sitting**, because sitting and standing differed only *below* L5/S1 and the
lumbar spine could not tell. What separates Wilke's two figures is pelvic tilt and the
lordosis that goes with it, and this model's pelvis did not rotate. **A missing segment
had become a missing degree of freedom.**

Two more limitations were named in the same breath: the five lumbar levels shared one
orientation, and `spine.cljc`'s own docstring said *"There is no curvature: the model's
spine is four straight segments and no arc, so it has no lordosis and no shear
component."*

### The trunk is two segments now, hinged at T12/L1

| segment | spans | length (1.70 m) | mass (70 kg) | CoM from proximal |
|---|---|---|---|---|
| `lumbar` | L5/S1 disc → T12/L1 disc | 171.4 mm | 9.73 kg | 0.500 |
| `thorax` | T12/L1 disc → C7 | 318.2 mm | 15.12 kg | 0.553 |

`thorax_abdomen` is gone. New `:base` names for a renderer: **`lumbar`, `thorax`**, and a
new joint `:t12l1`.

**Why two and not five.** Five lumbar segments would give each level its own orientation,
which is anatomically better and is not honest here: it needs a mass and a centre of mass
*per vertebra*, which nothing publishes, and an angle for each of five joints where the
posture supplies one number. That is the argument `cervical-split` made against
per-vertebra necks and it is not weaker one region down. Said plainly: **the five lumbar
levels still share one orientation.** It is the lumbar's own rather than the thorax's, and
that is the whole change.

**Where the mass split came from: Winter, unlike the cervical one.** The neck split needed
`head-share-of-complex = 0.80` because three standard tables were checked and none of them
divides head from neck. This one did not have that problem — Winter divides the trunk at
exactly the place the split needs it:

| Winter 4e Table 4.1 | definition | mass frac |
|---|---|---|
| Thorax | C7–T1 / T12–L1 and diaphragm | **0.216** |
| Abdomen | T12–L1 / L4–L5 | **0.139** |
| Thorax and abdomen | C7–T1 / L4–L5 | 0.355 ← the row this model was already using |

and 0.216 + 0.139 = 0.355 exactly. Full text read 2026-09-08 from
<https://courses.grainger.illinois.edu/me481/sp2021/Anthro-Winter.pdf>.

The **boundary** at 0.35 of L5/S1→C7 is this model's own 0.07 lumbar level spacing
continued upward past L1/L2 at 0.28 — the same derivation as `lower-cervical-span`. It puts
the lumbar spine at 171 mm at 1.70 m stature against about 170 mm in an adult: a
plausibility check, not a measurement.

⚠ **One level of mismatch, inherited rather than created.** Winter's abdomen ends at L4–L5
and his pelvis begins there; this model's trunk ends at L5/S1. The model has always applied
Winter's 0.355 to a span one level longer than his and now applies his 0.139 the same way.

⚠ **And a discrepancy the split surfaced and deliberately did not fix.** Winter's own rows
put the trunk's centre of mass at **0.37** from the bottom; this model has used **0.50**
since it existed. `thorax-com-frac` is therefore *solved* to preserve the 0.50 (coming out
at 0.553, where Winter's thorax row says 0.18) rather than taken from Winter, for the same
reason `head-com-frac` was solved: correcting it would move every moment in the library at
once and make the lordosis result below unattributable. It is recorded in
`segment/trunk-com-frac` with the direction — a trunk CoM that high **over-states** every
trunk moment — so the next reader can take it.

### The pelvic tilt, and the lordosis that follows from it

`:pelvic-tilt-deg` (anterior positive, defaulting to 0.0) rotates the pelvis and turns the
lumbar spine's **lower endplate** with it. The upper endplate is held by the thorax. So:

```
lumbar lordosis  =  pelvic-tilt                    (the angle between the two ends,
                                                    which is what a Cobb L1–S1 measures)
lumbar chord     =  trunk-flexion + pelvic-tilt/2  (a circular arc's chord bisects its
                                                    two end tangents)
pelvis segment   = -pelvic-tilt                    (tipping the sacrum forward carries
                                                    the femoral heads back)
```

Nothing is chosen there. **Constant curvature is the assumption**, and it is stated.

**The neutral is measured, not convenient.** The model's neutral is a straight lumbar spine,
and Wilke's one comparable posture is *"relaxed sitting on a stool with a normally straight
back"*. Cho, Park, Park, Kim, Jung & Lee, *"The Effect of Standing and Different Sitting
Positions on Lumbar Lordosis: Radiographic Study of 30 Healthy Volunteers"*, **Asian Spine J
2015;9(5):762–769** (full text read 2026-09-08 from
<https://www.asianspinejournal.org/journal/view.php?doi=10.4184%2Fasj.2015.9.5.762>)
radiographed 30 healthy volunteers — 31.1 y, **73.6 kg, 175 cm**, close in build to Wilke's
45-year-old, 70 kg, 168 cm subject — and measured Cobb L1–S1:

| posture | lordosis |
|---|---|
| standing | **47.1° ± 10.5°** |
| chair with lumbar support | 36.2° ± 8.4° |
| 90°-angled chair | 17.7° ± 4.4° |
| **stool** | **0.6° ± 3.6°** |
| chair with anterior support | −4.9° ± 3.3° |
| cross-legged | −7.4° ± 3.5° |

**A stool is straight to inside its own scatter.** So the model's neutral *is* Wilke's
posture, measured, and standing is 46.5° from it.

### What that did to Wilke — with the direction stated

| | before | after | direction |
|---|---|---|---|
| **sitting relaxed, no backrest** (the comparable entry) | 350.887 N, ratio 0.6357 | **348.862 N, ratio 0.6320** | **AWAY** from the reference — and it is *not* the lordosis, which is zero at this posture. It is the trunk mass split: Winter's abdomen is 20% denser per unit length than his thorax, so less mass sits above L4/L5 than an assumed-uniform trunk put there. `70 × 9.80665 × (0.93×0.355 − (0.8×0.139 + 0.216)) = 2.0251 N`, to five figures |
| **relaxed standing** | refused | **682.422 N, ratio 1.1374** | the entry produces a ratio for the first time, and **OVERSHOOTS** |

Neither is inside the reference's own spread. **The model brackets the measurement** — below
it when the spine is straight, above it when the spine is lordotic:

```
standing − sitting :   model 333.560 N        Wilke 48.0 N        ratio 6.95
lordosis that would reproduce Wilke's 48 N :  5.736°   (Cho measured 46.5°)
```

Dividing the chord's sensitivity by 8 brings the difference ratio to 1.03 — the same
statement from the other end. **That number is a diagnostic and is installed nowhere.** A
fudge factor of 1/8 would make this section read like a validation and would be worth
nothing; this repo has now recorded that five times.

The dominant cause is stated in `pose/lumbar-chord-tilt-deg`: **L5/S1 is the root of this
chain and does not move**, so tilting the lumbar chord translates the whole trunk anteriorly,
where a real pelvis rotates about the *hips* and L5/S1 itself moves back.

### The direction agreeing with Wilke is not evidence — and the first version of that control passed for the wrong reason

The model says standing loads L4/L5 more. So does Wilke. **That agreement is worth nothing**,
and finding out *why* took a break that produced no failure — which is the most valuable
result on this branch.

The control asserted that lordosis of either sign raises the compression, and explained it as
*"either sign carries the mass above the level off the load line"*. Breaking
`lumbar-chord-tilt-deg` so that it ignored the pelvic tilt entirely left the control **green**.
It should have gone red. What it was hiding:

- **The lordosis path is signed.** Freeze the pelvis so that only the chord follows the input:
  −46.5° gives **320.531 N against 348.862 N flat**. A posterior lordosis *unloads* the level,
  because all it does there is take a cosine.
- **A second path is not lordosis at all.** Eight muscle groups here originate on the **pelvis**
  — erector spinae, quadratus lumborum, latissimus dorsi, obliques, the posterior lumbar
  ligaments, and three of the lower limb — so rotating the pelvis moves their origins whatever
  the lumbar spine does. Posteriorly it swings them under L5/S1 and their moment arms collapse:
  lumbar held straight, −46.5° reports **2121.6 N**.

Path two swamps path one, so the model answers *"the tilted posture loads more"* for either
sign, and would have said *"sitting loads more"* just as confidently had Cho's two numbers gone
the other way. **The model's agreement with Wilke's direction is mostly a moment-arm
degeneracy.** It does not contaminate the standing figure, where the tilt is anterior and
lengthens the same arms rather than collapsing them.

The control now asserts the counterfactual at the real magnitude and goes red when the pelvis
is frozen; `pelvic-tilt-reaches-l4l5-by-a-second-path-that-is-not-lordosis` names the second
path.

### A limitation the degree of freedom created

A tilted level takes only `w × cos(chord tilt)` of the weight above it, and **this model
carries no shear**. At 46.5° of lordosis the weight term at L4/L5 falls 348.862 N → 320.531 N.
`a-tilted-level-drops-its-shear-and-nothing-carries-it` derives it from the chord rule rather
than pinning the number. `spine.cljc` used to say *"no curvature … so no lordosis and no
shear"*; half of that stopped being true and the other half got worse.

⚠ **This paragraph said "28.3 N of real load leaves the model and arrives nowhere", and that
understated the uncarried load by a factor of five** (corrected 2026-09-09). 28.331 N is what
leaves the **compressive** term, `W × (1 − cos)`. What appears **transverse** to the level —
the shear nothing here carries — is `W × sin`, and on Wilke's body that is **137.711 N**. The
two are different numbers and the smaller one was being quoted as the load that arrives
nowhere. `the-compression-the-tilt-drops-and-the-shear-it-creates-are-different-numbers`
derives both from the level's own axis and asserts that the second is several times the first.

### What could not be sourced

- **How lordosis divides between the sacrum and the lumbar discs.** Cho reports the
  correlations (r = 0.731 with sacral slope, r = −0.842 with pelvic tilt) and **not the
  partition**. This model has no vertebral wedging, so the pelvis carries all of it, which
  **over-rotates the hips and both legs** in a lordotic posture. Named in
  `posture/pelvic-tilt-for` with the direction of the error rather than split by a guess.
- **Pelvic incidence.** The morphological constant that fixes how much sacral slope a
  particular pelvis has needs a sacral endplate and a femoral-head geometry; this model has a
  rod from L5/S1 to the hip axis. So `:pelvic-tilt-deg` is a **change** from the straight-lumbar
  neutral, not an absolute pelvic tilt, and the model can compare two postures without being
  able to state either one's SS or PT. Wilke's comparison is a difference too.
- **Wilke's own subject's lordosis, in either posture.** Both reference entries now carry
  `:parameter-not-in-source`. The sitting one's is zero and measured, which is why it stays
  comparable; the standing one's is 46.5° from a different cohort with an SD of 10.5°, and the
  entry says so rather than letting a ratio imply that one paper supplied both halves.

### What did not move

| | |
|---|---|
| **Hansraj 2014 cervical anchor** | `1.0 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 / 4.830127018922192` — **byte-identical**, measured on both trees. `load/cervical-load` is a function of head tilt from vertical and the mass above C7, and the trunk split preserved the trunk's mass, length and centre of mass while the pelvis defaults to zero rotation. |
| cervical load, 3 reference workstations | 273.61858521664936 / 194.4819901245166 / 103.0363709306259 N — **byte-identical** |
| L5/S1 moment, 3 reference workstations | 58.5355504960427 / 16.140935480849357 / 12.253319657291728 N·m — 1 ulp, from a two-term sum replacing one term |
| every reference posture in the repo | still `:pelvic-tilt-deg 0.0` **on that branch**. `posture/quiet-standing` carried **zero** lordosis where Cho measures 47.1°, which was wrong and was deliberately left wrong there. It was done on 2026-09-09 — see *"Every posture's lordosis came from somewhere"* below. |

### The repo's own guard found the joint I had not named

`every-placed-joint-has-an-equilibrium-or-is-named-as-a-gap` refused the new `:t12l1`: the
split placed a joint and nothing acts about it. It is named now, beside `:c2c3`, with the
reason — this model states trunk extension as **one** equilibrium at `:l5s1`, its single
lumped erector spinae inserts *below* T12/L1 and does not even cross it, and carving segmental
fascicles out of a 34.0 cm² lump that is itself `:representative` would need a number to divide
it by that this repo does not have. Its absence makes no number wrong; what is unavailable is
any statement about the thoracolumbar junction at all.

## Every posture's lordosis came from somewhere (2026-09-09)

The pelvis learned to rotate on 2026-09-08 and **not one posture in the library was given a
lordosis**. Every one of them carried zero, and every one of them carried it the same way:
by never mentioning `:pelvic-tilt-deg` and letting `pose/lumbar-chord-tilt-deg`'s
`(or (:pelvic-tilt-deg posture) 0.0)` supply it.

Zero is the right answer for **exactly one** posture. Cho et al. measure a stool at 0.6° ± 3.6°
— straight to well inside its own scatter — and that is the posture Wilke's *"relaxed sitting"*
entry is taken in. It was also the answer for standing (Cho: **47.1° ± 10.5°**), for a deep
squat, and for three seated workstations.

**A number that is correct once and unset five times is worse than a wrong number, because it
reads identically to a measured one.**

### What each posture got

| posture | lordosis | basis |
|---|---|---|
| `quiet-standing` | **46.5°** | **measured** — Cho standing 47.1° ± 10.5°, minus her stool's 0.6°. Derived as `(pelvic-tilt-for :standing)`, not written as a literal |
| `seated-posture` (the constructor's default) | **0.0°** | **measured** — Cho stool 0.6° ± 3.6°, minus itself. The one zero in this library that is a measurement, and it is derived the same way |
| `standing-neutral` | 0.0° | **by construction** — every joint at neutral is the definition of the posture, and the lumbar spine is a joint. Not a claim about a body |
| `deep-squat` | 0.0° | **`:parameter-not-in-source`** — Cho radiographs standing and five *sitting* postures; a squat is not among them. Direction stated: a squat is the posture lordosis is most often reported to reverse in, so a zero here likely **over**-states it |
| `laptop-on-lap` | 0.0° | **`:parameter-not-in-source`** — a slumped unsupported sit. Cho's nearest rows are a stool (0.6) and a 90° chair (17.7) and it is neither. Her five sitting rows span −7.4 to 36.2, so the sign of the error is **not** stated |
| `laptop-on-desk`, `external-monitor+keyboard` | 0.0° | **`:parameter-not-in-source`** — a backrested desk chair. Cho has *chair with lumbar support* (36.2) and *90° chair* (17.7); `:back-supported` does not say which, and choosing would be fitting. Both candidates are positive, so this model **under**-states these postures' lordosis by somewhere between 17° and 36° |

`posture/lordosis-provenance` holds all seven, `posture/named-postures` is the enumeration it
is checked against, and `posture/lordosis-provenance-for` **refuses** a posture it has no entry
for rather than answering `nil` — nil and *"no lordosis"* are the same value, which is the exact
failure the table replaces. `posture-test` is the gate: add a posture without an entry and the
suite goes red.

Both Wilke reference entries now **derive** their tilts from the same table
(`(pelvic-tilt-for :standing)` / `(pelvic-tilt-for :stool)`) instead of carrying `46.5` and
`0.0` as literals. `47.1 − 0.6 == 46.5` to the bit in double, so **neither cross-check moved**.

### Installing the measured standing lordosis breaks four other measurements

This is the most useful result on the branch, and it arrives from a completely independent
direction from the sevenfold overshoot at L4/L5.

A real person standing still has **both** of these: about 47° of lumbar lordosis, and a line of
gravity 2–6 cm anterior to the ankle, which is why the plantarflexors are never off. **This
model can produce either one and not both**, because L5/S1 is the root of its chain — tilting
the lumbar spine *translates* the whole body forward instead of rotating the pelvis under a
trunk that stays put.

| quantity | lumbar neutral | Cho's 46.5° | measured range |
|---|---|---|---|
| line of gravity ahead of ankle | 0.03704 m | **0.13970 m** | 0.02–0.06 m |
| ankle moment, per side | −12.069 N·m | **−47.307 N·m** | 10–20 N·m |
| knee moment, per side | −0.4255 N·m | **−35.663 N·m** | standing is unloaded |
| soleus vs gastrocnemius | 159.3 > 71.2 N | **444.3 < 459.7 N** | soleus carries more |

**No range was widened.** The four assertions moved to `posture/quiet-standing-lumbar-neutral`,
a named control that is what `quiet-standing` used to be, and
`cho-s-standing-lordosis-and-the-measured-line-of-gravity-cannot-both-hold` pins all four sizes
in both configurations. It is also the control on the fix: remove the lordosis to make the leg
tests pass again and that test goes red.

Splitting the 10.27 cm of spurious translation between the two paths, measured with
`lumbar-chord-tilt-deg` **temporarily broken** to ignore the pelvic tilt (the break was
reverted; sha256 checked): **7.11 cm** of it survives with only the pelvis following the input
— the legs swinging with it — so the pelvis path is about 7.1 cm and the chord path about
3.2 cm. That number is a probe result rather than a quantity the shipped tree computes; there
is no way to freeze one path through the public API, and adding a knob to do it would put a
counterfactual in the model.

### The 7×, decomposed into five terms that sum to it

`spine/standing-sitting-decomposition` splits the 333.560 N this model puts between Wilke's two
postures. The terms sum to the difference with a residual of **5.7 × 10⁻¹⁴**.

| term | newtons | share | isolated by |
|---|---|---|---|
| `:lumbar-chord-cosine` | **−28.331** | −8.5% | `weight-standing = weight-sitting × cos(chord)`. The lordosis **unloads** this term |
| `:lumbosacral-moment-on-the-neutral-geometry` | **+384.375** | **+115.2%** | the standing lumbosacral moment (0 → **21.407 N·m**) over the *sitting* arm and the *sitting* projection |
| `:pelvis-origin-moment-arms` | **−25.775** | −7.7% | then swap the erector spinae's arm for the tilted one (0.054469 → 0.058384 m) |
| `:level-axis-under-the-muscle-line` | **+2.073** | +0.6% | then swap the projection (0.97802 → 0.98368) |
| `:other-crossing-muscles` | **+1.217** | +0.4% | everything crossing L4/L5 that is not the erector spinae — here, the two obliques |
| `:trunk-mass-split` | **0.000** | 0.0% | exactly zero: both postures carry the same weight above L4/L5 (348.86176710 N either way), so the 2.0251 N the T12/L1 split took off this level cancels |

**One term is larger than the whole difference.** ⚠ **This paragraph blamed the chain's rooting
until 2026-09-10 and that attribution was wrong** — see *The root was not the defect* below,
which measures it. What the 21.407 N·m actually is: `pose/lumbar-chord-tilt-deg` puts the lumbar
chord at `trunk + lordosis/2`, so 46.5° of lordosis under a vertical thorax tilts it 23.25° and
puts T12/L1 `L_lumbar × sin 23.25° = 6.685 cm` anterior to L5/S1, with the whole 367.945 N above
the level riding out on a 5.818 cm lever.

**The counterfactual runs the other way from Wilke, and that is the finding.** Take that term
out — leave the lordosis, remove the moment its chord creates — and what is left of standing is
its weight term alone, **320.531 N, which is *below* sitting's 348.862 N**. So this model's
agreement with Wilke's *direction* is produced by that one term: without it the model says
standing **unloads** L4/L5, and Wilke says it loads it.
`the-dominant-term-of-the-7x-is-the-lumbar-chord-and-not-the-root` asserts that sign.

**The second path is 7% and negative, not the story.** Eight muscle groups originate on the
pelvis (`posterior_lumbar_ligaments`, `erector_spinae`, `latissimus_dorsi`,
`quadratus_lumborum`, `obliques`, `gluteus_maximus`, `rectus_femoris`, `hamstrings`, counted
from the attachments rather than listed). At an **anterior** tilt their arms *lengthen* —
erector spinae +7.2%, quadratus lumborum −9.0%, obliques −8.0% — so the same moment costs
*less* force. The 2121.6 N reported for a posterior tilt with the lumbar spine held straight is
the same path with the arms collapsing instead. The asymmetry is why the model answers *"the
tilted posture loads more"* for either sign.

**Order matters and is stated.** Three of those terms are factors of one product, so
attributing them one at a time is order-dependent; the function swaps moment, then arm, then
projection, and says so. What is not order-dependent is the sum, which is asserted.

**The identity the split rests on is checked, not assumed.** The erector-spinae terms are
`moment / arm × projection`, which is the solved force only while the erector spinae is the one
trunk-extension candidate carrying load. `:chain-identity-residual-nm` is −7.1 × 10⁻¹⁵ and the
test refuses a split whose residual has grown — so the day a second extensor carries force,
this goes red instead of quietly attributing that muscle's share to the pelvis.

**Nothing was installed.** Dividing the chord's sensitivity by 8 still brings the difference
ratio to 1.03; that number remains a diagnostic and lives nowhere in the model. No divisor, no
fudge factor, no fitted constant was added on this branch.

### The cross-checks, before and after, with the direction

| | before | after | direction |
|---|---|---|---|
| Wilke `sitting relaxed, no backrest` | 348.86176709999995 N, ratio 0.6319959548913042 | **byte-identical** | **unchanged.** The tilt is derived now instead of written as `0.0`, and `0.6 − 0.6` is the same zero |
| Wilke `relaxed standing` | 682.4216680362731 N, ratio 1.1373694467271218 | **byte-identical** | **unchanged.** `47.1 − 0.6 == 46.5` in double, so deriving it moved nothing |
| standing − sitting | 333.55990093627315 N, ratio 6.949164602839024 | **byte-identical** | **unchanged, and now decomposed** rather than made smaller |
| `lordosis-matching-reference-difference-deg` | 5.7357797531487655° | **byte-identical** | **unchanged**, and still installed nowhere |
| Hansraj cervical anchor | `1.0 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 / 4.830127018922192` | **byte-identical**, probed on both trees | **did not move.** `cervical-load` is a function of head tilt from vertical and the mass above C7; `pose` places the skull at `trunk + head` and the pelvis is not in that chain |
| `cervical-cross-check`, `laptop-on-lap` | 1.703621550846191 | **byte-identical** | unchanged — no workstation's lordosis moved |
| `cervical-cross-check`, quiet standing | 1.7123137218782603 | **1.7123137218782603** | unchanged even though the posture's lordosis moved 0 → 46.5°, which is the control on the row above |
| endurance dose (`strain/session-cross-check`), quiet standing | **0 compared / 56** | **9 compared / 56** | **a change, and not obviously an improvement.** Nine muscles now work hard enough to have a finite endurance where none did, and the model/reference ratios are erector spinae 0.573 (`:trunk`), soleus 0.573 and gastrocnemius 0.496 (`:ankle`), gluteus maximus 0.577 (`:trunk`, `:nearest-region`), hamstrings 1.110 (`:knee`). The %MVCs behind them are 14–27%, which is the same overshoot arriving in the strain layer: they are driven by the invented lumbosacral moment and by the ankle and knee moments the translation creates, not by anything about standing. **More rows compared is not more agreement** — nothing here says the nine are right |
| endurance dose, three workstations | unchanged | **byte-identical** | no workstation's lordosis moved |
| L4/L5 at quiet standing | 363.96355817189135 N | **696.5922364394459 N** | **+91%**, from the same artefact — the invented lumbosacral moment. There is no reference for this posture at this level, so it is a movement and not a disagreement |

### What could not be sourced, this time

- **A lordosis for a deep squat.** Cho radiographs standing and five sitting postures. Nothing
  measured was found for a squat, so the model holds its neutral and says which way that is
  likely wrong.
- **Which of Cho's two chairs a `:back-supported` workstation is.** *Chair with lumbar support*
  (36.2° ± 8.4°) and *90°-angled chair* (17.7° ± 4.4°) are both plausible readings of the
  library's one boolean, and reading either one onto it would be choosing. The bracket is
  recorded instead.
- ~~**How much of the 10.27 cm of translation a real standing pelvis would remove.**~~ **Sized
  on 2026-09-10: none of it.** The hip-rooted chain now exists and the answer is that re-rooting
  is a rigid translation, so it removes nothing. The split is also exact now —
  `pose/line-of-gravity` gives 6.279 cm sacrum-over-feet, 3.156 cm trunk-over-sacrum and
  0.832 cm below L5/S1 — without the counterfactual the ≈7.1/≈3.2 estimate needed. See below.
- **Wilke's own subject's lordosis**, in either posture — unchanged from 2026-09-08, and still
  the reason both entries carry `:parameter-not-in-source`.

### Breaks that produced no failure

None. Twelve breaks were run against the twelve tests added here and every one of them went
red with a message naming the claim it broke; every file was restored byte-identically
(sha256 checked). Two are worth recording anyway:

- **The residual assertion does not catch mis-attribution *within* the erector-spinae chain.**
  Making the arm swap a no-op left the sum unchanged — the terms telescope — so
  `:residual-n` stayed at 10⁻¹⁴ and only the *pinned per-term values* caught it. Closure is a
  weaker check than it looks; it catches a term being dropped (tested separately, residual
  1.2169 N) and not a term being mis-priced.
- **The 2026-09-08 break that produced no failure now produces eleven.** Making
  `lumbar-chord-tilt-deg` ignore the pelvic tilt — the break that left the old
  direction-agreement control green — now fails `the-direction-agreeing-with-wilke-is-not-
  evidence`, all four of the new decomposition tests, and four clauses of the
  line-of-gravity contradiction.

## The root was not the defect (2026-09-10)

The chain is rooted at the support now — the feet when standing, the pelvis when seated. **It
changed no moment, no muscle force and no compression, and that is the result.** The 2026-09-09
section above blamed this model's largest disagreement with Wilke on where the chain was rooted;
that attribution was wrong, and this section is the measurement that says so.

### What was rooted where, and the argument from the kinematics

`pose/solve-pose` still *builds* the chain from L5/S1 outward — `spine/levels` states every level
as a fraction measured from L5/S1 upward, and that reading has to stay in one frame. What is new
is that the finished chain is then translated so the point **the world holds still** sits at the
origin (`pose/support-landmarks`, `pose/rooted-at`, recorded in the pose's `:root`).

| support | root | why that point |
|---|---|---|
| `:standing` | midpoint of the two ankles | the world holds the **feet**. The foot is a rigid segment whose tilt is set by world-referenced joint angles, so holding the ankle holds the whole sole — contact patch, `ground-y` and `base-of-support` with it. The ankle is also the joint the entire ground reaction passes through on its way up the leg |
| `:seated` | base of the pelvis | `posture/support-mode` already said in words that the chair takes the trunk through the **ischial tuberosities**, and that the load reaches the seat without passing through hip, knee or ankle. This model has no ischium; the hip axis stands in for them, and the substitution costs nothing measurable because the offset between the two is a constant vector inside one rigid bone |

An unrecognised support mode is **refused**, not defaulted back to L5/S1: a chain nothing holds
and a chain whose support was not recognised must not be the same value.

**What that repaired.** Rooted at L5/S1, tilting the pelvis moved the **floor**. At Cho's standing
lordosis the soles travelled 11.7 cm posteriorly and 5.0 cm upward — the model saying a person who
arches their back slides their feet backwards and lifts off the ground. `ground-y` and
`base-of-support` are now byte-identical across a 46.5° pelvic tilt, with a control asserting that
something *did* move (the sacrum, 11.7 cm anteriorly) so the assertion is not a no-op.

### What it did not repair, and why it could not

**Every joint angle in this model is measured from the world vertical.** The chain's shape is
therefore complete before anything is anchored, and changing the anchor is a *rigid translation*.
A moment is `Σ weight × (x_com − x_joint)`; under a translation both x's move together. No moment,
no lever, no muscle force, no compression and no line of gravity can change.

`re-rooting-the-chain-moves-no-moment` measures it rather than asserting it: the same standing
posture rooted at `:l5s1`, `:pelvis-base` and `:mid-ankle` gives the same **22.513 N·m** lumbosacral
moment and the same line of gravity, with a control first that the three chains really are in
visibly different places, and a check that the summed segments are the ones
`load/lumbosacral-moment` sums rather than a hand-assembled lookalike.

**So the +115% term is not a rooting artefact.** It is the lumbar chord: `lumbar-chord-tilt-deg`
puts the chord at `trunk + lordosis/2`, which is where a circular arc's chord lies between its two
end tangents. At 46.5° of lordosis under a vertical thorax that is 23.25°, so T12/L1 sits
`L_lumbar × sin 23.25° = 6.685 cm` anterior to L5/S1 and the 367.945 N above the level rides out
on a 5.818 cm lever. **A lordotic lumbar spine really does put its top end in front of its bottom
one.** What is open is whether 46.5° of *lordosis* should be spent as 46.5° of *rigid pelvic
rotation*, which is what `pose/lumbar-lordosis-deg` makes it — and that is not tested here.

The old claim was also wrong in its **sign**: it said *"a real pelvis rotates about the hips and
carries L5/S1 backward"*. L5/S1 sits **above** the axis a pelvis turns about, so an anterior tilt
carries it **anterior** to the hip. `the-pelvis-actually-rotates` now derives the separation as
`L_pelvis × sin(tilt)` from the segment table instead of writing it, and asserts which end moves.

### The decomposition, before and after, term by term

| term | 2026-09-09 | 2026-09-10 | moved by |
|---|---|---|---|
| `:lumbar-chord-cosine` | −28.330641931507728 | −28.330641931507728 | **0** |
| `:lumbosacral-moment-on-the-neutral-geometry` | 384.37536398936294 | 384.37536398936305 | **1 ulp** (3 × 10⁻¹⁶ relative) |
| `:pelvis-origin-moment-arms` | −25.775144221675873 | −25.775144221675873 | **0** |
| `:level-axis-under-the-muscle-line` | 2.073382586920559 | 2.073382586920559 | **0** |
| `:other-crossing-muscles` | 1.2169405131731992 | 1.2169405131733129 | **1 ulp** |
| `:trunk-mass-split` | 0.0 | 0.0 | **0** |

The two that moved did so because the standing chain is now rooted at the ankles, whose x is not
zero, and `(a+t) − (b+t)` is not bit-for-bit `a − b`. **That drift is the evidence the translation
ran at all** — a re-rooting that changed literally nothing would be indistinguishable from one
that was never applied. Its `:sourced` tag is `:consequence-of-the-lumbar-chord-tilt` now, and
the entry says what it used to claim and why that was false.

### Does the model still agree with Wilke's direction? Yes, unchanged, and for the same reason

| | 2026-09-09 | 2026-09-10 | |
|---|---|---|---|
| Wilke `sitting relaxed, no backrest` | 348.86176709999995 N | **348.86176709999995 N** | **byte-identical**, pinned with `=`. A seated chain re-roots along y alone — its pelvis base sits at x = 0 — so every x is untouched |
| Wilke `relaxed standing` | 682.4216680362731 N | 682.4216680362733 N | **2 ulp** |
| standing − sitting | 333.55990093627315 N | 333.5599009362734 N | ratio 6.949164602839024 → 6.949164602839029 |
| `:same-direction?` | true | **true** | the model still says standing loads L4/L5 more than sitting, still about **seven times** too much, and still only because of the term above |
| Hansraj cervical anchor | `1.0 / 2.260021051801672 / 3.366025403784438 / 4.242640687119285 / 4.830127018922192` | **byte-identical** | probed on `3e4efb3` in a separate worktree and on this branch, not assumed. `cervical-load` is a function of an angle and a mass; no coordinate reaches it |

`the-two-wilke-cross-checks-are-pinned-at-full-precision` is new, and it closes a gap this README
created: **both entries have been called byte-identical through four waves of change and nothing
enforced it.** The assertions in place were `ratio < 1 < ratio` and `difference-ratio > 5`, which
would not have noticed a 10 N move in either.

### The four leg/balance quantities: unchanged, to within 4 ulp

This is the check the task set for whether the fix is real rather than a re-parameterisation, and
**it says the change is a re-parameterisation.** No quantity moved toward its measured range.

| quantity | lumbar neutral before → after | Cho's 46.5° before → after | measured |
|---|---|---|---|
| line of gravity ahead of ankle | 0.03703697189273052 → …54 | 0.13970138699304935 → …43 | 0.02–0.06 m |
| ankle moment, per side | −12.0692894805646 → −12.069289480564604 | −47.307079002588544 → −47.30707900258857 | 10–20 N·m |
| knee moment, per side | −0.42553777096251155 → −0.4255377709625161 | −35.66332729298645 → −35.66332729298649 | standing is unloaded |
| soleus vs gastrocnemius | 159.3203987394856 > 71.24916196814029 → 159.32039873948565 > 71.24916196814036 | 444.2919447834063 < 459.6954265419417 → 444.2919447834064 < 459.6954265419423 | soleus carries more |
| L4/L5 at quiet standing | — | 696.5922364394459 → 696.5922364394462 N | no reference at this posture |

So **`cho-s-standing-lordosis-and-the-measured-line-of-gravity-cannot-both-hold` still holds, and
this change did not resolve it.** It could not: the contradiction is a statement about the chain's
*shape*, and the root does not touch the shape.

### What the hip-rooted chain did make measurable

2026-09-09 recorded one contribution as *named and not sized*: how much of the 10.27 cm of trunk
travel a correctly hinged pelvis would remove. **The answer is none of it**, and the reason is the
invariance above.

The split itself is now exact and shipped. `pose/line-of-gravity` decomposes the line of gravity
into three travels that sum to it as an identity (residual < 10⁻¹⁶), where 2026-09-09 had to break
`lumbar-chord-tilt-deg`, run the model and revert to approximate two of them:

| term | quiet standing, lumbar neutral | Cho's 46.5° | difference |
|---|---|---|---|
| line of gravity ahead of ankle | 0.0370370 m | 0.1397014 m | **+0.1026644 m** |
| `:sacrum-over-the-feet-m` | 0.0195364 | 0.0823277 | **+0.0627913** (was estimated ≈7.1 cm) |
| `:trunk-over-the-sacrum-m` | 0.0012398 | 0.0327954 | **+0.0315556** (was estimated ≈3.2 cm) |
| `:everything-below-l5s1-m` | 0.0162608 | 0.0245783 | **+0.0083175** (was not separated at all) |

`:sacrum-over-the-feet-m` is the sacrum's travel discounted by the fraction of body weight that
rides on it — the legs are below L5/S1 and do not follow it — which is what makes the three terms
add up. Both the sums and every term's absolute size are pinned, in both postures.

### Breaks, including the one that produced no failure

Nine breaks, each restored byte-identically (sha256 checked against the pre-break file).

| break | result |
|---|---|
| standing rooted at `:l5s1` | 11 failures — the floor moves, the base of support moves, the ankle moves |
| `rooted-at` translates segment CoMs by 0.9 d (non-rigid) | 32 failures, including the moment invariance and the line-of-gravity identity |
| `rooted-at` made a no-op (`d = [0 0 0]`) | 11 failures, including the control that the three rootings differ |
| `:sacrum-over-the-feet-m` drops its weight fraction | 5 failures — the residual, in every posture |
| **a constant 1 cm moved from the trunk term into the sacrum term** | **0 failures** — see below |
| `lumbar-chord-tilt-deg` ignores the pelvic tilt | 36 failures, including both Wilke pins and the derived 6.685 cm |
| seated rooted at the ankles | 3 failures naming the seated root |
| `trunk-mass-split` `:lumbar` 0.139 → 0.140 | 39 failures, including the sitting entry's exact `=` |
| the pelvis rotation's sign flipped | 34 failures, including the derived `L_pelvis × sin(tilt)` |

**The fifth produced no failure, and the reason is worth more than the fix.** Two checks that both
look like per-term checks were blind to the same defect: the residual could not see it because the
sum is unchanged *by construction*, and the per-term pins could not either because they were
pinned on the **difference** between two postures, and a constant offset appears in both and
cancels. That is 2026-09-09's lesson about the erector-spinae chain — *closure is a weaker check
than it looks* — arriving by a different route in a split whose terms do not telescope. **A
difference of two pinned quantities is not two pinned quantities.** Both postures' terms are
pinned absolutely now, and the same break then fails four assertions naming the two terms it moved.

### What could not be done

- **Make the root change anything.** It is a rigid translation and the model is invariant under
  it. If that reads as a null result, it is a null result that removes a wrong explanation from
  four places in this repo.
- **Fix the contradiction between Cho's lordosis and the measured line of gravity.** It survives
  intact. The next thing to test is the identity `lumbar lordosis == a rigid rotation of the whole
  pelvis` (`pose/lumbar-lordosis-deg`), which is what spends 46.5° of Cho's lordosis change as
  46.5° of pelvic rotation and produces both travels above. Nothing here measures whether a real
  stool-to-standing pelvis rotates that far.
- ~~**Say whether 21.407 N·m is the right standing lumbosacral moment.**~~ **Superseded by the
  2026-09-11 wave below: the chord stopped being derived from the rigid-pelvis identity, so the
  standing lumbosacral moment is now a number the model computes (0.0496 N·m at Cho's 46.5°) rather
  than an unexplainable 21.407. That moment contributes 0.891 N to the standing–sitting force
  difference, and whether the 0.0496 N·m is *right* is a different, still-open question
  — see *The segmental lordosis crossed the standing–sitting difference* below.**
- **Give the seated chain a real ischial tuberosity.** The model has no ischium. The hip axis
  stands in for it, and because the offset is rigid this costs nothing measurable *today* — it
  will stop being free the moment a seat reaction is applied at a point rather than assumed to
  take the whole thigh.
- **Nothing was installed.** No divisor, no fudge factor, no fitted constant. Dividing the chord's
  sensitivity by 8 still brings the difference ratio to 1.03 and still lives nowhere.

**Counts.** 342 → **346** tests / 11925 → **11990** assertions on the JVM; 312 → **316** tests /
2708 → **2773** assertions on ClojureScript; lint **0 errors / 13 warnings**, unchanged.

**Honest R0**: design + runnable physics + a cervical model validated **along one line**.
Anthropometry / muscle / endurance parameters are `:representative` (G7); the cervical leg is
validated at `trunk = 0`, which is how Hansraj measured it and therefore all the anchor can say —
its response to trunk flexion is geometry the anchor does not constrain (2026-09-07). The muscle
%MVC leg is mechanistically grounded but illustrative. **The strain / dose leg is no longer "illustrative":
since 2026-09-07 it answers to Frey Law & Avin's meta-analysis of measured endurance times, and the
answer is that it agrees with the pooled curve and disagrees with the joint-specific ones — see
"The dose layer against the endurance literature" above.** The per-level spinal profile is
**not** validated, and since 2026-09-07 that is a measurement rather than a disclaimer: it disagrees
with the Hansraj-calibrated cervical model by about 70%, and with Wilke's in-vivo lumbar
pressure by about a factor of two thirds in the other direction. Both disagreements are computed by
`cervical-cross-check` / `lumbar-cross-check` and asserted by tests, so neither can quietly stop
being true. The cervical figure was `about a factor of two` until the crossing rule was repaired
later the same day. **The lumbar one moved on 2026-09-08, twice and in the same direction: away.**
The trunk was split at T12/L1 onto Winter's own non-uniform mass rows (350.887 → 348.862 N,
ratio 0.6357 → 0.6320), and the pelvis learned to rotate, which made Wilke's `relaxed standing`
entry comparable for the first time — where the model **overshoots**, at ratio 1.137. It now
brackets the measurement rather than matching either end, and separates the two postures by about
**seven times** too much. See "The pelvis had no rotation" above. **On 2026-09-09 that sevenfold
gap was decomposed into five terms that sum to it, and one of them is larger than the whole
difference: a lumbosacral moment that exists only because L5/S1 is the root of the chain and does
not move — **that description is the pre-2026-09-11 state and is superseded: the segmental-lordosis
wave below re-derived the chord from a measured shape, the lumbosacral moment fell from 21.407 to
0.0496 N·m (a 0.891 N contribution), and the standing–sitting difference crossed from a sevenfold overshoot to a thirtieth
undershoot with the sign flipped — see *The segmental lordosis crossed the standing–sitting
difference* below.** Every posture now
declares where its lordosis came from, and installing the standing one Cho measured breaks four
separately measured facts about quiet standing — see "Every posture's lordosis came from
somewhere" above. No hardware, no live member scan, no live kami-genesis backend. Cells `.solve()` raise
at R0; `load_solve` transitions are unit-tested.

## The segmental lordosis crossed the standing–sitting difference (2026-09-11)

The five lumbar levels stopped sharing one orientation, and the pelvis stopped spending the whole
lordosis. Both were measured rather than assumed, and the answer was that **this model crossed from
overshooting Wilke to undershooting him, and the direction of the disagreement flipped** — the model
now says standing loads L4/L5 *less* than sitting, which is the opposite of the measurement. This is a
movement, not a validation, and the numbers below are the measurement.

### What was measured, and where

Two numbers that were previously **assumptions** of `pose/lumbar-chord-tilt-deg` were replaced by
measurements of the *same* posture change, so neither imports a second population. Both are from
Mills ES, Richardson MK, Wang JC, Chung BC, Romoff M, Heckmann ND, *Defining the relationship between
the hip, pelvis, and lumbar spine*, **N Am Spine Soc J 2026;26:100883** (DOI `10.1016/j.xnsj.2026.100883`,
PMID `42212188`, PMC `PMC13213316`; full text read 2026-09-11 through the Europe PMC REST API, because
PubMed's HTML serves a cookie page and Europe PMC's article pages are JS-rendered — the REST endpoint is
the one that answers). 50 asymptomatic volunteers aged 18–35, three lateral radiographs each. Transcribed
into `posture/lumbar-segmental-shares` and `posture/sacral-slope-share-of-lordosis`, not rounded:

| number | value | what it replaces |
|---|---|---|
| sacral-slope share of a lordosis change | **0.586** (ΔSS 16.7 / ΔLL 28.5) | the identity *lumbar lordosis == a rigid rotation of the whole pelvis* — the pelvis spends **0.586** of the change, not all of it |
| chord's turn fraction | **0.5848** (segmental 25.7 / 27.3 / 21.5 / 14.8 / 10.7%) | the circular-arc assumption that puts the chord at exactly 0.5 |

The two nearly cancel: the chord tilts **(0.586 − 0.5848) × 46.5° = 0.052°** at Cho's standing lordosis,
against the **23.25°** it returned until 2026-09-11. `pose/lumbar-chord-tilt-deg` computes it; the chord
no longer sits 6.685 cm anterior to L5/S1, it sits 0.0155 cm anterior.

⚠ **0.052° IS A DIFFERENCE OF TWO NEARLY EQUAL RATIOS, AND ITS SIGN DOES NOT SURVIVE THE
TABLE'S OWN LAST DIGIT** (probed 2026-09-10, bot/suji-anatomy — a diagnostic, installed nowhere).
0.586 is `16.7/28.5` and 0.5848 is `∫c(t)dt` over the standing segments `13.4/14.2/11.2/7.7/5.6`
(normalised by their 52.1 sum). Every input is published to 0.1°, so re-running both ratios with
each input at either end of its last digit — 2 × 2 × 2⁵ = 128 combinations — moves the chord tilt
over **−0.134° to +0.239°, and 42 of the 128 combinations make it NEGATIVE**: the small anterior
tilt this wave installed can be an equally small posterior one without any transcription being
wrong. The 0.0496 N·m standing lumbosacral moment and the 0.891 N it contributes sit inside that
band and inherit it. The *size* of the reduction (23.25° → ~0.05°) is robust — it is the sign and
the last digit that are not — so nothing here changes a value; the wave's own caveat ("whether
the 0.0496 N·m is *right*") now carries a bound: it is right to within a factor the source's
printing precision does not resolve.

### What crossed — measured on origin/main (`d29095b`), 70 kg / 1.68 m

Run against `origin/main` itself, not a branch:

| quantity | before (rigid pelvis + circular arc) | after (measured shape) | direction |
|---|---|---|---|
| standing − sitting, L4/L5 | +333.560 N (ratio 6.95) | **−10.859 N (ratio −0.226)** | the model **crossed**: it now undershoots by about a thirtieth of Wilke's 48 N |
| `:same-direction?` | **true** | **false** | the model now says standing loads L4/L5 **less** than sitting — the opposite of Wilke's 0.50 : 0.46 |
| lumbosacral-moment term | +21.407 N·m | **0.0496 N·m** (→ +0.891 N on the difference) | fell by a factor of about 431 (21.407 / 0.0496); the mechanism is unchanged, the size is — and it is no longer 115% of the difference |
| Wilke `relaxed standing` | 682.422 N, ratio 1.137 | **338.002 N, ratio 0.563** | no longer "overshoots" — it sits below the spread, as the sitting entry does |
| Wilke `sitting relaxed` | 348.86176709999995 N | **byte-identical** | the sitting posture's lordosis is Cho's stool minus itself (0.0), so the wave cannot reach it |
| `lordosis-matching-reference-difference-deg` | 5.736° | **nil** | no lordosis inside Cho's measured 46.5° reproduces Wilke's 48 N, so it refuses rather than returning an endpoint |

The five lumbar levels also took their own orientations from the measured shape; at 46.5° they stand
**27.19 / 15.24 / 2.56 / −7.43 / −14.31°** from the chord (L5/S1 → L1/L2), so the sacral end leans
anteriorly and the top of the lumbar spine leans back. That is what a lordosis is, and it is why the
`:lumbar-chord-cosine` term is now −12.34 N (the level's own axis is 15.24° from vertical) rather than the
≈0 it was when all five shared the chord's frame. The 91.98 N of shear the same tilt creates is still
carried by nothing — the wave did not add facets or anulus.

### The caveat the numbers carry

The ratio is measured over a **28.5°** lordosis change (Mills standing → relaxed-seated at 90° hips) and
applied to this model's **46.5°** one. It is a ratio, stable to about 7% of itself in the source
(flexed-forward gives 0.63, R = 0.85), but it is **not Wilke's subject and not Cho's cohort** — Wilke's
man was 45 and 70 kg, Cho's 30 volunteers were 31 y and 73.6 kg, and Mills' 50 are 25.7 y with only their
BMI stated. The model *crossed*; it did not become *right* about the size. Nothing here says the sign
flipped because the model is now correct — the chord's turn fraction and the pelvis's share are both
sourced ratios, and the direction of the error is stated, not removed.

### What this means for the sections above

Every *"current"* claim in *The pelvis had no rotation*, *Every posture's lordosis came from somewhere*
and *The root was not the defect* about the standing entry, the lumbosacral moment and the standing–
sitting difference is the **pre-2026-09-11** state. The two that read as conclusions now point at this
section: *"say whether 21.407 N·m is right"* (now a computed 0.891) and the Honest-R0 *"contradicts
Wilke's direction … seven times too much"* (now *the opposite direction, a thirtieth too little*). The
Hansraj cervical anchor and the Wilke **sitting** pin are byte-identical, because neither is on the
lordosis/chord path — `load/cervical-load` is a function of head tilt and the mass above C7, and the
sitting posture's lordosis is the one zero that is a measurement.
