(ns publicator.use-cases.user.log-in
  (:require
   [publicator.domain.aggregate :as agg]
   [publicator.util :as u]
   [darkleaf.multidecorators :as md]
   [publicator.domain.aggregates.user :as user]))

(md/decorate agg/validate :form.user/log-in
  (fn [super agg]
    (-> (super agg)
        (agg/predicate-validator 'root
          {:user/login    #"\w{3,255}"
           :user/password #".{8,255}"})
        (agg/required-validator  'root
          #{:user/login
            :user/password}))))

(def allowed-attrs #{:user/login :user/password})

(defn- check-additional-attrs [datoms]
  (let [additional (->> datoms
                        (map :a)
                        (remove allowed-attrs)
                        (set))]
    (when (not-empty additional)
      [[:ui/show-additional-attributes-error additional]])))

(defn- has-validation-errors [form]
  (let [errors (-> form agg/validate agg/errors)]
    (when (not-empty errors)
      [[:ui/show-validation-errors errors]])))

(defn- fetch-user-by-login [login]
  (u/linearize
   [[:persistence/user-by-login login]
    (fn [user] <>)]
   (if (nil? user)
     [[:ui/show-user-not-found-error]])
   [[:sub/return user]]))

(defn- check-user-password [[user password]]
  (u/linearize
   (let [digest (-> user agg/root :user/password-digest)])
   [[:hasher/check password digest]
    (fn [ok] <>)]
   (if-not ok
     [[:ui/show-user-not-found-error]])
   [[:sub/return]]))

(defn precondition [_]
  (u/linearize
   [[:session/get] (fn [session] <>)]
   (if (-> session :current-user-id some?)
     [[:sub/return [[:ui/show-main-screen]]]])
   [[:sub/return]]))

(defn process [tx-data]
  (u/linearize
   (u/sub precondition nil (fn [ex-effect] <>))
   (or ex-effect)
   (let [[form datoms] (-> :form.user/log-in agg/allocate (agg/apply-tx* tx-data))])
   (or (check-additional-attrs datoms))
   (or (has-validation-errors form))
   (let [form-root (agg/root form)
         login     (:user/login form-root)
         password  (:user/password form-root)])
   (u/sub fetch-user-by-login login (fn [user] <>))
   (u/sub check-user-password [user password] (fn [_] <>))
   (if-not (user/active? user)
     [[:ui/show-main-screen]])
   (let [id (-> user agg/root :agg/id)])
   [[:do
     [:session/assoc :current-user-id id]
     [:ui/show-main-screen]]]))
