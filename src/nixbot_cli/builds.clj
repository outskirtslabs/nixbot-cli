(ns nixbot-cli.builds
  (:require
   [clojure.string :as str]))

;; Build statuses: pending | evaluating | building | succeeded | failed | cancelled
(def terminal-build-statuses #{"succeeded" "failed" "cancelled"})

(def status-aliases
  {"success"  "succeeded"
   "failure"  "failed"
   "canceled" "cancelled"
   "running"  "building"
   "queued"   "pending"})

(def known-statuses
  #{"pending" "evaluating" "building" "succeeded" "failed" "cancelled"})

(defn normalize-status
  "Maps a user-supplied --status value onto a Nixbot build status."
  [status]
  (when-not (str/blank? status)
    (let [status (str/lower-case (str/trim status))
          status (get status-aliases status status)]
      (when-not (contains? known-statuses status)
        (throw (ex-info (str "Unsupported status: " status
                             " (expected one of " (str/join ", " (sort known-statuses)) ")")
                        {:type   :nixbot-cli.builds/unsupported-status
                         :status status})))
      status)))

(defn field [m k]
  (cond
    (contains? m k) (get m k)
    (contains? m (name k)) (get m (name k))
    :else nil))

;; Attribute statuses: pending | building | succeeded | failed | cancelled
;; | skipped_local | dependency_failed | cached_failure | failed_eval
(def attr-status->group
  {"succeeded"         :succeeded
   "skipped_local"     :succeeded
   "failed"            :failed
   "dependency_failed" :failed
   "cached_failure"    :failed
   "failed_eval"       :failed
   "pending"           :pending
   "building"          :pending
   "cancelled"         :cancelled})

(defn attr-group [attribute]
  (get attr-status->group (str (field attribute :status)) :pending))

(defn failed-attr? [attribute]
  (= :failed (attr-group attribute)))

(defn summarize-attrs
  "Counts a build's attributes: {:succeeded n :failed n :pending n :cancelled n}."
  [attributes]
  (reduce (fn [acc attribute]
            (update acc (attr-group attribute) (fnil inc 0)))
          {:succeeded 0 :failed 0 :pending 0 :cancelled 0}
          attributes))

(defn terminal?
  "True when the build reached a final status."
  [build]
  (contains? terminal-build-statuses (str (field build :status))))

(defn exit-code [build]
  (if (= "succeeded" (str (field build :status)))
    0
    1))

(defn fetch-up-to
  "Fetches builds page by page via `fetch-page` (page-number -> BuildPage)
  until `limit` items are collected or pages run out."
  [fetch-page limit]
  (loop [page  1
         items []]
    (let [resp       (fetch-page page)
          page-items (vec (or (field resp :items) []))
          items      (into items page-items)]
      (if (and (field resp :has_next) (< (count items) limit) (seq page-items))
        (recur (inc page) items)
        (vec (take limit items))))))
