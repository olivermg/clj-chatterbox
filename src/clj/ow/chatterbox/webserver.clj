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
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
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
                  {ws-handler-setup :handler-setup ws-prefix :prefix :as ws-cfg} :ws
                  all-cfg :all
                  :as cfg}
                 {:keys [ajax-post-fn ajax-get-or-ws-handshake-fn ch-recv send-fn connected-uids]
                  :as senteobjs}]

  {:pre [(if site-handler
           (fn? site-handler)
           true)
         (if (or api-handler api-prefix)
           (fn? api-handler)
           true)
         (if (or ws-handler-setup ws-prefix)
           (fn? ws-handler-setup)
           true)]
   :post (fn? %)}

  (let [wrapped-site-handler (and site-handler (wrap-site site-handler (merge all-cfg site-cfg)))
        wrapped-api-handler (and api-handler (wrap-api api-handler (merge all-cfg api-cfg)))
        ws-handler (fn [{:keys [request-method] :as req}]
                     (case request-method
                       :get (ajax-get-or-ws-handshake-fn req)
                       :post (ajax-post-fn req)))

        api-pattern (and api-prefix (re-pattern (str "^" api-prefix)))
        ws-pattern (and ws-prefix (re-pattern (str "^" ws-prefix)))]

    ;;; setup ws async listener:
    (when ws-handler-setup
      (ws-handler-setup ch-recv send-fn connected-uids))

    (fn [{:keys [uri] :as req}]
      (condp #(and %1 %2 (re-find %1 %2)) uri
        ws-pattern (ws-handler req)
        api-pattern (wrapped-api-handler req)
        (when wrapped-site-handler
          (wrapped-site-handler req))))))

(defrecord Webserver [cfg
                      server senteobjs]

  c/Lifecycle

  (start [this]
    (if-not server
      (let [senteobjs (sente/make-channel-socket! (get-sch-adapter) {})
            app (wrap-app cfg senteobjs)
            srv (hsrv/run-server app (merge {:worker-name-prefix "http-worker-"}
                                            (:server cfg)))]
        (assoc this :server srv :senteobjs senteobjs))
      this))

  (stop [this]
    (if server
      (do (server :timeout 20000)
          (dissoc this :server :senteobjs))
      this)))

(defn webserver [cfg]
  (map->Webserver {:cfg cfg}))


(comment

  (require '[buddy.sign.jwt :as jwt]
           '[buddy.auth :refer [authenticated? throw-unauthorized]]
           '[clojure.core.async :refer [go-loop <!]])

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
                    :prefix "/ws"}}))

  (def ws1 (webserver cfg1))

  (def ws1 (c/start ws1))

  (def ws1 (c/stop ws1))

  )
