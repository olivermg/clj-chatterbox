(ns ow.chatterbox.core
  (:require [org.httpkit.server :refer [with-channel] :as srv]
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
            [ow.chatterbox.middleware.exceptions :refer [wrap-exceptions]]))

(defn- random-string [len]
  (let [alphabet (str "abcdefghijklmnopqrstuvwxyz"
                      "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                      "0123456789")]
    (->> #(rand-nth alphabet)
         repeatedly
         (take len)
         (apply str))))

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

(defn wrap-app [{{site-handler :handler :as site-cfg} :site
                 {api-handler :handler api-prefix :prefix :as api-cfg} :api
                 {ws-handler :handler ws-prefix :prefix :as ws-cfg} :ws
                 default-cfg :default
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

  (let [wrapped-site-handler (and site-handler (wrap-site site-handler (merge default-cfg site-cfg)))
        wrapped-api-handler (and api-handler (wrap-api api-handler (merge default-cfg api-cfg)))
        wrapped-ws-handler (and ws-handler (wrap-ws ws-handler (merge default-cfg ws-cfg)))

        api-pattern (and api-prefix (re-pattern (str "^" api-prefix)))
        ws-pattern (and ws-prefix (re-pattern (str "^" ws-prefix)))]

    (fn [{:keys [uri] :as req}]
      (condp #(and %1 %2 (re-find %1 %2)) uri
        ws-pattern (wrapped-ws-handler req)
        api-pattern (wrapped-api-handler req)
        (when wrapped-site-handler
          (wrapped-site-handler req))))))


(comment {:default {:dev? true}
          :site {:handler (fn [req] {:status 200})
                 :session-store (memory-store)
                 :unauthorized-redirect "/login"}
          :api {:handler (fn [req] {:status 201})
                :prefix "/api/"
                :jws-secret "foosecret"}
          :ws {:handler (fn [req] {:status 202})
               :prefix "/ws/"
               :jws-secret "barsecret"}})
