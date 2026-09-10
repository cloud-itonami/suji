(ns suji.methods.girdle-test
  "The scapulothoracic contact: that its normal is a real surface normal, that it
  carries compression and refuses to carry tension BY NAME, that it is a patch and
  not a point, and that it places the girdle loads no suspension line could.

  Every test here has been run against a deliberately broken copy of the thing it
  names, and the failure it printed matched its label. A test that has never
  failed is theatre."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [suji.methods.attachment :as attachment]
            [suji.methods.girdle :as girdle]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.recruit :as recruit]
            [suji.methods.posture :as posture]))

(def body (girdle/body-with-defaults))
(def stature 1.70)

(def neutral
  {:head-flexion-deg 0.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
   :elbow-flexion-deg 0.0 :arms-supported false})

(defn- surface-for [posture]
  (girdle/thoracic-surface (pose/solve-pose body posture) stature))

;; The posture the whole element exists for: 60 deg of head flexion with 40 deg of
;; lateral bend, on the RIGHT. Taken from the measured set of 1,920 girdle
;; side-solves that placed nothing at suji main 1abb702 — the simplest member of
;; it, in the sense of having the fewest non-zero joint angles.
(def suspenders-all-refuse
  {:head-flexion-deg 60.0 :trunk-flexion-deg 0.0 :shoulder-flexion-deg 0.0
   :elbow-flexion-deg 0.0 :shoulder-abduction-deg 0.0
   :trunk-lateral-bend-deg 40.0 :wrist-extension-deg 15.0 :arms-supported true})

;; 60 deg of trunk EXTENSION — leaning back far enough that the thoracic surface
;; under the scapula has rolled over and its outward normal points DOWN.
(def leaning-back
  {:head-flexion-deg 0.0 :trunk-flexion-deg -60.0 :shoulder-flexion-deg 0.0
   :elbow-flexion-deg 45.0 :arms-supported true})

(def leaning-back-and-bent
  (assoc leaning-back :trunk-lateral-bend-deg 40.0 :shoulder-abduction-deg 0.0))

;; --- the surface -------------------------------------------------------------

(deftest the-surface-normal-is-perpendicular-to-the-surface
  ;; THE DERIVATION IS NOT TAKEN ON TRUST. `surface-normal` returns a closed form
  ;; got by hand from the cross product of the two surface tangents; a sign or a
  ;; swapped taper in it produces a vector that still points roughly outward, still
  ;; normalises, and is not normal to anything. A normal is defined by being
  ;; perpendicular to the surface, so that is what is checked — against finite
  ;; differences of `surface-point` in BOTH surface directions.
  (let [s (surface-for neutral)]
    (doseq [level [0.30 0.3672 0.42]
            theta [95.0 140.0 180.0 225.0 265.0]]
      (let [n (girdle/surface-normal s level theta)
            along (math/vnorm (math/v- (girdle/surface-point s (+ level 1e-6) theta)
                                       (girdle/surface-point s (- level 1e-6) theta)))
            around (math/vnorm (math/v- (girdle/surface-point s level (+ theta 1e-3))
                                        (girdle/surface-point s level (- theta 1e-3))))]
        (is (some? n))
        (is (math/nearly= 1.0 (math/vlen n) 1e-9) "it must be a unit vector")
        (is (math/nearly= 0.0 (math/vdot n along) 1e-6)
            (str "normal must be perpendicular to the surface ALONG the thorax at "
                 level " / " theta ", got " (math/vdot n along)))
        (is (math/nearly= 0.0 (math/vdot n around) 1e-6)
            (str "normal must be perpendicular to the surface AROUND the thorax at "
                 level " / " theta ", got " (math/vdot n around)))))))

(deftest the-normal-points-away-from-the-thorax-axis
  ;; An INWARD normal is what the raw cross product gives; the flip is deliberate
  ;; and a lost minus sign would turn every push in this namespace into a pull
  ;; while leaving every magnitude intact.
  (let [s (surface-for neutral)
        level 0.3672]
    (doseq [theta [0.0 90.0 140.0 180.0 270.0]]
      (let [p (girdle/surface-point s level theta)
            axis (math/v+ (:origin s) (math/v* (:long s) level))
            outward (math/vnorm (math/v- p axis))
            n (girdle/surface-normal s level theta)]
        (is (pos? (math/vdot n outward))
            (str "the normal at " theta " deg must point away from the axis"))))))

