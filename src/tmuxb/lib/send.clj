(ns tmuxb.lib.send
  "EDN-based DSL for sending keys, text, and mouse events to tmux panes.

   Any user-visible behavior changes must be documented in:
   doc/send-keys-dsl.md"
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader]))

(def named-keys
  "Map of DSL key names to tmux key names."
  {:Enter     "Enter"
   :Return    "Enter"
   :Escape    "Escape"
   :Esc       "Escape"
   :Tab       "Tab"
   :Space     "Space"
   :Backspace "BSpace"
   :BS        "BSpace"
   :Delete    "DC"
   :Del       "DC"
   :Insert    "IC"
   :Ins       "IC"
   :Home      "Home"
   :End       "End"
   :PageUp    "PageUp"
   :PgUp      "PageUp"
   :PageDown  "PageDown"
   :PgDn      "PageDown"
   :Up        "Up"
   :Down      "Down"
   :Left      "Left"
   :Right     "Right"
   :F1        "F1"
   :F2        "F2"
   :F3        "F3"
   :F4        "F4"
   :F5        "F5"
   :F6        "F6"
   :F7        "F7"
   :F8        "F8"
   :F9        "F9"
   :F10       "F10"
   :F11       "F11"
   :F12       "F12"
   :Control   "Control"
   :Shift     "Shift"
   :Alt       "Alt"
   :Meta      "Meta"
   :Super     "Super"
   :Hyper     "Hyper"})

(def modifier-prefixes
  "Modifier prefix patterns.
   tmux send-keys only supports C-, M-, S-.
   Super- and Hyper- require hex mode (Kitty protocol)."
  [["C-"     {:tmux "C-" :kitty-bit 4}]
   ["M-"     {:tmux "M-" :kitty-bit 2}]
   ["S-"     {:tmux "S-" :kitty-bit 1}]
   ["Super-" {:tmux nil :kitty-bit 8}]
   ["Hyper-" {:tmux nil :kitty-bit 16}]])

(defn char->codepoint
  "Get Unicode codepoint for a character or key name."
  [key-str]
  (cond
    (= 1 (count key-str)) (int (first key-str))
    (= "Enter" key-str)   13
    (= "Escape" key-str)  27
    (= "Tab" key-str)     9
    (= "Space" key-str)   32
    (= "BSpace" key-str)  127
    :else                 (int (first key-str))))

