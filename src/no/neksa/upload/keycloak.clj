(ns no.neksa.upload.keycloak
  (:require
   [clojure.edn :as edn]
   [clj-http.client :as client]
   [ring.util.response :refer [redirect response]]))

(def config (merge (-> "config.edn" slurp edn/read-string)
                   (-> "secret.edn" slurp edn/read-string)))

(defn oauth-authorization-uri
  "Creates an authorization uri"
  [state]
  (str (:authorize-uri config)
       "?state=" state
       "&redirect_uri=" (:callback-uri config)
       "&client_id=" (:client-id config)
       "&response_type=code"
       "&scope=openid profile email"))

(defn fetch-access-token [code]
  (-> (client/post (:token-uri config)
                   {:accept        :json
                    :as            :json
                    :cookie-policy :standard
                    :form-params   {:grant_type    "authorization_code"
                                    :client_id     (:client-id config)
                                    :client_secret (:client-secret config)
                                    :code          code
                                    :redirect_uri  (:callback-uri config)}})
      :body
      :access_token))

(defn fetch-user-info [access-token]
  (-> (client/get (:user-info-uri config)
                  {:accept        :json
                   :as            :json
                   :cookie-policy :standard
                   :headers       {"Authorization" (str "Bearer " access-token)}})
      :body))

(defn exchange-code-with-user-info [code]
  (-> code
      fetch-access-token
      fetch-user-info))

(defn authentication-handler [req]
  (let [code          (-> req :query-params (get "code"))
        state         (-> req :query-params (get "state"))
        session-state (-> req :session :state)]
    (if (= state session-state)
      (-> (redirect "/")
          (assoc :session (with-meta
                            {:identity (exchange-code-with-user-info code)}
                            {:recreate true})))
      (-> (response (str "Invalid state " state " " session-state))
          (assoc :status 401)))))

(defn random-string [len]
  (let [sample        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        sample-size   (count sample)
        secure-random (java.security.SecureRandom.)]
    (->> (repeatedly #(nth sample (.nextInt secure-random sample-size)))
         (take len)
         (apply str))))

(defn login-handler []
  (let [state (random-string 40)]
    (-> (redirect (oauth-authorization-uri state))
        (assoc-in [:session :state] state))))

(defn logout-handler [req]
  (-> (redirect (str (:logout-uri config)
                     "?redirect_uri="
                     (-> req :headers (get "referer"))))
      (assoc :session nil)))

(defn wrap-keycloak [handler]
  (fn [req]
    (case (:uri req)
      "/auth"   (authentication-handler req)
      "/logout" (logout-handler req)
      (if (-> req :session :identity)
        (handler req)
        (login-handler)))))

