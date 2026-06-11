(ns nixbot-cli.repo
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def host->forge
  {"github.com" "github"
   "gitlab.com" "gitlab"})

(defn- repo-map [forge owner name]
  {:forge forge
   :owner owner
   :name  name
   :slug  (str forge "/" owner "/" name)})

(defn parse-repo
  "Parses a --repo argument: FORGE/OWNER/REPO or OWNER/REPO (using `default-forge`)."
  [repo default-forge]
  (let [parts (->> (str/split (str/trim (or repo "")) #"/")
                   (remove str/blank?)
                   vec)]
    (case (count parts)
      2 (repo-map default-forge (first parts) (second parts))
      3 (repo-map (get host->forge (first parts) (first parts))
                  (second parts)
                  (nth parts 2))
      (throw (ex-info "Repository must use OWNER/REPO or FORGE/OWNER/REPO format"
                      {:type :nixbot-cli.repo/invalid-repo
                       :repo repo})))))

(defn remote-url->repo
  "Parses a git remote URL (ssh or https) into a repo map. The forge is
  derived from the host when known, otherwise `default-forge` is used."
  [remote-url default-forge]
  (let [url          (-> (str/trim (or remote-url ""))
                         (str/replace #"\.git$" "")
                         (str/replace #"^(ssh://)?git@" "")
                         (str/replace #"^https?://" "")
                         (str/replace #":" "/"))
        parts        (->> (str/split url #"/")
                          (remove str/blank?)
                          vec)
        host         (first parts)
        [owner name] (take-last 2 parts)]
    (if (and host owner name (>= (count parts) 3))
      (repo-map (get host->forge host default-forge) owner name)
      (throw (ex-info (str "Could not parse git remote URL: " remote-url)
                      {:type       :nixbot-cli.repo/invalid-remote
                       :remote-url remote-url})))))

(defn find-git-dir
  "Walks up from `start-dir` looking for a directory containing .git.
  Returns the containing directory as a string, or nil."
  [start-dir]
  (loop [dir (fs/absolutize start-dir)]
    (cond
      (nil? dir) nil
      (fs/exists? (fs/path dir ".git")) (str dir)
      :else (recur (fs/parent dir)))))

(defn- git-remote-url [dir]
  (let [{:keys [exit out]} (p/sh {:out :string :err :string :continue true}
                                 "git" "-C" dir "remote" "get-url" "origin")]
    (when (zero? exit)
      (str/trim out))))

(defn current-repo
  "Determines the repo from the git checkout at (or above) the current directory."
  [{:keys [default-forge start-dir git-remote-fn]
    :or   {start-dir "."}}]
  (let [git-dir (find-git-dir start-dir)
        remote  (when git-dir
                  ((or git-remote-fn git-remote-url) git-dir))]
    (if (str/blank? remote)
      (throw (ex-info "Could not determine repository from git. Pass -R [FORGE/]OWNER/REPO."
                      {:type :nixbot-cli.repo/missing-repo}))
      (remote-url->repo remote default-forge))))

(defn resolve-repo
  "Resolves the target repo from a --repo argument or the local git checkout."
  ([repo-arg]
   (resolve-repo repo-arg {:default-forge "github"}))
  ([repo-arg opts]
   (if (str/blank? repo-arg)
     (current-repo opts)
     (parse-repo repo-arg (:default-forge opts)))))
