# :c2c3 pin — installed (PR #108, 2026-09-24 tick)

## What this gap was
README:3979 "What the coupled solve still cannot express" → `:c2c3`. The
2026-09-20b annotation requested two pins at `math/nearly=` 1e-12 next to the
`:c7` pin in `test/suji/methods/muscle_strain_test.kotoba`, at
`posture-from-workstation`'s output (NOT the raw posture def), and recorded
them as un-installable because no engine sees `.kotoba` as a namespace.

## Values (reproduced to the digit at 6d5a7c0, nbb mirror, 3 runs)
- laptop-on-lap pfw: `:two-joint-unfed-nm :c2c3 = -0.1581450793522565` N·m
- deep-squat pfw:    `:two-joint-unfed-nm :c2c3 = -0.03408252217605897` N·m
- RAW `posture/deep-squat` map gives -0.05846667… (feeds nobody)
- RAW workstation map as posture gives 0 (LC hired at 0 force) — this is what
  the 1.9e-4 prose once measured, a pre-coupled-solve snapshot.

## Route that made it installable
nbb mirror: copy tree with `.kotoba`→`.cljc` rename + `io.github.kotoba-lang/text`
gitlib on the classpath, run `scripts/nbb_test.cljc`. Baseline 333/3023/0 at
6d5a7c0. This bypasses the kbb blocker for VERIFICATION; it does not change the
repo (PR is test-only, +69 lines).

## Break verification (the discipline that makes the pin worth having)
- Break A — delete `:crosses {:joint :c2c3}` from LC: 8 failures (all pin
  assertions + pre-existing `the-two-joint-muscles-are-solved-at-both-joints-at-once`
  1 fail — crossing declaration was already guarded there).
- Break B — scale `attachment/secondary-arm` ×1.01 at `:c2c3` only: exactly the
  2 number pins fail; `:c7` pin, lower-limb key-set pin, everything else green.
  This is the silently-drifting class the README names — only the number pins
  catch it.
- Restore byte-identical (sha256) after every break; verify with `git diff --stat`.

## Pitfalls hit
- `patch` tool failed 4× on this test file (non-ASCII `筋`, em-dash, `·` likely
  broke its matcher). Append via `cat append.txt >> file` works — write the
  block to /tmp first.
- The 8th assertion of the new deftest counts because `=` on maps is an
  assertion too: pinned run was 3031 = 3023 + 8, not +7.
- `nearly=` signature: `(nearly= a b tol)`, absolute tolerance; `(double a)` on
  nil throws — assert `number?` first.
