(ns metabase.api.revision-test
  (:require [expectations :refer :all]
            [korma.core :as k]
            [medley.core :as m]
            [metabase.api.revision :refer :all]
            [metabase.db :as db]
            (metabase.models [dashboard :refer [Dashboard]]
                             [dashboard-card :refer [DashboardCard]]
                             [revision-test :refer [with-fake-card]])
            [metabase.test.data.users :refer :all]))


(defn x []
  (with-fake-card [{card-id₁ :id}]
    (with-fake-card [{card-id₂ :id}]
      ((user->client :rasta) :get 200 "revision", :entity :card, :id card-id₁))))

(defn y []
  (with-fake-card [{card-id :id}]
    ((user->client :rasta) :get 200 "revision", :entity :card, :id card-id)))

(defn- fake-dashboard [& {:as kwargs}]
  (m/mapply db/ins Dashboard (merge {:name         (str (java.util.UUID/randomUUID))
                                     :public_perms 0
                                     :creator_id   (user->id :rasta)}
                                    kwargs)))

(defmacro with-fake-dashboard [[binding & {:as kwargs}] & body]
  `(let [dash# (fake-dashboard ~@kwargs)
         ~binding dash#]
     (try ~@body
          (finally
            (db/cascade-delete Dashboard :id (:id dash#))))))

(def ^:private rasta-revision-info
  (delay {:id (user->id :rasta) :common_name "Rasta Toucan", :first_name "Rasta", :last_name "Toucan"}))

;;; # TESTS FOR GET /api/revision
(expect [{:description "First revision.", :user {}}]
  (with-fake-card [{card-id :id}]
    ((user->client :rasta) :get 200 "revision", :entity :card, :id card-id)))

(expect [{:is_reversion false, :user @rasta-revision-info, :description "First revision."}]
  (with-fake-dashboard [{dash-id :id}]
    (with-fake-card [{card-id :id}]
      ((user->client :rasta) :post 200 (format "dash/%d/cards" dash-id), {:cardId card-id})
      (->> ((user->client :rasta) :get 200 "revision", :entity :dashboard, :id dash-id)
           (mapv #(dissoc % :timestamp :id))))))

(expect [{:is_reversion false, :user @rasta-revision-info, :description "Rasta Toucan added a card."}
         {:is_reversion false, :user @rasta-revision-info, :description "First revision."}]
  (with-fake-dashboard [{dash-id :id}]
    (with-fake-card [{card-id₁ :id}]
      (with-fake-card [{card-id₂ :id}]
        ((user->client :rasta) :post 200 (format "dash/%d/cards" dash-id), {:cardId card-id₁})
        ((user->client :rasta) :post 200 (format "dash/%d/cards" dash-id), {:cardId card-id₂})
        (->> ((user->client :rasta) :get 200 "revision", :entity :dashboard, :id dash-id)
             (mapv #(dissoc % :timestamp :id)))))))

(expect [{:is_reversion false, :user @rasta-revision-info, :description "Rasta Toucan removed a card."}
         {:is_reversion false, :user @rasta-revision-info, :description "Rasta Toucan added a card."}
         {:is_reversion false, :user @rasta-revision-info, :description "First revision."}]
  (with-fake-dashboard [{dash-id :id}]
    (with-fake-card [{card-id₁ :id}]
      (with-fake-card [{card-id₂ :id}]
        ((user->client :rasta) :post 200   (format "dash/%d/cards" dash-id), {:cardId card-id₁})
        ((user->client :rasta) :post 200   (format "dash/%d/cards" dash-id), {:cardId card-id₂})
        ((user->client :rasta) :delete 204 (format "dash/%d/cards" dash-id), :dashcardId (db/sel :one :id DashboardCard (k/order :id :desc)))
        (->> ((user->client :rasta) :get 200 "revision", :entity :dashboard, :id dash-id)
             (mapv #(dissoc % :timestamp :id)))))))

;;; # TESTS FOR POST /api/revision/revert
(expect [2
         [{:is_reversion true,  :user @rasta-revision-info, :description "Rasta Toucan reverted to an earlier revision and added a card."}
          {:is_reversion false, :user @rasta-revision-info, :description "Rasta Toucan removed a card."}
          {:is_reversion false, :user @rasta-revision-info, :description "Rasta Toucan added a card."}
          {:is_reversion false, :user @rasta-revision-info, :description "First revision."}]]
  (with-fake-dashboard [{dash-id :id}]
    (with-fake-card [{card-id₁ :id}]
      (with-fake-card [{card-id₂ :id}]
        ((user->client :rasta) :post 200   (format "dash/%d/cards" dash-id), {:cardId card-id₁})
        ((user->client :rasta) :post 200   (format "dash/%d/cards" dash-id), {:cardId card-id₂})
        ((user->client :rasta) :delete 204 (format "dash/%d/cards" dash-id), :dashcardId (db/sel :one :id DashboardCard (k/order :id :desc)))
        (let [[_ {previous-revision-id :id}] (metabase.models.revision/revisions Dashboard dash-id)]
          ;; Revert to the previous revision
          ((user->client :rasta) :post 200 "revision/revert", {:entity :dashboard, :id dash-id, :revision_id previous-revision-id})
          [ ;; [1] There should be 2 cards again
           (count @(:ordered_cards (Dashboard dash-id)))
           ;; [2] A new revision recording the reversion should have been pushed
           (->> ((user->client :rasta) :get 200 "revision", :entity :dashboard, :id dash-id)
                (mapv #(dissoc % :timestamp :id)))])))))
