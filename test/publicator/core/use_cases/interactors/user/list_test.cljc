(ns publicator.core.use-cases.interactors.user.list-test
  (:require
   [publicator.core.use-cases.interactors.user.list :as list]
   [publicator.core.domain.aggregate :as agg]
   [darkleaf.effect.core :as e]
   [darkleaf.effect.script :as script]
   [datascript.core :as d]
   [clojure.test :as t]))

(t/deftest process-success
  (let [users        [(agg/allocate {:db/ident             :root
                                     :agg/id               1
                                     :user/login           "alice"
                                     :user/password-digest "digest"
                                     :user/state           :active})
                      (agg/allocate {:db/ident             :root
                                     :agg/id               2
                                     :user/login           "john"
                                     :user/password-digest "digest"
                                     :user/state           :active
                                     :user/author?         true}
                                    {:author.translation/author     :root
                                     :author.translation/lang       :en
                                     :author.translation/first-name "John"
                                     :author.translation/last-name  "Doe"}
                                    {:author.translation/author     :root
                                     :author.translation/lang       :ru
                                     :author.translation/first-name "Иван"
                                     :author.translation/last-name  "Иванов"})]
        views        [{:agg/id              1
                       :user/login          "alice"
                       :user/state          :active
                       :control/can-update? false}
                      {:agg/id                        2
                       :user/login                    "john"
                       :user/state                    :active
                       :author.translation/first-name "John"
                       :author.translation/last-name  "Doe"
                       :control/can-update?           false}]
        script       [{:args []}
                      {:effect   [:persistence.user/asc-by-login]
                       :coeffect users}
                      {:effect   [:session/get]
                       :coeffect {}}
                      {:effect   [:session/get]
                       :coeffect {}}
                      {:effect   [:session/get]
                       :coeffect {}}
                      {:effect   [:session/get]
                       :coeffect {}}
                      {:final-effect [::list/->processed views]}]
        continuation (e/continuation list/process)]
    (script/test continuation script)))