(deftest a-cylinder-holds-nothing-up
  ;; THE CONTROL THAT NAMES THE MECHANISM. The scapula is held up because the
  ;; thorax NARROWS toward the top, so the surface it rests on slopes and its
  ;; outward normal has a component up the thorax. Take the taper away and the
  ;; surface is a right cylinder: every normal is perpendicular to the axis, the
  ;; contact can press the scapula inward and can hold nothing up, and
  ;; `lift-reaction` must say so rather than returning a very large force.
  (let [tapered (surface-for neutral)
        cylinder (assoc tapered :taper-depth 0.0 :taper-breadth 0.0)
        level 0.3672
        n-cyl (girdle/surface-normal cylinder level 140.0)
        n-tap (girdle/surface-normal tapered level 140.0)]
    (is (math/nearly= 0.0 (math/vdot n-cyl (:long cylinder)) 1e-12)
        "a cylinder's outward normal has no component along its own axis")
    (is (> (math/vdot n-tap (:long tapered)) 0.15)
        "the tapered thorax's does, and that component is the whole mechanism")
    (let [refusal (girdle/lift-reaction n-cyl 20.0)]
      (is (= :contact-would-pull (:refused refusal))
          "a cylinder cannot hold the girdle up, and must refuse rather than answer")
      (is (nil? (:normal-n refusal)) "and must not hand back a force"))))

;; --- compression only: the defining property ---------------------------------

(deftest a-contact-carries-compression-only
  ;; THE TEST THIS ELEMENT EXISTS TO PASS. A joint can pull; a surface cannot.
  ;; Constructed input, so that what fires is unambiguous: a single force pointing
  ;; ALONG the outward normal is a force lifting the scapula off the ribcage, and
  ;; the only reaction that balances it is the ribcage pulling the scapula back
  ;; on. That is not a contact, and the element must say which impossibility it
  ;; hit — by keyword, not merely by refusing.
  (let [n [0.0 1.0 0.0]
        lifting-off (girdle/contact-reaction n [[0.0 40.0 0.0]])]
    (is (= :contact-would-pull (:refused lifting-off))
        "the reason must be named, not just the refusal")
    (is (nil? (:normal-n lifting-off))
        "a refused contact transmits NO force, not a negative one")
    (is (math/nearly= -40.0 (:required-n lifting-off) 1e-9)
        "and it must report what it was asked for")))

(deftest a-press-onto-the-surface-is-carried
  ;; THE OTHER DIRECTION. An element that has only ever been seen to refuse has
  ;; not been shown to discriminate — it could be refusing everything.
  (let [n [0.0 1.0 0.0]
        pressing (girdle/contact-reaction n [[0.0 -40.0 0.0]])]
    (is (nil? (:refused pressing)))
    (is (math/nearly= 40.0 (:normal-n pressing) 1e-9))
    (is (math/nearly= 40.0 (math/vlen (:force pressing)) 1e-9)))
  ;; and the boundary: resting on a surface and transmitting nothing is a real
  ;; state, not a refusal
  (let [resting (girdle/contact-reaction [0.0 1.0 0.0] [[3.0 0.0 4.0]])]
    (is (nil? (:refused resting)) "a force parallel to the surface is not a pull")
    (is (math/nearly= 0.0 (:normal-n resting) 1e-9))))

