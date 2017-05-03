(ns ow.chatterbox.webserver
  (:require [com.stuartsierra.component :as c]
            [org.httpkit.server :refer [with-channel] :as hsrv]
            [ring.middleware.defaults :refer [wrap-defaults
                                              site-defaults api-defaults
                                              secure-site-defaults secure-api-defaults]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            #_[ring.middleware.session.memory :refer [memory-store]]
            [ring.util.response :refer [response redirect]]
            [ring.logger :refer [wrap-with-logger]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.auth.backends :as backends]
            [ow.chatterbox.middleware.exceptions :refer [wrap-exceptions]]
            [ow.chatterbox.util :refer [random-string]]))

(defn- wrap-site [handler {:keys [dev? session-store cookie-session-secret
                                  unauthorized-redirect]
                           :as cfg}]
  (let [session-store (or session-store
                          (cookie-store {:key (or (and (not-empty cookie-session-secret)
                                                       cookie-session-secret)
                                                  (random-string 16))}))
        auth-backend-options (or (and unauthorized-redirect
                                      [{:unauthorized-handler (fn [req metadata]
                                                                (redirect unauthorized-redirect))}])
                                 [])
        auth-backend (apply backends/session auth-backend-options)]
    (-> handler
        (wrap-authentication auth-backend)
        (wrap-authorization auth-backend)
        (wrap-defaults (-> (if dev?
                             site-defaults
                             (assoc secure-site-defaults :proxy true))
                           (assoc-in [:session :store] session-store)))
        (wrap-exceptions :expose-errors? dev?)
        wrap-with-logger
        wrap-gzip)))

(defn- wrap-api [handler {:keys [dev? jws-secret] :as cfg}]
  (let [jws-secret (or (and (not-empty jws-secret) jws-secret)
                       (random-string 32))
        auth-backend (backends/jws {:secret jws-secret})]
    (-> handler
        (wrap-authentication auth-backend)
        (wrap-authorization auth-backend)
        (wrap-defaults (-> (if dev?
                             api-defaults
                             (assoc secure-api-defaults :proxy true))))
        ;;;(wrap-exceptions :expose-errors? dev?)
        wrap-with-logger
        wrap-gzip)))

(def ^:private wrap-ws wrap-api)

(defn- wrap-app [{{site-handler :handler :as site-cfg} :site
                  {api-handler :handler api-prefix :prefix :as api-cfg} :api
                  {ws-handler :handler ws-prefix :prefix :as ws-cfg} :ws
                  all-cfg :all
                  :as cfg}]

  {:pre [(if site-handler
           (fn? site-handler)
           true)
         (if (or api-handler api-prefix)
           (fn? api-handler)
           true)
         (if (or ws-handler ws-prefix)
           (fn? ws-handler)
           true)]
   :post (fn? %)}

  (let [wrapped-site-handler (and site-handler (wrap-site site-handler (merge all-cfg site-cfg)))
        wrapped-api-handler (and api-handler (wrap-api api-handler (merge all-cfg api-cfg)))
        wrapped-ws-handler (and ws-handler (wrap-ws ws-handler (merge all-cfg ws-cfg)))

        api-pattern (and api-prefix (re-pattern (str "^" api-prefix)))
        ws-pattern (and ws-prefix (re-pattern (str "^" ws-prefix)))]

    (fn [{:keys [uri] :as req}]
      (condp #(and %1 %2 (re-find %1 %2)) uri
        ws-pattern (wrapped-ws-handler req)
        api-pattern (wrapped-api-handler req)
        (when wrapped-site-handler
          (wrapped-site-handler req))))))

(defrecord Webserver [cfg
                      server]

  c/Lifecycle

  (start [this]
    (if-not server
      (let [app (wrap-app cfg)
            srv (hsrv/run-server app (:server cfg))]
        (assoc this :server srv))
      this))

  (stop [this]
    (if server
      (do (server :timeout 20000)
          (dissoc this :server))
      this)))

(defn webserver [cfg]
  (map->Webserver {:cfg cfg}))


(comment

  (require '[buddy.sign.jwt :as jwt]
           '[buddy.auth :refer [authenticated? throw-unauthorized]])

  (def cfg1 (let [jsecret-api "foofoo"
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

                            ws-handler (fn [req]
                                         (println "ws")
                                         {:status 200
                                          :body "public ws content"})]

                        {:server {:port 8899}
                         :all {:dev? true}
                         :site {:handler site-handler
                                :unauthorized-redirect "/login"
                                :cookie-session-secret ssecret}
                         :api {:handler api-handler
                               :prefix "/api/"
                               :jws-secret jsecret-api}
                         :ws {:handler ws-handler
                              :prefix "/ws/"
                              :jws-secret jsecret-ws}}))

  (def ws1 (webserver cfg1))

  (def ws1 (c/start ws1))

  (def ws1 (c/stop ws1))

  )
