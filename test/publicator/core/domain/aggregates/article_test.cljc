(ns publicator.core.domain.aggregates.article-test
  (:require
   [publicator.core.domain.aggregate :as agg]
   [clojure.test :as t]))

(t/deftest has-no-errors
  (let [agg (-> (agg/allocate :agg/article)
                (agg/apply-tx [{:db/ident              :root
                                :publication/state     :active
                                :publication/stream-id 1
                                :article/image-url     "http://cats.com/cat.jpg"}
                               {:publication.translation/publication  :root
                                :publication.translation/lang         :ru
                                :publication.translation/state        :published
                                :publication.translation/title        "some title"
                                :publication.translation/summary      "some summary"
                                :publication.translation/published-at #inst "2019-01-01"
                                :article.translation/content          "some content"}
                               {:publication.related/publication :root
                                :publication.related/id          1
                                :publication.related/type        :article}])
                (agg/validate))]
    (t/is (agg/has-no-errors? agg))))