(deftest lifting-is-refused-by-the-same-name-as-pulling
  ;; There are two equations — how hard the surface is PRESSED, and how much of
  ;; the girdle it HOLDS UP — and both can come out as a pull. They refuse with
  ;; the same keyword because the keyword names the physics and not the equation.
  (let [downward [0.0 -0.5 0.866]
        flat [0.9 0.0 0.436]]
    (is (= :contact-would-pull (:refused (girdle/lift-reaction downward 20.0)))
        "a surface whose normal points down cannot hold anything up")
    (is (= :contact-would-pull (:refused (girdle/lift-reaction flat 20.0)))
        "nor can one whose normal is horizontal")
    (is (nil? (:refused (girdle/lift-reaction [0.0 1.0 0.0] 20.0))))
    (is (math/nearly= 20.0 (:normal-n (girdle/lift-reaction [0.0 1.0 0.0] 20.0)) 1e-9))
    (is (nil? (:refused (girdle/lift-reaction downward 0.0)))
        "and being asked to lift nothing is not a refusal at any orientation")))

(deftest a-real-posture-reaches-the-compression-refusal
  ;; The constructed tests above prove the rule fires. This proves the rule is
  ;; REACHABLE by the model on its own — a refusal that only a hand-built vector
  ;; can trigger is a rule about nothing. Measured: leaning 60 deg back rolls the
  ;; thoracic surface under the scapula far enough that its outward normal points
  ;; downward, and both equations refuse somewhere in that region.
  (let [lift-side (girdle/solve-suspension body leaning-back-and-bent :right)
        press-side (girdle/solve-contact body leaning-back :left)]
    (is (= :contact-would-pull (get-in lift-side [:lift :refused]))
        "leaning back with a lateral bend: nothing can hold this girdle up")
    (is (false? (:placed? lift-side))
        "and the element must NOT report the load as placed")
    (is (= :nobody (:carried-by lift-side)))
    (is (= :contact-would-pull (get-in press-side [:contact :refused]))
        "leaning back: the scapula is being lifted off the thorax, not pressed onto it")
    (is (nil? (get-in press-side [:contact :normal-n]))))
  ;; and the surface really has rolled over — the reason, not just the outcome
  (let [r (girdle/contact-region (pose/solve-pose body leaning-back) stature
                                 leaning-back :left)]
    (is (neg? (math/vdot (:normal r) girdle/vertical))
        "the outward normal points DOWN at this posture, which is why")))

;; --- a surface, not a point --------------------------------------------------

(deftest the-contact-region-is-a-patch-not-a-point
  ;; A point contact has one normal and no moment capacity. The patch has an
  ;; extent in both surface directions, its edges face measurably different ways,
  ;; and that extent is what bounds the centre of pressure.
  (let [p (pose/solve-pose body neutral)
        r (girdle/contact-region p stature neutral :left)]
    (is (> (:circumferential-half-m r) 0.02)
        "the patch must be wide enough for its centre of pressure to travel")
    (is (> (:axial-half-m r) 0.05))
    (is (> (math/vlen (math/v- (:medial r) (:lateral r))) 0.05)
        "the medial and lateral borders are different places on the ribcage")
    ;; the edges do not merely differ in position; they FACE differently, which is
    ;; the fact a single point cannot represent
    (is (< (math/vdot (:medial-normal r) (:lateral-normal r)) 0.95)
        "the outward normal swings across the patch")
    (is (> (math/vdot (:medial-normal r) (:lateral-normal r)) 0.5)
        "though not so far that they are facing away from each other")))

(deftest the-centre-of-pressure-is-bounded-by-the-patch
  ;; What the surface supplies for free, and where that stops. Inside the patch
  ;; the contact supplies the moment exactly; past it the offset is held at the
  ;; edge — the ligament's discipline, not a refusal — and `:at-patch-edge?` says
  ;; so, so the remainder can be charged to the muscles instead of vanishing.
  (let [p (pose/solve-pose body neutral)
        r (girdle/contact-region p stature neutral :left)
        n (:normal r)
        limit (min (:circumferential-half-m r) (:axial-half-m r))
        ;; a tangential moment small enough to be supplied inside the patch
        tangent (math/vnorm (math/vcross n [0.0 1.0 0.0]))
        small (math/v* (math/vcross n tangent) (* 100.0 limit 0.25))
        big (math/v* (math/vcross n tangent) (* 100.0 limit 4.0))
        inside (girdle/centre-of-pressure r 100.0 small)
        outside (girdle/centre-of-pressure r 100.0 big)]
    (is (false? (:at-patch-edge? inside)))
    (is (< (:reach-m inside) limit))
    (is (math/nearly= 0.0 (math/vlen (math/v- (:supplied-nm inside) small)) 1e-6)
        "inside the patch the contact supplies the demanded moment exactly")
    (is (true? (:at-patch-edge? outside))
        "beyond it the centre of pressure has left the surface and must say so")
    (is (math/nearly= limit (math/vlen (:offset outside)) 1e-9)
        "and is held at the edge rather than allowed off the scapula")
    (is (< (math/vlen (:supplied-nm outside)) (math/vlen big))
        "so it supplies less than was demanded, and the muscles owe the rest")))

