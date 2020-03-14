(ns publicator.core.use-cases.stream.list-test
  (:require
   [publicator.core.domain.aggregate :as agg]
   [publicator.core.use-cases.stream.list :as list]
   [clojure.test :as t]
   [darkleaf.effect.core :as e]
   [darkleaf.effect.script :as script]
   [datascript.core :as d]))

(t/deftest process-success-with-admin
  (let [script       [{:args []}
                      {:effect   [:persistence.stream/active]
                       :coeffect [(-> (agg/allocate)
                                      (d/db-with [{:db/ident     :root
                                                   :agg/id       1
                                                   :stream/state :active}
                                                  {:stream.translation/stream :root
                                                   :stream.translation/lang   :en
                                                   :stream.translation/name   "Stream"}
                                                  {:stream.translation/stream :root
                                                   :stream.translation/lang   :ru
                                                   :stream.translation/name   "Поток"}]))]}
                      {:effect   [:session/get]
                       :coeffect {:current-user-id 1}}
                      {:effect   [:session/get]
                       :coeffect {:current-user-id 1}}
                      {:effect   [:persistence.user/get-by-id 1]
                       :coeffect (-> (agg/allocate)
                                     (d/db-with [{:db/ident             :root
                                                  :agg/id               1
                                                  :user/login           "admin"
                                                  :user/password-digest "digest"
                                                  :user/state           :active
                                                  :user/role            :admin}]))}
                      {:final-effect [:ui.screen/show :streams [{:agg/id           1
                                                                 :stream.view/name "Поток"
                                                                 :ui/can-edit?     true}]]}]
        continuation (e/continuation list/process)]
    (script/test continuation script)))
