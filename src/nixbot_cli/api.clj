(ns nixbot-cli.api
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   [java.net URLEncoder]))

(defn- trim-trailing-slashes [s]
  (str/replace (or s "") #"/+$" ""))

(defn- encode-segment [s]
  (-> (URLEncoder/encode (str s) "UTF-8")
      (str/replace "+" "%20")))

(defn- encode-attr
  "Attribute names may contain slashes; the server routes them with a
  path-converter, so slashes stay literal while the rest is encoded."
  [attr]
  (->> (str/split (str attr) #"/" -1)
       (map encode-segment)
       (str/join "/")))

(defn url [base-url path]
  (str (trim-trailing-slashes base-url) path))

(defn- query-string [params]
  (let [pairs (for [[k v] params
                    :when (some? v)]
                (str (name k) "=" (encode-segment v)))]
    (when (seq pairs)
      (str "?" (str/join "&" pairs)))))

(defn- success-status? [status]
  (<= 200 (long status) 299))

(defn- parse-json-body [body]
  (when-not (str/blank? body)
    (json/parse-string body true)))

(defn request!
  ([client method path]
   (request! client method path {}))
  ([{:keys [base-url token request-fn]
     :or   {request-fn http/request}}
    method
    path
    opts]
   (let [default-headers (cond-> {"Accept" "application/json"}
                           (not (str/blank? token))
                           (assoc "Authorization" (str "Bearer " token)))
         headers         (merge default-headers (:headers opts))
         req             (merge {:method  method
                                 :uri     (url base-url path)
                                 :headers headers
                                 :throw   false}
                                (dissoc opts :headers))
         resp            (request-fn req)
         status          (:status resp)]
     (if (success-status? status)
       resp
       (let [detail (or (:detail (try (parse-json-body (:body resp))
                                      (catch Exception _ nil)))
                        (some-> (:body resp) str/trim not-empty))]
         (throw (ex-info (str "Nixbot API request failed with HTTP " status
                              (when detail (str ": " detail))
                              " (" (:uri req) ")")
                         {:type   :nixbot-cli.api/http-error
                          :status status
                          :body   (:body resp)
                          :uri    (:uri req)})))))))

(defn- repo-path [{:keys [forge owner name]}]
  (str "/api/repos/" (encode-segment forge)
       "/" (encode-segment owner)
       "/" (encode-segment name)))

(defn fetch-repos! [client]
  (-> (request! client :get "/api/repos")
      :body
      parse-json-body))

(defn fetch-repo! [client repo]
  (-> (request! client :get (repo-path repo))
      :body
      parse-json-body))

(defn fetch-builds!
  "One page of builds. `params` supports :page :status :branch :pr_number :commit."
  [client repo params]
  (-> (request! client :get (str (repo-path repo) "/builds" (query-string params)))
      :body
      parse-json-body))

(defn fetch-build! [client repo number]
  (-> (request! client :get (str (repo-path repo) "/builds/" number))
      :body
      parse-json-body))

(defn fetch-failures! [client repo number {:keys [tail]}]
  (-> (request! client :get (str (repo-path repo) "/builds/" number "/failures"
                                 (query-string {:tail tail})))
      :body
      parse-json-body))

(defn fetch-attr-history! [client repo attr]
  (-> (request! client :get (str (repo-path repo) "/attrs/" (encode-attr attr)))
      :body
      parse-json-body))

(defn fetch-queue! [client]
  (-> (request! client :get "/api/queue")
      :body
      parse-json-body))

(defn restart-build! [client repo number]
  (-> (request! client :post (str (repo-path repo) "/builds/" number "/restart"))
      :body
      parse-json-body))

(defn cancel-build! [client repo number]
  (-> (request! client :post (str (repo-path repo) "/builds/" number "/cancel"))
      :body
      parse-json-body))

(defn set-enabled! [client repo enabled?]
  (-> (request! client :post (str (repo-path repo) (if enabled? "/enable" "/disable")))
      :body
      parse-json-body))

(defn fetch-log!
  "Raw plain-text log of one attribute of a build. The raw log route lives
  outside /api. `:tail` limits output to the last N lines."
  [client {:keys [forge owner name]} number attr {:keys [tail]}]
  (:body (request! client :get
                   (str "/repos/" (encode-segment forge)
                        "/" (encode-segment owner)
                        "/" (encode-segment name)
                        "/builds/" number
                        "/logs/raw/" (encode-attr attr)
                        (query-string {:tail tail}))
                   {:headers {"Accept" "text/plain"}})))
