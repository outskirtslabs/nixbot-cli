(ns nixbot-cli.core
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nixbot-cli.api :as api]
   [nixbot-cli.auth :as auth]
   [nixbot-cli.builds :as builds]
   [nixbot-cli.format :as fmt]
   [nixbot-cli.repo :as repo])
  (:gen-class))

(def cli-config
  {:spec      {:base-url    {:desc "Nixbot instance URL (defaults to NIXBOT_URL)"
                             :ref  "<url>"}
               :format      {:desc     "Output format: human, plain, json, edn"
                             :ref      "<format>"
                             :coerce   :keyword
                             :validate #{:human :plain :json :edn}}
               :json        {:desc   "Output JSON"
                             :coerce :boolean}
               :edn         {:desc   "Output EDN"
                             :coerce :boolean}
               :plain       {:desc   "Output plain text"
                             :coerce :boolean}
               :repo        {:desc "Select repository: [FORGE/]OWNER/REPO"
                             :ref  "<repo>"}
               :status      {:desc "Filter builds by status"
                             :ref  "<status>"}
               :branch      {:desc "Filter builds by branch"
                             :ref  "<branch>"}
               :pr          {:desc   "Filter builds by PR number"
                             :ref    "<n>"
                             :coerce :long}
               :commit      {:desc "Filter builds by commit SHA prefix"
                             :ref  "<sha>"}
               :limit       {:desc   "Maximum number of items to fetch"
                             :ref    "<n>"
                             :coerce :long}
               :page        {:desc   "Fetch a single page of builds"
                             :ref    "<n>"
                             :coerce :long}
               :tail        {:desc   "Limit logs to the last N lines"
                             :ref    "<n>"
                             :coerce :long}
               :compact     {:desc   "Show only compact watch output"
                             :coerce :boolean}
               :exit-status {:desc   "Exit with non-zero status if the watched build fails"
                             :coerce :boolean}
               :interval    {:desc   "Refresh interval in seconds"
                             :ref    "<seconds>"
                             :coerce :long}
               :help        {:desc   "Show help"
                             :alias  :h
                             :coerce :boolean}}
   :exec-args {:format   :human
               :limit    20
               :interval 3}
   :alias     {:R :repo
               :L :limit
               :s :status
               :i :interval}
   :restrict  true})

(defn usage-error [message]
  (throw (ex-info message {:type :nixbot-cli.core/usage-error})))

(def commands
  #{"help" "repos" "builds" "queue" "build" "failures" "attr" "logs"
    "watch" "restart" "cancel" "enable" "disable" "config"})

(def value-options
  #{"--base-url" "--format" "--repo" "-R" "--status" "-s" "--branch"
    "--pr" "--commit" "--limit" "-L" "--page" "--tail" "--interval" "-i"})

(defn- find-command-index [argv]
  (loop [i 0]
    (when (< i (count argv))
      (let [arg (nth argv i)]
        (cond
          (contains? commands arg) i
          (contains? value-options arg) (recur (+ i 2))
          (and (str/starts-with? arg "--") (str/includes? arg "=")) (recur (inc i))
          :else (recur (inc i)))))))

(defn- normalize-argv [argv]
  (let [argv (vec argv)
        idx  (find-command-index argv)]
    (if (and idx (pos? idx))
      (vec (concat [(nth argv idx)]
                   (subvec argv (inc idx))
                   (subvec argv 0 idx)))
      argv)))

(defn- normalize-format [opts]
  (let [format (cond
                 (:json opts) :json
                 (:edn opts) :edn
                 (:plain opts) :plain
                 :else (:format opts))]
    (-> opts
        (assoc :format (if (keyword? format) format (keyword format)))
        (dissoc :json :edn :plain))))

