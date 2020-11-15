(ns no.neksa.upload.keycloak
  (:require
   [clojure.edn :as edn]
   [clj-http.client :as client]
   [ring.util.response :refer [redirect response]]
   [cheshire.core :as json]))

(def config (merge (-> "config.edn" slurp edn/read-string)
                   (-> "secret.edn" slurp edn/read-string)))

(defn server-url [req]
  (str (name (:scheme req)) "://" (:server-name req) ":" (:server-port req)))

(defn callback-url [req]
  (str (server-url req) "/auth"))

(defn oauth-authorization-uri
  "Creates an authorization uri"
  [req state]
  (str (:authorize-uri config)
       "?state=" state
       "&redirect_uri=" (callback-url req)
       "&client_id=" (:client-id config)
       "&response_type=code"
       "&scope=openid profile email"))

(defn fetch-access-token [req code]
  (-> (client/post (:token-uri config)
                   {:accept        :json
                    :as            :json
                    :cookie-policy :standard
                    :form-params   {:grant_type    "authorization_code"
                                    :client_id     (:client-id config)
                                    :client_secret (:client-secret config)
                                    :code          code
                                    :redirect_uri  (callback-url req)}})
      :body
      :access_token))

(defn access-token->claims [token]
  (as-> token %
    (clojure.string/split % #"\.")
    (nth % 1)
    (.decode (java.util.Base64/getDecoder) %)
    (String. %)
    (json/parse-string % true)))

(defn code->claims [req code]
  (->> code
       (fetch-access-token req)
       access-token->claims))

(defn authentication-handler [req]
  (let [code          (-> req :query-params (get "code"))
        state         (-> req :query-params (get "state"))
        session-state (-> req :session :state)]
    (if (= state session-state)
      (-> (redirect "/")
          (assoc :session (with-meta
                            {:identity (code->claims req code)}
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

(defn login-handler [req]
  (let [state (random-string 40)]
    (-> (redirect (oauth-authorization-uri req state))
        (assoc-in [:session :state] state))))

(defn logout-handler [req]
  (-> (redirect (str (:logout-uri config)
                     "?redirect_uri="
                     (server-url req)))
      (assoc :session nil)))

(defn authenticated? [req]
  (-> req :session :identity some?))

(defn authorized? [req]
  (->> req
       :session
       :identity
       :realm_access
       :roles
       (some #(= % "upload"))))

(defn wrap-keycloak-authentication [handler]
  (fn [req]
    (case (:uri req)
      "/auth"   (authentication-handler req)
      "/logout" (logout-handler req)
      (if (authenticated? req)
        (handler req)
        (login-handler req)))))

(defn wrap-keycloak-authorization [handler]
  (fn [req]
    (if (authorized? req)
      (handler req)
      (-> (response "401 You do not have access to this page")
          (assoc :session nil)
          (assoc :status 401)))))

