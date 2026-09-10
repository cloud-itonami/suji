(require '[suji.methods.analyze :as analyze]
         '[suji.methods.attachment :as attachment]
         '[suji.methods.load :as load]
         '[suji.methods.muscle :as muscle]
         '[suji.methods.posture :as posture]
         '[suji.methods.pose :as pose]
         '[suji.methods.segment :as segment]
         '[suji.methods.spine :as spine]
         '[suji.methods.strain :as strain])

(def body (segment/build-body 70.0 1.70))

(println "=== SYMPTOM 1: two-joint unfed, deep squat ===")
(doseq [pn [posture/deep-squat posture/quiet-standing posture/standing-neutral]]
  (let [p pn
        loads (load/solve-posture-loads body p)
        tens (muscle/solve-muscle-tensions body p loads)
        s (muscle/tension-summary tens loads)]
    (println (:name p) (pr-str (:two-joint-unfed-nm s)))))

(println)
(println "=== reference workstations, two-joint-unfed + AO ===")
(doseq [ws posture/reference-workstations]
  (let [p (posture/posture-from-workstation ws)
        loads (load/solve-posture-loads body p)
        tens (muscle/solve-muscle-tensions body p loads)
        s (muscle/tension-summary tens loads)]
    (println (:name ws))
    (println "  two-joint-unfed:" (pr-str (:two-joint-unfed-nm s)))
    (println "  ao-over-supplied-nm:" (:atlanto-occipital-over-supplied-nm s))
    (println "  ao-surplus-mvc-pct:" (:atlanto-occipital-surplus-mvc-pct s))
    (println "  max-mvc:" (:max-mvc-pct s) " refused:" (:refused s) " antagonists:" (:antagonists s))))

(println)
(println "=== SYMPTOM 2: AO detail at laptop-on-lap ===")
(let [p (posture/posture-from-workstation posture/laptop-on-lap)
      loads (load/solve-posture-loads body p)
      tens (muscle/solve-muscle-tensions body p loads)
      cerv (filter #(#{"semispinalis_capitis" "splenius_capitis"} (:name %)) tens)
      ao (load/atlanto-occipital-moment body p (into {} (for [x tens :when (#{"semispinalis_capitis" "splenius_capitis"} (:name x))] [(:name x) (:force-n x)])))]
  (println "  AO gravitational moment-nm:" (:moment-nm ao))
  (println "  capitis-nm:" (:capitis-nm ao))
  (println "  residual-nm:" (:residual-nm ao))
  (println "  over-supplied-nm:" (:over-supplied-nm ao))
  (println "  grav-flexion-nm:" (:gravitational-flexion-nm ao))
  (println "  decomposition-surplus-nm:" (:decomposition-surplus-nm ao))
  (doseq [t tens :when (#{:atlanto-occipital-flexion :atlanto-occipital-extension} (:task t))]
    (println "   " (:name t) "force" (:force-n t) "fmax" (:f-max-n t) "mvc" (:mvc-pct t)
             "surplus-f" (:surplus-force-n t) "surplus-mvc" (:surplus-mvc-pct t)))
  (println "  capitis forces:" (pr-str (mapv (juxt :name :force-n :coeff) cerv))))

(println)
(println "=== SYMPTOM 3: longus_capitis c2c3 ===")
(doseq [ws posture/reference-workstations]
  (let [p (posture/posture-from-workstation ws)
        loads (load/solve-posture-loads body p)
        tens (muscle/solve-muscle-tensions body p loads)]
    (println (:name ws)
             (pr-str (for [t tens :when (:crosses-joint t)]
                       [(:name t) (:crosses-joint t) (:secondary-arm-m t) (:secondary-moment-nm t)])))))

(println)
(println "=== CROSS-CHECKS ===")
(let [p (posture/posture-from-workstation posture/laptop-on-lap)
      loads (load/solve-posture-loads body p)
      tens (muscle/solve-muscle-tensions body p loads)
      cc (spine/cervical-cross-check body p tens (:cervical loads))
      lc (spine/lumbar-cross-check body p tens)]
  (println "cervical-cross-check ratio:" (:ratio cc) " level:" (:level-force-n cc) " lumped:" (:lumped-force-n cc))
  (println "lumbar-cross-check:" (pr-str (dissoc lc :reference :note))))

(println)
(println "=== HANSRAJ PIN ===")
(println (mapv #(:compressive-load-n (load/cervical-load % 1.0 1.0 1.0)) [0 15 30 45 60]))
