(ns ow.chatterbox.middleware.p3p
  (:require [ring.util.response :refer [response status]]))

(defn wrap-p3p [handler]
  (fn [req]
    (update-in (handler req) [:headers]
               #(assoc % "P3P" (str "CP=\"This is not a P3P policy. We only store data required "
                                    "to fulfill the business processes you request from our site.\"")))))
