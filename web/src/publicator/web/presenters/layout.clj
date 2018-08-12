(ns publicator.web.presenters.layout
  (:require
   [publicator.use-cases.services.user-session :as user-session]
   [publicator.web.url-helpers :as url-helpers]
   [ring.middleware.anti-forgery :as anti-forgery]))

(defn present [req]
  (cond-> {:csrf anti-forgery/*anti-forgery-token*}
    (user-session/logged-in?)
    (assoc :log-out {:text   "Log out"
                     :url    (url-helpers/path-for :user.log-out/handler)})

    (user-session/logged-out?)
    (assoc :register {:text "Register"
                      :url  (url-helpers/path-for :user.register/form)})

    (user-session/logged-out?)
    (assoc :log-in {:text "Log in"
                    :url  (url-helpers/path-for :user.log-in/form)})))