(deftest a-frictionless-contact-supplies-no-twist-about-its-own-normal
  ;; A pressure distribution can move its centre of pressure, which is a moment in
  ;; the tangent plane. It cannot twist about the normal at any centre of
  ;; pressure, because every element of it acts ALONG that normal. The demanded
  ;; twist is therefore reported rather than quietly absorbed.
  (let [p (pose/solve-pose body neutral)
        r (girdle/contact-region p stature neutral :left)
        n (:normal r)
        pure-twist (math/v* n 7.0)
        cop (girdle/centre-of-pressure r 100.0 pure-twist)]
    (is (math/nearly= 7.0 (:unbalanced-twist-nm cop) 1e-9)
        "the whole of a pure twist must be REPORTED as unbalanced, not dropped")
    (is (math/nearly= 0.0 (math/vlen (:offset cop)) 1e-9)
        "and no centre-of-pressure offset can supply any of it")
    (is (math/nearly= 0.0 (math/vlen (:supplied-nm cop)) 1e-9)
        "so the contact supplies none of it")
    (is (false? (:at-patch-edge? cop))
        "and it is not a patch-edge event — the patch is not the limit here")
    ;; whatever the contact DOES supply is perpendicular to the normal, always
    (let [mixed (math/v+ pure-twist (math/v* (math/vnorm (math/vcross n [0.0 1.0 0.0])) 2.0))
          c (girdle/centre-of-pressure r 100.0 mixed)]
      (is (math/nearly= 0.0 (math/vdot (:supplied-nm c) n) 1e-9)))))

