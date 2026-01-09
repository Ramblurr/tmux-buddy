(ns tmuxb.lib.send-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [tmuxb.lib.send :as send])
  (:import [java.io PushbackReader StringReader]))

(defn capture-actions
  "Execute actions and capture what was sent. Returns {:sends [...] :hexes [...]}"
  [actions]
  (let [sends (atom [])
        hexes (atom [])]
    (send/execute #(swap! sends conj %)
                  #(swap! hexes conj %)
                  actions)
    {:sends @sends :hexes @hexes}))

(deftest parse-key-keyword-test
  (testing "simple keys"
    (is (= {:tmux "Enter"} (send/parse-key-keyword :Enter)))
    (is (= {:tmux "Escape"} (send/parse-key-keyword :Escape)))
    (is (= {:tmux "Escape"} (send/parse-key-keyword :Esc)))
    (is (= {:tmux "Tab"} (send/parse-key-keyword :Tab)))
    (is (= {:tmux "Space"} (send/parse-key-keyword :Space)))
    (is (= {:tmux "BSpace"} (send/parse-key-keyword :Backspace)))
    (is (= {:tmux "BSpace"} (send/parse-key-keyword :BS))))

  (testing "single character keys"
    (is (= {:tmux "a"} (send/parse-key-keyword :a)))
    (is (= {:tmux "x"} (send/parse-key-keyword :x)))
    (is (= {:tmux "1"} (send/parse-key-keyword :1))))

  (testing "function keys"
    (is (= {:tmux "F1"} (send/parse-key-keyword :F1)))
    (is (= {:tmux "F12"} (send/parse-key-keyword :F12))))

  (testing "arrow keys"
    (is (= {:tmux "Up"} (send/parse-key-keyword :Up)))
    (is (= {:tmux "Down"} (send/parse-key-keyword :Down)))
    (is (= {:tmux "Left"} (send/parse-key-keyword :Left)))
    (is (= {:tmux "Right"} (send/parse-key-keyword :Right))))

  (testing "single modifier"
    (is (= {:tmux "C-x"} (send/parse-key-keyword :C-x)))
    (is (= {:tmux "M-x"} (send/parse-key-keyword :M-x)))
    (is (= {:tmux "S-Tab"} (send/parse-key-keyword :S-Tab))))

  (testing "multiple modifiers"
    (is (= {:tmux "C-M-x"} (send/parse-key-keyword :C-M-x)))
    (is (= {:tmux "C-S-Enter"} (send/parse-key-keyword :C-S-Enter)))
    (is (= {:tmux "C-M-S-a"} (send/parse-key-keyword :C-M-S-a))))

  (testing "Super/Hyper modifiers return hex mode"
    (is (contains? (send/parse-key-keyword :Super-l) :hex))
    (is (contains? (send/parse-key-keyword :Hyper-a) :hex))
    (is (not (contains? (send/parse-key-keyword :Super-l) :tmux)))
    (is (not (contains? (send/parse-key-keyword :Hyper-a) :tmux)))))

(deftest sgr-mouse-press-test
  (testing "left click no modifiers"
    (let [hex (send/sgr-mouse-press 50 40 :left [])]
      (is (= "1b 5b 3c 30 3b 35 30 3b 34 30 4d" hex))))

  (testing "right click"
    (let [hex (send/sgr-mouse-press 50 40 :right [])]
      (is (= "1b 5b 3c 32 3b 35 30 3b 34 30 4d" hex))))

  (testing "middle click"
    (let [hex (send/sgr-mouse-press 50 40 :middle [])]
      (is (= "1b 5b 3c 31 3b 35 30 3b 34 30 4d" hex))))

  (testing "ctrl+click"
    (let [hex (send/sgr-mouse-press 50 40 :left [:C])]
      (is (= "1b 5b 3c 31 36 3b 35 30 3b 34 30 4d" hex))))

  (testing "shift+click"
    (let [hex (send/sgr-mouse-press 50 40 :left [:S])]
      (is (= "1b 5b 3c 34 3b 35 30 3b 34 30 4d" hex))))

  (testing "ctrl+shift+click"
    (let [hex (send/sgr-mouse-press 50 40 :left [:C :S])]
      (is (= "1b 5b 3c 32 30 3b 35 30 3b 34 30 4d" hex)))))

(deftest sgr-mouse-release-test
  (testing "release uses lowercase m"
    (let [hex (send/sgr-mouse-release 50 40 :left [])]
      (is (= "1b 5b 3c 30 3b 35 30 3b 34 30 6d" hex)))))

(deftest sgr-scroll-test
  (testing "scroll up"
    (let [hex (send/sgr-scroll 50 40 :up [])]
      (is (= "1b 5b 3c 36 34 3b 35 30 3b 34 30 4d" hex))))

  (testing "scroll down"
    (let [hex (send/sgr-scroll 50 40 :down [])]
      (is (= "1b 5b 3c 36 35 3b 35 30 3b 34 30 4d" hex)))))

(deftest kitty-key-press-test
  (testing "simple key press"
    (let [hex (send/kitty-key-press :a)]
      (is (= "1b 5b 39 37 3b 31 3a 31 75" hex))))

  (testing "ctrl+key press"
    (let [hex (send/kitty-key-press :C-a)]
      (is (= "1b 5b 39 37 3b 35 3a 31 75" hex)))))

(deftest kitty-key-release-test
  (testing "simple key release"
    (let [hex (send/kitty-key-release :a)]
      (is (= "1b 5b 39 37 3b 31 3a 33 75" hex)))))

