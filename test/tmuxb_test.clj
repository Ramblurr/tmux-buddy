(ns tmuxb-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tmuxb :as t]))

(deftest transform-lines-style-test
  (testing "plain line gets two-space prefix"
    (is (= "  hello" (t/transform-lines-style "hello"))))

  (testing "inverse (selection) line gets > prefix"
    (is (= "> selected" (t/transform-lines-style "\u001b[7mselected\u001b[0m"))))

  (testing "red line gets ! prefix"
    (is (= "! error" (t/transform-lines-style "\u001b[31merror\u001b[0m"))))

  (testing "bold line gets * prefix"
    (is (= "* important" (t/transform-lines-style "\u001b[1mimportant\u001b[0m"))))

  (testing "multi-line content processed correctly"
    (is (= "  plain\n! error\n* bold"
           (t/transform-lines-style "plain\n\u001b[31merror\u001b[0m\n\u001b[1mbold\u001b[0m")))))

(deftest transform-tags-style-test
  (testing "plain text unchanged"
    (is (= "hello" (t/transform-tags-style "hello"))))

  (testing "bold text wrapped with [b]...[/b]"
    (is (= "[b]bold[/b]" (t/transform-tags-style "\u001b[1mbold\u001b[0m"))))

  (testing "red text wrapped with [r]...[/r]"
    (is (= "[r]error[/r]" (t/transform-tags-style "\u001b[31merror\u001b[0m"))))

  (testing "unclosed style gets closed at end"
    (is (= "[b]bold[/b]" (t/transform-tags-style "\u001b[1mbold")))))

(deftest insert-cursor-marker-test
  (testing "cursor at position 0 on line 0"
    (is (= "᚛h᚜ello" (t/insert-cursor-marker "hello" 0 0))))

  (testing "cursor in middle of text"
    (is (= "he᚛l᚜lo" (t/insert-cursor-marker "hello" 2 0))))

  (testing "cursor past end of line adds space"
    (is (= "hello᚛ ᚜" (t/insert-cursor-marker "hello" 10 0))))

  (testing "cursor on second row"
    (is (= "line1\n᚛l᚜ine2" (t/insert-cursor-marker "line1\nline2" 0 1))))

  (testing "cursor row out of bounds returns content unchanged"
    (is (= "hello" (t/insert-cursor-marker "hello" 0 5)))))

(deftest schema->tmux-format-test
  (testing "str type produces quoted string with escaped quotes"
    (let [schema {:name [:str "session_name"]}
          result (t/schema->tmux-format schema)]
      (is (str/includes? result "\"#{s/\"/\\\\\":session_name}\""))
      (is (str/starts-with? result "{"))
      (is (str/ends-with? result "}"))))

  (testing "int type produces bare #{var}"
    (let [schema {:count [:int "window_count"]}
          result (t/schema->tmux-format schema)]
      (is (str/includes? result "#{window_count}"))
      (is (not (str/includes? result "\"#{window_count}\"")))))

  (testing "multiple keys combined"
    (let [schema {:name [:str "n"] :count [:int "c"]}
          result (t/schema->tmux-format schema)]
      (is (str/includes? result ":name"))
      (is (str/includes? result ":count")))))
