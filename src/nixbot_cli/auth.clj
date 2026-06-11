(ns nixbot-cli.auth
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def token-env-var "NIXBOT_API_TOKEN")
(def token-command-env-var "NIXBOT_API_TOKEN_COMMAND")
(def url-env-var "NIXBOT_URL")
(def default-forge-env-var "NIXBOT_CLI_DEFAULT_FORGE")

(def config-keys [:url :token :token-command :default-forge])

(defn- non-blank [s]
  (when-not (str/blank? s)
    s))

(defn- strip-matching-quotes [s]
  (let [s (str/trim (or s ""))]
    (if (and (>= (count s) 2)
             (or (and (= \" (first s)) (= \" (last s)))
                 (and (= \' (first s)) (= \' (last s)))))
      (subs s 1 (dec (count s)))
      s)))

(defn- parse-dotenv-line [line]
  (let [line (str/trim line)
        line (if (str/starts-with? line "export ")
               (str/trim (subs line 7))
               line)]
    (when (and (not (str/blank? line))
               (not (str/starts-with? line "#")))
      (let [[k v] (str/split line #"=" 2)]
        (when (and k v (re-matches #"[A-Za-z_][A-Za-z0-9_]*" (str/trim k)))
          [(str/trim k) (strip-matching-quotes v)])))))

(defn dotenv-map
  ([]
   (dotenv-map ".env"))
  ([path]
   (if (fs/regular-file? path)
     (->> (fs/read-all-lines path)
          (keep parse-dotenv-line)
          (into {}))
     {})))

(defn config-file-path
  "Default config file location, honoring XDG_CONFIG_HOME."
  []
  (str (fs/xdg-config-home "nixbot-cli") "/config.edn"))

(defn read-config-file
  "Parses the config.edn at `path`. Missing file yields {}. The file must
  contain an EDN map; only string values for the known keys are kept."
  [path]
  (if (fs/regular-file? path)
    (let [data (try
                 (edn/read-string (slurp (str path)))
                 (catch Exception e
                   (throw (ex-info (str "Could not parse " path ": " (ex-message e))
                                   {:type :nixbot-cli.auth/invalid-config
                                    :path (str path)}))))]
      (when-not (map? data)
        (throw (ex-info (str path " must contain an EDN map")
                        {:type :nixbot-cli.auth/invalid-config
                         :path (str path)})))
      (into {}
            (for [k     config-keys
                  :let  [v (get data k)]
                  :when (and (string? v) (not (str/blank? v)))]
              [k v])))
    {}))

(defn- settings-sources [opts]
  {:env    (if (contains? opts :env) (:env opts) (System/getenv))
   :dotenv (if (contains? opts :dotenv)
             (:dotenv opts)
             (dotenv-map (or (:dotenv-path opts) ".env")))
   :config (if (contains? opts :config)
             (:config opts)
             (read-config-file (or (:config-path opts) (config-file-path))))})

(defn- run-token-command [opts command]
  (let [out (if-let [runner (:command-runner opts)]
              (runner command)
              (let [{:keys [exit out err]} (p/shell {:out :string :err :string :continue true} command)]
                (when-not (zero? exit)
                  (throw (ex-info (str token-command-env-var " failed: " (str/trim (or err "")))
                                  {:type    :nixbot-cli.auth/token-command-failed
                                   :command command
                                   :exit    exit
                                   :err     err})))
                out))]
    (non-blank (str/trim (or out "")))))

(defn- found [value source]
  (when value
    {:value value :source source}))

(defn resolve-token*
  "Resolves the API token and where it came from. Order: NIXBOT_API_TOKEN,
  NIXBOT_API_TOKEN_COMMAND (env, then .env for both), then :token and
  :token-command from config.edn."
  [opts]
  (let [{:keys [env dotenv config]} (settings-sources opts)]
    (or (found (non-blank (get env token-env-var)) :env)
        (found (non-blank (get dotenv token-env-var)) :dotenv)
        (found (some->> (non-blank (get env token-command-env-var))
                        (run-token-command opts))
               :env-command)
        (found (some->> (non-blank (get dotenv token-command-env-var))
                        (run-token-command opts))
               :dotenv-command)
        (found (non-blank (:token config)) :config)
        (found (some->> (non-blank (:token-command config))
                        (run-token-command opts))
               :config-command))))

(defn resolve-token [opts]
  (:value (resolve-token* opts)))

(defn- ensure-scheme
  "A bare domain like ci.example.com gets an https:// prefix."
  [url]
  (when url
    (if (str/includes? url "://")
      url
      (str "https://" url))))

(defn resolve-base-url*
  "Resolves the Nixbot instance URL and where it came from: --base-url,
  NIXBOT_URL (env, then .env), then :url from config.edn."
  [opts]
  (let [{:keys [env dotenv config]} (settings-sources opts)
        resolved                    (or (found (non-blank (:base-url opts)) :flag)
                                        (found (non-blank (get env url-env-var)) :env)
                                        (found (non-blank (get dotenv url-env-var)) :dotenv)
                                        (found (non-blank (:url config)) :config))]
    (some-> resolved (update :value ensure-scheme))))

(defn resolve-base-url [opts]
  (:value (resolve-base-url* opts)))

(defn resolve-default-forge*
  "Resolves the default forge and where it came from: NIXBOT_CLI_DEFAULT_FORGE
  (env, then .env), then :default-forge from config.edn, else github."
  [opts]
  (let [{:keys [env dotenv config]} (settings-sources opts)]
    (or (found (non-blank (get env default-forge-env-var)) :env)
        (found (non-blank (get dotenv default-forge-env-var)) :dotenv)
        (found (non-blank (:default-forge config)) :config)
        (found "github" :default))))

(defn resolve-default-forge [opts]
  (:value (resolve-default-forge* opts)))
