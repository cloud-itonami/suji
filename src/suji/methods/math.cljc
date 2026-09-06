(ns suji.methods.math
  "suji (筋) — the portable numeric floor. Stdlib only, no I/O.

  WHY THIS EXISTS. Every other namespace here is named `.cljc`, but four of them
  called JVM-only interop — `Math/toRadians`, `Double/POSITIVE_INFINITY`,
  `Double/isInfinite` — with no reader conditional. They were portable in name and
  JVM-only in fact: loading `suji.methods.load` under ClojureScript failed at the
  first `radians` call. A posture simulator that cannot run in a browser cannot be
  put in front of the person whose posture it is.

  The rule this namespace enforces: **a `.cljc` namespace in this repo calls no host
  interop.** It calls here instead. `Math/sin` / `Math/cos` / `Math/exp` / `Math/pow`
  resolve on both hosts (ClojureScript maps them onto `js/Math`), so those stay
  inline; the ones that do NOT resolve are wrapped here, once, where the reader
  conditional is visible and testable.

  ROUNDING — the divergence this consolidation exposed. `round-to` was copy-pasted
  into four namespaces as `(/ (Math/round (* v 10^n)) 10^n)` on the ClojureScript
  side and exact `BigDecimal` HALF_EVEN on the JVM side. Those are not the same
  function: the multiply moves the value before rounding, so JVM and JS disagreed on
  ordinary inputs, not just on ties — `(round-to 2.675 2)` was 2.67 on the JVM and
  2.68 in JS. `datoms` claims byte-identical Python `repr` output, so the JS answer
  was simply wrong there. `.toFixed` rounds the double's EXACT value (ECMA-262), so
  it agrees with `BigDecimal` everywhere except an exact tie, where the spec picks
  the larger n (half-up) and `BigDecimal` picks the even neighbour. Ties are only
  reachable when the double is exactly k.5×10⁻ⁿ; none of this repo's quantities are.
  `math-test` pins the disagreement set to empty over a representative vector."
  (:refer-clojure :exclude [infinite?])
  (:require [clojure.string :as str]))

(def pi #?(:clj Math/PI :cljs js/Math.PI))

(defn radians
  "Degrees → radians. `Math/toRadians` is JVM-only; this is the portable form."
  [deg]
  (* deg (/ pi 180.0)))

(defn degrees
  "Radians → degrees."
  [rad]
  (* rad (/ 180.0 pi)))

(def inf
  "Positive infinity on both hosts."
  #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity))

(defn infinite?
  "True when v is an infinity. `Double/isInfinite` is JVM-only, and
  `clojure.core/infinite?` throws rather than answering for a non-number."
  [v]
  (and (number? v)
       #?(:clj  (Double/isInfinite (double v))
          :cljs (and (not (js/isFinite v)) (not (js/isNaN v))))))

(defn finite?
  "True when v is a real, finite number. `Double/isFinite` is JVM-only."
  [v]
  (and (number? v)
       #?(:clj  (Double/isFinite (double v))
          :cljs (js/isFinite v))))

(defn round-to
  "Round v to n decimal places against the double's exact value: half-even on the
  JVM (Python's `round`), half-up at an exact tie in JS. See the ns docstring —
  this is the single copy of what used to be four disagreeing ones."
  [v n]
  #?(:clj  (-> (java.math.BigDecimal. (double v))
               (.setScale (int n) java.math.RoundingMode/HALF_EVEN)
               (.doubleValue))
     :cljs (js/parseFloat (.toFixed (double v) n))))

(defn clamp [x lo hi] (max lo (min hi x)))

(defn abs* [x] (if (neg? x) (- x) x))

(defn nearly=
  "Absolute-tolerance float comparison, for tests and cross-host parity checks."
  ([a b] (nearly= a b 1e-9))
  ([a b tol] (< (abs* (- (double a) (double b))) tol)))

;; --- 3-vector helpers (the pose layer needs them; kept tiny and pure) ---------
(defn v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn v* [[x y z] s] [(* x s) (* y s) (* z s)])
(defn vlen [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))
(defn vmid [a b] (v* (v+ a b) 0.5))

(defn fmt-fixed
  "n-decimal fixed-point string on both hosts (display only)."
  [v n]
  #?(:clj  (format (str "%." n "f") (double v))
     :cljs (.toFixed (double v) n)))

(defn join-lines [xs] (str/join "\n" xs))
