(ns commiteth.routes.services
  (:require [ring.util.http-response :refer :all]
            [compojure.api.sweet :refer :all]
            [compojure.api.exception :as ex]
            [schema.core :as s]
            [compojure.api.meta :refer [restructure-param]]
            [buddy.auth.accessrules :refer [restrict]]
            [buddy.auth :refer [authenticated?]]
            [commiteth.db.core :as db]
            [commiteth.db.users :as users]
            [commiteth.db.usage-metrics :as usage-metrics]
            [commiteth.db.repositories :as repositories]
            [commiteth.db.bounties :as bounties-db]
            [commiteth.db.issues :as issues]
            [commiteth.bounties :as bounties]
            [commiteth.eth.core :as eth]
            [commiteth.eth.tracker :as tracker]
            [commiteth.github.core :as github]
            [clojure.tools.logging :as log]
            [commiteth.config :refer [env]]
            [commiteth.util.util :refer [usd-decimal->str
                                         eth-decimal->str
                                         to-db-map]]
            [crypto.random :as random]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [commiteth.eth.multisig-wallet :as multisig]))

(defn add-bounties-for-existing-issues? []
  (env :add-bounties-for-existing-issues false))

(defn access-error [_ _]
  (unauthorized {:error "unauthorized"}))

(defn wrap-restricted [handler rule]
  (restrict handler {:handler  rule
                     :on-error access-error}))

(defmethod restructure-param :auth-rules
  [_ rule acc]
  (update-in acc [:middleware] conj [wrap-restricted rule]))

