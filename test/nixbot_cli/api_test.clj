(ns nixbot-cli.api-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.api :as api]))

(defn echo-json-request [req]
  {:status 200
   :body   (json/generate-string
            {:seen {:method        (name (:method req))
                    :uri           (:uri req)
                    :authorization (get-in req [:headers "Authorization"])
                    :accept        (get-in req [:headers "Accept"])}})})

(def client
  {:base-url   "https://example.test/"
   :token      "token-123"
   :request-fn echo-json-request})

(def repo
  {:forge "github" :owner "outskirtslabs" :name "nixbot-cli"})

(deftest fetch-repos-uses-api-repos-endpoint
  (is (= {:seen {:method        "get"
                 :uri           "https://example.test/api/repos"
                 :authorization "Bearer token-123"
                 :accept        "application/json"}}
         (api/fetch-repos! client))))

(deftest fetch-repo-uses-forge-owner-name-path
  (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli"
         (get-in (api/fetch-repo! client repo) [:seen :uri]))))

(deftest fetch-builds-passes-query-filters
  (testing "page only"
    (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds?page=1"
           (get-in (api/fetch-builds! client repo {:page 1}) [:seen :uri]))))
  (testing "all filters, nils omitted"
    (is (= (str "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds"
                "?page=2&status=failed&branch=main&pr_number=7&commit=abc123")
           (get-in (api/fetch-builds! client repo {:page      2
                                                   :status    "failed"
                                                   :branch    "main"
                                                   :pr_number 7
                                                   :commit    "abc123"
                                                   :ignored   nil})
                   [:seen :uri])))))

(deftest fetch-build-uses-build-number-path
  (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds/42"
         (get-in (api/fetch-build! client repo 42) [:seen :uri]))))

(deftest fetch-failures-supports-tail
  (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds/42/failures?tail=10"
         (get-in (api/fetch-failures! client repo 42 {:tail 10}) [:seen :uri])))
  (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds/42/failures"
         (get-in (api/fetch-failures! client repo 42 {}) [:seen :uri]))))

(deftest fetch-attr-history-keeps-slashes-in-attr
  (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/attrs/checks.x86_64-linux.default"
         (get-in (api/fetch-attr-history! client repo "checks.x86_64-linux.default") [:seen :uri])))
  (testing "slashes survive, other reserved characters are encoded"
    (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/attrs/a/b%22c"
           (get-in (api/fetch-attr-history! client repo "a/b\"c") [:seen :uri])))))

(deftest fetch-queue-uses-api-queue-endpoint
  (is (= "https://example.test/api/queue"
         (get-in (api/fetch-queue! client) [:seen :uri]))))

(deftest control-endpoints-use-post
  (testing "restart"
    (let [seen (:seen (api/restart-build! client repo 42))]
      (is (= "post" (:method seen)))
      (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds/42/restart"
             (:uri seen)))))
  (testing "cancel"
    (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/builds/42/cancel"
           (get-in (api/cancel-build! client repo 42) [:seen :uri]))))
  (testing "enable and disable"
    (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/enable"
           (get-in (api/set-enabled! client repo true) [:seen :uri])))
    (is (= "https://example.test/api/repos/github/outskirtslabs/nixbot-cli/disable"
           (get-in (api/set-enabled! client repo false) [:seen :uri])))))

(deftest fetch-log-uses-raw-log-route-outside-api
  (let [request-fn (fn [req]
                     {:status 200
                      :body   (str (name (:method req)) " " (:uri req)
                                   " accept=" (get-in req [:headers "Accept"]))})]
    (is (= (str "get https://example.test/repos/github/outskirtslabs/nixbot-cli"
                "/builds/42/logs/raw/x86_64-linux.tests?tail=50 accept=text/plain")
           (api/fetch-log! {:base-url "https://example.test" :request-fn request-fn}
                           repo 42 "x86_64-linux.tests" {:tail 50})))))

(deftest http-errors-include-status-body-and-detail
  (let [e (try
            (api/fetch-repos! {:base-url   "https://example.test"
                               :request-fn (fn [_] {:status 403
                                                    :body   "{\"detail\":\"not authorized\"}"})})
            nil
            (catch Exception e e))]
    (is (= {:type   :nixbot-cli.api/http-error
            :status 403
            :body   "{\"detail\":\"not authorized\"}"}
           (select-keys (ex-data e) [:type :status :body])))
    (is (re-find #"HTTP 403: not authorized" (ex-message e)))))
