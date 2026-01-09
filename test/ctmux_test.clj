(ns ctmux-test
  (:require [babashka.nrepl.server :as srv]
            [babashka.process :refer [shell]]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

