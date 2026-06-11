(ns nixbot-cli.format
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [nixbot-cli.builds :as builds]))

(def field builds/field)

(defn- text [x]
  (cond
    (nil? x) ""
    (keyword? x) (name x)
    :else (str x)))

(defn short-commit [commit]
  (let [commit (text commit)]
    (subs commit 0 (min 8 (count commit)))))

(defn status-label [status]
  (let [status (text status)]
    (str (case status
           ("succeeded" "skipped_local") "[OK]"
           ("failed" "dependency_failed" "cached_failure" "failed_eval") "[FAIL]"
           ("pending" "evaluating" "building") "⏳"
           "cancelled" "[CANCELLED]"
           "[UNKNOWN]")
         " " status)))

(defn- machine [data output-format]
  (case output-format
    :json (json/generate-string data)
    :edn (pr-str data)
    nil))

(defn- labeled-lines
  "`label` on the first line, continuation lines aligned under it."
  [label s]
  (let [lines  (str/split-lines (str/trim (text s)))
        indent (apply str (repeat (count label) " "))]
    (cons (str label (first lines))
          (map #(str indent %) (rest lines)))))

(defn- error-excerpt [err]
  (let [lines (str/split-lines (str/trim (text err)))
        tail  (take-last 3 lines)]
    (cond->> tail
      (> (count lines) 3) (cons "…"))))

(defn- counts-text [{:keys [succeeded failed pending cancelled]}]
  (str succeeded " succeeded, " failed " failed, "
       pending " pending, " cancelled " cancelled"))

;; --- repos ---

(defn- repo-row [repo]
  (str (field repo :forge) "/" (field repo :owner) "/" (field repo :name)
       "  " (if (field repo :enabled) "enabled" "disabled")
       "  " (field repo :default_branch)
       "  " (field repo :url)))

(defn format-repos [repos output-format]
  (or (machine repos output-format)
      (if (seq repos)
        (str/join "\n" (map repo-row repos))
        "No repos found")))

;; --- builds list ---

(defn- build-row [build]
  (str "#" (field build :number)
       "  " (status-label (field build :status))
       "  " (field build :branch)
       "  " (short-commit (field build :commit_sha))
       (when-let [pr (field build :pr_number)] (str "  PR#" pr))
       "  " (field build :created_at)))

(defn format-builds [{:keys [repo items]} output-format]
  (or (machine {:repo (:slug repo) :items items} output-format)
      (if (seq items)
        (str/join "\n"
                  (cons (str "Builds for " (:slug repo))
                        (map build-row items)))
        (str "No builds found for " (:slug repo)))))

;; --- queue ---

(defn- queue-row [entry]
  (str (if-let [pos (field entry :queue_position)] (str pos ".") "*")
       "  " (field entry :forge) "/" (field entry :owner) "/" (field entry :project_name)
       "  #" (field entry :number)
       "  " (status-label (field entry :status))
       "  " (field entry :branch)
       "  " (short-commit (field entry :commit_sha))))

(defn format-queue [entries output-format]
  (or (machine entries output-format)
      (if (seq entries)
        (str/join "\n"
                  (cons "Build queue (* = running):"
                        (map queue-row entries)))
        "Queue is empty")))

;; --- build detail ---

(defn- eval-warning-lines [warnings]
  (when (seq warnings)
    (cons "Eval warnings:"
          (map (fn [w]
                 (str "  [" (field w :level) "] " (field w :message)
                      (let [n (field w :count)]
                        (when (and n (> n 1)) (str " (x" n ")")))))
               warnings))))

(defn- failed-attr-lines [repo build attributes]
  (let [failed (filter builds/failed-attr? attributes)]
    (when (seq failed)
      (concat ["" "Failed attributes:"]
              (mapcat (fn [attribute]
                        (concat
                         [(str "  • " (field attribute :attr)
                               " (" (or (field attribute :system) "unknown") "): "
                               (status-label (field attribute :status)))]
                         (when-let [err (field attribute :error)]
                           (map #(str "    " %) (error-excerpt err)))
                         [(str "    Logs: nixbot-cli logs " (field build :number)
                               " '" (field attribute :attr) "'"
                               " -R " (:slug repo))]))
                      failed)))))

(defn- format-build-human [{:keys [repo build attributes]}]
  (let [counts (builds/summarize-attrs attributes)]
    (str/join "\n"
              (concat
               [(str "# Build #" (field build :number) " for " (:slug repo))
                ""
                (str "Status: " (status-label (field build :status)))
                (str "Branch: " (field build :branch)
                     (when-let [pr (field build :pr_number)] (str " (PR#" pr ")")))
                (str "Commit: " (field build :commit_sha))
                (str "Created: " (field build :created_at))
                (str "Finished: " (or (field build :finished_at) "-"))]
               (when-let [err (field build :error)]
                 (labeled-lines "Error: " err))
               (eval-warning-lines (field build :eval_warnings))
               [""
                "## Summary"
                (str "- [OK] Succeeded: " (:succeeded counts))
                (str "- [FAIL] Failed: " (:failed counts))
                (str "- ⏳ Pending: " (:pending counts))
                (str "- [CANCELLED] Cancelled: " (:cancelled counts))]
               (failed-attr-lines repo build attributes)))))

(defn- format-build-plain [{:keys [repo build attributes]}]
  (let [counts (builds/summarize-attrs attributes)]
    (str/join "\n"
              (concat
               [(str "Build #" (field build :number) " for " (:slug repo))
                (str "Status: " (text (field build :status)))
                (str "Branch: " (field build :branch))
                (str "Commit: " (field build :commit_sha))
                (str "Summary: " (counts-text counts))
                ""]
               (map (fn [attribute]
                      (str "  " (field attribute :attr)
                           "  " (text (field attribute :status))
                           " (" (or (field attribute :system) "unknown") ")"
                           (when (field attribute :cached) "  cached")))
                    attributes)))))

(defn format-build [data output-format]
  (or (machine (dissoc data :repo) output-format)
      (case output-format
        :plain (format-build-plain data)
        (format-build-human data))))

;; --- failures ---

(defn- failure-lines [failure]
  (concat
   [(str "  • " (field failure :attr) ": " (status-label (field failure :status)))]
   (when-let [err (field failure :error)]
     (labeled-lines "    Error: " err))
   (when-let [tail (field failure :log_tail)]
     (cons "    --- log tail ---"
           (map #(str "    " %) (str/split-lines tail))))))

(defn format-failures [{:keys [repo number] :as data} output-format]
  (let [summary (:summary data)]
    (or (machine summary output-format)
        (str/join "\n"
                  (concat
                   [(str "Failures for build #" number " (" (:slug repo) ")")
                    (str "Status: " (status-label (field summary :status)))]
                   (when-let [err (field summary :error)]
                     (labeled-lines "Error: " err))
                   (eval-warning-lines (field summary :eval_warnings))
                   (let [failures (field summary :failures)]
                     (if (seq failures)
                       (cons "" (mapcat failure-lines failures))
                       ["" "No failed attributes"])))))))

;; --- attribute history ---

(defn- history-row [entry]
  (str "#" (field entry :build_number)
       "  " (status-label (field entry :status))
       "  " (field entry :branch)
       "  " (short-commit (field entry :commit_sha))
       (when (field entry :cached) "  cached")
       "  " (field entry :build_created_at)))

(defn format-attr-history [{:keys [repo attr entries]} output-format]
  (or (machine entries output-format)
      (if (seq entries)
        (str/join "\n"
                  (cons (str "History for " attr " in " (:slug repo))
                        (map history-row entries)))
        (str "No history for " attr " in " (:slug repo)))))

;; --- watch ---

(defn- failed-attrs-text [attributes]
  (let [failed (filter builds/failed-attr? attributes)]
    (when (seq failed)
      (str " — failed: " (str/join ", " (map #(field % :attr) failed))))))

(defn format-watch [{:keys [repo build attributes] :as data} mode]
  (case mode
    :compact (let [counts (builds/summarize-attrs attributes)]
               (str "#" (field build :number)
                    " " (:slug repo)
                    " " (field build :branch)
                    " " (text (field build :status))
                    " — " (counts-text counts)
                    (or (failed-attrs-text attributes) "")))
    (format-build data mode)))

;; --- control actions ---

(defn format-action [result output-format]
  (or (machine result output-format)
      (str "Build #" (field result :number) ": " (field result :action) " requested")))

(defn format-enabled [result output-format]
  (or (machine result output-format)
      (str "Repo " (field result :owner) "/" (field result :name) " is now "
           (if (field result :enabled) "enabled" "disabled"))))
