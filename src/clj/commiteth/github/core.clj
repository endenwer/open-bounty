(ns commiteth.github.core
  (:require [tentacles.repos :as repos]
            [tentacles.users :as users]
            [tentacles.repos :as repos]
            [tentacles.issues :as issues]
            [ring.util.codec :as codec]
            [clj-http.client :as http]
            [commiteth.config :refer [env]])
  (:import [java.util UUID]))

(defn server-address [] (:server-address env))
(defn client-id [] (:github-client-id env))
(defn client-secret [] (:github-client-secret env))
(defn redirect-uri [] (str (server-address) "/callback"))
(def allow-signup true)
(def hook-secret "Mu0eiV8ooy7IogheexathocaiSeLeineiLue0au8")

;; @todo: github user which will post QR codes (already banned)
(def self "h0gL")
(def self-password "Fahh7ithoh8Ahghe")

(defn authorize-url []
  (let [params (codec/form-encode {:client_id    (client-id)
                                   :redirect_uri (redirect-uri)
                                   :scope        "admin:repo_hook"
                                   :allow_signup allow-signup
                                   :state        (str (UUID/randomUUID))})]
    (str "https://github.com/login/oauth/authorize" "?" params)))

(defn post-for-token
  [code state]
  (http/post "https://github.com/login/oauth/access_token"
    {:content-type :json
     :form-params  {:client_id     (client-id)
                    :client_secret (client-secret)
                    :code          code
                    :redirect_uri  (redirect-uri)
                    :state         state}}))

(defn- auth-params
  [token]
  {:oauth-token  token
   :client-id    (client-id)
   :client-token (client-secret)})

(defn- self-auth-params []
  {:auth (str self ":" self-password)})

(def repo-fields
  [:id
   :name
   :full_name
   :description
   :html_url
   :has_issues
   :open_issues
   :issues_url
   :fork
   :created_at
   :permissions
   :private])

(def login-field [:owner :login])

(defn list-repos
  "List all repos managed by the given user."
  [token]
  (->>
    (map #(merge
           {:login (get-in % login-field)}
           (select-keys % repo-fields))
      (repos/repos (merge (auth-params token) {:type "all"})))
    (filter #(not (:fork %)))
    (filter #(-> % :permissions :admin))))

(defn get-user
  [token]
  (users/me (auth-params token)))

(defn add-webhook
  [token user repo]
  (println "adding webhook")
  (repos/create-hook user repo "web"
    {:url          (str (server-address) "/webhook")
     :content_type "json"}
    (merge (auth-params token)
      {:events ["issues", "issue_comment", "pull_request"]
       :active true})))

(defn remove-webhook
  [token user repo hook-id]
  (println "removing webhook")
  (repos/delete-hook user repo hook-id (auth-params token)))

(defn post-comment
  [user repo issue-id issue-address]
  (issues/create-comment user repo issue-id
    (str "a comment with an image link to the web service. Issue address is " issue-address) (self-auth-params)))

(defn get-commit
  [user repo commit-id]
  (repos/specific-commit user repo commit-id (self-auth-params)))

(defn get-issue
  [user repo issue-number]
  (issues/specific-issue user repo issue-number (self-auth-params)))

(defn get-issue-events
  [user repo issue-id]
  (issues/issue-events user repo issue-id (self-auth-params)))
