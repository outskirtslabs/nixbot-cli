(ns nixbot-cli.auth-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.auth :as auth]))

(defn- opts [m]
  (merge {:env {} :dotenv {} :config {}} m))

(deftest resolve-token-prefers-env-value-over-command
  (is (= "env-token"
         (auth/resolve-token (opts {:env {"NIXBOT_API_TOKEN"         "env-token"
                                          "NIXBOT_API_TOKEN_COMMAND" "should-not-run"}
                                    :command-runner
                                    (fn [_] (throw (ex-info "command should not run" {})))})))))

(deftest resolve-token-falls-back-to-dotenv
  (is (= "dotenv-token"
         (auth/resolve-token (opts {:dotenv {"NIXBOT_API_TOKEN" "dotenv-token"}})))))

(deftest resolve-token-executes-token-command
  (is (= "command-token"
         (auth/resolve-token (opts {:env            {"NIXBOT_API_TOKEN_COMMAND" "secret-tool get token"}
                                    :command-runner (fn [command]
                                                      (is (= "secret-tool get token" command))
                                                      "command-token\n")})))))

(deftest resolve-token-returns-nil-when-unset
  (is (nil? (auth/resolve-token (opts {})))))

(deftest resolve-token-env-vars-take-precedence-over-config
  (testing "env token beats config token"
    (is (= {:value "env-token" :source :env}
           (auth/resolve-token* (opts {:env    {"NIXBOT_API_TOKEN" "env-token"}
                                       :config {:token "config-token"}})))))
  (testing "env token command beats config token"
    (is (= {:value "env-command-token" :source :env-command}
           (auth/resolve-token* (opts {:env            {"NIXBOT_API_TOKEN_COMMAND" "cmd"}
                                       :config         {:token "config-token"}
                                       :command-runner (constantly "env-command-token")}))))))

(deftest resolve-token-from-config
  (testing ":token"
    (is (= {:value "config-token" :source :config}
           (auth/resolve-token* (opts {:config {:token "config-token"}})))))
  (testing ":token-command runs when no :token is set"
    (is (= {:value "config-command-token" :source :config-command}
           (auth/resolve-token* (opts {:config         {:token-command "pass show nixbot"}
                                       :command-runner (fn [command]
                                                         (is (= "pass show nixbot" command))
                                                         "config-command-token\n")}))))))

(deftest resolve-base-url-prefers-flag-then-env-then-dotenv-then-config
  (testing "--base-url wins"
    (is (= "https://flag.test"
           (auth/resolve-base-url (opts {:base-url "https://flag.test"
                                         :env      {"NIXBOT_URL" "https://env.test"}})))))
  (testing "NIXBOT_URL env"
    (is (= "https://env.test"
           (auth/resolve-base-url (opts {:env    {"NIXBOT_URL" "https://env.test"}
                                         :dotenv {"NIXBOT_URL" "https://dotenv.test"}})))))
  (testing "dotenv beats config"
    (is (= "https://dotenv.test"
           (auth/resolve-base-url (opts {:dotenv {"NIXBOT_URL" "https://dotenv.test"}
                                         :config {:url "https://config.test"}})))))
  (testing "config fallback"
    (is (= {:value "https://config.test" :source :config}
           (auth/resolve-base-url* (opts {:config {:url "https://config.test"}})))))
  (testing "a bare domain gets an https:// prefix"
    (is (= "https://ci.example.com"
           (auth/resolve-base-url (opts {:config {:url "ci.example.com"}})))))
  (testing "nil when unset everywhere"
    (is (nil? (auth/resolve-base-url (opts {}))))))

(deftest resolve-default-forge-prefers-env-then-config-then-github
  (is (= {:value "github" :source :default}
         (auth/resolve-default-forge* (opts {}))))
  (is (= {:value "gitea" :source :config}
         (auth/resolve-default-forge* (opts {:config {:default-forge "gitea"}}))))
  (is (= {:value "gitlab" :source :env}
         (auth/resolve-default-forge* (opts {:env    {"NIXBOT_CLI_DEFAULT_FORGE" "gitlab"}
                                             :config {:default-forge "gitea"}})))))

(deftest read-config-file-handles-missing-invalid-and-extra-keys
  (testing "missing file yields {}"
    (is (= {} (auth/read-config-file (str (fs/path (fs/create-temp-dir) "config.edn"))))))
  (testing "valid file keeps only known string-valued keys"
    (let [path (str (fs/path (fs/create-temp-dir) "config.edn"))]
      (spit path (pr-str {:url           "https://ci.example.com"
                          :token         "bnix_abc"
                          :token-command "pass show nixbot"
                          :default-forge "gitea"
                          :unknown-key   "ignored"
                          :extra         42}))
      (is (= {:url           "https://ci.example.com"
              :token         "bnix_abc"
              :token-command "pass show nixbot"
              :default-forge "gitea"}
             (auth/read-config-file path)))))
  (testing "non-string values for known keys are dropped"
    (let [path (str (fs/path (fs/create-temp-dir) "config.edn"))]
      (spit path (pr-str {:url 42 :default-forge "gitea"}))
      (is (= {:default-forge "gitea"} (auth/read-config-file path)))))
  (testing "invalid EDN raises a helpful error"
    (let [path (str (fs/path (fs/create-temp-dir) "config.edn"))]
      (spit path "{:url \"unclosed")
      (is (thrown-with-msg? Exception #"Could not parse"
                            (auth/read-config-file path)))))
  (testing "non-map EDN raises a helpful error"
    (let [path (str (fs/path (fs/create-temp-dir) "config.edn"))]
      (spit path "[:not :a :map]")
      (is (thrown-with-msg? Exception #"must contain an EDN map"
                            (auth/read-config-file path))))))

(deftest dotenv-parsing-handles-quotes-comments-and-exports
  (let [tmp (java.io.File/createTempFile "nixbot-cli" ".env")]
    (try
      (spit tmp (str "# comment\n"
                     "export NIXBOT_API_TOKEN=\"quoted-token\"\n"
                     "NIXBOT_URL='https://example.test'\n"
                     "INVALID LINE\n"))
      (is (= {"NIXBOT_API_TOKEN" "quoted-token"
              "NIXBOT_URL"       "https://example.test"}
             (auth/dotenv-map (str tmp))))
      (finally
        (.delete tmp)))))
