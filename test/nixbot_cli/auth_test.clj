(ns nixbot-cli.auth-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.auth :as auth]))

(deftest resolve-token-prefers-env-value-over-command
  (is (= "env-token"
         (auth/resolve-token {:env    {"NIXBOT_API_TOKEN"         "env-token"
                                       "NIXBOT_API_TOKEN_COMMAND" "should-not-run"}
                              :dotenv {}
                              :command-runner
                              (fn [_] (throw (ex-info "command should not run" {})))}))))

(deftest resolve-token-falls-back-to-dotenv
  (is (= "dotenv-token"
         (auth/resolve-token {:env    {}
                              :dotenv {"NIXBOT_API_TOKEN" "dotenv-token"}}))))

(deftest resolve-token-executes-token-command
  (is (= "command-token"
         (auth/resolve-token {:env            {"NIXBOT_API_TOKEN_COMMAND" "secret-tool get token"}
                              :dotenv         {}
                              :command-runner (fn [command]
                                                (is (= "secret-tool get token" command))
                                                "command-token\n")}))))

(deftest resolve-token-returns-nil-when-unset
  (is (nil? (auth/resolve-token {:env {} :dotenv {}}))))

(deftest resolve-base-url-prefers-flag-then-env-then-dotenv
  (testing "--base-url wins"
    (is (= "https://flag.test"
           (auth/resolve-base-url {:base-url "https://flag.test"
                                   :env      {"NIXBOT_URL" "https://env.test"}
                                   :dotenv   {}}))))
  (testing "NIXBOT_URL env"
    (is (= "https://env.test"
           (auth/resolve-base-url {:env    {"NIXBOT_URL" "https://env.test"}
                                   :dotenv {"NIXBOT_URL" "https://dotenv.test"}}))))
  (testing "dotenv fallback"
    (is (= "https://dotenv.test"
           (auth/resolve-base-url {:env    {}
                                   :dotenv {"NIXBOT_URL" "https://dotenv.test"}})))))

(deftest resolve-default-forge-defaults-to-github
  (is (= "github" (auth/resolve-default-forge {:env {} :dotenv {}})))
  (is (= "gitea"
         (auth/resolve-default-forge {:env    {"NIXBOT_CLI_DEFAULT_FORGE" "gitea"}
                                      :dotenv {}}))))

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
