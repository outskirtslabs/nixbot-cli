(ns nixbot-cli.builds-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nixbot-cli.builds :as builds]))

(deftest normalize-status-accepts-aliases-and-rejects-unknown
  (is (= "succeeded" (builds/normalize-status "success")))
  (is (= "failed" (builds/normalize-status "failure")))
  (is (= "failed" (builds/normalize-status "FAILED")))
  (is (= "cancelled" (builds/normalize-status "canceled")))
  (is (= "building" (builds/normalize-status "running")))
  (is (nil? (builds/normalize-status nil)))
  (is (nil? (builds/normalize-status "  ")))
  (is (thrown-with-msg? Exception
                        #"Unsupported status"
                        (builds/normalize-status "bogus"))))

(deftest summarize-attrs-groups-statuses
  (is (= {:succeeded 2 :failed 3 :pending 1 :cancelled 1}
         (builds/summarize-attrs
          [{:status "succeeded"}
           {:status "skipped_local"}
           {:status "failed"}
           {:status "dependency_failed"}
           {:status "failed_eval"}
           {:status "building"}
           {:status "cancelled"}]))))

(deftest terminal-and-exit-code-follow-build-status
  (testing "terminal statuses"
    (is (builds/terminal? {:status "succeeded"}))
    (is (builds/terminal? {:status "failed"}))
    (is (builds/terminal? {:status "cancelled"}))
    (is (not (builds/terminal? {:status "pending"})))
    (is (not (builds/terminal? {:status "evaluating"})))
    (is (not (builds/terminal? {:status "building"}))))
  (testing "exit codes"
    (is (= 0 (builds/exit-code {:status "succeeded"})))
    (is (= 1 (builds/exit-code {:status "failed"})))
    (is (= 1 (builds/exit-code {:status "cancelled"})))))

(deftest fetch-up-to-paginates-until-limit
  (let [pages {1 {:items [{:number 5} {:number 4}] :has_next true}
               2 {:items [{:number 3} {:number 2}] :has_next true}
               3 {:items [{:number 1}] :has_next false}}
        calls (atom [])
        fetch (fn [page]
                (swap! calls conj page)
                (get pages page))]
    (testing "stops once the limit is reached"
      (reset! calls [])
      (is (= [{:number 5} {:number 4} {:number 3}]
             (builds/fetch-up-to fetch 3)))
      (is (= [1 2] @calls)))
    (testing "stops when pages run out"
      (reset! calls [])
      (is (= 5 (count (builds/fetch-up-to fetch 100))))
      (is (= [1 2 3] @calls)))))
