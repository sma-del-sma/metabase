(ns metabase.notification.payload.impl.dashboard
  (:require
   [metabase.channel.render.core :as channel.render]
   [metabase.events :as events]
   [metabase.models.params.shared :as shared.params]
   [metabase.notification.payload.core :as notification.payload]
   [metabase.notification.payload.execute :as notification.execute]
   [metabase.notification.send :as notification.send]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [toucan2.core :as t2]))

(defenterprise the-parameters
  "OSS way of getting filter parameters for a dashboard subscription"
  metabase-enterprise.dashboard-subscription-filters.parameter
  [_dashboard-subscription-params dashboard-params]
  dashboard-params)

(defn- parameters
  "Returns the list of parameters applied to a dashboard subscription, filtering out ones
  without a value"
  [dashboard-subscription-params dashboard-params]
  (filter
   shared.params/param-val-or-default
   (the-parameters dashboard-subscription-params dashboard-params)))

(mu/defmethod notification.payload/payload :notification/dashboard
  [{:keys [creator_id dashboard_subscription] :as _notification-info} :- ::notification.payload/Notification]
  (log/with-context {:dashboard_id (:dashboard_id dashboard_subscription)}
    (let [dashboard-id (:dashboard_id dashboard_subscription)
          dashboard    (t2/hydrate (t2/select-one :model/Dashboard dashboard-id) :tabs)
          parameters   (parameters (:parameters dashboard_subscription) (:parameters dashboard))]
      {:dashboard_parts        (cond->> (notification.execute/execute-dashboard dashboard-id creator_id parameters)
                                 (:skip_if_empty dashboard_subscription)
                                 (remove (fn [{part-type :type :as part}]
                                           (and
                                            (= part-type :card)
                                            (zero? (get-in part [:result :row_count] 0))))))
       :dashboard              dashboard
       :style                  {:color_text_dark   channel.render/color-text-dark
                                :color_text_light  channel.render/color-text-light
                                :color_text_medium channel.render/color-text-medium}
       :parameters             parameters
       :dashboard_subscription dashboard_subscription})))

(mu/defmethod notification.payload/skip-reason :notification/dashboard
  [{:keys [payload] :as _noti-payload}]
  (let [{:keys [dashboard_parts dashboard_subscription]} payload]
    (when (and (:skip_if_empty dashboard_subscription)
               (every? notification.execute/is-card-empty? dashboard_parts))
      :empty)))

(defmethod notification.send/do-after-notification-sent :notification/dashboard
  [{:keys [id creator_id handlers] :as notification-info} notification-payload]
  ;; clean up all the temp files that we created for this notification
  (try
    (run! #(some-> % :result :data :rows notification.payload/cleanup!) (->> notification-payload :payload :dashboard_parts))
    (catch Exception e
      (log/warn e "Error cleaning up temp files for notification" id)))
  (events/publish-event! :event/subscription-send
                         {:id      id
                          :user-id creator_id
                          :object  {:recipients (->> handlers
                                                     (mapcat :recipients)
                                                     (map #(or (:user %) (:email %))))
                                    :filters    (-> notification-info :dashboard_subscription :parameters)}}))
