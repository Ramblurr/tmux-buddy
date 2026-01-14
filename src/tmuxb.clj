#!/usr/bin/env bb
(ns tmuxb
  "tmux-buddy - tmux interface for Claude Code."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [tmuxb.lib.send :as send]))

;;; ---------------------------------------------------------------------------
;;; ANSI Handling
;;; ---------------------------------------------------------------------------

(def ansi-pattern #"\x1b\[[0-9;]*m")

(defn strip-ansi
  "Remove all ANSI escape codes from text."
  [text]
  (str/replace text ansi-pattern ""))

(def style-codes
  "Regex patterns for detecting ANSI styles."
  {:inverse   #"\x1b\[7m"
   :bg-color  #"\x1b\[4[0-7]m"
   :bold      #"\x1b\[1m"
   :red       #"\x1b\[31m"
   :green     #"\x1b\[32m"
   :yellow    #"\x1b\[33m"
   :underline #"\x1b\[4m(?![0-7])"
   :reset     #"\x1b\[0?m"})

;;; ---------------------------------------------------------------------------
;;; Cursor Markers
;;; ---------------------------------------------------------------------------

(def cursor-left "᚛")
(def cursor-right "᚜")
(def cursor-placeholder-left "\u0000CURSOR_L\u0000")
(def cursor-placeholder-right "\u0000CURSOR_R\u0000")

(defn insert-cursor-marker
  "Insert visible cursor markers around the character at cursor position.
   Works on ANSI-stripped content where visual positions match character positions."
  [content cursor-x cursor-y]
  (let [lines (str/split-lines content)]
    (if (and (>= cursor-y 0) (< cursor-y (count lines)))
      (let [line     (nth lines cursor-y)
            new-line (if (< cursor-x (count line))
                       (str (subs line 0 cursor-x)
                            cursor-left
                            (subs line cursor-x (inc cursor-x))
                            cursor-right
                            (subs line (inc cursor-x)))
                       (str line cursor-left " " cursor-right))]
        (str/join "\n" (assoc (vec lines) cursor-y new-line)))
      content)))

(defn insert-cursor-placeholder
  "Insert placeholder markers at cursor position (for use before ANSI transformation)."
  [content cursor-x cursor-y]
  (let [lines (str/split-lines content)]
    (if (and (>= cursor-y 0) (< cursor-y (count lines)))
      (let [line       (nth lines cursor-y)
            clean-line (strip-ansi line)]
        (if (< cursor-x (count clean-line))
          ;; Find where cursor-x in clean line corresponds to in original line
          (loop [visual-pos 0
                 actual-pos 0]
            (if (or (>= visual-pos cursor-x) (>= actual-pos (count line)))
              (let [char-at-cursor (if (< actual-pos (count line))
                                     (subs line actual-pos (inc actual-pos))
                                     " ")
                    new-line       (str (subs line 0 actual-pos)
                                        cursor-placeholder-left
                                        char-at-cursor
                                        cursor-placeholder-right
                                        (subs line (min (inc actual-pos) (count line))))]
                (str/join "\n" (assoc (vec lines) cursor-y new-line)))
              (if (= (nth line actual-pos) \u001b)
                ;; Skip ANSI sequence
                (let [end (str/index-of line "m" actual-pos)]
                  (if end
                    (recur visual-pos (inc end))
                    (recur (inc visual-pos) (inc actual-pos))))
                (recur (inc visual-pos) (inc actual-pos)))))
          ;; Cursor past end of line
          (let [new-line (str line cursor-placeholder-left " " cursor-placeholder-right)]
            (str/join "\n" (assoc (vec lines) cursor-y new-line)))))
      content)))

(defn replace-cursor-placeholders
  "Replace cursor placeholders with actual Ogham markers."
  [content]
  (-> content
      (str/replace cursor-placeholder-left cursor-left)
      (str/replace cursor-placeholder-right cursor-right)))

;;; ---------------------------------------------------------------------------
;;; Style Detection and Transformation
;;; ---------------------------------------------------------------------------

(defn detect-line-style
  "Detect dominant style in a line. Returns style keyword or nil."
  [line]
  (cond
    (re-find (:inverse style-codes) line)  :inverse
    (re-find (:bg-color style-codes) line) :inverse  ; Treat bg color as selection
    (re-find (:red style-codes) line)      :red
    (re-find (:bold style-codes) line)     :bold
    :else nil))

(defn transform-lines-style
  "Transform content with line-prefix style markers."
  [content]
  (->> (str/split-lines content)
       (map (fn [line]
              (let [style      (detect-line-style line)
                    clean-line (strip-ansi line)]
                (case style
                  :inverse (str "> " clean-line)
                  :red     (str "! " clean-line)
                  :bold    (str "* " clean-line)
                  (str "  " clean-line)))))
       (str/join "\n")))

(defn transform-tags-style
  "Transform content with inline semantic tags."
  [content]
  (let [tag-map {:inverse "i" :bg-color "i" :bold      "b" :red "r"
                 :green   "g" :yellow   "y" :underline "u"}]
    (loop [i              0
           result         []
           current-styles #{}
           text           content]
      (if (>= i (count text))
        ;; Close any remaining open tags
        (apply str (concat result
                           (for [style current-styles
                                 :let  [tag (get tag-map style)]
                                 :when tag]
                             (str "[/" tag "]"))))
        (if (and (= (nth text i) \u001b)
                 (< (inc i) (count text))
                 (= (nth text (inc i)) \[))
          ;; ANSI escape sequence
          (let [end (str/index-of text "m" i)]
            (if end
              (let [seq (subs text i (inc end))]
                (cond
                  ;; Reset
                  (re-matches #"\x1b\[0?m" seq)
                  (let [close-tags (for [style current-styles
                                         :let  [tag (get tag-map style)]
                                         :when tag]
                                     (str "[/" tag "]"))]
                    (recur (inc end) (into result close-tags) #{} text))

                  ;; Default bg (49) resets selection
                  (re-matches #"\x1b\[49m" seq)
                  (if (contains? current-styles :bg-color)
                    (recur (inc end) (conj result "[/i]") (disj current-styles :bg-color) text)
                    (recur (inc end) result current-styles text))

                  ;; Check for style start
                  :else
                  (let [matched-style (first (for [[style-name pattern] style-codes
                                                   :when                (and (not= style-name :reset)
                                                                             (re-matches pattern seq))]
                                               style-name))]
                    (if (and matched-style (not (contains? current-styles matched-style)))
                      (let [tag (get tag-map matched-style)]
                        (if tag
                          (recur (inc end) (conj result (str "[" tag "]"))
                                 (conj current-styles matched-style) text)
                          (recur (inc end) result current-styles text)))
                      (recur (inc end) result current-styles text)))))
              (recur (inc i) (conj result (nth text i)) current-styles text)))
          ;; Regular character
          (recur (inc i) (conj result (nth text i)) current-styles text))))))

;;; ---------------------------------------------------------------------------
;;; Screen Hash Persistence
;;; ---------------------------------------------------------------------------

(defn md5-hash
  "Calculate MD5 hash of a string."
  [s]
  (let [md    (java.security.MessageDigest/getInstance "MD5")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn cache-dir
  "Returns the XDG cache directory for tmux-buddy.
  Uses $XDG_CACHE_HOME if set, otherwise falls back to $HOME/.cache."
  []
  (let [xdg-cache (System/getenv "XDG_CACHE_HOME")
        base      (if (and xdg-cache (not (str/blank? xdg-cache)))
                    xdg-cache
                    (str (System/getenv "HOME") "/.cache"))]
    (fs/path base "tmux-buddy")))

(def hash-file (fs/path (cache-dir) "screen_hashes.edn"))

(defn load-screen-hashes
  "Load screen hashes from cache file."
  []
  (if (fs/exists? hash-file)
    (try
      (edn/read-string (slurp (str hash-file)))
      (catch Exception _ {}))
    {}))

(defn save-screen-hash
  "Save a screen hash to cache file."
  [key hash-val]
  (let [hashes (assoc (load-screen-hashes) key hash-val)]
    (fs/create-dirs (cache-dir))
    (spit (str hash-file) (pr-str hashes))))

;;; ---------------------------------------------------------------------------
;;; Session File Discovery
;;; ---------------------------------------------------------------------------

(defn find-session-file
  "Walk up from cwd looking for .tmuxb_session file.
  Returns the path if found, nil otherwise."
  []
  (loop [dir (fs/cwd)]
    (let [f (fs/path dir ".tmuxb_session")]
      (cond
        (fs/exists? f)           f
        (nil? (fs/parent dir))   nil
        :else                    (recur (fs/parent dir))))))

(defn read-session-file
  "Read and parse .tmuxb_session file, returns nil if not found or invalid.
  Expected format: {:session \"name\" :socket \"/path/to/socket\"}"
  []
  (when-let [f (find-session-file)]
    (try
      (edn/read-string (slurp (str f)))
      (catch Exception _ nil))))

(defn resolve-socket-path
  "Resolve socket path. Bare names become $XDG_RUNTIME_DIR/tmuxb/name or /tmp/tmuxb/name.
  Paths starting with /, ./, or ../ are used as-is."
  [socket]
  (when socket
    (if (or (str/starts-with? socket "/")
            (str/starts-with? socket "./")
            (str/starts-with? socket "../"))
      socket
      (let [runtime-dir (or (System/getenv "XDG_RUNTIME_DIR") "/tmp")
            dir         (fs/path runtime-dir "tmuxb")]
        (fs/create-dirs dir)
        (str (fs/path dir socket))))))

(defn write-session-file
  "Write .tmuxb_session file in cwd."
  [session socket]
  (let [content (cond-> {:session session}
                  socket (assoc :socket socket))]
    (spit ".tmuxb_session" (pr-str content))))

(defn delete-session-file-if-matches
  "Delete .tmuxb_session in cwd if it matches the given session/socket."
  [session socket]
  (when (fs/exists? ".tmuxb_session")
    (let [current (read-session-file)]
      (when (and (= (:session current) session)
                 (= (:socket current) socket))
        (fs/delete ".tmuxb_session")))))

(defn get-socket-path
  "Get the tmux server socket path by querying tmux."
  []
  (try
    (let [result (p/sh ["tmux" "display-message" "-p" "#{socket_path}"])]
      (if (zero? (:exit result))
        (str/trim (:out result))
        "default"))
    (catch Exception _ "default")))

(defn make-screen-hash-key
  "Create a cache key that includes socket hash to prevent collisions
  between different tmux servers."
  [session pane]
  (let [socket-hash (subs (md5-hash (get-socket-path)) 0 8)]
    (str socket-hash ":" session ":" (or pane ""))))

;;; ---------------------------------------------------------------------------
;;; tmux Format Generation
;;; ---------------------------------------------------------------------------

;; Define formats as EDN with type hints:
;;   :str  -> quoted string with escaped quotes: "#{s/"/\\":var}"
;;   :int  -> bare integer: #{var}
;;   :bool -> bare integer (0/1): #{var}

(def session-schema
  {:name     [:str "session_name"]
   :id       [:str "session_id"]
   :windows  [:int "session_windows"]
   :attached [:int "session_attached"]})

(def window-schema
  {:name   [:str "window_name"]
   :id     [:str "window_id"]
   :index  [:int "window_index"]
   :panes  [:int "window_panes"]
   :active [:int "window_active"]})

(def pane-schema
  {:id           [:str "pane_id"]
   :index        [:int "pane_index"]
   :window       [:str "window_name"]
   :window-index [:int "window_index"]
   :width        [:int "pane_width"]
   :height       [:int "pane_height"]
   :active       [:int "pane_active"]})

(defn schema->tmux-format
  "Convert an EDN schema to a tmux format string.

   Schema is a map where values are [type tmux-var] pairs:
   - [:str \"var\"]  -> quoted with escaped quotes
   - [:int \"var\"]  -> bare number
   - [:bool \"var\"] -> bare number (0/1)"
  [schema]
  (str "{"
       (->> schema
            (map (fn [[k [type var]]]
                   (let [placeholder (case type
                                       :str (str "\"#{s/\"/\\\\\":" var "}\"")
                                       :int (str "#{" var "}")
                                       :bool (str "#{" var "}"))]
                     (str k " " placeholder))))
            (str/join " "))
       "}"))

(def session-format (schema->tmux-format session-schema))
(def window-format (schema->tmux-format window-schema))
(def pane-format (schema->tmux-format pane-schema))

;;; ---------------------------------------------------------------------------
;;; tmux Wrapper Functions
;;; ---------------------------------------------------------------------------

(def ^:dynamic *socket*
  "Dynamic var for tmux socket path. When set, all tmux commands use -S flag."
  nil)

(defn tmux
  "Run tmux command, return stdout or throw on error.
  Uses *socket* if bound to specify the tmux server socket."
  [& args]
  (let [base   (if *socket* ["tmux" "-S" *socket*] ["tmux"])
        result (p/sh (into base args))]
    (if (zero? (:exit result))
      (:out result)
      (throw (ex-info (str "tmux error: " (:err result))
                      {:stderr (:err result) :args args})))))

(defn tmux-ok?
  "Run tmux command, return true if successful.
  Uses *socket* if bound to specify the tmux server socket."
  [& args]
  (let [base   (if *socket* ["tmux" "-S" *socket*] ["tmux"])
        result (p/sh (into base args))]
    (zero? (:exit result))))

(defn parse-edn-lines
  "Parse tmux EDN-formatted output into seq of maps."
  [output]
  (->> (str/split-lines output)
       (remove str/blank?)
       (map edn/read-string)))

(defn list-sessions*
  "List all tmux sessions."
  []
  (try
    (-> (tmux "list-sessions" "-F" session-format)
        parse-edn-lines)
    (catch Exception _ [])))

(defn find-session
  "Find a session by name."
  [name]
  (first (filter #(= (:name %) name) (list-sessions*))))

(defn list-windows*
  "List windows in a session."
  [session]
  (-> (tmux "list-windows" "-t" session "-F" window-format)
      parse-edn-lines))

(defn list-panes*
  "List panes in a session."
  [session & {:keys [window]}]
  (let [target (if window (str session ":" window) session)]
    (-> (tmux "list-panes" "-t" target "-F" pane-format)
        parse-edn-lines)))

(defn capture-pane*
  "Capture pane contents."
  [session & {:keys [pane start end escape-sequences]}]
  (let [target (if pane (str session ":" pane) session)
        args   (cond-> ["capture-pane" "-t" target "-p"]
                 escape-sequences (conj "-e")
                 start (conj "-S" (str start))
                 end (conj "-E" (str end)))]
    (apply tmux args)))

(defn get-cursor-pos
  "Get cursor position as [x y]."
  [session & {:keys [pane]}]
  (let [target (if pane (str session ":" pane) session)
        output (tmux "display-message" "-t" target "-p" "#{cursor_x} #{cursor_y}")
        [x y]  (str/split (str/trim output) #" ")]
    [(parse-long x) (parse-long y)]))

(defn send-keys*
  "Send keys to a pane."
  [session keys & {:keys [pane literal]}]
  (let [target (if pane (str session ":" pane) session)
        args   (cond-> ["send-keys" "-t" target]
                 literal (conj "-l"))]
    (apply tmux (conj args "--" keys))))

(defn send-keys-hex*
  "Send hex-encoded bytes to a pane via tmux send-keys -H."
  [session hex-string & {:keys [pane]}]
  (let [target (if pane (str session ":" pane) session)]
    (apply tmux "send-keys" "-t" target "-H" (str/split hex-string #" "))))

(defn new-session*
  "Create a new tmux session."
  [name & {:keys [window cmd width height]}]
  (let [args (cond-> ["new-session" "-d" "-s" name]
               window (conj "-n" window)
               width (conj "-x" (str width))
               height (conj "-y" (str height)))]
    (apply tmux args)
    (when cmd
      (send-keys* name cmd))))

(defn kill-session*
  "Kill a tmux session."
  [session]
  (tmux "kill-session" "-t" session))

;;; ---------------------------------------------------------------------------
;;; CLI Help and Error Handling
;;; ---------------------------------------------------------------------------

;; Forward declarations for cmd-help and error handling
(declare commands)
(declare commands-order)
(declare get-cmd-spec)
(declare add-help-to-spec)

(defn print-cmd-help
  "Print help for a specific command."
  [cmd-name usage desc spec]
  (println (str "Usage: tmuxb " cmd-name " " usage))
  (println)
  (println desc)
  (when (seq spec)
    (println)
    (println "Options:")
    (println (cli/format-opts {:spec spec}))))

(defn exit-with-error
  "Print error message, show command help, and exit."
  [cmd-name message]
  (binding [*out* *err*]
    (println (str "Error: " message))
    (println)
    (when-let [{:keys [usage desc spec]} (get-cmd-spec cmd-name)]
      (print-cmd-help cmd-name usage desc (add-help-to-spec spec))))
  (System/exit 1))

;;; ---------------------------------------------------------------------------
;;; CLI Command Implementations
;;; ---------------------------------------------------------------------------

(defn cmd-list
  "List all tmux sessions."
  [{:keys [opts]}]
  (let [sessions (list-sessions*)]
    (cond
      (:json opts) (println (json/generate-string sessions {:pretty true}))
      (:edn opts)  (prn sessions)
      :else        (if (empty? sessions)
                     (println "No tmux sessions found")
                     (doseq [s sessions]
                       (let [attached (if (= (:attached s) 1) "*" "")]
                         (println (str (:name s) attached " (" (:windows s) " windows)"))))))))

(defn cmd-windows
  "List windows in a session."
  [{:keys [opts]}]
  (let [{:keys [session json edn]} opts]
    (when-not session
      (exit-with-error "windows" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))
    (let [windows (list-windows* session)]
      (cond
        json (println (json/generate-string windows {:pretty true}))
        edn  (prn windows)
        :else (doseq [w windows]
                (let [active (if (= (:active w) 1) "*" "")]
                  (println (str (:index w) ": " (:name w) active " (" (:panes w) " panes)"))))))))

(defn cmd-panes
  "List panes in a session/window."
  [{:keys [opts]}]
  (let [{:keys [session window json edn]} opts]
    (when-not session
      (exit-with-error "panes" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))
    (let [panes (list-panes* session :window window)]
      (cond
        json  (println (json/generate-string panes {:pretty true}))
        edn   (prn panes)
        :else (doseq [p panes]
                (let [active (if (= (:active p) 1) "*" "")]
                  (println (str (:id p) active " [" (:window p) ":" (:index p) "] "
                                (:width p) "x" (:height p)))))))))

(defn cmd-capture
  "Capture pane contents."
  [{:keys [opts]}]
  (let [{:keys [session pane lines if-changed history raw style]
         :or   {lines 50 style "none"}}                          opts]
    (when-not session
      (exit-with-error "capture" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))

    (let [need-ansi (contains? #{"lines" "tags" "ansi"} style)
          content   (if history
                      (capture-pane* session :pane pane
                                     :start (str "-" lines) :end "-0"
                                     :escape-sequences need-ansi)
                      (capture-pane* session :pane pane
                                     :start 0 :end "-"
                                     :escape-sequences need-ansi))]
      ;; Check if changed
      (when if-changed
        (let [content-hash (md5-hash content)
              pane-key     (make-screen-hash-key session pane)
              hashes       (load-screen-hashes)]
          (if (= (get hashes pane-key) content-hash)
            (do (println "[no change]")
                (System/exit 0))
            (save-screen-hash pane-key content-hash))))

      (let [[cursor-x cursor-y] (get-cursor-pos session :pane pane)
            result              (case style
                                  "lines"
                                  (let [transformed (transform-lines-style content)]
                                    (if raw
                                      transformed
                                      (insert-cursor-marker transformed (+ cursor-x 2) cursor-y)))

                                  "tags"
                                  (let [with-placeholder (if raw
                                                           content
                                                           (insert-cursor-placeholder content cursor-x cursor-y))
                                        transformed      (transform-tags-style with-placeholder)]
                                    (if raw
                                      transformed
                                      (replace-cursor-placeholders transformed)))

                                  "ansi"
                                  (if raw
                                    content
                                    (-> content
                                        strip-ansi
                                        (insert-cursor-marker cursor-x cursor-y)))

                     ;; "none" (default)
                                  (let [stripped (if need-ansi (strip-ansi content) content)]
                                    (if raw
                                      stripped
                                      (insert-cursor-marker stripped cursor-x cursor-y))))]
        (println result)))))

(defn cmd-watch
  "Watch pane for changes, outputting only when content changes."
  [{:keys [opts]}]
  (let [{:keys [session pane interval timeout until]
         :or   {interval 0.5 timeout 30.0}}          opts]
    (when-not session
      (exit-with-error "watch" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))

    (let [start-time (System/currentTimeMillis)]
      (loop [last-hash ""]
        (let [elapsed (/ (- (System/currentTimeMillis) start-time) 1000.0)]
          (if (>= elapsed timeout)
            (binding [*out* *err*]
              (println (str "[timeout after " timeout "s]")))
            (let [content      (capture-pane* session :pane pane :start 0 :end "-")
                  content-hash (md5-hash content)]
              (when (not= content-hash last-hash)
                (println (str "--- [" (format "%.1f" elapsed) "s] ---"))
                (println content)
                (let [[x y] (get-cursor-pos session :pane pane)]
                  (println (str "[cursor: " x "," y "]")))
                (when (and until (str/includes? content until))
                  (binding [*out* *err*]
                    (println (str "[found '" until "']")))
                  (System/exit 0)))
              (Thread/sleep (long (* interval 1000)))
              (recur content-hash))))))))

(defn cmd-send
  "Send keys to a pane using EDN DSL.

   Reads from stdin if no EDN arguments provided.
   See doc/send-keys-dsl.md for DSL specification."
  [{:keys [opts args]}]
  (let [{:keys [session pane delay]} opts
        edn-str                      (str/join " " (or args []))]
    (when-not session
      (exit-with-error "send" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))

    (let [send-fn     #(send-keys* session % :pane pane)
          send-hex-fn #(send-keys-hex* session % :pane pane)]
      (send/run-send send-fn send-hex-fn edn-str *in* delay))))

(defn cmd-mouse
  "Send mouse click to pane at x,y coordinates."
  [{:keys [opts]}]
  (let [{:keys [session x y pane click double]
         :or   {click "left"}}                 opts]
    (when-not session
      (exit-with-error "mouse" "session name required"))
    (when-not (and x y)
      (exit-with-error "mouse" "x and y coordinates required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))

    (let [button-map    {"left" 0 "middle" 1 "right" 2}
          button        (get button-map click 0)
          cb            (char (+ button 32))
          cx            (char (+ x 33))
          cy            (char (+ y 33))
          mouse-press   (str "\u001b[M" cb cx cy)
          mouse-release (str "\u001b[M" (char (+ 3 32)) cx cy)]
      ;; Mouse press and release
      (send-keys* session mouse-press :pane pane :literal true)
      (send-keys* session mouse-release :pane pane :literal true)

      (when double
        (Thread/sleep 50)
        (send-keys* session mouse-press :pane pane :literal true)
        (send-keys* session mouse-release :pane pane :literal true))

      (println (str "Clicked " click " at (" x ", " y ")" (when double " x2"))))))

(defn cmd-new
  "Create a new tmux session."
  [{:keys [opts]}]
  (let [{:keys [name socket window cmd width height no-session-file force]
         :or   {window "main" width 120 height 40}}                        opts
        socket                                                             (resolve-socket-path socket)]
    (when-not name
      (exit-with-error "new" "session name required"))

    ;; Check if .tmuxb_session already exists and the session is still alive
    (when (and (not no-session-file)
               (not force)
               (fs/exists? ".tmuxb_session"))
      (let [existing (read-session-file)]
        (binding [*socket* (resolve-socket-path (:socket existing))]
          (when (find-session (:session existing))
            (exit-with-error "new" ".tmuxb_session already exists (use --force to overwrite)")))))

    ;; Check if session already exists on this server
    (binding [*socket* socket]
      (when (find-session name)
        (binding [*out* *err*] (println (str "Session '" name "' already exists")))
        (System/exit 1))

      (new-session* name :window window :cmd cmd :width width :height height))

    ;; Write session file unless --no-session-file
    (when-not no-session-file
      (write-session-file name socket))

    (println (str "Created session '" name "'"
                  (when socket (str " (socket: " socket ")"))))))

(defn cmd-kill
  "Kill a tmux session."
  [{:keys [opts]}]
  (let [{:keys [session socket]} opts]
    (when-not session
      (exit-with-error "kill" "session name required"))
    (when-not (find-session session)
      (binding [*out* *err*] (println (str "Session '" session "' not found")))
      (System/exit 1))

    (kill-session* session)

    ;; Delete .tmuxb_session if it matches this session
    (delete-session-file-if-matches session socket)

    (println (str "Killed session '" session "'"))))

(defn cmd-attach
  "Attach to a tmux session interactively."
  [{:keys [opts]}]
  (let [{:keys [session socket read-only detach-other]} opts
        session-data                                    (when-not session (read-session-file))
        session                                         (or session (:session session-data))
        socket                                          (resolve-socket-path (or socket (:socket session-data)))]
    (when-not session
      (exit-with-error "attach" "no session specified and no .tmuxb_session found"))

    (binding [*socket* socket]
      (when-not (find-session session)
        (binding [*out* *err*] (println (str "Session '" session "' not found")))
        (System/exit 1)))

    (let [args (cond-> ["tmux"]
                 socket (conj "-S" socket)
                 :always (conj "attach-session" "-t" session)
                 read-only (conj "-r")
                 detach-other (conj "-d"))]
      ;; Replace current process with tmux attach
      (p/exec args))))

(defn cmd-help
  "Show help message."
  [_]
  (let [cmd-by-name (into {} (map (juxt :name identity) commands))
        ordered     (keep cmd-by-name commands-order)
        cmd-entries (for [{:keys [name usage desc]} ordered]
                      (let [args (-> usage
                                     (str/replace #"\[?options\]?" "")
                                     (str/replace #"\s+" " ")
                                     str/trim)]
                        {:cmd        (str name (when (seq args) (str " " args)))
                         :short-desc (first (str/split desc #"\.\s*"))}))
        max-width   (+ 2 (apply max (map #(count (:cmd %)) cmd-entries)))]
    (println "tmuxb - tmux-buddy CLI

A CLI tool that enables humans or LLMs to interact with tmux sessions.

Commands:")
    (doseq [{:keys [cmd short-desc]} cmd-entries]
      (println (format (str "  %-" max-width "s %s") cmd short-desc)))
    (println)
    (println "Use -h or --help with any command for more options.")))

;;; ---------------------------------------------------------------------------
;;; CLI Dispatch
;;; ---------------------------------------------------------------------------

;; Command specifications with help metadata
(def commands-order ["new" "kill" "list" "capture" "send" "watch" "windows" "panes" "mouse" "attach"])
(def commands
  [{:name       "list"
    :usage      "[options]"
    :desc       "List all tmux sessions."
    :args->opts []
    :coerce     {}
    :spec       {:socket {:alias :S :desc "tmux socket path"}
                 :json   {:coerce :boolean :desc "Output as JSON"}
                 :edn    {:coerce :boolean :desc "Output as EDN"}}}

   {:name       "windows"
    :usage      "SESSION [options]"
    :desc       "List windows in a session."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket {:alias :S :desc "tmux socket path"}
                 :json   {:coerce :boolean :desc "Output as JSON"}
                 :edn    {:coerce :boolean :desc "Output as EDN"}}}

   {:name       "panes"
    :usage      "SESSION [options]"
    :desc       "List panes in a session or window."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket {:alias :S :desc "tmux socket path"}
                 :window {:alias :w :desc "Window name or index"}
                 :json   {:coerce :boolean :desc "Output as JSON"}
                 :edn    {:coerce :boolean :desc "Output as EDN"}}}

   {:name       "capture"
    :usage      "SESSION [options]"
    :desc       "Capture pane contents. Includes cursor position by default."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket     {:alias :S :desc "tmux socket path"}
                 :pane       {:alias :p :desc "Pane ID or index"}
                 :lines      {:alias :n :coerce :long :default 50 :desc "Number of history lines"}
                 :if-changed {:coerce :boolean :desc "Only output if screen changed"}
                 :history    {:alias :H :coerce :boolean :desc "Include scrollback history"}
                 :raw        {:alias :r :coerce :boolean :desc "Raw output without cursor metadata"}
                 :style      {:alias :s :default "none" :desc "Style: none, lines, tags, ansi"}}}

   {:name       "watch"
    :usage      "SESSION [options]"
    :desc       "Watch pane for changes, outputting only when content changes."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket   {:alias :S :desc "tmux socket path"}
                 :pane     {:alias :p :desc "Pane ID or index"}
                 :interval {:alias :i :coerce :double :default 0.5 :desc "Poll interval in seconds"}
                 :timeout  {:alias :t :coerce :double :default 30.0 :desc "Max time to watch"}
                 :until    {:alias :u :desc "Stop when this text appears"}}}

   {:name       "send"
    :usage      "SESSION [options] [EDN...] or stdin"
    :desc       "Send keys using EDN DSL. Reads from stdin if no args. See doc/send-keys-dsl.md"
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket {:alias :S :desc "tmux socket path"}
                 :pane   {:alias :p :desc "Pane ID or index"}
                 :delay  {:alias :d :coerce :long :default 30 :desc "Delay between actions in ms"}}}

   {:name       "mouse"
    :usage      "SESSION X Y [options]"
    :desc       "Send mouse click to pane at x,y coordinates."
    :args->opts [:session :x :y]
    :coerce     {:session :string :x :long :y :long}
    :spec       {:socket {:alias :S :desc "tmux socket path"}
                 :pane   {:alias :p :desc "Pane ID or index"}
                 :click  {:default "left" :desc "Click type: left, right, middle"}
                 :double {:coerce :boolean :desc "Double click"}}}

   {:name       "new"
    :usage      "NAME [options]"
    :desc       "Create a new tmux session."
    :args->opts [:name]
    :coerce     {:name :string}
    :spec       {:socket          {:alias :S :desc "tmux socket path"}
                 :window          {:alias :w :default "main" :desc "Initial window name"}
                 :cmd             {:alias :c :desc "Initial command to run"}
                 :width           {:coerce :long :default 120 :desc "Window width"}
                 :height          {:coerce :long :default 40 :desc "Window height"}
                 :no-session-file {:coerce :boolean :desc "Don't create .tmuxb_session file"}
                 :force           {:alias :f :coerce :boolean :desc "Overwrite existing .tmuxb_session"}}}

   {:name       "attach"
    :usage      "[SESSION] [options]"
    :desc       "Attach to a tmux session interactively (FOR HUMAN USE ONLY). Agents and LLMs must not use this command."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket       {:alias :S :desc "tmux socket path"}
                 :read-only    {:alias :r :coerce :boolean :desc "Attach in read-only mode"}
                 :detach-other {:alias :d :coerce :boolean :desc "Detach other clients"}}}

   {:name       "kill"
    :usage      "SESSION [options]"
    :desc       "Kill a tmux session."
    :args->opts [:session]
    :coerce     {:session :string}
    :spec       {:socket {:alias :S :desc "tmux socket path"}}}])

(defn get-cmd-spec
  "Get the spec for a command by name."
  [cmd-name]
  (first (filter #(= (:name %) cmd-name) commands)))

(defn add-help-to-spec
  "Add help option to a spec."
  [spec]
  (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"}))

(defn wrap-with-help
  "Wrap a command function to handle --help."
  [cmd-fn cmd-name]
  (fn [{:keys [opts] :as m}]
    (if (:help opts)
      (let [{:keys [usage desc spec]} (get-cmd-spec cmd-name)]
        (print-cmd-help cmd-name usage desc (add-help-to-spec spec)))
      (cmd-fn m))))

(defn looks-like-edn?
  "Check if string looks like EDN (starts with quote, colon, bracket, etc.)"
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (or (str/starts-with? trimmed "\"")
          (str/starts-with? trimmed ":")
          (str/starts-with? trimmed "[")
          (str/starts-with? trimmed "{")
          (str/starts-with? trimmed "(")
          (re-matches #"^\d+$" trimmed)))))

(defn wrap-with-session-file
  "Wrap a command function to merge .tmuxb_session defaults into opts.
  CLI opts take precedence over file values. Binds *socket* if provided.

  If the parsed session looks like EDN and we have a default session from
  .tmuxb_session, treats the parsed session as an arg instead."
  [cmd-fn]
  (fn [{:keys [opts args] :as m}]
    (let [defaults       (read-session-file)
          parsed-session (:session opts)
          [session args] (cond
                           (nil? parsed-session)
                           [(:session defaults) args]

                           (and (looks-like-edn? parsed-session)
                                (:session defaults))
                           [(:session defaults) (into [parsed-session] args)]

                           :else
                           [parsed-session args])
          merged         (-> (merge defaults opts)
                             (assoc :session session))
          socket         (:socket merged)]
      (binding [*socket* socket]
        (cmd-fn (assoc m :opts merged :args args))))))

(def dispatch-table
  (into
   [(into {:cmds [] :fn cmd-help})]
   (for [{:keys [name args->opts coerce spec]} commands]
     {:cmds       [name]
      :fn         (wrap-with-help
                   (case name
                     "list"    (wrap-with-session-file cmd-list)
                     "windows" (wrap-with-session-file cmd-windows)
                     "panes"   (wrap-with-session-file cmd-panes)
                     "capture" (wrap-with-session-file cmd-capture)
                     "watch"   (wrap-with-session-file cmd-watch)
                     "send"    (wrap-with-session-file cmd-send)
                     "mouse"   (wrap-with-session-file cmd-mouse)
                     "new"     cmd-new
                     "attach"  (wrap-with-session-file cmd-attach)
                     "kill"    (wrap-with-session-file cmd-kill))
                   name)
      :args->opts args->opts
      :coerce     coerce
      :spec       (add-help-to-spec spec)})))

(defn -main [& args]
  (cli/dispatch dispatch-table args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
