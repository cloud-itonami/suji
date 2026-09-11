#  — CLAUDE.md

## Identity

- **Name**: suji (筋 — muscle/sinew AND line-of-force/reasoning; the double meaning of a
  static force-balance engine)
- **DID**: `did:web:etzhayyim.com:actor:suji`
- **ADR**: ADR-2606061900 (R0 scaffold, 2026-06-06)
- **Parent ADR**: ADR-2605261000 (Liberation Ladder — L4 Care Tier gate)
- **L4 Care Tier role**: **biomechanics-simulation / instrument layer — UPSTREAM of clinical
  adjudication.** `kizashi senses → suji simulates the loads → mitate diagnoses → iyashi treats`.
- **Siblings**: kizashi 兆 (sensing), mitate (diagnosis), iyashi (clinical care), kokoro (mental
  health), kami-genesis (the articulation solver suji's chain maps onto).
- **Status**: R0 — 5 cells (load_solve + strain_accumulate coded + 3 scaffold) + 6 lexicons + runnable, validated
  physics methods + kami-genesis/Isaac WIT contract. 45 tests green.

## What this is

The **physics-simulation** answer to "what does a laptop posture do to the body?". It builds a
sagittal articulated **bone** chain (de Leva/Winter anthropometry — the kami-genesis `PlanarChain`),
solves the **static inverse dynamics** (Featherstone RNEA gravity term) for joint moments + cervical
compressive load, distributes those moments to **muscle** %MVC (Hill-type), and accumulates **強張り
stiffness** over a work session (Rohmert). It compares laptop-on-lap vs desk vs external-monitor.

It is a *simulation*, not a clinic and not a scanner. `kizashi` owns sensing; `mitate`/`iyashi` own
diagnosis and care. suji sits between them as the mechanics engine.

## Constitutional Discipline (CRITICAL — IMMUTABLE)

The first gate is the whole reason this is a separate actor and not folded into mitate:

1. **NON-DIAGNOSTIC (G1, 医師法 §17)** — outputs are mechanical quantities ONLY (moment N·m, muscle
   force N, %MVC, kg-force, a normalised stiffness dose). No `diagnosis`/`disease`/`icd`/
   `prescription`/`treatment`/`condition` field exists in the schema, the lexicons, OR the
   `load_solve` cell. `state_machine.transition_assert_nondiagnostic` REFUSES a payload carrying any
   clinical key — structurally unrepresentable, the nusa `:thc-class` / tazuna `:weaponizable` /
   kamado `:fossil-virgin-crude` pattern. A licensed clinician owns any diagnosis.
2. **Simulation-only / not a medical device (G2, 薬機法/SaMD)** — no sensing hardware, no biometric
   capture; inputs are posture *parameters*. R0..R2 are pure simulation.
3. **Self-referenced Wellbecoming (G3)** — stiffness is `as-of` and compared only against the SAME
   member's other postures (the choice "raise the screen"); never a population rank / score-of-soul
   (非終末論, no final state datom).
4. **Encrypted envelope on a real scan (G4)** — a body built from a real kizashi scan carries 要配慮
   PII → `encryptedPayloadCid`. suji's reference bodies are `:representative` population averages.
5. **Murakumo-only (G5) · no-server-key (G6) · sourcing-honest (G7) · outward-gated (G8) ·
   kotoba-EAVT (G9) · anti-pseudoscience (G10)** — Hill-model muscles only; 経絡/気/波動 excluded.

Non-goals: N1 not diagnosis/treatment · N2 not a medical device · N3 not a person ranking · N4 not
an ergonomics regulatory attestation · N5 Murakumo-only · N6 no live actuation (passive body model;
tazuna force-class boundary).

## Architecture

```
src/suji/methods/  segment.cljc   anthropometric sagittal chain (de Leva/Winter)
          posture.cljc   laptop workstation → joint angles
          load.cljc      static inverse dynamics (RNEA gravity); cervical VALIDATED vs Hansraj 2014
          muscle.cljc    Hill-type moment-arm → %MVC (緊張)
          strain.cljc    Rohmert sustained-isometric dose → stiffness (強張り)
          analyze.cljc   end-to-end laptop posture comparison report
          kami_biomech_bridge.cljc   kami-genesis/Isaac articulation (wire/wit/kami-biomech.wit)
data/cells/    segment_build · posture_resolve · load_solve+strain_accumulate(CODED) · strain_accumulate · ergonomic_compare
data/lex/      bodyModel · postureScenario · jointLoad · muscleTension · strainReport · ergonomicComparison
kotoba/   schema.edn (no clinical ident) · seed.edn (:representative 3-posture set)
```

The **load_solve** cell is the coded heart: a phase state machine (static-inverse-dynamics →
muscle-distribute → **assert-nondiagnostic** → emit) whose transitions are unit-tested; `.solve()`
raises at R0 until Council activation.

## Validation

- Cervical load reproduces **Hansraj (2014)** within ~10% (test_load.cljc).
- Hill muscle %MVC ∈ [0,100); Rohmert endurance falls with load, ∞ below ~8% MVC (the classic 15%
  sustainability threshold) (test_muscle_strain.cljc).
- G1/G3/G10 enforced structurally over the parsed lexicons + schema (test_charter_invariants.cljc).
- bridge static moments ≡ load.cljc moments; manifest ↔ disk drift-locked (test_bridge_consistency.cljc).

## Run

```bash
kbb -M:test                 # all auto-discovered cljc test suites (currently 43 tests)
kbb -m suji.methods.analyze     # writes generated posture report
```

## Honest R0

Design + runnable physics + a validated cervical model. Anthropometry/muscle/endurance are
`:representative` (G7); the cervical leg is validated, the muscle/strain legs are mechanistically
grounded but illustrative. No hardware, no live member scan, no live kami-genesis backend (the
`40-engine/kami-engine` submodule is unpopulated → WIT contract + Python reference, the noroshi
pattern). Live kizashi feed / clinical handoff to mitate is Council Lv6+ + operator gated (G8).
ZERO invariant amendments — strengthens the kizashi non-diagnostic / SaMD boundary, kotoba-canonical
state, no-server-key, and Murakumo-only.
