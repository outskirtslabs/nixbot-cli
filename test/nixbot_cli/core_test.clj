(ns nixbot-cli.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.core :as core]))

(deftest parse-command-defaults-to-help
  (is (= :help (:command (core/parse-command []))))
  (is (= :help (:command (core/parse-command ["help"])))))

(deftest parse-command-accepts-options-before-the-command
  (let [parsed (core/parse-command ["-R" "owner/repo" "--json" "builds"])]
    (is (= :builds (:command parsed)))
    (is (= "owner/repo" (:repo parsed)))
    (is (= :json (:format parsed)))))

(deftest parse-command-normalizes-format-flags
  (is (= :json (:format (core/parse-command ["repos" "--json"]))))
  (is (= :edn (:format (core/parse-command ["repos" "--edn"]))))
  (is (= :plain (:format (core/parse-command ["repos" "--plain"]))))
  (is (= :human (:format (core/parse-command ["repos"])))))

(deftest parse-command-handles-positional-build-numbers
  (testing "optional numbers"
    (is (nil? (:number (core/parse-command ["build"]))))
    (is (= 42 (:number (core/parse-command ["build" "42"]))))
    (is (= 42 (:number (core/parse-command ["build" "#42"]))))
    (is (= 7 (:number (core/parse-command ["watch" "7" "--compact"]))))
    (is (nil? (:number (core/parse-command ["failures"])))))
  (testing "required numbers"
    (is (= 9 (:number (core/parse-command ["restart" "9"]))))
    (is (thrown-with-msg? Exception #"restart requires"
                          (core/parse-command ["restart"])))
    (is (thrown-with-msg? Exception #"cancel requires"
                          (core/parse-command ["cancel"]))))
  (testing "non-numeric numbers are rejected"
    (is (thrown-with-msg? Exception #"must be a build number"
                          (core/parse-command ["build" "not-a-number"])))))

(deftest parse-command-logs-requires-number-attr-is-optional
  (let [parsed (core/parse-command ["logs" "42" "x86_64-linux.tests" "--tail" "10"])]
    (is (= :logs (:command parsed)))
    (is (= 42 (:number parsed)))
    (is (= "x86_64-linux.tests" (:attr parsed)))
    (is (= 10 (:tail parsed))))
  (testing "without an attribute the command lists the build's attributes"
    (let [parsed (core/parse-command ["logs" "42"])]
      (is (= :logs (:command parsed)))
      (is (nil? (:attr parsed)))))
  (is (thrown-with-msg? Exception #"logs requires"
                        (core/parse-command ["logs"]))))

(deftest parse-command-attr-requires-attribute
  (is (= "checks.default" (:attr (core/parse-command ["attr" "checks.default"]))))
  (is (thrown-with-msg? Exception #"attr requires"
                        (core/parse-command ["attr"]))))

(deftest parse-command-enable-disable-and-control
  (is (= :enable (:command (core/parse-command ["enable" "-R" "o/r"]))))
  (is (= :disable (:command (core/parse-command ["disable"]))))
  (is (= :cancel (:command (core/parse-command ["cancel" "3"])))))

(deftest parse-command-rejects-unknown-commands
  (is (thrown-with-msg? Exception #"unknown command"
                        (core/parse-command ["bogus"]))))

(deftest parse-command-defaults
  (let [parsed (core/parse-command ["builds"])]
    (is (= 20 (:limit parsed)))
    (is (= 3 (:interval parsed)))))
