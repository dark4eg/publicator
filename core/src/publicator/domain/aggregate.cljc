(ns publicator.domain.aggregate
  (:require
   [publicator.util :as u]
   [datascript.core :as d]
   [datascript.parser :as d.p]
   [clojure.core.match :as m]))

(defprotocol Aggregate
  :extend-via-metadata true
  (rules [agg])
  (validate [agg])
  (msg->tx [agg msg]))

(defn extend-schema [agg ext]
  (let [schema   (merge ext (:schema agg))
        metadata (meta agg)]
    (-> agg
        (d/datoms :eavt)
        (d/init-db schema)
        (with-meta metadata))))

(defn decorate [agg decorators-map]
  (vary-meta agg #(merge-with (fn [f decorator] (partial decorator f))
                              % decorators-map)))

(defn- rules-impl [agg]
  '[[(root ?e)
     [?e :db/ident :root]]])

(defn- validate-impl [agg]
  agg)

(defn- msg->tx-impl [agg msg]
  (m/match msg
    [a :add e v] [[:db/add e a v]]
    [a :retract e v] [[:db/retract e a v]]
    [a :retract e] [[:db.fn/retractAttribute e a]]))

(def blank
  (-> (d/empty-db)
      (d/db-with [[:db/add 1 :db/ident :root]])
      (with-meta
        {`rules    #'rules-impl
         `validate #'validate-impl
         `msg->tx  #'msg->tx-impl})))

(defn root [agg]
  (d/entity agg :root))

(def ^{:arglists '([agg tx-data])} with d/db-with)

(defn- normalize-query [query]
  (cond
    (map? query)        query
    (sequential? query) (d.p/query->map query)))

(defn q [agg query & inputs]
  (let [query  (normalize-query query)
        query  (update query :in (fn [in] (concat '[$ %] in)))
        inputs (concat [agg (rules agg)] inputs)]
    (apply d/q query inputs)))

(defn with-msgs [agg msgs]
  (let [tx-data (map (fn [msg] [:db.fn/call msg->tx msg])
                     msgs)]
    (with agg tx-data)))

(defn has-errors? [agg]
  (boolean
   (not-empty
    (q agg '{:find [[?e ...]]
             :where [[?e :error/type _]]}))))

(defn has-no-errors? [agg]
  (not (has-errors? agg)))

(defn errors [agg]
  (let [errs (q agg '[:find [(pull ?e [*]) ...]
                      :where [?e :error/type _]])]
    (->> errs
         (map #(dissoc % :db/id))
         (set))))

(defn- normalize-rule-form [rule-or-form]
  (cond
    (symbol? rule-or-form) (list rule-or-form '?e)
    (list? rule-or-form)   rule-or-form))

(defn required-validator [agg rule-or-form attrs]
  (let [rule-form (normalize-rule-form rule-or-form)
        query     '{:find  [?e ?a]
                    :in    [[?a ...]]
                    :where [[(missing? $ ?e ?a)]]}
        query     (update query :where #(into [rule-form] %))
        data      (q agg query attrs)
        tx-data   (for [[e a] data]
                    {:error/type   :required
                     :error/entity e
                     :error/attr   a
                     :error/rule   (first rule-form)})]
    (with agg tx-data)))

(defn- apply-predicate [p val]
  (cond
    (ifn? p)
    (p val)

    (u/regexp? p)
    (and (string? val)
         (re-matches p val))))

(defn predicate-validator [agg rule-or-form pred-map]
  (if (has-errors? agg)
    agg
    (let [rule-form (normalize-rule-form rule-or-form)
          query     '{:find  [?e ?a ?v ?pred]
                      :in    [?apply [[?a ?pred]]]
                      :where [[?e ?a ?v]
                              (not [(?apply ?pred ?v)])]}
          query     (update query :where #(into [rule-form] %))
          data      (q agg query apply-predicate pred-map)
          tx-data   (for [[e a v pred] data]
                      {:error/type   :predicate
                       :error/entity e
                       :error/attr   a
                       :error/value  v
                       :error/pred   pred
                       :error/rule   (first rule-form)})]
      (with agg tx-data))))

(defn query-validator [agg rule-or-form query predicate]
  (if (has-errors? agg)
    agg
    (let [rule-form (normalize-rule-form rule-or-form)
          entities  (q agg [:find '[?e ...] :where rule-form])
          query     (-> query
                        (normalize-query)
                        (assoc :in '[?e]))
          tx-data   (for [e    entities
                          :let [res (q agg query e)]]
                      (if (not (predicate res))
                        {:error/type      :query
                         :error/entity    e
                         :error/result    res
                         :error/predicate predicate
                         :error/rule      (first rule-form)
                         :error/query     query}))]
      (with agg tx-data))))
