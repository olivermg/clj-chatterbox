(ns ow.chatterbox.middleware.exceptions
  (:require [ring.util.response :refer [response status]]))

(defn- handle-ex [e & {:keys [expose-errors?]}]
  (println "EXCEPTION in web stack:" (pr-str e))
  (status (response (or (and expose-errors? (.getMessage e))
                        "internal server error"))
          (or (-> e ex-data :status)
              500)))

(defn wrap-exceptions [handler & {:keys [expose-errors?]}]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (handle-ex e :expose-errors? expose-errors?))
      (catch Error e
        (handle-ex e :expose-errors? expose-errors?)))))
