# upload-clj

Simple webapp that lets you upload and download files. 

## Run locally

### Prerequisites

1. java
2. Clojure CLI
3. docker

### Set up OIDC server

The webapp uses Open ID Connect for authentication and authorization. The example bellow sets up a Keycloak server, but any OIDC server should work (though you might need to change the `no.neksa.upload/authorized?` function).

1. Start a Keycloak container `docker run -d --name keycloak -p 8080:8080 -e KEYCLOAK_USER=admin -e KEYCLOAK_PASSWORD=admin quay.io/keycloak/keycloak:14.0.0` 
2. Go to http://localhost:8080 and login using username _admin_ and password _admin_
3. Create a new client named _upload_ `Clients > Create`
4. Once created change the _Access Type_ to _Confidential_
5. Add http://localhost:3030/* as a _Valid Redirect URI_
6. Press _Save_
7. Go to the _Credentials_-tab and copy the client secret
8. Create a file named `secret.edn` in the project root folder with the following contents
```clojure
{:client-secret "<paste secret here>"}
```
9. Create a new role called _upload_ `Roles > Add Role`
10. Add the _upload_ role to a user, for example the admin user `Users > ~select a user~ > Role Mappings`
11. Log out of the Keycloak admin console

### Start the app
#### Run app locally using REPL
1. Start the Keycloak if it is not already started `docker start keycloak`
2. Change directory to the project root folder
3. Start the repl `clj`
4. Enter the following into the REPL to start the app 
```clojure
(require 'no.neksa.upload.core)
(no.neksa.upload.core/-main)
```

#### Build and run an uberjar
1. Start the Keycloak if it is not already started `docker start keycloak`
2. Change directory to the project root folder
3. Build the uberjar `clj -M:uberdeps`
4. Run the jar `java -cp target/upload-clj.jar clojure.main -m no.neksa.upload.core`

