(ns ow.chatterbox.core
  (:require [com.stuartsierra.component :as c]
            [ow.chatterbox.webserver :as srv]))

(defn webapp [cfg]
  (c/system-map

   :webserver (c/using
               (srv/webserver (:webserver cfg))
               [])))


(comment

  (require '[buddy.sign.jwt :as jwt]
           '[buddy.auth :refer [authenticated? throw-unauthorized]]
           '[clojure.core.async :refer [go-loop <!]])

  (def cfg1 {:webserver (let [jsecret-api "foofoo"
                  jsecret-ws "barbar"
                  ssecret "xyzxyz0123456789" ;; must be 16 bytes
                  userid {:user 123
                          :scope :web}

                  site-handler (fn [req]
                                 (println "site")
                                 (case (:uri req)
                                   "/login" {:status 200
                                             :session (assoc (:session req) :identity userid)}
                                   "/authed" (do (when (not (authenticated? req))
                                                   (throw-unauthorized {:message "not authorized for site"}))
                                                 {:status 200
                                                  :body "private site content"})
                                   {:status 200
                                    :body "public site content"}))

                  api-handler (fn [req]
                                (println "api")
                                (case (:uri req)
                                  "/api/login" {:status 200
                                                :body (str "{ \"token\": \""
                                                           (jwt/sign userid jsecret-api)
                                                           "\" }")
                                                :headers {"Content-Type" "application/json"}}
                                  "/api/authed" (do (when (not (authenticated? req))
                                                      (throw-unauthorized {:message "not authorized for api"}))
                                                    {:status 200
                                                     :body "private api content"
                                                     :headers {"Content-Type" "application/json"}})
                                  {:status 200
                                   :body "public api content"
                                   :headers {"Content-Type" "application/json"}}))

                  ws-handler-setup (fn [ch-recv send-fn connected-uids]
                                     (println "ws setup")
                                     (go-loop [{:keys [?reply-fn] :as req} (<! ch-recv)]
                                       (println "got ws request:" req)
                                       (when ?reply-fn
                                         (?reply-fn {:foo "bar"})
                                         (recur (<! ch-recv)))))]

              {:server {:port 8899}
               :all {:dev? true}
               :site {:handler site-handler
                      :unauthorized-redirect "/login"
                      :cookie-session-secret ssecret}
               :api {:handler api-handler
                     :prefix "/api/"
                     :jws-secret jsecret-api}
               :ws {:handler-setup ws-handler-setup
                    :prefix "/ws"}})})

  (def wa1 (webapp cfg1))

  (def wa1 (c/start wa1))

  (def wa1 (c/stop wa1))

  )
