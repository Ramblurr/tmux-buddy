(ns tmuxb-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p]))

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
        (tmux-cmd "new-session" "-d" "-s" "test-session" "-x" "80" "-y" "24")
        (f)
        (finally
          (try (tmux-cmd "kill-server") (catch Exception _))
          (fs/delete-if-exists socket-path))))))

(use-fixtures :each with-isolated-tmux)

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
