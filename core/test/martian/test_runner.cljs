(ns martian.test-runner
  (:require [martian.core-test]
            [martian.schema-test]
            [martian.interceptors-test]
            [martian.swagger-test]
            [martian.openapi-test]
            [figwheel.main.testing :refer [run-tests-async]]))

(defn -main [& args]
  (run-tests-async 5000))
