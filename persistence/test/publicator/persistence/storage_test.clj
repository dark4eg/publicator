(ns publicator.persistence.storage-test
  (:require
   [publicator.utils.test.instrument :as instrument]
   [clojure.test :as t]
   [hugsql.core :as hugsql]
   [jdbc.core :as jdbc]
   [publicator.domain.abstractions.aggregate :as aggregate]
   [publicator.use-cases.abstractions.storage :as storage]
   [publicator.persistence.test.db :as db]
   [publicator.persistence.storage :as sut]))

(defrecord TestEntity [id counter]
  aggregate/Aggregate
  (id [_] id)
  (spec [_] any?))

(defn build-test-entity []
  (TestEntity. 42 0))

;; ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

(hugsql/def-db-fns "publicator/persistence/storage_test.sql")

(defn- aggregate->sql [aggregate]
  (vals aggregate))

(defn- row->versioned-aggregate [row]
  {:aggregate (-> row (dissoc :version) map->TestEntity)
   :version   (-> row (get :version))})

(def mapper (reify sut/Mapper
              (-lock [_ conn ids]
                (test-entity-locks conn {:ids ids}))
              (-select [_ conn ids]
                (map row->versioned-aggregate (test-entity-select conn {:ids ids})))
              (-insert [_ conn states]
                (test-entity-insert conn {:vals (map aggregate->sql states)}))
              (-delete [_ conn ids]
                (test-entity-delete conn {:ids ids}))))

;; ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

(defn- setup [t]
  (with-bindings (sut/binding-map db/*data-source* {TestEntity mapper})
    (t)))

(defn- test-table [t]
  (with-open [conn (jdbc/connection db/*data-source*)]
    (drop-test-entity-table conn)
    (create-test-entity-table conn))
  (t))

(t/use-fixtures :once
  instrument/fixture
  db/once-fixture)

(t/use-fixtures :each
  db/each-fixture
  test-table
  setup)

(t/deftest create
  (let [entity (storage/tx-create (build-test-entity))]
    (t/is (some? (storage/tx-get-one (aggregate/id entity))))))

(t/deftest change
  (let [entity (storage/tx-create (build-test-entity))
        _      (storage/tx-alter entity update :counter inc)
        entity (storage/tx-get-one (:id entity))]
    (t/is (= 1 (:counter entity)))))

(t/deftest identity-map-persisted
  (let [id (:id (storage/tx-create (build-test-entity)))]
    (storage/with-tx t
      (let [x (storage/get-one t id)
            y (storage/get-one t id)]
        (t/is (identical? x y))))))

(t/deftest identity-map-in-memory
  (storage/with-tx t
    (let [x (storage/create t (build-test-entity))
          y (storage/get-one t (aggregate/id @x))]
      (t/is (identical? x y)))))

(t/deftest identity-map-swap
  (storage/with-tx t
    (let [x (storage/create t (build-test-entity))
          y (storage/get-one t (aggregate/id @x))]
       (dosync (alter x update :counter inc))
      (t/is (= 1 (:counter @x) (:counter @y))))))

(t/deftest concurrency
  (let [test (storage/tx-create (build-test-entity))
        id   (aggregate/id test)
        n    10
        _    (->> (repeatedly #(future (storage/tx-alter test update :counter inc)))
                  (take n)
                  (doall)
                  (map deref)
                  (doall))
        test (storage/tx-get-one id)]
    (t/is (= n (:counter test)))))

(t/deftest inner-concurrency
  (let [test (storage/tx-create (build-test-entity))
        id   (aggregate/id test)
        n    10
        _    (storage/with-tx t
               (->> (repeatedly #(future (as-> id <>
                                           (storage/get-one t <>)
                                           (dosync (alter <> update :counter inc)))))
                    (take n)
                    (doall)
                    (map deref)
                    (doall)))
        test (storage/tx-get-one id)]
    (t/is (= n (:counter test)))))
