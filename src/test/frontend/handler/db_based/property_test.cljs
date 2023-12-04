(ns frontend.handler.db-based.property-test
  (:require [frontend.handler.db-based.property :as db-property-handler]
            [frontend.db :as db]
            [clojure.test :refer [deftest is testing are use-fixtures]]
            [frontend.test.helper :as test-helper]
            [datascript.core :as d]
            [frontend.handler.property.util :as pu]
            [frontend.state]
            [frontend.config]
            [frontend.handler.page :as page-handler]
            [frontend.handler.editor :as editor-handler]))

(def repo test-helper/test-db-name-db-version)

(def init-data (test-helper/initial-test-page-and-blocks))
(defn start-and-destroy-db
  [f]
  (test-helper/db-based-start-and-destroy-db
   f
   {:init-data (fn [conn] (d/transact! conn init-data))}))

;; init page id
;; (def pid (:block/uuid (first init-data)))
;; first block id
(def fbid (:block/uuid (second init-data)))
(def sbid (:block/uuid (nth init-data 2)))

(use-fixtures :each start-and-destroy-db)

;; set-block-property!
;; delete-property-value!
;; remove-block-property!
;; batch-set-property!
;; batch-remove-property!
;; upsert-property!
;; update-property!
(deftest ^:large-vars/cleanup-todo block-property-test
  (testing "Add a property to a block"
    (db-property-handler/set-block-property! repo fbid "property-1" "value" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-1"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :default}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        1
        (uuid? (ffirst properties))
        true
        (second (first properties))
        "value")))

  (testing "Add another property"
    (db-property-handler/set-block-property! repo fbid "property-2" "1" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-2"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :number}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (every? uuid? (map first properties))
        true
        (second (second properties))
        1)))

  (testing "Update property value"
    (db-property-handler/set-block-property! repo fbid "property-2" 2 {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (second (second properties))
        2)))

  (testing "Wrong type property value shouldn't transacted"
    (db-property-handler/set-block-property! repo fbid "property-2" "Not a number" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (second (second properties))
        2)))

  (testing "Add a multi-values property"
    (db-property-handler/upsert-property! repo "property-3" {:type :number :cardinality :many} {})
    (db-property-handler/set-block-property! repo fbid "property-3" 1 {})
    (db-property-handler/set-block-property! repo fbid "property-3" 2 {})
    (db-property-handler/set-block-property! repo fbid "property-3" 3 {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :number :cardinality :many}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        3
        (get properties (:block/uuid property))
        #{1 2 3}))

    ;; update property value from 1 to 4
    (db-property-handler/set-block-property! repo fbid "property-3" 4 {:old-value 1})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        3
        (get properties (:block/uuid property))
        #{4 2 3})

      (db-property-handler/delete-property-value! repo block (:block/uuid property) "value 4")
      (let [properties (:block/properties block)]
        (is (get properties (:block/uuid property))
            #{"value 2" "value 3"}))))

  (testing "Remove a property"
    (db-property-handler/remove-block-property! repo fbid "property-3")
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (contains? (set (keys properties)) (:block/uuid property))
        false)))

  (testing "Batch set properties"
    (let [k "property-4"
          v "batch value"]
      (db-property-handler/batch-set-property! repo [fbid sbid] k v)
      (let [fb (db/entity [:block/uuid fbid])
            sb (db/entity [:block/uuid sbid])]
        (are [x y] (= x y)
          (pu/get-property fb k)
          v
          (pu/get-property sb k)
          v))))

  (testing "Batch remove properties"
    (let [k "property-4"]
      (db-property-handler/batch-remove-property! repo [fbid sbid] k)
      (let [fb (db/entity [:block/uuid fbid])
            sb (db/entity [:block/uuid sbid])]
        (are [x y] (= x y)
          (count (:block/properties fb))
          2
          (count (:block/properties sb))
          0)))))

;; class related
;; class-add-property!
;; class-remove-property!
;; class-set-schema!
;; get-block-classes-properties
(deftest property-class-test
  (let [opts {:redirect? false :create-first-block? false :class? true}
        _ (page-handler/create! "class1" opts)
        _ (page-handler/create! "class2" opts)
        _ (page-handler/create! "class3" opts)
        c1 (db/entity [:block/name "class1"])
        c2 (db/entity [:block/name "class2"])
        c1id (:block/uuid c1)
        c2id (:block/uuid c2)]

    (testing "Create classes"
      (are [x y] (= x y)
        (:block/type (db/entity [:block/name "class1"]))
        #{"class"}
        (:block/type (db/entity [:block/name "class2"]))
        #{"class"}))

    (testing "Class add property"
      (db-property-handler/class-add-property! repo c1id "property-1")
      (db-property-handler/class-add-property! repo c1id "property-2")
      ;; repeated adding property-2
      (db-property-handler/class-add-property! repo c1id "property-2")
      (is (= 2 (count (:properties (:block/schema (db/entity (:db/id c1))))))))

    (testing "Class remove property"
      (db-property-handler/class-remove-property! repo c1id (:block/uuid (db/entity [:block/name "property-1"])))
      (is (= 1 (count (:properties (:block/schema (db/entity (:db/id c1))))))))
    (testing "Add classes to a block"
      (editor-handler/save-block! repo fbid "Block 1 #class1 #class2 #class3")
      (is (= 3 (count (:block/tags (db/entity [:block/uuid fbid]))))))
    (testing "Remove a class from a block"
      ;; make sure class2 will not be deleted when removing it from the first block
      (editor-handler/save-block! repo sbid "Block 2 #class2")
      (editor-handler/save-block! repo fbid "Block 1 #class1 #class3")
      (is (= 2 (count (:block/tags (db/entity [:block/uuid fbid]))))))
    (testing "Get block's classes properties"
      ;; set c2 as parent of c3
      (let [c3 (db/entity [:block/name "class3"])]
        (db/transact! [{:db/id (:db/id c3)
                        :block/namespace (:db/id c2)}]))
      (db-property-handler/class-add-property! repo c2id "property-3")
      (db-property-handler/class-add-property! repo c2id "property-4")
      (is (= 3 (count (:classes-properties
                       (db-property-handler/get-block-classes-properties (:db/id (db/entity [:block/uuid fbid]))))))))))

(defn- get-value-ids
  [property-name]
  (:values (:block/schema (db/entity [:block/name property-name]))))

(defn- get-closed-values
  "Get value from block ids"
  [values]
  (set (map #(get-in (db/entity [:block/uuid %]) [:block/schema :value]) values)))

;; closed values related
;; upsert-closed-value
;; add-existing-values-to-closed-values!
;; delete-closed-value
(deftest closed-values-test
  (testing "Create properties and closed values"
    (db-property-handler/set-block-property! repo fbid "property-1" "1" {})
    (db-property-handler/set-block-property! repo sbid "property-1" "2" {})
    (let [k "property-1"
          property (db/entity [:block/name k])]
      (testing "Add existing values to closed values"
        (db-property-handler/add-existing-values-to-closed-values! property [1 2])
        (let [values (get-value-ids k)]
          (is (every? uuid? values))
          (is (= #{1 2} (get-closed-values values)))
          (is (every? #(contains? (:block/type (db/entity [:block/uuid %])) "closed value")
                      values))))

      (testing "Add non-numbers shouldn't work"
        (let [result (db-property-handler/upsert-closed-value property {:value "not a number"})]
          (is (= result :value-invalid))
          (let [values (get-value-ids k)]
            (is (= #{1 2} (get-closed-values values))))))

      (testing "Add existing value"
        (let [result (db-property-handler/upsert-closed-value property {:value 2})]
          (is (= result :value-exists))))

      (testing "Add new value"
        (let [{:keys [block-id tx-data]} (db-property-handler/upsert-closed-value property {:value 3})]
          (db/transact! tx-data)
          (let [b (db/entity [:block/uuid block-id])]
            (is (= 3 (:value (:block/schema b))))
            (is (contains? (:block/type b) "closed value")))
          (let [values (get-value-ids k)]
            (is (= #{1 2 3} (get-closed-values values))))

          (testing "Update closed value"
            (let [{:keys [tx-data]} (db-property-handler/upsert-closed-value property {:id block-id
                                                                                       :value 4
                                                                                       :description "choice 4"})]
              (db/transact! tx-data)
              (let [b (db/entity [:block/uuid block-id])]
                (is (= 4 (:value (:block/schema b))))
                (is (= "choice 4" (:description (:block/schema b))))
                (is (contains? (:block/type b) "closed value")))))

          (testing "Delete closed value"
            (db-property-handler/delete-closed-value! property (db/entity [:block/uuid block-id]))
            (is (nil? (db/entity [:block/uuid block-id])))
            (is (= 2 (count (:values (:block/schema (db/entity [:block/name k]))))))))))))

;; others
;; get-property-block-created-block
;; property-create-new-block
;; convert-property-input-string
;; replace-key-with-id
;; collapse-expand-property! TODO


;; template (TBD, template implementation not settle down yet)
;; property-create-new-block-from-template

#_(cljs.test/run-tests)