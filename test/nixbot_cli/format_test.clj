(ns nixbot-cli.format-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.format :as fmt]))

(def repo
  {:forge "github"                          :owner "outskirtslabs" :name "nixbot-cli"
   :slug  "github/outskirtslabs/nixbot-cli"})

(def build
  {:number        7
   :status        "failed"
   :branch        "main"
   :pr_number     nil
   :commit_sha    "f54be57a1ffcc44ed023f96ced7f91a49d1d7836"
   :created_at    "2026-06-10T16:33:17Z"
   :finished_at   "2026-06-10T16:38:02Z"
   :error         nil
   :eval_warnings nil})

(def attributes
  [{:attr "x86_64-linux.ok" :system "x86_64-linux" :status "succeeded" :cached false}
   {:attr  "x86_64-linux.bad"   :system "x86_64-linux" :status "failed" :cached false
    :error "line one\nline two"}])

(deftest format-repos-renders-rows-or-empty-message
  (is (= "github/outskirtslabs/nixbot-cli  enabled  main  https://example.test/r.git"
         (fmt/format-repos [{:forge   "github"                     :owner          "outskirtslabs" :name "nixbot-cli"
                             :enabled true                         :default_branch "main"
                             :url     "https://example.test/r.git"}]
                           :human)))
  (is (= "No repos found" (fmt/format-repos [] :human))))

(deftest format-builds-includes-pr-number-when-present
  (let [out (fmt/format-builds {:repo  repo
                                :items [(assoc build :pr_number 12)]}
                               :human)]
    (is (str/includes? out "Builds for github/outskirtslabs/nixbot-cli"))
    (is (str/includes? out "#7  [FAIL] failed  main  f54be57a  PR#12"))))

(deftest format-queue-handles-empty-and-running-entries
  (is (= "Queue is empty" (fmt/format-queue [] :human)))
  (let [out (fmt/format-queue
             [{:queue_position nil :forge  "github"   :owner  "o"    :project_name "p"
               :number         3   :status "building" :branch "main" :commit_sha   "abcdef1234"}
              {:queue_position 1 :forge  "github"  :owner  "o"    :project_name "q"
               :number         9 :status "pending" :branch "main" :commit_sha   "abcdef1234"}]
             :human)]
    (is (str/includes? out "*  github/o/p  #3  ⏳ building"))
    (is (str/includes? out "1.  github/o/q  #9  ⏳ pending"))))

(deftest format-build-human-lists-failed-attributes-with-log-hint
  (let [out (fmt/format-build {:repo repo :build build :attributes attributes} :human)]
    (is (str/includes? out "# Build #7 for github/outskirtslabs/nixbot-cli"))
    (is (str/includes? out "- [OK] Succeeded: 1"))
    (is (str/includes? out "- [FAIL] Failed: 1"))
    (is (str/includes? out "x86_64-linux.bad (x86_64-linux): [FAIL] failed"))
    (is (str/includes? out "Logs: nixbot-cli logs 7 'x86_64-linux.bad' -R github/outskirtslabs/nixbot-cli"))))

(deftest format-build-json-round-trips
  (let [out (fmt/format-build {:repo repo :build build :attributes attributes} :json)]
    (is (= 7 (get-in (json/parse-string out true) [:build :number])))))

(deftest format-failures-renders-log-tail
  (let [out (fmt/format-failures
             {:repo    repo
              :number  7
              :summary {:status        "failed"
                        :error         nil
                        :eval_warnings [{:level "warning" :message "w" :count 2}]
                        :failures      [{:attr     "x86_64-linux.bad"
                                         :status   "failed"
                                         :error    "boom"
                                         :log_tail "tail line 1\ntail line 2"}]}}
             :human)]
    (is (str/includes? out "Failures for build #7"))
    (is (str/includes? out "[warning] w (x2)"))
    (is (str/includes? out "Error: boom"))
    (is (str/includes? out "    tail line 1"))))

(deftest format-watch-compact-is-one-line-with-failed-attrs
  (let [out (fmt/format-watch {:repo repo :build build :attributes attributes} :compact)]
    (is (= 1 (count (str/split-lines out))))
    (is (str/includes? out "#7 github/outskirtslabs/nixbot-cli main failed"))
    (is (str/includes? out "1 succeeded, 1 failed, 0 pending, 0 cancelled"))
    (is (str/includes? out "failed: x86_64-linux.bad"))))

(deftest format-attr-history-renders-rows
  (let [out (fmt/format-attr-history
             {:repo    repo
              :attr    "x86_64-linux.tests"
              :entries [{:build_number     3                      :status "succeeded" :branch "main"
                         :commit_sha       "f54be57a99"           :cached true
                         :build_created_at "2026-06-10T16:33:17Z"}]}
             :human)]
    (is (str/includes? out "History for x86_64-linux.tests"))
    (is (str/includes? out "#3  [OK] succeeded  main  f54be57a  cached")))
  (testing "string keys from JSON parsing work too"
    (is (str/includes?
         (fmt/format-attr-history
          {:repo repo :attr "a" :entries [{"build_number"     1      "status"     "failed"
                                           "branch"           "main" "commit_sha" "abc"
                                           "build_created_at" "t"}]}
          :human)
         "#1  [FAIL] failed"))))

(deftest format-action-and-enabled-render-confirmation
  (is (= "Build #7: restart requested"
         (fmt/format-action {:number 7 :action "restart"} :human)))
  (is (= "Repo outskirtslabs/nixbot-cli is now disabled"
         (fmt/format-enabled {:owner "outskirtslabs" :name "nixbot-cli" :enabled false} :human))))
