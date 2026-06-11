(ns nixbot-cli.test-runner
  (:require
   [clojure.test :as t]
   [nixbot-cli.api-test]
   [nixbot-cli.auth-test]
   [nixbot-cli.builds-test]
   [nixbot-cli.core-test]
   [nixbot-cli.format-test]
   [nixbot-cli.repo-test]))

(def test-namespaces
  ['nixbot-cli.api-test
   'nixbot-cli.auth-test
   'nixbot-cli.builds-test
   'nixbot-cli.core-test
   'nixbot-cli.format-test
   'nixbot-cli.repo-test])

(defn -main [& _args]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