(defmethod restructure-param :current-user
  [_ binding acc]
  (update-in acc [:letks] into [binding `(:identity ~'+compojure-api-request+)]))


(defn status-team-member? [req]
  (let [token (get-in req [:params :token])
        member? (github/status-team-member? token)]
    (log/debug "token" token "member?" member?)
    member?))

(defn in? [coll elem]
  (some #(= elem %) coll))

(defn handle-get-user-repos [user token]
  (log/debug "handle-get-user-repos")
  (let [github-repos (github/get-user-repos token)
        enabled-repos (vec (repositories/get-enabled (:id user)))
        repo-enabled? (fn [repo] (in? enabled-repos (:id repo)))
        update-enabled (fn [repo] (assoc repo :enabled (repo-enabled? repo)))]
    (into {}
          (map (fn [[group repos]] {group
                                   (map update-enabled repos)})
               github-repos))))

(def bounty-renames
  ;; TODO this needs to go away ASAP we need to be super consistent
  ;; about keys unless we will just step on each others toes constantly
  {:user-name :display-name
   :user-avatar-url :avatar-url
   :type :item-type})

(defn ^:private enrich-owner-bounties [owner-bounty]
  (let [claims      (map
                     #(update % :value-usd usd-decimal->str)
                     (bounties-db/bounty-claims (:issue-id owner-bounty)))
        with-claims (assoc owner-bounty :claims claims)]
    (-> with-claims
        (rename-keys bounty-renames)
        (update :value-usd usd-decimal->str)
        (update :balance-eth eth-decimal->str)
        (assoc :state (bounties/bounty-state with-claims)))))

(defn user-bounties [user]
  (let [owner-bounties (bounties-db/owner-bounties (:id user))]
    (->> owner-bounties
         (map enrich-owner-bounties)
         (map (juxt :issue-id identity))
         (into {}))))

(defn top-hunters []
  (let [renames {:user-name :display-name}]
    (map #(-> %
              (rename-keys renames)
              (update :total-usd usd-decimal->str))
         (bounties-db/top-hunters))))


(defn prettify-bounty-items [bounty-items]
  (let [format-float (fn [bounty balance]
                       (try 
                         (format "%.2f" (double balance))
                         (catch Throwable ex 
                           (log/error (str (:repo-owner bounty)
                                           "/"
                                           (:repo-name bounty)
                                           "/"
                                           (:issue-number bounty)) 
                                      "Failed to convert token value:" balance)
                           "0.00")))
        update-token-values (fn [bounty]
                              (->> bounty
                                   :tokens
                                   (map (fn [[tla balance]]
                                          [tla (format-float bounty balance)]))
                                   (into {})
                                   (assoc bounty :tokens)))]
    (map #(-> %
              (rename-keys bounty-renames)
              (update :value-usd usd-decimal->str)
              (update :balance-eth eth-decimal->str)
              update-token-values)
         bounty-items)))

(defn activity-feed []
  (prettify-bounty-items (bounties-db/bounty-activity)))

(defn open-bounties []
  (prettify-bounty-items (bounties-db/open-bounties)))



(defn current-user-token [user]
  (some identity (map #(% user) [:token :admin-token])))

(defn handle-get-user [user admin-token]
  (let [status-member? (github/status-team-member? admin-token)]
    {:user
     (-> (users/get-user (:id user))
         (dissoc :email)
         (assoc :status-team-member? status-member?))}))

(defn user-whitelisted? [user]
  (let [whitelist (env :user-whitelist #{})]
    (whitelist user)))

(defn execute-revocation [issue-id contract-address payout-address]
  (log/info (str "executing revocation for " issue-id "at" contract-address))
  (try 
    (let [tx-info (bounties/execute-payout issue-id contract-address payout-address)]
      (:tx-hash tx-info))
    (catch Throwable ex
      (log/errorf ex "error revoking funds for %s" issue-id))))


(defapi service-routes
  (when (:dev env)
    {:swagger    {:ui   "/swagger-ui"
                  :spec "/swagger.json"
                  :data {:info {:version     "0.1"
                                :title       "commitETH API"
                                :description "commitETH API"}}}
     :exceptions {:handlers 
                  {::ex/request-parsing     (ex/with-logging ex/request-parsing-handler :info)
                   ::ex/response-validation (ex/with-logging ex/response-validation-handler :error)}}})

  (context "/api" []
           (GET "/top-hunters" []
                (log/debug "/top-hunters")
                (ok (top-hunters)))
           (GET "/activity-feed" []
                (log/debug "/activity-feed")
                (ok (activity-feed)))
           (GET "/open-bounties" []
                (log/debug "/open-bounties")
                (ok (open-bounties)))
           (GET "/usage-metrics" []
                :query-params [token :- String]
                :auth-rules status-team-member?
                :current-user user
                (do
                  (log/debug "/usage-metrics" user)
                  (ok (usage-metrics/usage-metrics-by-day))))

           (context "/user" []

                    (GET "/" {:keys [params]}
                         :auth-rules authenticated?
                         :current-user user
                         (ok (handle-get-user user (:token params))))

                    (POST "/" []
                          :auth-rules authenticated?
                          :current-user user
                          :body [body {:address              s/Str
                                       :is-hidden-in-hunters s/Bool}]
                          :summary "Updates user's fields."

                          (let [user-id           (:id user)
                                {:keys [address is-hidden-in-hunters]} body]

                            (when-not (eth/valid-address? address)
                              (log/debugf "POST /user: Wrong address %s" address)
                              (bad-request! (format "Invalid Ethereum address: %s" address)))

                            (db/with-tx
                              (when-not (db/user-exists? {:id user-id})
                                (not-found! "No such a user."))
                              (db/update! :users (to-db-map address is-hidden-in-hunters)
                                          ["id = ?" user-id]))

                            (ok)))

                    (GET "/repositories" {:keys [params]}
                         :auth-rules authenticated?
                         :current-user user
                         (ok {:repositories (handle-get-user-repos
                                             user
                                             (:token params))}))
                    (GET "/enabled-repositories" []
                         :auth-rules authenticated?
                         :current-user user
                         (ok (repositories/get-enabled (:id user))))
                    (POST "/bounty/:issue{[0-9]{1,9}}/payout" {:keys [params]}
                          :auth-rules authenticated?
                          :current-user user
                          (do
                            (log/info "/bounty/X/payout" params)
                            (let [{issue       :issue
                                   payout-hash :payout-hash} params
                                  result                     (bounties-db/update-payout-hash
                                                              (Integer/parseInt issue)
                                                              payout-hash)]
                              (log/debug "result" result)
                              (if (= 1 result)
                                (ok)
                                (internal-server-error)))))
                    (GET "/bounties" []
                         :auth-rules authenticated?
                         :current-user user
                         (log/debug "/user/bounties")
                         (ok (user-bounties user)))
                    (POST "/revoke"  {{issue-id :issue-id} :params}
                          :auth-rules authenticated?
                          :current-user user
                          (let [{:keys [contract-address owner-address]} (issues/get-issue-by-id issue-id)]
                            (do (log/infof "calling revoke-initiate for %s with %s %s" issue-id contract-address owner-address)
                                (if-let [execute-hash (execute-revocation issue-id contract-address owner-address)]
                                  (ok {:issue-id         issue-id
                                       :execute-hash     execute-hash
                                       :contract-address contract-address})
                                  (bad-request (str "Unable to withdraw funds from " contract-address))))))
                    (POST "/remove-bot-confirmation"  {{issue-id :issue-id} :params}
                          :auth-rules authenticated?
                          :current-user user
                          (do (log/infof "calling remove-bot-confirmation for %s " issue-id)
                              ;; if this resulted in updating a row, return success
                              (if (pos? (issues/reset-bot-confirmation issue-id))
                                (ok (str "Updated execute and confirm hash for " issue-id))
                                (bad-request (str "Unable to update execute and confirm hash for  " issue-id))))))))
