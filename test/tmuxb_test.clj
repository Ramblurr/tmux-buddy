(ns tmuxb-test
  (:require [clojure.test :refer [deftest is testing]]))

(deftest hello-world-test
  (testing "hello equals world"
    (is (= "hello" "world"))))
