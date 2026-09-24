#!/usr/bin/env nbb
;; anatomy_evidence.cljs — suji-anatomy bot の測定。**agent は再計算しない。**
;;
;;   cd ~/.hermes/profiles/suji-anatomy && nbb scripts/anatomy_evidence.cljs
;;
;; 測るもの（どれも 1 コマンドで、外に書き込まない）:
;;   1. 3 repo の west pin と upstream default branch tip の差（何 commit 遅れか）
;;   2. suji README が**自分で『できない / 得られなかった』と書いている節**
;;      —— 固定の見出し 1 本に縛らない（初版はそれで 0 件を返した）
;;   3. kami-app-suji の公開ページが**実際に配信しているか**
;;      —— root が 404 でも中のファイルが 200 なら「入口が無い」であって死んでは
;;      いない。root 1 本で判定した検出器が 52 件中 48 件を誤分類した実例が在る。
;;   4. ledger（workspace/anatomy-ledger.jsonl）へ 1 行追記
;;
;; **測れなかったら SMOKE-OK を出さない。** 出さなかった run は測定失敗であって
;; 「異常なし」ではない。

(ns anatomy-evidence
  (:require ["node:child_process" :as cp]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]))

(def root (path/join (os/homedir) "github" "com-junkawasaki"))
(def here (path/join (os/homedir) ".hermes" "profiles" "suji-anatomy"))
(def ledger (path/join here "workspace" "anatomy-ledger.jsonl"))

(def repos
  [{:entry "suji"           :full "cloud-itonami/suji"          :role "物理"}
   {:entry "kami-app-suji"  :full "kotoba-lang/kami-app-suji"   :role "可視面"}
   {:entry "biomech"        :full "kotoba-lang/biomech"         :role "組織スケール"}])