(deftest parse-raw-string-test
  (testing "plain ascii"
    (is (= "68 65 6c 6c 6f" (send/parse-raw-string "hello"))))

  (testing "\\e escape"
    (is (= "1b 5b 48" (send/parse-raw-string "\\e[H"))))

  (testing "\\x hex escape"
    (is (= "1b 5b 48" (send/parse-raw-string "\\x1b[H"))))

  (testing "mixed escapes"
    (is (= "1b 5b 39 37 3b 31 3a 31 75"
           (send/parse-raw-string "\\e[97;1:1u")))))

(deftest parse-raw-hex-test
  (testing "normalizes whitespace"
    (is (= "1b 5b 39 37" (send/parse-raw-hex "1b 5b 39 37")))
    (is (= "1b 5b 39 37" (send/parse-raw-hex "1b  5b  39  37")))))

(deftest execute-string-action-test
  (testing "string sends literal text"
    (let [result (capture-actions ["hello"])]
      (is (= ["hello"] (:sends result)))
      (is (= [] (:hexes result))))))

(deftest execute-keyword-action-test
  (testing "keyword sends key name"
    (let [result (capture-actions [:Enter])]
      (is (= ["Enter"] (:sends result)))))

  (testing "modified key"
    (let [result (capture-actions [:C-x])]
      (is (= ["C-x"] (:sends result)))))

  (testing "Super modifier uses hex mode"
    (let [result (capture-actions [:Super-l])]
      (is (= [] (:sends result)))
      (is (= 1 (count (:hexes result))))))

  (testing "Hyper modifier uses hex mode"
    (let [result (capture-actions [:Hyper-a])]
      (is (= [] (:sends result)))
      (is (= 1 (count (:hexes result)))))))

(deftest execute-sleep-action-test
  (testing "sleep pauses execution"
    (let [start   (System/currentTimeMillis)
          _       (capture-actions [[:Sleep 50]])
          elapsed (- (System/currentTimeMillis) start)]
      (is (>= elapsed 45)))))

(deftest execute-click-action-test
  (testing "click sends press and release"
    (let [result (capture-actions [[:Click 50 40]])]
      (is (= [] (:sends result)))
      (is (= 2 (count (:hexes result))))
      (is (str/ends-with? (first (:hexes result)) "4d"))
      (is (str/ends-with? (second (:hexes result)) "6d"))))

  (testing "ctrl+click"
    (let [result (capture-actions [[:Click 50 40 :C]])]
      (is (= 2 (count (:hexes result))))
      (is (str/includes? (first (:hexes result)) "31 36")))))

(deftest execute-key-repeat-test
  (testing "key repetition"
    (let [result (capture-actions [[:Enter 3]])]
      (is (= ["Enter" "Enter" "Enter"] (:sends result)))))

  (testing "string repetition"
    (let [result (capture-actions [["ab" 3]])]
      (is (= ["ab" "ab" "ab"] (:sends result))))))

(deftest execute-key-press-release-test
  (testing "key press"
    (let [result (capture-actions [[:a :down]])]
      (is (= 1 (count (:hexes result))))
      (is (str/ends-with? (first (:hexes result)) "31 75"))))

  (testing "key release"
    (let [result (capture-actions [[:a :up]])]
      (is (= 1 (count (:hexes result))))
      (is (str/ends-with? (first (:hexes result)) "33 75")))))

(deftest execute-raw-test
  (testing "raw string"
    (let [result (capture-actions [[:Raw "\\e[H"]])]
      (is (= 1 (count (:hexes result))))
      (is (= "1b 5b 48" (first (:hexes result))))))

  (testing "raw hex"
    (let [result (capture-actions [[:RawHex "1b 5b 48"]])]
      (is (= 1 (count (:hexes result))))
      (is (= "1b 5b 48" (first (:hexes result)))))))

(deftest execute-scroll-test
  (testing "scroll up"
    (let [result (capture-actions [[:ScrollUp 50 40]])]
      (is (= 1 (count (:hexes result))))))

  (testing "scroll with count"
    (let [result (capture-actions [[:ScrollDown 50 40 3]])]
      (is (= 3 (count (:hexes result)))))))

(deftest complex-sequence-test
  (testing "full workflow"
    (let [result (capture-actions
                  ["hello" :Enter
                   [:Sleep 10]
                   :C-x :C-s
                   [:Click 50 40 :C]
                   [:a :down] [:a :up]])]
      (is (= ["hello" "Enter" "C-x" "C-s"] (:sends result)))
      (is (= 4 (count (:hexes result)))))))

(deftest execute-string-api-test
  (testing "execute-string parses and executes EDN"
    (let [sends (atom [])
          hexes (atom [])]
      (send/execute-string #(swap! sends conj %)
                           #(swap! hexes conj %)
                           "\"hello\" :Enter [:Sleep 10]")
      (is (= ["hello" "Enter"] @sends)))))

(deftest execute-stream-test
  (testing "execute-stream reads from PushbackReader"
    (let [sends (atom [])
          hexes (atom [])
          rdr   (PushbackReader. (StringReader. "\"world\" :Tab [:Enter 2]"))]
      (send/execute-stream #(swap! sends conj %)
                           #(swap! hexes conj %)
                           rdr)
      (is (= ["world" "Tab" "Enter" "Enter"] @sends))))

  (testing "execute-stream handles empty input"
    (let [sends (atom [])
          hexes (atom [])
          rdr   (PushbackReader. (StringReader. ""))]
      (send/execute-stream #(swap! sends conj %)
                           #(swap! hexes conj %)
                           rdr)
      (is (= [] @sends))
      (is (= [] @hexes))))

  (testing "execute-stream processes actions incrementally"
    (let [order (atom [])
          rdr   (PushbackReader. (StringReader. "\"a\" \"b\" \"c\""))]
      (send/execute-stream #(swap! order conj %)
                           (fn [_])
                           rdr)
      (is (= ["a" "b" "c"] @order)))))
