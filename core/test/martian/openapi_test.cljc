(ns martian.openapi-test
  (:require [clojure.test :refer [deftest is testing]]
            [martian.openapi :refer [openapi->handlers]]
            [martian.test-helpers #?@(:clj  [:refer [json-resource yaml-resource]]
                                      :cljs [:refer-macros [json-resource yaml-resource]])]
            [schema-tools.core :as st]
            [schema.core :as s]))

(def openapi-json
  (json-resource "openapi.json"))

(def openapi-2-json
  (json-resource "openapi2.json"))

(def jira-openapi-v3-json
  (json-resource "jira-openapi-v3.json"))

(def kubernetes-openapi-v3-yaml
  (yaml-resource "kubernetes-openapi-v3-converted.yaml"))

(deftest openapi-sanity-check
  (testing "parses each handler"
    (is (= {:summary        "Update an existing pet"
            :description    "Update an existing pet by Id"
            :method         :put
            :produces       ["application/json"]
            :path-schema    nil
            :query-schema   nil
            :form-schema    nil
            :path-parts     ["/pet"]
            :headers-schema nil
            :consumes       ["application/json"]
            :body-schema
            {:body
             {(s/optional-key :id)       s/Int
              :name                      s/Str
              (s/optional-key :category) {(s/optional-key :id)   s/Int
                                          (s/optional-key :name) s/Str}
              :photoUrls                 [s/Str]
              (s/optional-key :tags)     [{(s/optional-key :id)   s/Int
                                           (s/optional-key :name) s/Str}]
              (s/optional-key :status)   (s/enum "sold" "pending" "available")}}
            :route-name     :update-pet
            :response-schemas
            [{:status (s/eq 200)
              :body
              {(s/optional-key :id)       s/Int
               :name                      s/Str
               (s/optional-key :category) {(s/optional-key :id)   s/Int
                                           (s/optional-key :name) s/Str}
               :photoUrls                 [s/Str]
               (s/optional-key :tags)     [{(s/optional-key :id)   s/Int
                                            (s/optional-key :name) s/Str}]
               (s/optional-key :status)   (s/enum "sold" "pending" "available")}}
             {:status (s/eq 400) :body nil}
             {:status (s/eq 404) :body nil}
             {:status (s/eq 405) :body nil}]}

           (-> openapi-json
               (openapi->handlers {:encodes ["application/json" "application/octet-stream"]
                                   :decodes ["application/json" "application/octet-stream"]})
               (->> (filter #(= (:route-name %) :update-pet)))
               first
               (dissoc :openapi-definition)))))

  (testing "chooses the first supported content-type"
    (is (= {:consumes ["application/xml"]
            :produces ["application/json"]}

           (-> openapi-json
               (openapi->handlers {:encodes ["application/xml"]
                                   :decodes ["application/json"]})
               (->> (filter #(= (:route-name %) :update-pet)))
               first
               (select-keys [:consumes :produces]))))))

(deftest openapi-parameters-test
  (testing "parses parameters"
    (is (= {:description nil,
            :method :get,
            :produces ["application/json"],
            :path-schema {:projectId s/Str},
            :query-schema {(s/optional-key :key) (st/default s/Str "some-default-key")},
            :form-schema nil,
            :path-parts ["/project/" :projectKey],
            :headers-schema {(s/optional-key :userAuthToken) s/Str},
            :consumes nil
            :summary "Get specific values from a configuration for a specific project",
            :body-schema nil,
            :route-name :get-project-configuration,
            :response-schemas
            [{:status (s/eq 200), :body s/Str}
             {:status (s/eq 403), :body nil}
             {:status (s/eq 404), :body nil}]}
           (-> openapi-2-json
               (openapi->handlers {:encodes ["application/json" "application/octet-stream"]
                                   :decodes ["application/json" "application/octet-stream"]})
               (->> (filter #(= (:route-name %) :get-project-configuration)))
               first
               (dissoc :openapi-definition))))))

(deftest jira-openapi-v3-test
  (is (= 410
         (-> jira-openapi-v3-json
             (openapi->handlers {:encodes ["application/json"]
                                 :decodes ["application/json"]})
             count))))

(deftest reffed-params-test
  (let [openapi-json
        {:paths {(keyword "/models/{model_id}/{version}")
                 {:get {:operationId "load-models-id-version-get"
                        :summary "Loads a pet by id"
                        :parameters [{:$ref "#/components/parameters/model_id"}
                                     {:$ref "#/components/parameters/version"}]}}}
         :components {:parameters {:model_id {:in "path"
                                              :name "model_id"
                                              :schema {:type "string"}
                                              :required true}
                                   :version {:in "path"
                                             :name "version"
                                             :schema {:type "string"}
                                             :required true}}}}
        [handler] (openapi->handlers openapi-json {:encodes ["application/json"]
                                                   :decodes ["application/json"]})]
    (is (= {:path-parts ["/models/" :model_id "/" :version],
            :path-schema {:model_id s/Str
                          :version s/Str}}
           (select-keys handler [:path-parts :path-schema])))))

(deftest reffed-responses-test
  (let [openapi-json
        {:paths {(keyword "/models")
                 {:get {:operationId "list-models"
                        :summary "Lists models"
                        :responses {:401 {:$ref "#/components/responses/Unauthorized"}
                                    :404 {:$ref "#/components/responses/NotFound"}}}}}
         :components {:responses {:NotFound
                                  {:description "The requested resource was not found."
                                   :content
                                   {:application/json
                                    {:schema {:$ref "#/components/schemas/Error"}}}}
                                  :Unauthorized
                                  {:description "Unauthorized."
                                   :content
                                   {:application/json
                                    {:schema {:$ref "#/components/schemas/Error"}}}}}
                      :schemas {:Error
                                {:type "object"
                                 :properties
                                 {:code
                                  {:description "An enumerated error for machine use.",
                                   :type "integer",
                                   :readOnly true},
                                  :details
                                  {:description "A human-readable description of the error.",
                                   :type "string",
                                   :readOnly true}}}}}}
        [handler] (openapi->handlers openapi-json {:encodes ["application/json"]
                                                   :decodes ["application/json"]})]
    (is (= [{:status (s/eq 401)
             :body {(s/optional-key :code) s/Int (s/optional-key :details) s/Str}}
            {:status (s/eq 404)
             :body {(s/optional-key :code) s/Int (s/optional-key :details) s/Str}}]
           (:response-schemas handler)))))

(deftest schemas-without-type-test
  (let [openapi-json
        {:paths {(keyword "/models")
                 {:get {:operationId "list-models"
                        :summary "Lists models"
                        :responses {:404 {:$ref "#/components/responses/NotFound"}}}}}
         :components {:responses {:NotFound
                                  {:description "The requested resource was not found."
                                   :content
                                   {:application/json
                                    {:schema {:$ref "#/components/schemas/Error"}}}}}
                      :schemas {:Error
                                {:properties
                                 {:code
                                  {:description "An enumerated error for machine use.",
                                   :type "integer",
                                   :readOnly true},
                                  :details
                                  {:description "A human-readable description of the error.",
                                   :type "string",
                                   :readOnly true}}}}}}
        [handler] (openapi->handlers openapi-json {:encodes ["application/json"]
                                                   :decodes ["application/json"]})]
    (is (= [{:status (s/eq 404)
             :body {(s/optional-key :code) s/Int (s/optional-key :details) s/Str}}]
           (:response-schemas handler)))))

(deftest body-object-without-any-parameters-takes-values
  (is (= {:body {s/Any s/Any}}
         (let [[handler] (filter #(= (:route-name %) :patch-core-v-1-namespaced-secret)
                                 (openapi->handlers kubernetes-openapi-v3-yaml
                                                    {:encodes ["application/json-patch+json"]
                                                     :decodes ["application/json"]}))]

           (:body-schema handler)))))

(deftest form-encoded-schemas-test
  (let [openapi-json
        {:paths {(keyword "/models")
                 {:post {:operationId "create-thing"
                         :summary "Creates things"
                         :requestBody {:required true,
                                       :content
                                       {:application/x-www-form-urlencoded
                                        {:schema
                                         {:type "object"
                                          :properties {:foo {:type "string"} :bar {:type "number"}}
                                          :required ["foo" "bar"]}}}}}}}}
        [handler] (openapi->handlers openapi-json {:encodes ["application/x-www-form-urlencoded"]
                                                   :decodes ["application/json"]})]
    (testing "parses parameters"
      (is (= {:body {:foo s/Str :bar s/Num}}
             (:body-schema handler))))))

(deftest reffed-requestbody-test
  (let [openapi-json
        {:paths {(keyword "/pets")
                 {:post {:operationId "create-pet"
                         :summary "Creates a pet"
                         :requestBody {:$ref "#/components/requestBodies/PetBody"}}}}
         :components {:requestBodies {:PetBody {:required true
                                                :content {:application/json
                                                          {:schema {:$ref "#/components/schemas/Pet"}}}}}
                      :schemas {:Pet {:type "object"
                                      :required ["name"]
                                      :properties {:name {:type "string"}
                                                   :age {:type "integer"}}}}}}
        [handler] (openapi->handlers openapi-json {:encodes ["application/json"]
                                                   :decodes ["application/json"]})]
    (is (= {:body {:name s/Str
                   (s/optional-key :age) s/Int}}
           (:body-schema handler)))))

(deftest status-nXX-test
  (let [oas-for (fn oas-for [n]
                  {:paths {(keyword "/getfoo")
                           {:get {:operationId "testit"
                                  :summary "For testing"
                                  :responses {(keyword (str n "XX"))
                                              {:description "Works fine"
                                               :content
                                               {:application/json
                                                {:schema {:type "integer"
                                                          :description "A number"}}}}}}}}})]
    (doseq [n [1 2 3 4 5]]
      (let [openapi-json (oas-for n)
            [handler] (openapi->handlers openapi-json {:encodes ["application/json"]
                                                       :decodes ["application/json"]})
            response-schemas (:response-schemas handler)
            status-schema (:status (first response-schemas))
            valid-statuses (repeatedly 3 #(+ (* n 100) (rand-int 100))) ; sample 3 ints in range
            invalid-status (* (inc n) 100)]
        (testing (str "checks response status range schema for " n "XX")
          (doseq [status valid-statuses]
            (is (s/validate status-schema status)))
          (is (thrown? #?(:clj Throwable
                          :cljs :default)
                       (s/validate status-schema invalid-status))))))))

(defn- schema-for
  "Helper to create and extract schema,
  after merging in the OAS `schema-addition`."
  [schema-addition]
  (let [base-spec {:openapi "3.1.0"
                   :paths {(keyword "/getnone")
                           {:get {:operationId "testit"
                                  :summary "For testing"
                                  :parameters [{:in "path"
                                                :name "id"
                                                :schema {:type "number"}
                                                :required true}]
                                  :responses {(keyword "204") {:description "OK"}}}}}}
        oas (update-in base-spec
                       [:paths (keyword "/getnone") :get :parameters 0 :schema]
                       merge
                       schema-addition)
        [handler] (openapi->handlers oas {:encodes ["application/json"]
                                          :decodes ["application/json"]})
        path-schema (:path-schema handler)]
    (:id path-schema)))

(deftest bounds-test

  (testing "both minimum and exclusiveMinimum — exclusive wins when equal"
    (let [s (schema-for {:minimum 5 :exclusiveMinimum 5})
          v (comp nil? (s/checker s))]
      ;; x >=5 AND x >5  ==> x >5
      (is (false? (v 5)))
      (is (true?  (v 5.1)))
      (is (false? (v 4.999)))))

  (testing "both minimum and exclusiveMinimum — inclusive is stricter"
    (let [s (schema-for {:minimum 5 :exclusiveMinimum 3})
          v (comp nil? (s/checker s))]
      ;; x >=5 AND x >3  ==> x >=5
      (is (false? (v 4)))
      (is (true?  (v 5)))
      (is (true?  (v 5.0001)))
      (is (false? (v 4.999)))))

  (testing "both minimum and exclusiveMinimum — exclusive is stricter"
    (let [s (schema-for {:minimum 5 :exclusiveMinimum 7})
          v (comp nil? (s/checker s))]
      ;; x >=5 AND x >7  ==> x >7
      (is (false? (v 7)))
      (is (true?  (v 7.0001)))
      (is (false? (v 6.999)))
      (is (false? (v 5)))))

  (testing "symmetric for upper bounds"
    (let [s (schema-for {:maximum 10 :exclusiveMaximum 10})
          v (comp nil? (s/checker s))]
      ;; <=10 AND <10  ==> <10
      (is (false? (v 10)))
      (is (true?  (v 9.999)))
      (is (false? (v 10.001))))

    (let [s (schema-for {:maximum 10 :exclusiveMaximum 15})
          v (comp nil? (s/checker s))]
      ;; <=10 AND <15  ==> <=10
      (is (true?  (v 10)))
      (is (true?  (v 9)))
      (is (false? (v 11))))

    (let [s (schema-for {:maximum 10 :exclusiveMaximum 8})
          v (comp nil? (s/checker s))]
      ;; <=10 AND <8  ==> <8
      (is (false? (v 8)))
      (is (true?  (v 7.999)))
      (is (false? (v 8.001)))))

  (testing "all four bounds together"
    (let [s (schema-for {:minimum 2 :exclusiveMinimum 1
                :maximum 10 :exclusiveMaximum 12})
          v (comp nil? (s/checker s))]
      ;; x>=2 AND x>1 AND x<=10 AND x<12  ==> x>=2 AND x<=10
      (is (true?  (v 2)))
      (is (true?  (v 10)))
      (is (false? (v 1.5)))
      (is (false? (v 10.5)))))

  (testing "conflicting bounds"
    (is (thrown? #?(:clj Throwable
                    :cljs :default)
                 (schema-for {:minimum 10 :maximum 2})))
    (is (thrown? #?(:clj Throwable
                    :cljs :default)
                 (schema-for {:exclusiveMinimum 10 :exclusiveMaximum 2}))))

  (testing "invalid data"
    (is (thrown? #?(:clj Throwable
                    :cljs :default)
                 (schema-for {:minimum "a string"})))
    (is (thrown? #?(:clj Throwable
                    :cljs :default)
                 (schema-for {:exclusiveMinimum {:a 1}}))))
  )
