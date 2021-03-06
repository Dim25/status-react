(ns status-im.models.browser
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.data-store.browser :as browser-store]
            [status-im.data-store.dapp-permissions :as dapp-permissions]
            [status-im.i18n :as i18n]
            [status-im.ui.screens.browser.default-dapps :as default-dapps]
            [status-im.utils.http :as http]
            [clojure.string :as string]
            [status-im.utils.ethereum.resolver :as resolver]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.utils.ethereum.ens :as ens]
            [status-im.utils.multihash :as multihash]
            [status-im.utils.handlers-macro :as handlers-macro]))

(defn get-current-url [{:keys [history history-index]}]
  (when (and history-index history)
    (nth history history-index)))

(defn can-go-back? [{:keys [history-index]}]
  (pos? history-index))

(defn can-go-forward? [{:keys [history-index history]}]
  (< history-index (dec (count history))))

(defn check-if-dapp-in-list [{:keys [history history-index] :as browser}]
  (let [history-host (http/url-host (try (nth history history-index) (catch js/Error _)))
        dapp         (first (filter #(= history-host (http/url-host (:dapp-url %))) (apply concat (mapv :data default-dapps/all))))]
    (if dapp
      (assoc browser :dapp? true :name (:name dapp))
      (assoc browser :dapp? false :name (i18n/label :t/browser)))))

(defn update-browser-fx [browser {:keys [db now]}]
  (let [updated-browser (check-if-dapp-in-list (assoc browser :timestamp now))]
    {:db            (update-in db [:browser/browsers (:browser-id updated-browser)]
                               merge updated-browser)
     :data-store/tx [(browser-store/save-browser-tx updated-browser)]}))

(defn update-browser-history-fx [browser url loading? cofx]
  (when-not loading?
    (let [history-index (:history-index browser)
          history       (:history browser)
          history-url   (try (nth history history-index) (catch js/Error _))]
      (when (not= history-url url)
        (let [slash?      (= url (str history-url "/"))
              new-history (if slash?
                            (assoc history history-index url)
                            (conj (subvec history 0 (inc history-index)) url))
              new-index   (if slash?
                            history-index
                            (dec (count new-history)))]
          (update-browser-fx (assoc browser :history new-history :history-index new-index)
                             cofx))))))

(defn ens? [host]
  (and (string? host)
       (string/ends-with? host ".eth")))

(defn ens-multihash-callback [hex]
  (let [hash (when hex (multihash/base58 (multihash/create :sha2-256 (subs hex 2))))]
    (if (and hash (not= hash resolver/default-hash))
      (re-frame/dispatch [:ens-multihash-resolved hash])
      (re-frame/dispatch [:update-browser-options {:resolving? false}]))))

(defn resolve-multihash-fx [host loading error? {{:keys [web3 network] :as db} :db}]
  (let [network (get-in db [:account/account :networks network])
        chain   (ethereum/network->chain-keyword network)]
    (if (and (not loading) (not error?) (ens? host))
      {:db                    (assoc-in db [:browser/options :resolving?] true)
       :resolve-ens-multihash {:web3     web3
                               :registry (get ens/ens-registries
                                              chain)
                               :ens-name host
                               :cb       ens-multihash-callback}}
      {})))

(defn update-new-browser-and-navigate [host browser cofx]
  (handlers-macro/merge-fx
   cofx
   {:dispatch [:navigate-to :browser {:browser-id (:browser-id browser)
                                      :resolving? (ens? host)}]}
   (update-browser-fx browser)
   (resolve-multihash-fx host false false)))

(defn update-browser-and-navigate [browser cofx]
  (merge (update-browser-fx browser cofx)
         {:dispatch [:navigate-to :browser {:browser-id (:browser-id browser)}]}))

(def permissions {constants/dapp-permission-contact-code {:title       (i18n/label :t/wants-to-access-profile)
                                                          :description (i18n/label :t/your-contact-code)
                                                          :icon        :icons/profile-active}
                  constants/dapp-permission-web3         {:title       (i18n/label :t/dapp-would-like-to-connect-wallet)
                                                          :description (i18n/label :t/allowing-authorizes-this-dapp)
                                                          :icon        :icons/wallet-active}})

(defn update-dapp-permissions-fx [{:keys [db]} permissions]
  {:db            (-> db
                      (assoc-in [:browser/options :show-permission] nil)
                      (assoc-in [:dapps/permissions (:dapp permissions)] permissions))
   :data-store/tx [(dapp-permissions/save-dapp-permissions permissions)]})

(defn request-permission [{:keys [dapp-name index requested-permissions permissions-allowed user-permissions
                                  permissions-data]
                           :as   params}
                          {:keys [db] :as cofx}]
  ;; iterate all requested permissions
  (if (< index (count requested-permissions))
    (let [requested-permission (get requested-permissions index)]
      ;; if requested permission exists and valid continue if not decline permission
      (if (and requested-permission (get permissions requested-permission))
        ;; if permission already allowed go to next, if not, show confirmation dialog
        (if ((set user-permissions) requested-permission)
          {:dispatch [:next-dapp-permission params requested-permission permissions-data]}
          {:db (assoc-in db [:browser/options :show-permission] {:requested-permission requested-permission
                                                                 :params               params})})
        {:dispatch [:next-dapp-permission params]}))
    (cond-> (update-dapp-permissions-fx cofx {:dapp        dapp-name
                                              :permissions (vec (set (concat (keys permissions-allowed)
                                                                             user-permissions)))})
      (not (zero? (count permissions-allowed)))
      (assoc :send-to-bridge-fx [{:type constants/status-api-success
                                  :data permissions-allowed
                                  :keys (keys permissions-allowed)}
                                 (:webview-bridge db)])

      true
      (assoc :dispatch [:check-permissions-queue]))))

(defn next-permission [{:keys [params permission permissions-data]} cofx]
  (request-permission
   (cond-> params
     true
     (update :index inc)

     (and permission permissions-data)
     (assoc-in [:permissions-allowed permission] (get permissions-data permission)))
   cofx))

(defn web3-send-async [{:keys [method] :as payload} message-id {:keys [db]}]
  (if (or (= method constants/web3-send-transaction)
          (= method constants/web3-personal-sign))
    {:db       (update-in db [:wallet :transactions-queue] conj {:message-id message-id :payload payload})
     :dispatch [:check-dapps-transactions-queue]}
    {:call-rpc [payload
                #(re-frame/dispatch [:send-to-bridge
                                     {:type      constants/web3-send-async-callback
                                      :messageId message-id
                                      :error     %1
                                      :result    %2}])]}))

(defn initialize-browsers
  [{:keys [db all-stored-browsers]}]
  (let [browsers (into {} (map #(vector (:browser-id %) %) all-stored-browsers))]
    {:db (assoc db :browser/browsers browsers)}))

(defn  initialize-dapp-permissions
  [{:keys [db all-dapp-permissions]}]
  (let [dapp-permissions (into {} (map #(vector (:dapp %) %) all-dapp-permissions))]
    {:db (assoc db :dapps/permissions dapp-permissions)}))