(defn- parse-build-number [s context]
  (when-not (str/blank? s)
    (or (parse-long (str/replace s #"^#" ""))
        (usage-error (str context " must be a build number, got: " s)))))

(defn parse-command [argv]
  (let [argv                   (normalize-argv argv)
        {:keys [opts args]}    (cli/parse-args argv cli-config)
        [command & positional] args
        opts                   (normalize-format opts)]
    (case command
      nil (assoc opts :command :help)
      "help" (assoc opts :command :help)
      "repos" (assoc opts :command :repos)
      "builds" (assoc opts :command :builds)
      "queue" (assoc opts :command :queue)
      "build" (assoc opts :command :build
                     :number (parse-build-number (first positional) "build"))
      "failures" (assoc opts :command :failures
                        :number (parse-build-number (first positional) "failures"))
      "attr" (let [attr (first positional)]
               (when (str/blank? attr)
                 (usage-error "attr requires an attribute name"))
               (assoc opts :command :attr :attr attr))
      "logs" (let [[number attr] positional]
               (when (str/blank? number)
                 (usage-error "logs requires a build number"))
               (assoc opts :command :logs
                      :number (parse-build-number number "logs")
                      :attr (when-not (str/blank? attr) attr)))
      "watch" (assoc opts :command :watch
                     :number (parse-build-number (first positional) "watch"))
      "restart" (let [number (parse-build-number (first positional) "restart")]
                  (when-not number
                    (usage-error "restart requires a build number"))
                  (assoc opts :command :restart :number number))
      "cancel" (let [number (parse-build-number (first positional) "cancel")]
                 (when-not number
                   (usage-error "cancel requires a build number"))
                 (assoc opts :command :cancel :number number))
      "enable" (assoc opts :command :enable)
      "disable" (assoc opts :command :disable)
      "config" (assoc opts :command :config)
      (usage-error (str "unknown command: " command)))))

(defn help-text []
  (str/join
   "\n"
   ["nixbot-cli - inspect and control Nixbot CI builds"
    ""
    "Usage:"
    "  nixbot-cli repos"
    "  nixbot-cli builds [-R REPO] [--status STATUS] [--branch BRANCH] [--pr N] [--commit SHA] [--limit N | --page N]"
    "  nixbot-cli queue"
    "  nixbot-cli build [number] [-R REPO]"
    "  nixbot-cli failures [number] [-R REPO] [--tail N]"
    "  nixbot-cli attr <attribute> [-R REPO] [--limit N]"
    "  nixbot-cli logs <number> [attribute] [-R REPO] [--tail N]"
    "  nixbot-cli watch [number] [-R REPO] [--compact] [--exit-status] [--interval N]"
    "  nixbot-cli restart <number> [-R REPO]"
    "  nixbot-cli cancel <number> [-R REPO]"
    "  nixbot-cli enable [-R REPO]"
    "  nixbot-cli disable [-R REPO]"
    "  nixbot-cli config"
    ""
    "REPO is [FORGE/]OWNER/REPO; without -R the repo is detected from the local"
    "git checkout. The default forge is github (override: NIXBOT_CLI_DEFAULT_FORGE)."
    ""
    "Environment:"
    "  NIXBOT_URL                Nixbot instance URL"
    "  NIXBOT_API_TOKEN          API token"
    "  NIXBOT_API_TOKEN_COMMAND  Command printing the API token to stdout"
    ""
    "Configuration file ($XDG_CONFIG_HOME/nixbot-cli/config.edn, all keys optional;"
    "environment variables take precedence):"
    "  {:url           \"https://nixbot.example.com\""
    "   :token         \"bnix_...\""
    "   :token-command \"pass show nixbot-token\""
    "   :default-forge \"github\"}"
    ""
    "Options:"
    (cli/format-opts cli-config)]))

(defn- client [opts]
  (let [base-url (auth/resolve-base-url opts)]
    (when (str/blank? base-url)
      (usage-error (str "Nixbot instance URL is required. Set NIXBOT_URL, pass --base-url, "
                        "or put :url in " (auth/config-file-path) ".")))
    {:base-url base-url
     :token    (auth/resolve-token opts)}))

(defn- target-repo [parsed]
  (repo/resolve-repo (:repo parsed)
                     {:default-forge (auth/resolve-default-forge parsed)}))

(defn- build-filters [parsed]
  {:status    (builds/normalize-status (:status parsed))
   :branch    (:branch parsed)
   :pr_number (:pr parsed)
   :commit    (:commit parsed)})

(defn- latest-build-number! [client repo]
  (let [{:keys [items]} (api/fetch-builds! client repo {:page 1})]
    (or (some-> items first :number)
        (usage-error (str "No builds found for " (:slug repo))))))

(defn- resolve-number! [parsed client repo]
  (or (:number parsed)
      (latest-build-number! client repo)))

;; Builds in these states can have failed attributes worth surfacing.
(def ^:private failed-attr-statuses #{"failed" "building"})

(defn- with-failed-attrs
  "Nests each failed/building build's failed attributes under :failed_attrs
  (one extra request per such build)."
  [client repo items]
  (mapv (fn [item]
          (if (contains? failed-attr-statuses (str (builds/field item :status)))
            (let [{:keys [attributes]} (api/fetch-build! client repo (builds/field item :number))
                  failed               (filterv builds/failed-attr? attributes)]
              (cond-> item
                (seq failed) (assoc :failed_attrs failed)))
            item))
        items))

(defn- list-builds! [parsed client repo]
  (let [filters (build-filters parsed)
        items   (if (:page parsed)
                  (:items (api/fetch-builds! client repo (assoc filters :page (:page parsed))))
                  (builds/fetch-up-to
                   #(api/fetch-builds! client repo (assoc filters :page %))
                   (:limit parsed)))
        items   (with-failed-attrs client repo (vec items))
        data    {:repo repo :items items}]
    (println (fmt/format-builds data (:format parsed)))
    data))

(defn- build-detail! [client repo number]
  (let [{:keys [build attributes]} (api/fetch-build! client repo number)]
    {:repo repo :build build :attributes (vec attributes)}))

(defn- watch! [parsed client repo]
  (let [number (resolve-number! parsed client repo)
        mode   (if (:compact parsed) :compact (:format parsed))]
    (loop []
      (let [data (build-detail! client repo number)]
        (println (fmt/format-watch data mode))
        (flush)
        (if (builds/terminal? (:build data))
          {:data      data
           :exit-code (if (:exit-status parsed) (builds/exit-code (:build data)) 0)}
          (do
            (Thread/sleep (* 1000 (:interval parsed)))
            (recur)))))))

(defn execute! [parsed]
  (case (:command parsed)
    :help (do (println (help-text)) nil)
    :config (let [path (str (or (:config-path parsed) (auth/config-file-path)))
                  data {:config-file   {:path   path
                                        :exists (boolean (fs/regular-file? path))}
                        :url           (auth/resolve-base-url* parsed)
                        :token         (some-> (auth/resolve-token* parsed)
                                               (update :value fmt/mask-token))
                        :default-forge (auth/resolve-default-forge* parsed)}]
              (println (fmt/format-config data (:format parsed)))
              data)
    :repos (let [client (client parsed)
                 repos  (api/fetch-repos! client)]
             (println (fmt/format-repos repos (:format parsed)))
             repos)
    :queue (let [client  (client parsed)
                 entries (api/fetch-queue! client)]
             (println (fmt/format-queue entries (:format parsed)))
             entries)
    :builds (list-builds! parsed (client parsed) (target-repo parsed))
    :build (let [client (client parsed)
                 repo   (target-repo parsed)
                 data   (build-detail! client repo (resolve-number! parsed client repo))]
             (println (fmt/format-build data (:format parsed)))
             data)
    :failures (let [client  (client parsed)
                    repo    (target-repo parsed)
                    number  (resolve-number! parsed client repo)
                    summary (api/fetch-failures! client repo number {:tail (:tail parsed)})
                    data    {:repo repo :number number :summary summary}]
                (println (fmt/format-failures data (:format parsed)))
                data)
    :attr (let [client  (client parsed)
                repo    (target-repo parsed)
                entries (->> (api/fetch-attr-history! client repo (:attr parsed))
                             (take (:limit parsed))
                             vec)
                data    {:repo repo :attr (:attr parsed) :entries entries}]
            (println (fmt/format-attr-history data (:format parsed)))
            data)
    :logs (let [client (client parsed)
                repo   (target-repo parsed)]
            (if (:attr parsed)
              (let [logs (api/fetch-log! client repo (:number parsed) (:attr parsed)
                                         {:tail (:tail parsed)})]
                (println logs)
                logs)
              ;; No attribute given: list the build's attributes instead.
              (let [data (build-detail! client repo (:number parsed))]
                (println (fmt/format-attr-listing data (:format parsed)))
                data)))
    :watch (watch! parsed (client parsed) (target-repo parsed))
    :restart (let [client (client parsed)
                   repo   (target-repo parsed)
                   result (api/restart-build! client repo (:number parsed))]
               (println (fmt/format-action result (:format parsed)))
               result)
    :cancel (let [client (client parsed)
                  repo   (target-repo parsed)
                  result (api/cancel-build! client repo (:number parsed))]
              (println (fmt/format-action result (:format parsed)))
              result)
    :enable (let [client (client parsed)
                  repo   (target-repo parsed)
                  result (api/set-enabled! client repo true)]
              (println (fmt/format-enabled result (:format parsed)))
              result)
    :disable (let [client (client parsed)
                   repo   (target-repo parsed)
                   result (api/set-enabled! client repo false)]
               (println (fmt/format-enabled result (:format parsed)))
               result)))

(defn -main [& args]
  (try
    (let [result (execute! (parse-command args))]
      (when (and (map? result) (pos? (or (:exit-code result) 0)))
        (System/exit (:exit-code result))))
    (catch Exception e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit 1))))
