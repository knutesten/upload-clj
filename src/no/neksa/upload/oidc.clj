(ns no.neksa.upload.oidc
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clj-http.client :as client]
   [ring.util.response :refer [redirect response]]
   [cheshire.core :as json]))

(def config (let [config (-> "config.edn" slurp edn/read-string)]
              (merge
                config
                (-> "secret.edn" slurp edn/read-string)
                (:body (client/get (:oidc-configuration-endpoint config) {:as :json})))))

(defn server-url [req]
  (str (or (-> req :headers (get "x-forwarded-proto"))
           (name (:scheme req)))
       "://"
       (-> req :headers (get "host"))))

(defn callback-url [req]
  (str (server-url req) "/auth"))

(defn oauth-authorization-uri
  "Creates an authorization uri"
  [req state nonce code-challenge]
  (str (:authorization_endpoint config)
       "?state=" state
       "&nonce=" nonce
       "&redirect_uri=" (callback-url req)
       "&client_id=" (:client-id config)
       "&code_challenge=" code-challenge
       "&code_challenge_method=S256"
       "&response_type=code"
       "&scope=openid profile email"))

(defn fetch-access-token [req code code-verifier]
  (-> (client/post (:token_endpoint config)
                   {:accept        :json
                    :as            :json
                    :cookie-policy :standard
                    :form-params   {:grant_type    "authorization_code"
                                    :code_verifier code-verifier
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

(defn code->claims [req code code-verifier]
  (->> (fetch-access-token req code code-verifier)
       access-token->claims))

(defn authentication-handler [req]
  (let [code          (-> req :query-params (get "code"))
        state         (-> req :query-params (get "state"))
        session-state (-> req :session :state)
        nonce         (-> req :session :nonce)
        code-verifier (-> req :session :code-verifier)
        claims        (code->claims req code code-verifier)]
    (if (and (= state session-state)
             (= nonce (:nonce claims)))
      (-> (redirect "/")
          (assoc :session (with-meta
                            {:identity claims}
                            {:recreate true})))
      (-> (response (str "Invalid state " state " " session-state " or\n"
                         "invalid nonce " nonce " " (:nonce claims)))
          (assoc :status 401)))))

(defn random-string [len]
  (let [sample        "-._~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        sample-size   (count sample)
        secure-random (java.security.SecureRandom.)]
    (->> (repeatedly #(nth sample (.nextInt secure-random sample-size)))
         (take len)
         (apply str))))

(defn code-verifier->code-challenge [code-verifier]
  (let [encoder (java.util.Base64/getUrlEncoder)
        s256    (java.security.MessageDigest/getInstance "SHA-256")
        encoded (->> code-verifier
                     (.getBytes)
                     (.digest s256)
                     (.encodeToString encoder))]
    (str/replace encoded #"^(.*)=" "$1")))

(defn login-handler [req]
  (let [state          (random-string 40)
        nonce          (random-string 40)
        code-verifier  (random-string 100)
        code-challenge (code-verifier->code-challenge code-verifier)]
    (-> (redirect (oauth-authorization-uri req state nonce code-challenge))
        (assoc-in [:session :state] state)
        (assoc-in [:session :nonce] nonce)
        (assoc-in [:session :code-verifier] code-verifier))))

(defn logout-handler [req]
  (-> (redirect (str (:end_session_endpoint config)
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

(defn wrap-oidc-authentication [handler]
  (fn [req]
    (case (:uri req)
      "/auth"   (authentication-handler req)
      "/logout" (logout-handler req)
      (if (authenticated? req)
        (handler req)
        (login-handler req)))))

(defn wrap-oidc-authorization [handler]
  (fn [req]
    (if (authorized? req)
      (handler req)
      (-> (response "401 You do not have access to this page")
          (assoc :session nil)
          (assoc :status 401)))))

