(ns publicator.domain.user
  (:require
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::login (s/and string? #(re-matches #"\w{3,255}" %)))
(s/def ::full-name (s/and string? #(re-matches #".{2,255}" %)))
(s/def ::password (s/and string? #(re-matches #".{8,255}" %)))
(s/def ::password-digest string?)

(s/def ::attrs (s/keys :req-un [::id ::login ::full-name ::password-digest]))
(s/def ::build-params (s/keys :req-un [::login ::full-name ::password]))

(defrecord User [id login full-name password-digest])

(defn build [{:keys [login full-name password]}]
  (let [id              (java.util.UUID/randomUUID)
        password-digest (hashers/derive password)]
    (map->User {:id              id
                :login           login
                :full-name       full-name
                :password-digest password-digest})))
