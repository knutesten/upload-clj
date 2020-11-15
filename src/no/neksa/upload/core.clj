(ns no.neksa.upload.core
  (:require
   [no.neksa.upload.keycloak :refer [wrap-keycloak-authentication wrap-keycloak-authorization]]
   [hiccup.page :refer [html5]]
   [compojure.core :refer :all]
   [compojure.route :as route]
   [clojure.java.io :as io]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.anti-forgery :refer [anti-forgery-field]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
   [ring.util.response :refer [redirect response file-response]]
   [mount.core :refer [defstate start]]))

(defn file-elem [f]
  [:form {:action (str "/delete/" (.getName f)) :method "post"}
   (anti-forgery-field)
   [:a {:href (str f)} (.getName f)]
   "&nbsp;"
   [:button {:type "submit"} "X"]])

(defn file-list []
  [:div
   [:b "Files"]
   [:br]
   (->> (io/file "./uploads")
        (file-seq)
        (filter #(.isFile %))
        (map file-elem))])

(defn upload-file []
  [:form {:action "/upload" :method "post" :enctype "multipart/form-data"}
   (anti-forgery-field)
   [:label {:for "upload"} [:b "Upload file"]]
   [:br]
   [:input {:type "file" :id "upload" :name "file"}]
   [:br]
   [:button {:type "submit"} "Upload"]])

(defn user-info [req]
  [:div
   [:b "Logged in as: "]
   (-> req :session :identity :email)
   [:br]
   [:a {:href "/logout"} "Log out"]])

(defn list-files-handler [req]
  (response
    (html5
      (user-info req)
      [:br]
      (file-list)
      [:br]
      (upload-file))))

(defn download-handler [req]
  (file-response (-> req :params :file)
                 {:root (.getAbsolutePath (io/file "uploads"))}))

(defn upload-handler [req]
  (let [params   (-> req :multipart-params (get "file"))
        tmp-file (:tempfile params)
        filename (:filename params)]
    (.renameTo tmp-file (io/file (str "./uploads/" filename)))
    (redirect "/")))

(defn delete-handler [req]
  (let [file (-> req :params :file)]
    (.delete (io/file (str "./uploads/" file)))
    (redirect "/")))

(defroutes app
  (GET "/" [] list-files-handler)
  (GET "/uploads/:file" [] download-handler)
  (POST "/upload" [] upload-handler)
  (POST "/delete/:file" [] delete-handler)
  (route/not-found "404 Not found"))

(defstate server
  :start (run-jetty (-> app
                        wrap-anti-forgery
                        wrap-keycloak-authorization
                        wrap-keycloak-authentication
                        wrap-multipart-params
                        wrap-params
                        wrap-session)
                    {:port 3030 :join? false})
  :stop (.stop server))

(defn -main []
  (mount.core/start))