(deftest the-patch-migrates-because-the-scapula-slides
  ;; There is no joint here — the scapula SLIDES on the ribcage. A patch nailed to
  ;; one place would be a joint with extra steps, and its normal would be the same
  ;; direction whatever the girdle did.
  (let [p0 (pose/solve-pose body neutral)
        reached (assoc neutral :shoulder-flexion-deg 90.0)
        raised (assoc neutral :shoulder-elevation-deg 45.0)
        r0 (girdle/contact-region p0 stature neutral :left)
        r1 (girdle/contact-region (pose/solve-pose body reached) stature reached :left)
        r2 (girdle/contact-region (pose/solve-pose body raised) stature raised :left)]
    (is (< (:theta-deg r1) (- (:theta-deg r0) 15.0))
        "reaching forward carries the patch anteriorly around the thorax")
    (is (< (math/vdot (:normal r0) (:normal r1)) 0.95)
        "which changes the direction the surface pushes")
    (is (> (:s-m r2) (+ (:s-m r0) 0.03))
        "elevating the girdle raises the patch up the thorax")
    (is (not (math/nearly= (math/vdot (:normal r0) girdle/vertical)
                           (math/vdot (:normal r2) girdle/vertical)
                           1e-6))
        "onto a differently-sloping part of the surface — which is why the two
         tapers are stated separately")))

(deftest the-two-patches-are-mirror-images
  ;; Stated for the LEFT and mirrored, exactly as `attachment`'s lateral offsets
  ;; are. A reflection in the sagittal plane negates the lateral component of
  ;; every point and every normal and leaves the other two alone.
  (let [p (pose/solve-pose body neutral)
        l (girdle/contact-region p stature neutral :left)
        r (girdle/contact-region p stature neutral :right)
        [lx ly lz] (:normal l)
        [rx ry rz] (:normal r)]
    (is (math/nearly= lx rx 1e-12) "anterior components agree")
    (is (math/nearly= ly ry 1e-12) "axial components agree")
    (is (math/nearly= lz (- rz) 1e-12) "lateral components are opposite")
    (is (pos? lz) "and the left patch really is on the left")
    (is (math/nearly= (:circumferential-half-m l) (:circumferential-half-m r) 1e-12))
    ;; a symmetric posture must press the two sides identically
    (let [sl (girdle/solve-contact body neutral :left)
          sr (girdle/solve-contact body neutral :right)]
      (is (math/nearly= (:normal-n (:contact sl)) (:normal-n (:contact sr)) 1e-9)))))

;; --- the muscles that hold the scapula on ------------------------------------

(deftest the-couple-balances-the-moment-it-is-given
  ;; `recruit`'s criterion is exact, so whatever the stabilisers were asked for
  ;; comes back balanced — and the coefficients handed to it must be moment arms
  ;; in metres, because the load is a moment.
  (let [s (girdle/solve-contact body neutral :left)
        demand (:couple-demand-nm s)]
    (is (pos? demand) "the hanging arm really does try to tip the scapula off")
    (is (:couple-placed? s) "and the stabilisers can resist it")
    (is (math/nearly= 0.0 (recruit/residual (:couple s) demand)
                      (* 1e-9 (max 1.0 demand)))
        "the shared forces must balance the moment exactly")
    ;; the arms are metres, not cosines: a metre-scale coefficient on a body this
    ;; size is under 0.5 and over a millimetre
    (doseq [l (:lines s)]
      (is (some? (:coeff l)))
      (is (< (math/abs* (:coeff l)) 0.5) (str (:name l) " coefficient is a moment arm")))
    (is (= #{"serratus_anterior" "rhomboids" "trapezius_scapular"}
           (set (map :name (:lines s))))
        "all three named stabilisers are in the couple")))

(deftest a-refused-stabiliser-is-marked-as-the-antagonist-it-is
  ;; Three lines on one plate cannot all resist the same tipping direction, and a
  ;; static minimum-stress optimum does not co-contract. `recruit` refuses the
  ;; ones on the other side with `:acts-the-wrong-way`, correctly — and an
  ;; unmarked refusal reads downstream as an unanswered load, which it is not.
  (let [s (girdle/solve-contact body neutral :left)
        refused (filter :refused (:couple s))]
    (is (seq refused) "at this posture at least one stabiliser is the antagonist")
    (is (every? #(= :acts-the-wrong-way (:refused %)) refused)
        "and it is refused for acting the wrong way, not for want of leverage")
    (is (every? :antagonist? refused)
        "which must be marked, because the moment WAS placed")
    (is (:couple-placed? s))))

(deftest the-stabiliser-specific-tension-has-not-drifted-from-muscle
  ;; A restated constant is a constant in two places. Pinning it here is the whole
  ;; reason the restatement is allowed.
  (is (math/nearly= muscle/specific-tension-n-cm2 girdle/stabiliser-specific-tension 1e-12)))

;; --- what the element resolves -----------------------------------------------

(deftest the-contact-places-a-girdle-load-every-suspender-refuses
  ;; THE MEASURED GAP. At suji main 1abb702, 1,920 of 11,520 girdle side-solves
  ;; over a 5,760-posture sweep placed NOTHING: every suspension line refused, and
  ;; `recruit`'s note for that refusal says correctly that no wrapping surface and
  ;; no further muscle repairs it. Nothing in the model could push.
  ;;
  ;; The reason literal is pinned, not just the outcome. If these suspenders start
  ;; failing for some OTHER reason this test would otherwise go on passing while
  ;; measuring something else entirely.
  (let [loads (load/solve-posture-loads body suspenders-all-refuse)
        tensions (muscle/solve-muscle-tensions body suspenders-all-refuse loads)
        suspenders (filter #(and (= :scapular-suspension (:task %))
                                 (= :right (:side %)))
                           tensions)]
    (is (= 3 (count suspenders)) "three suspension lines act on this girdle")
    (is (every? #(= :acts-the-wrong-way (:refused %)) suspenders)
        "and at this posture every one of them pulls the girdle DOWN")
    (is (not-any? :antagonist? suspenders)
        "none of them is an antagonist — the load was simply not placed")
    (is (not-any? :force-n suspenders)))
  ;; and with a surface under it, it is
  (let [g (girdle/solve-suspension body suspenders-all-refuse :right)]
    (is (true? (:placed? g)) "the ribcage places what no muscle line could")
    (is (= :contact (:carried-by g))
        "and the consumer can see that a SURFACE placed it, not a muscle")
    (is (pos? (:load-n g)))
    (is (math/nearly= (:load-n g) (:contact-n g) 1e-9)
        "the whole girdle load is on the contact here")
    (is (nil? (get-in g [:lift :refused])))
    (is (pos? (math/vdot (get-in g [:contact :region :normal]) girdle/vertical))
        "because the surface it rests on slopes upward at this posture")))

(deftest the-reference-postures-still-place-their-girdle-load
  ;; The element must not have broken the postures that already worked. At the
  ;; three reference workstations the suspenders carry and the contact merely
  ;; helps, which is what a contact should do when the muscles are able.
  (doseq [ws posture/reference-workstations]
    (let [p (posture/posture-from-workstation ws)
          g (girdle/solve-girdle body p)]
      (doseq [side [:left :right]]
        (let [s (get g side)]
          (is (true? (:placed? s)) (str (:name ws) " " side " girdle load placed"))
          (is (= :both (:carried-by s))
              (str (:name ws) " " side " — muscles and contact share it"))
          (is (nil? (get-in s [:contact :contact :refused]))
              (str (:name ws) " " side " contact not refused"))
          (is (pos? (:contact-n s)) "and the contact really carries some of it")
          (is (< (:contact-n s) (:load-n s)) "but not all of it"))))))

(deftest the-contact-never-reports-a-negative-force-anywhere
  ;; The invariant, swept rather than argued. Across the postures this app's
  ;; sliders offer, every contact that was NOT refused reports a non-negative
  ;; normal force and a non-negative pressure — there is no path through this
  ;; namespace that returns a tensile contact.
  ;; The posture range deliberately includes trunk EXTENSION. Without it the
  ;; sweep never reaches the region where a contact would have to pull, so the
  ;; guard it is testing is never consulted and the test passes whether the guard
  ;; is there or not — measured, and this range is the repair.
  (let [postures (for [trunk [-60.0 -30.0 0.0 30.0 60.0]
                       head [0.0 30.0 60.0]
                       sh [0.0 45.0 90.0]
                       bend [0.0 40.0]
                       sup [true false]]
                   {:trunk-flexion-deg trunk :head-flexion-deg head
                    :shoulder-flexion-deg sh :elbow-flexion-deg 45.0
                    :trunk-lateral-bend-deg bend :arms-supported sup})
        results (for [p postures side [:left :right]]
                  (girdle/solve-contact body p side))
        forces (keep #(get-in % [:contact :normal-n]) results)
        refused (filter #(get-in % [:contact :refused]) results)]
    (is (= 360 (count results)) "the sweep really ran")
    (is (seq refused)
        "and it reaches postures where the contact WOULD have had to pull — a
         sweep that never gets there cannot discriminate the guard")
    (is (every? #(= :contact-would-pull (get-in % [:contact :refused])) refused)
        "which it refuses by name")
    (is (seq forces))
    (is (every? #(>= % 0.0) forces) "no contact transmits tension")
    (is (every? #(or (nil? %) (>= % 0.0)) (map girdle/contact-pressure-kpa results))
        "and no contact reports a negative pressure")))

(deftest attachment-still-owns-the-suspension-task
  ;; This namespace reads the suspender set out of `attachment` rather than
  ;; listing it, so that a suspender added or removed there is not silently
  ;; ignored here. That only works while the task keyword is the one it expects.
  (is (seq girdle/suspension-groups))
  (is (contains? girdle/suspension-groups "upper_trapezius"))
  (is (= girdle/suspension-groups
         (into #{} (comp (filter #(= :scapular-suspension (:task %))) (map :group))
               attachment/instances))))
