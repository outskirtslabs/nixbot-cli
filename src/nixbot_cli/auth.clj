(ns nixbot-cli.auth
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def token-env-var "NIXBOT_API_TOKEN")
(def token-command-env-var "NIXBOT_API_TOKEN_COMMAND")
(def url-env-var "NIXBOT_URL")
(def default-forge-env-var "NIXBOT_CLI_DEFAULT_FORGE")

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

(defn- env-and-dotenv [opts]
  {:env    (if (contains? opts :env) (:env opts) (System/getenv))
   :dotenv (if (contains? opts :dotenv)
             (:dotenv opts)
             (dotenv-map (or (:dotenv-path opts) ".env")))})

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

(defn resolve-token
  "Resolves the API token: NIXBOT_API_TOKEN value first, then the output
  of NIXBOT_API_TOKEN_COMMAND. Both are also read from a local .env file."
  [opts]
  (let [{:keys [env dotenv]} (env-and-dotenv opts)
        command              (or (non-blank (get env token-command-env-var))
                                 (non-blank (get dotenv token-command-env-var)))]
    (or (non-blank (get env token-env-var))
        (non-blank (get dotenv token-env-var))
        (some->> command (run-token-command opts)))))

(defn resolve-base-url
  "Resolves the Nixbot instance URL: --base-url, then NIXBOT_URL (env or .env)."
  [opts]
  (let [{:keys [env dotenv]} (env-and-dotenv opts)]
    (or (non-blank (:base-url opts))
        (non-blank (get env url-env-var))
        (non-blank (get dotenv url-env-var)))))

(defn resolve-default-forge
  "Resolves the default forge: NIXBOT_CLI_DEFAULT_FORGE (env or .env), else github."
  [opts]
  (let [{:keys [env dotenv]} (env-and-dotenv opts)]
    (or (non-blank (get env default-forge-env-var))
        (non-blank (get dotenv default-forge-env-var))
        "github")))