(defn kitty-key-event
  "Generate Kitty keyboard protocol sequence. Returns hex string.
   key-str   - the key name (e.g., \"a\", \"Enter\")
   modifiers - modifier bits + 1 (Kitty format)
   event     - 1 for press, 3 for release"
  [key-str modifiers event]
  (let [codepoint (char->codepoint key-str)
        seq       (format "\u001b[%d;%d:%du" codepoint modifiers event)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn parse-key-keyword
  "Parse a key keyword like :C-x or :Enter.
   Returns {:tmux \"C-x\"} for tmux-compatible keys,
   or {:hex \"...\"} when hex mode is required (Super/Hyper modifiers)."
  [kw]
  (let [s (name kw)]
    (loop [remaining   s
           tmux-prefix ""
           kitty-bits  0
           needs-hex?  false]
      (if-let [[dsl-prefix {:keys [tmux kitty-bit]}]
               (first (filter #(str/starts-with? remaining (first %))
                              modifier-prefixes))]
        (recur (subs remaining (count dsl-prefix))
               (if tmux (str tmux-prefix tmux) tmux-prefix)
               (bit-or kitty-bits kitty-bit)
               (or needs-hex? (nil? tmux)))
        (let [key-kw   (keyword remaining)
              key-name (or (get named-keys key-kw) remaining)]
          (if needs-hex?
            {:hex (kitty-key-event key-name (inc kitty-bits) 1)}
            {:tmux (str tmux-prefix key-name)}))))))

(def mouse-button-codes
  {:left   0
   :middle 1
   :right  2})

(def mouse-modifier-codes
  {:S     4
   :Shift 4
   :M     8
   :Meta  8
   :Alt   8
   :C     16
   :Ctrl  16})

(defn encode-mouse-button
  "Encode mouse button with modifiers. Returns button code."
  [button modifiers]
  (let [base     (get mouse-button-codes button 0)
        mod-bits (reduce + 0 (map #(get mouse-modifier-codes % 0) modifiers))]
    (+ base mod-bits)))

(defn sgr-mouse-press
  "Generate SGR mouse press sequence. Returns hex string."
  [x y button modifiers]
  (let [code (encode-mouse-button button modifiers)
        seq  (format "\u001b[<%d;%d;%dM" code x y)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn sgr-mouse-release
  "Generate SGR mouse release sequence. Returns hex string."
  [x y button modifiers]
  (let [code (encode-mouse-button button modifiers)
        seq  (format "\u001b[<%d;%d;%dm" code x y)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn sgr-scroll
  "Generate SGR scroll sequence. Returns hex string.
   direction is :up or :down"
  [x y direction modifiers]
  (let [base     (if (= direction :up) 64 65)
        mod-bits (reduce + 0 (map #(get mouse-modifier-codes % 0) modifiers))
        code     (+ base mod-bits)
        seq      (format "\u001b[<%d;%d;%dM" code x y)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn parse-kitty-key
  "Parse key keyword into [codepoint modifier-bits]."
  [kw]
  (let [s (name kw)]
    (loop [remaining s
           mod-bits  0]
      (cond
        (str/starts-with? remaining "C-")
        (recur (subs remaining 2) (bit-or mod-bits 4))

        (str/starts-with? remaining "M-")
        (recur (subs remaining 2) (bit-or mod-bits 2))

        (str/starts-with? remaining "S-")
        (recur (subs remaining 2) (bit-or mod-bits 1))

        (str/starts-with? remaining "Super-")
        (recur (subs remaining 6) (bit-or mod-bits 8))

        (str/starts-with? remaining "Hyper-")
        (recur (subs remaining 6) (bit-or mod-bits 16))

        :else
        (let [key-name  (or (get named-keys (keyword remaining)) remaining)
              codepoint (char->codepoint key-name)]
          [codepoint (inc mod-bits)])))))

(defn kitty-key-press
  "Generate Kitty keyboard protocol press sequence. Returns hex string."
  [key-kw]
  (let [[codepoint modifiers] (parse-kitty-key key-kw)
        seq                   (format "\u001b[%d;%d:1u" codepoint modifiers)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn kitty-key-release
  "Generate Kitty keyboard protocol release sequence. Returns hex string."
  [key-kw]
  (let [[codepoint modifiers] (parse-kitty-key key-kw)
        seq                   (format "\u001b[%d;%d:3u" codepoint modifiers)]
    (str/join " " (map #(format "%02x" (int %)) seq))))

(defn parse-raw-string
  "Parse raw string with \\e and \\xNN escapes. Returns hex string."
  [s]
  (let [sb  (StringBuilder.)
        len (count s)]
    (loop [i 0]
      (if (>= i len)
        (str/join " " (map #(format "%02x" (int %)) (str sb)))
        (let [c (nth s i)]
          (if (and (= c \\) (< (inc i) len))
            (let [next-c (nth s (inc i))]
              (cond
                (= next-c \e)
                (do (.append sb \u001b)
                    (recur (+ i 2)))

                (= next-c \x)
                (if (< (+ i 3) len)
                  (let [hex-str  (subs s (+ i 2) (+ i 4))
                        byte-val (Integer/parseInt hex-str 16)]
                    (.append sb (char byte-val))
                    (recur (+ i 4)))
                  (do (.append sb c)
                      (recur (inc i))))

                (= next-c \n) (do (.append sb \newline) (recur (+ i 2)))
                (= next-c \t) (do (.append sb \tab) (recur (+ i 2)))
                (= next-c \r) (do (.append sb \return) (recur (+ i 2)))
                (= next-c \\) (do (.append sb \\) (recur (+ i 2)))
                (= next-c \") (do (.append sb \") (recur (+ i 2)))

                :else
                (do (.append sb c)
                    (recur (inc i)))))
            (do (.append sb c)
                (recur (inc i)))))))))

(defn parse-raw-hex
  "Parse hex string like '1b 5b 39'. Returns normalized hex string."
  [s]
  (str/replace s #"\s+" " "))

(defn action-type
  "Determine the type of an action for dispatch."
  [action]
  (cond
    (string? action)  :string
    (keyword? action) :keyword
    (vector? action)  (let [head (first action)]
                        (cond
                          (string? head)  :string-repeat
                          (keyword? head) head
                          :else           :unknown))
    :else             :unknown))

(defmulti execute-action
  "Execute a single action. Dispatches on action type."
  (fn [_send-fn _send-hex-fn action] (action-type action)))

(defmethod execute-action :string
  [send-fn _send-hex-fn s]
  (send-fn s))

(defmethod execute-action :keyword
  [send-fn send-hex-fn kw]
  (let [{:keys [tmux hex]} (parse-key-keyword kw)]
    (if hex
      (send-hex-fn hex)
      (send-fn tmux))))

(defmethod execute-action :string-repeat
  [send-fn _send-hex-fn [s count & {:keys [delay]}]]
  (dotimes [i count]
    (send-fn s)
    (when (and delay (< i (dec count)))
      (Thread/sleep delay))))

(defmethod execute-action :Sleep
  [_send-fn _send-hex-fn [_ ms]]
  (Thread/sleep ms))

(defmethod execute-action :Click
  [_send-fn send-hex-fn [_ x y & modifiers]]
  (send-hex-fn (sgr-mouse-press x y :left modifiers))
  (send-hex-fn (sgr-mouse-release x y :left modifiers)))

(defmethod execute-action :RClick
  [_send-fn send-hex-fn [_ x y & modifiers]]
  (send-hex-fn (sgr-mouse-press x y :right modifiers))
  (send-hex-fn (sgr-mouse-release x y :right modifiers)))

(defmethod execute-action :MClick
  [_send-fn send-hex-fn [_ x y & modifiers]]
  (send-hex-fn (sgr-mouse-press x y :middle modifiers))
  (send-hex-fn (sgr-mouse-release x y :middle modifiers)))

(defmethod execute-action :Click+
  [_send-fn send-hex-fn [_ x y & modifiers]]
  (send-hex-fn (sgr-mouse-press x y :left modifiers)))

(defmethod execute-action :Click-
  [_send-fn send-hex-fn [_ x y & modifiers]]
  (send-hex-fn (sgr-mouse-release x y :left modifiers)))

(defmethod execute-action :Move
  [_send-fn send-hex-fn [_ x y]]
  (let [seq (format "\u001b[<%d;%d;%dM" 35 x y)
        hex (str/join " " (map #(format "%02x" (int %)) seq))]
    (send-hex-fn hex)))

(defmethod execute-action :ScrollUp
  [_send-fn send-hex-fn [_ x y & args]]
  (let [[count-or-kw & rest-args] args
        [cnt delay-val]           (if (number? count-or-kw)
                                    [count-or-kw (second rest-args)]
                                    [1 nil])]
    (dotimes [i cnt]
      (send-hex-fn (sgr-scroll x y :up []))
      (when (and delay-val (< i (dec cnt)))
        (Thread/sleep delay-val)))))

(defmethod execute-action :ScrollDown
  [_send-fn send-hex-fn [_ x y & args]]
  (let [[count-or-kw & rest-args] args
        [cnt delay-val]           (if (number? count-or-kw)
                                    [count-or-kw (second rest-args)]
                                    [1 nil])]
    (dotimes [i cnt]
      (send-hex-fn (sgr-scroll x y :down []))
      (when (and delay-val (< i (dec cnt)))
        (Thread/sleep delay-val)))))

(defmethod execute-action :Raw
  [_send-fn send-hex-fn [_ s]]
  (send-hex-fn (parse-raw-string s)))

(defmethod execute-action :RawHex
  [_send-fn send-hex-fn [_ s]]
  (send-hex-fn (parse-raw-hex s)))

(defmethod execute-action :default
  [send-fn send-hex-fn action]
  (if (vector? action)
    (let [[key-kw & rest-args] action]
      (cond
        (and (keyword? key-kw)
             (= 1 (count rest-args))
             (#{:down :up :press :release} (first rest-args)))
        (let [direction (first rest-args)]
          (if (#{:down :press} direction)
            (send-hex-fn (kitty-key-press key-kw))
            (send-hex-fn (kitty-key-release key-kw))))

        (and (keyword? key-kw) (number? (first rest-args)))
        (let [[cnt & {:keys [delay]}] rest-args
              {:keys [tmux hex]}      (parse-key-keyword key-kw)]
          (dotimes [i cnt]
            (if hex
              (send-hex-fn hex)
              (send-fn tmux))
            (when (and delay (< i (dec cnt)))
              (Thread/sleep delay))))

        :else
        (throw (ex-info "Unknown action" {:action action}))))
    (throw (ex-info "Unknown action type. Strings must be quoted in EDN format."
                    {:action action :type (type action)}))))

(def default-delay
  "Default delay in milliseconds between actions (simulates fast human typing)."
  30)

(defn execute
  "Execute a sequence of actions.

   send-fn     - function to send regular keys: (send-fn key-string)
   send-hex-fn - function to send hex bytes: (send-hex-fn hex-string)
   actions     - sequence of actions (strings, keywords, vectors)
   delay-ms    - delay in milliseconds between actions (default 30)"
  ([send-fn send-hex-fn actions]
   (execute send-fn send-hex-fn actions default-delay))
  ([send-fn send-hex-fn actions delay-ms]
   (let [action-vec (vec actions)
         last-idx   (dec (count action-vec))]
     (doseq [[idx action] (map-indexed vector action-vec)]
       (execute-action send-fn send-hex-fn action)
       (when (and (pos? delay-ms) (< idx last-idx))
         (Thread/sleep delay-ms))))))

(defn execute-stream
  "Execute actions from an EDN stream (e.g., stdin).
   Reads and executes one action at a time.

   send-fn     - function to send regular keys
   send-hex-fn - function to send hex bytes
   reader      - a PushbackReader
   delay-ms    - delay in milliseconds between actions (default 30)"
  ([send-fn send-hex-fn reader]
   (execute-stream send-fn send-hex-fn reader default-delay))
  ([send-fn send-hex-fn reader delay-ms]
   (loop [first? true]
     (when-let [action (edn/read {:eof nil} reader)]
       (when (and (not first?) (pos? delay-ms))
         (Thread/sleep delay-ms))
       (execute-action send-fn send-hex-fn action)
       (recur false)))))

(defn execute-string
  "Execute actions from an EDN string."
  ([send-fn send-hex-fn s]
   (execute-string send-fn send-hex-fn s default-delay))
  ([send-fn send-hex-fn s delay-ms]
   (with-open [rdr (PushbackReader. (java.io.StringReader. s))]
     (execute-stream send-fn send-hex-fn rdr delay-ms))))

(defn run-send
  "Execute send actions with clean error handling.
   Takes edn-str (may be blank) and stdin-reader for fallback.
   Returns nil on success, exits with code 1 on error."
  ([send-fn send-hex-fn edn-str stdin-reader]
   (run-send send-fn send-hex-fn edn-str stdin-reader default-delay))
  ([send-fn send-hex-fn edn-str stdin-reader delay-ms]
   (try
     (if (str/blank? edn-str)
       (with-open [rdr (PushbackReader. stdin-reader)]
         (execute-stream send-fn send-hex-fn rdr delay-ms))
       (execute-string send-fn send-hex-fn edn-str delay-ms))
     (catch clojure.lang.ExceptionInfo e
       (binding [*out* *err*]
         (println (str "Error: " (ex-message e)))
         (when-let [action (:action (ex-data e))]
           (println (str "  Got: " (pr-str action) " (type: " (type action) ")")))
         (println "  Hint: Strings must be quoted in EDN format, e.g., '\"echo hello\"'"))
       (System/exit 1)))))

(comment
  (defn mock-send [s] (println "send:" s))
  (defn mock-hex [s] (println "hex:" s))

  (execute mock-send mock-hex ["hello" :Enter])
  (execute mock-send mock-hex [[:Click 50 40 :C]])
  (execute mock-send mock-hex [[:Sleep 100] "done"])
  (execute mock-send mock-hex [[:Enter 5 :delay 100]])
  (execute mock-send mock-hex [[:a :down] [:Sleep 500] [:a :up]])
  (execute mock-send mock-hex [[:Raw "\\e[97;1:1u"]])
  (execute mock-send mock-hex [:Super-l])

  (execute-string mock-send mock-hex "\"hello\" :Enter [:Sleep 100]"))
