(ns ow.chatterbox.middleware.iframe
  (:require [ring.util.response :refer [response resource-response status]]))

(defn wrap-allow-iframe [handler]
  (fn [req]
    (let [res (handler req)
          headers (:headers res)]
      (assoc res :headers (dissoc headers "X-Frame-Options")))))
