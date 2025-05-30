(ns metabase.models.legacy-metric
  "A Metric is a saved MBQL 'macro' expanding to a combination of `:aggregation` and/or `:filter` clauses.
  It is passed in as an `:aggregation` clause but is replaced by the `expand-macros` middleware with the appropriate
  clauses."
  (:require
   [metabase.api.common :as api]
   [metabase.models.audit-log :as audit-log]
   [metabase.models.database :as database]
   [metabase.models.interface :as mi]
   [metabase.permissions.core :as perms]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(methodical/defmethod t2/table-name :model/LegacyMetric [_model] :metric)

(doto :model/LegacyMetric
  (derive :metabase/model)
  (derive :hook/timestamped?)
  (derive ::mi/write-policy.superuser)
  (derive ::mi/create-policy.superuser))

(defmethod mi/can-read? :model/LegacyMetric
  ([instance]
   (let [table (:table (t2/hydrate instance :table))]
     (perms/user-has-permission-for-table?
      api/*current-user-id*
      :perms/manage-table-metadata
      :yes
      (:db_id table)
      (u/the-id table))))
  ([model pk]
   (mi/can-read? (t2/select-one model pk))))

(t2/deftransforms :model/LegacyMetric
  {:definition mi/transform-legacy-metric-segment-definition})

(t2/define-before-update :model/LegacyMetric
  [{:keys [id], :as metric}]
  (u/prog1 (t2/changes metric)
    ;; throw an Exception if someone tries to update creator_id
    (when (contains? <> :creator_id)
      (when (not= (:creator_id <>) (t2/select-one-fn :creator_id :model/LegacyMetric :id id))
        (throw (UnsupportedOperationException. (tru "You cannot update the creator_id of a Metric.")))))))

(t2/define-before-delete :model/LegacyMetric
  [{:keys [id] :as _metric}]
  (t2/delete! :model/Revision :model "Metric" :model_id id))

(defmethod mi/perms-objects-set :model/LegacyMetric
  [metric read-or-write]
  (let [table (or (:table metric)
                  (t2/select-one ['Table :db_id :schema :id] :id (u/the-id (:table_id metric))))]
    (mi/perms-objects-set table read-or-write)))

;;; ------------------------------------------------ Audit Log --------------------------------------------------------

(defmethod audit-log/model-details :model/LegacyMetric
  [metric _event-type]
  (let [table-id (:table_id metric)
        db-id    (database/table-id->database-id table-id)]
    (assoc
     (select-keys metric [:name :description :revision_message])
     :table_id    table-id
     :database_id db-id)))
