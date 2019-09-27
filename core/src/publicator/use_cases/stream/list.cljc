(ns publicator.use-cases.stream.list
  (:require
   [publicator.util :as u]
   [publicator.domain.aggregate :as agg]
   [publicator.use-cases.stream.edit :as edit]))

(defn- stream->view [stream]
  (u/linearize
   [[:session/get] (fn [session] <>)]
   (let [lang (get session :lang :ru)])
   [[:sub/return
     {:agg/id           (agg/q stream '[:find ?id .
                                        :where
                                        [:root :agg/id ?id]])
      :stream.view/name (agg/q stream '[:find ?name .
                                        :in ?lang
                                        :where
                                        [?t :stream.translation/stream :root]
                                        [?t :stream.translation/lang ?lang]
                                        [?t :stream.translation/name ?name]]
                               lang)}]]))

(defn precondition [_]
  [[:sub/return]])

(defn process [_]
  (u/linearize
   (u/sub precondition nil (fn [ex-effect] <>))
   (or ex-effect)
   [[:persistence/active-streams] (fn [streams] <>)]
   [[:next [[] streams]] (fn loop [[acc streams]] <>)]
   (if (empty? streams)
     [[:ui/render-streams acc] nil])
   (let [[stream & rest] streams
         id              (-> stream agg/root :agg/id)])
   (u/sub stream->view stream (fn [view] <>))
   (u/sub edit/precondition id (fn [ex-effect] <>))
   (let [view (assoc view :ui/can-edit? (nil? ex-effect))])
   [[:next [(conj acc view) rest]] loop]))
