(ns ow.chatterbox.middleware.merge-request)

(defn wrap-merge-request [handler m]
  (fn [req]
    (handler (merge req m))))
