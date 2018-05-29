(ns commiteth.db.bounties
  (:require [commiteth.db.core :refer [*db*] :as db]
            [commiteth.util.util :refer [to-db-map]]
            [clojure.tools.logging :as log] 
            [clojure.java.jdbc :as jdbc]
            [clojure.set :refer [rename-keys]]))



(defn pending-contracts
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/pending-contracts con-db)))

(defn owner-bounties
  [owner-id]
  (jdbc/with-db-connection [con-db *db*]
    (db/owner-bounties con-db {:owner_id owner-id})))

(defn open-bounties
  []
    (jdbc/with-db-connection [con-db *db*]
      (db/open-bounties con-db)))

(defn bounty-claims
  [issue-id]
  (jdbc/with-db-connection [con-db *db*]
    (db/bounty-claims con-db {:issue_id issue-id})))

(defn pending-bounties
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/pending-bounties con-db)))

(defn pending-payouts
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/pending-payouts con-db)))

(defn confirmed-payouts
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/confirmed-payouts con-db)))

(defn confirmed-revocation-payouts
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/confirmed-revocation-payouts con-db)))

(defn update-winner-login
  [issue-id login]
  (jdbc/with-db-connection [con-db *db*]
    (db/update-winner-login con-db {:issue_id issue-id :winner_login login})))

(defn pending-watch-calls
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/pending-watch-calls con-db)))

(defn update-payout-hash
  [issue-id payout-hash]
  (jdbc/with-db-connection [con-db *db*]
    (db/update-payout-hash con-db (to-db-map issue-id payout-hash))))

(defn reset-payout-hash
  [issue-id]
  (jdbc/with-db-connection [con-db *db*]
    (db/reset-payout-hash con-db {:issue_id issue-id})))

(def payout-receipt-keys
  [:issue-id
   :payout-hash
   :contract-address
   :repo
   :owner
   :comment-id
   :issue-number
   :balance-eth
   :tokens
   :confirm-hash
   :payee-login
   :updated])

(defn update-payout-receipt
  [issue-id payout-receipt]
  (jdbc/with-db-connection [con-db *db*]
    (db/update-payout-receipt con-db (to-db-map issue-id payout-receipt))))

(defn get-bounty
  [owner repo issue-number]
  (jdbc/with-db-connection [con-db *db*]
 (log/info "get-bounty params:" (to-db-map owner repo issue-number))
    (db/get-bounty con-db (to-db-map owner repo issue-number))))

(defn open-bounty-contracts
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/open-bounty-contracts con-db)))

(defn top-hunters
  []
    (jdbc/with-db-connection [con-db *db*]
      (db/top-hunters con-db)))

(defn bounty-activity
  []
  (jdbc/with-db-connection [con-db *db*]
    (db/bounties-activity con-db)))


