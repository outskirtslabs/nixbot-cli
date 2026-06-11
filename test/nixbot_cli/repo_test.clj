(ns nixbot-cli.repo-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.repo :as repo]))

(deftest parse-repo-accepts-owner-repo-with-default-forge
  (is (= {:forge "github"
          :owner "outskirtslabs"
          :name  "nixbot-cli"
          :slug  "github/outskirtslabs/nixbot-cli"}
         (repo/parse-repo "outskirtslabs/nixbot-cli" "github")))
  (is (= "gitea"
         (:forge (repo/parse-repo "outskirtslabs/nixbot-cli" "gitea")))))

(deftest parse-repo-accepts-forge-owner-repo
  (is (= {:forge "gitea"
          :owner "outskirtslabs"
          :name  "nixbot-cli"
          :slug  "gitea/outskirtslabs/nixbot-cli"}
         (repo/parse-repo "gitea/outskirtslabs/nixbot-cli" "github"))))

(deftest parse-repo-maps-known-hosts-to-forges
  (is (= "github" (:forge (repo/parse-repo "github.com/outskirtslabs/nixbot-cli" "gitea"))))
  (is (= "gitlab" (:forge (repo/parse-repo "gitlab.com/owner/repo" "github")))))

(deftest parse-repo-rejects-invalid-repo-slugs
  (is (thrown-with-msg? Exception
                        #"OWNER/REPO"
                        (repo/parse-repo "not-enough" "github"))))

(deftest remote-url->repo-parses-common-git-remote-forms
  (testing "ssh remote"
    (is (= {:forge "github"
            :owner "outskirtslabs"
            :name  "nixbot-cli"
            :slug  "github/outskirtslabs/nixbot-cli"}
           (repo/remote-url->repo "git@github.com:outskirtslabs/nixbot-cli.git" "github"))))
  (testing "https remote"
    (is (= "github/outskirtslabs/nixbot-cli"
           (:slug (repo/remote-url->repo "https://github.com/outskirtslabs/nixbot-cli.git" "github")))))
  (testing "https remote without .git suffix"
    (is (= "github/outskirtslabs/clave"
           (:slug (repo/remote-url->repo "https://github.com/outskirtslabs/clave" "github")))))
  (testing "unknown host uses the default forge"
    (is (= "gitea/owner/repo"
           (:slug (repo/remote-url->repo "git@git.example.com:owner/repo.git" "gitea"))))))

(deftest find-git-dir-traverses-upwards
  (let [root (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path root "checkout" ".git"))
      (fs/create-dirs (fs/path root "checkout" "deep" "nested"))
      (testing "finds .git from a nested directory"
        (is (= (str (fs/path root "checkout"))
               (repo/find-git-dir (str (fs/path root "checkout" "deep" "nested"))))))
      (testing "nil when there is no .git above"
        (is (nil? (repo/find-git-dir (str (fs/create-temp-dir))))))
      (finally
        (fs/delete-tree root)))))

(deftest current-repo-uses-git-remote
  (let [root (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path root ".git"))
      (is (= "github/outskirtslabs/nixbot-cli"
             (:slug (repo/current-repo
                     {:default-forge "github"
                      :start-dir     (str root)
                      :git-remote-fn (fn [dir]
                                       (is (= (str root) dir))
                                       "git@github.com:outskirtslabs/nixbot-cli.git")}))))
      (finally
        (fs/delete-tree root)))))

(deftest current-repo-fails-without-git
  (is (thrown-with-msg? Exception
                        #"Could not determine repository"
                        (repo/current-repo {:default-forge "github"
                                            :start-dir     (str (fs/create-temp-dir))}))))