(defn- sh [cmd]
  (let [r (cp/spawnSync "/bin/sh" #js ["-c" cmd] #js {:encoding "utf8" :timeout 60000})]
    (when (zero? (.-status r)) (str/trim (.-stdout r)))))

(defn- gh [& args]
  (let [r (cp/spawnSync "gh" (clj->js (into ["api"] args)) #js {:encoding "utf8" :timeout 60000})]
    (when (zero? (.-status r)) (str/trim (.-stdout r)))))

(defn- pin-for [entry]
  ;; west.yml の当該 entry の revision。**working tree ではなく origin/main を読む。**
  ;; 実測 2026-09-08: 共有 checkout の main は他セッションの未 push commit で
  ;; diverge していて fast-forward できず、working tree の west.yml は 1 波ぶん
  ;; 古い pin を持っていた。この script はそれを読んで「0 commit 遅れ」と答えた ——
  ;; checkout・west pin・repo の main は 3 つの別物、という workspace の規則そのもの。
  (sh (str "cd " root " && git show origin/main:manifest/west.yml"
           " | grep -A3 '^    - name: " entry "$'"
           " | grep 'revision:' | head -1 | awk '{print $2}'")))

(defn- lag [full pin]
  (when (and pin (not (str/blank? pin)))
    (when-let [out (gh (str "repos/" full "/compare/" pin "...HEAD")
                       "--jq" "[.status, .ahead_by, .behind_by] | @tsv")]
      (let [[status ahead behind] (str/split out #"\t")]
        {:status status :ahead (js/parseInt ahead 10) :behind (js/parseInt behind 10)}))))

(def gap-heading
  ;; README の**自分自身の告白**を索引する。固定の見出し 1 本に縛ると、README が
  ;; 育ったときに黙って 0 件を返す —— 実測 2026-09-08、この script の初版は
  ;; 「いま本当に無いもの」を探して**読めず**を返した（その節はもう無く、英語の
  ;; 日付つき節に分かれていた）。だから語で拾う。
  #"(?i)cannot|could not|not expressible|not in source|無い|持たない")

(defn- suji-gaps
  "README の見出しのうち、その model が『できない / 得られなかった』と言っている
   ものを行番号つきで返す。**0 件は clean ではなく、README の形が変わった合図。**"
  []
  (when-let [md (gh "repos/cloud-itonami/suji/contents/README.md"
                    "-H" "Accept: application/vnd.github.raw")]
    (let [ls (str/split-lines md)]
      (->> (map-indexed vector ls)
           (filter (fn [[_ l]] (and (str/starts-with? l "#")
                                    (re-find gap-heading l))))
           (mapv (fn [[i l]] {:line (inc i)
                              :title (subs l 0 (min 96 (count l)))}))))))

(defn- http [url]
  (let [r (cp/spawnSync "curl" #js ["-sS" "-o" "/dev/null" "-w" "%{http_code}"
                                    "--max-time" "20" url]
                        #js {:encoding "utf8" :timeout 30000})]
    (when (zero? (.-status r)) (str/trim (.-stdout r)))))

(defn- page-state []
  (let [url  "https://kotoba-lang.github.io/kami-app-suji/"
        code (http url)
        ;; root が 404 でも配信しているかを 1 本だけ確かめる
        deep (when (not= "200" code) (http (str url "js/main.js")))]
    {:url url :root code :deep deep
     :verdict (cond (= "200" code) "serving"
                    (= "200" deep) "root-has-no-index"
                    :else "serves-nothing")}))

(defn -main []
  (let [pins (for [{:keys [entry full role]} repos
                   :let [pin (pin-for entry)
                         l   (lag full pin)]]
               {:entry entry :full full :role role :pin pin :lag l})
        gaps (suji-gaps)
        page (page-state)
        unreadable (filter #(nil? (:lag %)) pins)]
    (println "== west pin vs upstream tip")
    (doseq [p pins]
      (println (str "  " (.padEnd (:entry p) 16)
                    (if (:lag p)
                      (str (subs (:pin p) 0 7) "  " (:ahead (:lag p)) " commit 遅れ"
                           (when (pos? (:behind (:lag p)))
                             (str " / " (:behind (:lag p)) " 進んでいる（要確認）")))
                      "**測れず**（pin が読めない / API が答えない）"))))
    (println)
    (println (str "== suji が README で『できない / 得られなかった』と言っている節: "
                  (if gaps (count gaps) "**読めず**")))
    (doseq [g (or gaps [])]
      (println (str "  README:" (.padEnd (str (:line g)) 6) (:title g))))
    (println)
    (println (str "== 公開面 " (:url page) " root=" (:root page)
                  (when (:deep page) (str " deep=" (:deep page)))
                  " → " (:verdict page)))
    (println)
    (if (or (seq unreadable) (nil? gaps) (empty? gaps) (nil? (:root page)))
      (do (do (println "MEASUREMENT-FAILED\t測れなかった項目が在る。これは『異常なし』ではない。")
              (when (and gaps (empty? gaps))
                (println "  ギャップ 0 件は clean ではない —— README の形が変わって、この索引が当たらなくなった合図。")))
          (.exit js/process 2))
      (let [line (js/JSON.stringify
                  (clj->js {:at (.toISOString (js/Date.))
                            :pins (into {} (map (fn [p] [(:entry p) {:pin (subs (:pin p) 0 7)
                                                                     :behind (:ahead (:lag p))}]) pins))
                            :gaps (count gaps)
                            :page (:verdict page)}))]
        (fs/mkdirSync (path/dirname ledger) #js {:recursive true})
        (fs/appendFileSync ledger (str line "\n"))
        (let [seqn (count (remove str/blank? (str/split-lines (fs/readFileSync ledger "utf8"))))]
          (println (str "LEDGER\tseq=" seqn "\t" ledger))
          (println (str "SMOKE-OK\tpins=" (count pins) "\tgaps=" (count gaps)
                        "\tpage=" (:verdict page)))
          (.exit js/process 0))))))

(-main)
