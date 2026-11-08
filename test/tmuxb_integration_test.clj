(ns tmuxb-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [tmuxb :as t]))

(def ^:dynamic *tmux-socket* nil)

(defn tmux-cmd
  "Run tmux command with isolated socket."
  [& args]
  (let [result (p/sh (into ["tmux" "-S" *tmux-socket*] args))]
    (when-not (zero? (:exit result))
      (throw (ex-info "tmux command failed" {:args args :stderr (:err result)})))
    (str/trim (:out result))))

(defn with-isolated-tmux [f]
  (let [socket-path (str (fs/create-temp-file {:prefix "tmuxb-test-" :suffix ".sock"}))]
    (fs/delete-if-exists socket-path)
    (binding [*tmux-socket* socket-path]
      (try
        (tmux-cmd "-f" "/dev/null" "new-session" "-d" "-s" "test-session" "-x" "80" "-y" "24")
        (f)
        (finally
          (try (tmux-cmd "kill-server") (catch Exception _))
          (fs/delete-if-exists socket-path))))))

(use-fixtures :each with-isolated-tmux)

(deftest kill-window-selectors
  (binding [t/*socket* *tmux-socket*]
    (doseq [selector [:name :index :id]]
      (let [id     (tmux-cmd "new-window" "-t" "test-session" "-n" "victim" "-P" "-F" "#{window_id}")
            window (first (filter #(= id (:id %)) (t/list-windows* "test-session")))]
        (t/kill-window* "test-session" (str (selector window)))
        (is (= 1 (count (t/list-windows* "test-session")))))))
  (testing "missing and foreign windows leave the session intact"
    (let [foreign (tmux-cmd "new-session" "-d" "-s" "other" "-P" "-F" "#{window_id}")]
      (binding [t/*socket* *tmux-socket*]
        (doseq [selector ["missing" "" foreign]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Window not found"
                                (t/kill-window* "test-session" selector))))
        (is (= 2 (count (t/list-sessions*))))))))

(deftest kill-cli-preserves-session-file-until-last-window
  (let [dir          (fs/create-temp-dir {:prefix "tmuxb-cli-"})
        src          (str (fs/absolutize "src"))
        session-file (fs/path dir ".tmuxb_session")
        run-cli      (fn [& args]
                       (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                              "bb" "--classpath" src "-m" "tmuxb" args))]
    (try
      (spit (str session-file) (pr-str {:session "test-session" :socket *tmux-socket*}))
      (let [result (run-cli "processes" "--json")]
        (is (zero? (:exit result)))
        (is (= 1 (count (json/parse-string (:out result) true)))))
      (is (not (zero? (:exit (run-cli "processes" "--width" "0")))))
      (tmux-cmd "new-window" "-t" "test-session" "-n" "victim")
      (is (zero? (:exit (run-cli "kill" "-w" "victim"))))
      (is (fs/exists? session-file))
      (is (not (zero? (:exit (run-cli "kill" "--window" "missing")))))
      (is (not (zero? (:exit (run-cli "kill" "--window")))))
      (is (fs/exists? session-file))
      (let [id (tmux-cmd "list-windows" "-t" "test-session" "-F" "#{window_id}")]
        (is (zero? (:exit (run-cli "kill" "--window" id)))))
      (is (not (fs/exists? session-file)))
      (tmux-cmd "new-session" "-d" "-s" "test-session")
      (spit (str session-file) (pr-str {:session "test-session" :socket *tmux-socket*}))
      (let [result (run-cli "close" "--timeout" "0" "--json")]
        (is (zero? (:exit result)))
        (is (= {:session "test-session" :survivors [] :signal-errors []}
               (json/parse-string (:out result) true))))
      (is (not (fs/exists? session-file)))
      (let [result (run-cli "processes")]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:err result) "no session specified and no .tmuxb_session found")))
      (finally (fs/delete-tree dir)))))

(deftest window-activity-output
  (binding [t/*socket* *tmux-socket*]
    (let [before (:activity (first (t/list-windows* "test-session")))]
      (is (pos-int? before))
      (Thread/sleep 1100)
      (tmux-cmd "send-keys" "-t" "test-session" "echo activity-probe" "Enter")
      (let [after       (loop [attempts 50]
                          (let [activity (:activity (first (t/list-windows* "test-session")))]
                            (if (or (> activity before) (zero? attempts))
                              activity
                              (do (Thread/sleep 50) (recur (dec attempts))))))
            opts        {:session "test-session"}
            plain       (with-out-str (t/cmd-windows {:opts opts}))
            json-output (json/parse-string (with-out-str (t/cmd-windows {:opts (assoc opts :json true)})) true)
            edn-output  (edn/read-string (with-out-str (t/cmd-windows {:opts (assoc opts :edn true)})))]
        (is (> after before))
        (is (str/includes? plain "last activity: "))
        (is (= (mapv :activity edn-output) (mapv :activity json-output)))
        (is (every? pos-int? (map :activity edn-output)))))))

(deftest processes-cover-all-windows-and-children
  (let [dir (fs/create-temp-dir {:prefix "tmuxb-processes-"})]
    (try
      (let [pane-id (tmux-cmd "new-window" "-t" "test-session" "-n" "worker"
                              "-c" (str dir) "-P" "-F" "#{pane_id}"
                              "sh -c 'sleep 60 & wait'")]
        (binding [t/*socket* *tmux-socket*]
          (let [panes     (loop [attempts 50]
                            (let [snapshot (t/session-process-handles "test-session")]
                              (if (or (some #(and (= pane-id (get-in % [:pane :id]))
                                                  (> (count (:handles %)) 1)) snapshot)
                                      (zero? attempts))
                                snapshot
                                (do (Thread/sleep 20) (recur (dec attempts))))))
                worker    (first (filter #(= pane-id (get-in % [:pane :id])) panes))
                tree      (t/process-tree (mapv t/process-info (:handles worker))
                                          (get-in worker [:pane :pid]))
                processes (tree-seq #(seq (:children %)) :children tree)
                output    (json/parse-string
                           (with-out-str (t/-main "processes" "test-session" "-S" *tmux-socket* "--json")) true)]
            (is (= 2 (count output)))
            (is (some #(str/includes? (:command %) "sleep 60") (rest processes)))
            (is (every? #(= (str dir) (:cwd %)) processes))
            (is (= (get-in worker [:pane :pid]) (:pid tree))))))
      (finally (fs/delete-tree dir)))))

(deftest close-rejects-session-prefixes
  (binding [t/*socket* *tmux-socket*]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Session not found"
                          (t/close-session* "test-sess" 0)))
    (is (some? (t/find-session "test-session")))))

(deftest close-requests-cleanup-and-reports-survivors
  (doseq [survive? [false true]]
    (let [dir     (fs/create-temp-dir {:prefix "tmuxb-close-"})
          script  (fs/path dir "worker.sh")
          ready   (fs/path dir "ready")
          cleaned (fs/path dir "cleaned")
          handles (atom [])]
      (try
        (spit (str script)
              (str (if survive?
                     "trap '' TERM HUP\n"
                     (str "trap 'echo done > " cleaned "; exit 0' TERM\n"))
                   "echo $$ > " ready "\n"
                   "while :; do sleep 0.05 & wait; done\n"))
        (binding [t/*socket* *tmux-socket*]
          (when-not (t/find-session "test-session")
            (tmux-cmd "new-session" "-d" "-s" "test-session"))
          (tmux-cmd "new-window" "-t" "test-session" "-n" "worker"
                    (str "sh -c 'sh " script " & wait'"))
          (loop [attempts 100]
            (when (and (not (fs/exists? ready)) (pos? attempts))
              (Thread/sleep 20)
              (recur (dec attempts))))
          (is (fs/exists? ready))
          (reset! handles (mapcat :handles (t/session-process-handles "test-session")))
          (let [pid    (parse-long (str/trim (slurp (str ready))))
                result (t/close-session* "test-session" (if survive? 0.1 2))]
            (is (nil? (t/find-session "test-session")))
            (is (empty? (:signal-errors result)))
            (if survive?
              (is (some #(= pid (:pid %)) (:survivors result)))
              (do (is (fs/exists? cleaned))
                  (is (not-any? #(= pid (:pid %)) (:survivors result)))))))
        (finally
          (doseq [handle @handles]
            (when (.isAlive handle)
              (with-open [stream (.descendants handle)]
                (doseq [child (iterator-seq (.iterator stream))]
                  (.destroyForcibly child)))
              (.destroyForcibly handle)))
          (fs/delete-tree dir))))))

(deftest integration-list-sessions-test
  (testing "can list sessions from isolated tmux"
    (let [output (tmux-cmd "list-sessions" "-F" "#{session_name}")]
      (is (str/includes? output "test-session")))))

(deftest integration-send-and-capture-test
  (testing "can send keys and capture output"
    (tmux-cmd "send-keys" "-t" "test-session" "echo hello-from-test" "Enter")
    (Thread/sleep 100)
    (let [output (tmux-cmd "capture-pane" "-t" "test-session" "-p")]
      (is (str/includes? output "hello-from-test")))))