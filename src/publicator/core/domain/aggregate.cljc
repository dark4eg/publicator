(ns publicator.core.domain.aggregate
  (:require
   [publicator.util :as u]
   [datascript.core :as d]
   [datascript.parser :as d.p]
   [darkleaf.multidecorators :as md]))

(defn- rules-initial [type]
  '[[(root ?e)
     [?e :db/ident :root]]])

(defn- validate-initial [agg]
  (d/db-with agg [[:db.fn/call (fn clear-errors [agg]
                                 (for [error (d/q '[:find [?e ...] :where [?e :error/type _]] agg)]
                                   [:db.fn/retractEntity error]))]]))

(defn- schema-initial [type]
  {:error/entity { :db/valueType :db.type/ref}})

(defn- allowed-attribute-initial [type attr]
  (#{"db" "error"} (namespace attr)))

(defonce rules (md/multi identity #'rules-initial))
(defonce validate (md/multi u/type #'validate-initial))
(defonce schema (md/multi identity #'schema-initial))
(defonce allowed-attribute? (md/multi (fn [type attr] type) #'allowed-attribute-initial))

(def datom d/datom)

(defn apply-tx [agg tx-data]
  (let [agg-type   (u/type agg)
        result     (d/with agg tx-data)
        agg        (:db-after result)
        datoms     (:tx-data result)
        additional (remove (fn [{:keys [a]}]
                             (allowed-attribute? agg-type a))
                           datoms)]
    (if (seq additional)
      (throw (ex-info "Additional datoms" {:additional additional})))
    agg))

(defn allocate [type]
  (-> (d/empty-db (schema type))
      (with-meta {:type type})
      (d/db-with [[:db/add 1 :db/ident :root]])))

(defn becomes [agg type]
  (let [new-agg (allocate type)
        datoms (->> (d/datoms agg :eavt)
                    (filter (fn [{:keys [a]}]
                              (allowed-attribute? type a))))]
    (d/db-with new-agg datoms)))

(defn root [agg]
  (d/entity agg :root))

(defn- normalize-query [query]
  (cond
    (map? query)        query
    (sequential? query) (d.p/query->map query)))

(defn q [agg query & inputs]
  (let [query  (normalize-query query)
        query  (update query :in (fn [in] (concat '[$ %] in)))
        inputs (concat [agg (-> agg u/type rules)] inputs)]
    (apply d/q query inputs)))

(defn has-errors? [agg]
  (q agg '[:find ?e .
           :where [?e :error/entity _]]))

(defn has-no-errors? [agg]
  (not (has-errors? agg)))

(defn check-errors [agg]
  (if (has-errors? agg)
    (throw (ex-info "Invalid aggregate"
                    {:agg agg}))
    agg))

(defn- normalize-rule-form [rule-or-form]
  (cond
    (symbol? rule-or-form) (list rule-or-form '?e)
    (list? rule-or-form)   rule-or-form))

(defn ^{:style/indent :defn} required-validator [agg rule-or-form attrs]
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
    (apply-tx agg tx-data)))

(defprotocol Predicate
  (apply-predicate [p x])
  (predicate-as-data [p]))

(defn ^{:style/indent :defn} predicate-validator [agg rule-or-form pred-map]
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
                       :error/pred   (predicate-as-data pred)
                       :error/rule   (first rule-form)})]
      (apply-tx agg tx-data))))

(defn ^{:style/indent :defn} query-validator [agg rule-or-form query predicate]
  (if (has-errors? agg)
    agg
    (let [rule-form (normalize-rule-form rule-or-form)
          entities  (q agg [:find '[?e ...] :where rule-form])
          query*    (-> query
                        (normalize-query)
                        (assoc :in '[?e]))
          tx-data   (for [e    entities
                          :let [res (q agg query* e)]]
                      (if (not (predicate res))
                        (cond-> {:error/type   :query
                                 :error/entity e
                                 :error/pred   (predicate-as-data predicate)
                                 :error/rule   (first rule-form)
                                 :error/query  query}
                          (some? res) (assoc :error/result res))))]
      (apply-tx agg tx-data))))

(extend-protocol Predicate
  #?(:clj  clojure.lang.PersistentHashSet
     :cljs cljs.core/PersistentHashSet)
  (apply-predicate [p x] (p x))
  (predicate-as-data [p] p)

  #?(:clj  clojure.lang.Var
     :cljs cljs.core/Var)
  (apply-predicate [p x] (p x))
  (predicate-as-data [p] (symbol p))

  #?(:clj  java.util.regex.Pattern
     :cljs js/RegExp)
  (apply-predicate [p x]
    (and (string? x)
         (re-matches p x)))
  (predicate-as-data [p] (str p)))
