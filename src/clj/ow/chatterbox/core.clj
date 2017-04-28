(ns ow.chatterbox.core
  (:require [org.httpkit.server :refer [with-channel] :as srv]
            [ring.middleware.defaults :refer [wrap-defaults
                                              site-defaults api-defaults
                                              secure-site-defaults secure-api-defaults]]
            #_[ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.session.memory :refer [memory-store]]
            [ring.logger :refer [wrap-with-logger]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ow.chatterbox.middleware.exceptions :refer [wrap-exceptions]]))

(defn- wrap-site [handler {:keys [dev? sessionkey] :as cfg}]
  (let [#_cookie-store-args #_(if sessionkey
                            [{:key sessionkey}]
                            [])]
    (-> handler
        (wrap-defaults (-> (if dev?
                             site-defaults
                             (assoc secure-site-defaults :proxy true))
                           (assoc-in [:session :store]
                                     #_(cookie-store cookie-store-args)
                                     (memory-store))))
        wrap-with-logger
        (wrap-exceptions :expose-errors? dev?)
        wrap-gzip)))

(defn- wrap-api [handler cfg]
  (-> handler
      wrap-with-logger
      wrap-gzip))

(defn- wrap-ws [handler cfg]
  (-> handler
      wrap-with-logger
      wrap-gzip))

(defn wrap-app [{:keys [site-handler api-handler ws-handler
                        api-prefix ws-prefix]
                 :as cfg}]

  {:pre [(if site-handler
           (fn? site-handler)
           true)
         (if (or api-handler api-prefix)
           (and (fn? api-handler)
                (not-empty api-prefix))
           true)
         (if (or ws-handler ws-prefix)
           (and (fn? ws-handler)
                (not-empty ws-prefix))
           true)]
   :post (fn? %)}

  (let [wrapped-site-handler (and site-handler (wrap-site site-handler cfg))
        wrapped-api-handler (and api-handler (wrap-api api-handler cfg))
        wrapped-ws-handler (and ws-handler (wrap-ws ws-handler cfg))

        api-pattern (and api-prefix (re-pattern (str "^" api-prefix)))
        ws-pattern (and ws-prefix (re-pattern (str "^" ws-prefix)))]

    (fn [{:keys [uri] :as req}]
      (condp #(and %1 %2 (re-find %1 %2)) uri
        ws-pattern (wrapped-ws-handler req)
        api-pattern (wrapped-api-handler req)
        (when wrapped-site-handler
          (wrapped-site-handler req))))))
