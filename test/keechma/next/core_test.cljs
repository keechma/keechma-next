(ns keechma.next.core-test
  (:require
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]
    [keechma.next.controller :as ctrl]
    [keechma.next.core :refer [start! stop! subscribe subscribe-meta dispatch get-derived-state transact]]
    [keechma.next.conformer :refer [conform]]
    [cljs.spec.alpha :as s]))

#_(use-fixtures :once {:before (fn [] (js/console.clear))})

(defn log-cmd!
  ([ctrl cmd] (log-cmd! ctrl cmd nil))
  ([{controller-name :keechma.controller/name :keys [cmd-log*]} cmd _]
   (when cmd-log*
     (swap! cmd-log* conj [controller-name cmd]))))

(derive :counter-1 :keechma/controller)
(derive :counter-2 :keechma/controller)
(derive :counter-3 :keechma/controller)

(defmethod ctrl/start :counter-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/handle :counter-1 [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    :inc (swap! state* inc)
    nil))

(defmethod ctrl/stop :counter-2 [ctrl state _]
  (log-cmd! ctrl :keechma.lifecycle/stop)
  (* 2 state))

(defmethod ctrl/start :counter-2 [ctrl {:keys [counter-1]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-1))

(defmethod ctrl/handle :counter-2 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (reset! state* (inc (:counter-1 payload)))
    nil))

(defmethod ctrl/start :counter-3 [ctrl {:keys [counter-2]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-2))

(deftest send-1
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {:counter-1 0} (get-derived-state app-instance)))
    (is (= 0 (get-derived-state app-instance :counter-1)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1} (get-derived-state app-instance)))
    (is (= 1 (get-derived-state app-instance :counter-1)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [:counter-1 :inc]]
           @cmd-log*))
    (stop! app-instance)))

(deftest send-2
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   :counter-2 {:keechma.controller/params true
                                               :cmd-log* cmd-log*
                                               :keechma.controller/deps [:counter-1]}}}
        app-instance (start! app)]
    (is (= {:counter-1 0 :counter-2 1} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1 :counter-2 2} (get-derived-state app-instance)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [:counter-2 :keechma.lifecycle/start]
            [:counter-2 :keechma.on/start]
            [:counter-1 :inc]
            [:counter-2 :keechma.on/deps-change]]
           @cmd-log*))
    (stop! app-instance)))

(deftest send-3
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   [:counter-2 1] {:keechma.controller/params true
                                                   :cmd-log* cmd-log*
                                                   :keechma.controller/deps [:counter-1]}}}
        app-instance (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 1] 2} (get-derived-state app-instance)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/deps-change]]
           @cmd-log*))
    (stop! app-instance)))

(deftest send-4
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params {:counter-1 counter-1}}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1 [:counter-2 2] 1} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 2} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 3} (get-derived-state app-instance)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/stop]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 3] :keechma.on/stop]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [[:counter-2 4] :keechma.lifecycle/start]
            [[:counter-2 4] :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(deftest send-5
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1 [:counter-2 2] 1} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 1} (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 1} (get-derived-state app-instance)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/stop]
            [[:counter-2 2] :keechma.on/deps-change]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 3] :keechma.on/deps-change]
            [[:counter-2 4] :keechma.lifecycle/start]
            [[:counter-2 4] :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(deftest send-6
  (let [cmd-log* (atom [])
        app {:keechma/controllers
             {:counter-1 {:keechma.controller/params true
                          :cmd-log* cmd-log*}
              [:counter-2] {:keechma.controller.factory/produce
                            (fn [{:keys [counter-1]}]
                              (->> (range counter-1 (+ 2 counter-1))
                                   (map (fn [i] [(inc i) {:keechma.controller/params {:counter-1 counter-1}}]))
                                   (into {})))
                            :keechma.controller/deps [:counter-1]
                            :cmd-log* cmd-log*}
              [:counter-3] {:keechma.controller.factory/produce
                            (fn [deps]
                              (->> deps
                                   (map (fn [[[_ counter-2-id] val]]
                                          [counter-2-id {:keechma.controller/params {:counter-2 val}}]))
                                   (into {})))
                            :keechma.controller/deps [[:counter-2]]
                            :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {:counter-1 0
            [:counter-2 1] 1
            [:counter-2 2] 1
            [:counter-3 1] 2
            [:counter-3 2] 2}
           (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1
            [:counter-2 2] 2
            [:counter-2 3] 2
            [:counter-3 2] 3
            [:counter-3 3] 3}
           (get-derived-state app-instance)))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 2
            [:counter-2 3] 3
            [:counter-2 4] 3
            [:counter-3 3] 4
            [:counter-3 4] 4}
           (get-derived-state app-instance)))
    (stop! app-instance)))

(derive :token :keechma/controller)
(derive :current-user :keechma/controller)
(derive :login :keechma/controller)

(defmethod ctrl/handle :token [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-token (reset! state* payload)
    nil))

(defmethod ctrl/handle :current-user [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-user (reset! state* payload)
    nil))

(defmethod ctrl/handle :login [ctrl cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :do-login (js/setTimeout #(ctrl/transact ctrl
                                             (fn []
                                               (ctrl/dispatch ctrl :token :update-token "TOKEN")
                                               (ctrl/dispatch ctrl :current-user :update-user {:id 1 :username "retro"}))))
    nil))

(deftest transactions
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:token {:keechma.controller/params true
                                           :cmd-log* cmd-log*}
                                   :current-user {:keechma.controller/params true
                                                  :keechma.controller/deps [:token]
                                                  :cmd-log* cmd-log*}
                                   :login {:keechma.controller/params (fn [{:keys [token]}] (not token))
                                           :keechma.controller/deps [:token :current-user]
                                           :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (async done
      (is (= {:token nil :current-user nil :login nil} (get-derived-state app-instance)))
      (is (= [[:token :keechma.on/start]
              [:current-user :keechma.on/start]
              [:login :keechma.on/start]]
             @cmd-log*))
      (dispatch app-instance :login :do-login)
      (js/setTimeout
        (fn []
          (is (= {:current-user {:id 1 :username "retro"}
                  :token "TOKEN"}
                 (get-derived-state app-instance)))
          (is (= [
                  ;; Start phase
                  [:token :keechma.on/start]
                  [:current-user :keechma.on/start]
                  [:login :keechma.on/start]
                  ;; Sending :do-login cmd to the :login controller
                  [:login :do-login]
                  ;; Wrapping :do-login action in transact block ensures correct ordering of events
                  [:token :update-token]
                  [:current-user :update-user]
                  ;; Only after the actions in the transact block are done, keechma resumes control and sends pending actions
                  [:current-user :keechma.on/deps-change]
                  [:login :keechma.on/stop]]
                 @cmd-log*))
          (done))))))

(derive :causal-1 :keechma/controller)
(derive :causal-2 :keechma/controller)
(derive :causal-3 :keechma/controller)

(defmethod ctrl/start :causal-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/handle :causal-1 [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/start :causal-2 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (:causal-1 deps-state)))

(defmethod ctrl/handle :causal-2 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (inc (:causal-1 payload)))))

(defmethod ctrl/start :causal-3 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (:causal-2 deps-state)))

(defmethod ctrl/handle :causal-3 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (inc (:causal-2 payload)))))

(deftest causal
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:causal-1 {:keechma.controller/params true
                                              :cmd-log* cmd-log*}
                                   :causal-2 {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-1]
                                              :cmd-log* cmd-log*}
                                   :causal-3 {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-2]
                                              :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {:causal-1 0 :causal-2 1 :causal-3 2} (get-derived-state app-instance)))
    (dispatch app-instance :causal-1 :inc)
    (is (= {:causal-1 1 :causal-2 2 :causal-3 3} (get-derived-state app-instance)))
    (dispatch app-instance :causal-1 :inc)
    (is (= {:causal-1 2 :causal-2 3 :causal-3 4} (get-derived-state app-instance)))
    (is (= [[:causal-1 :keechma.lifecycle/start]
            [:causal-1 :keechma.on/start]
            [:causal-2 :keechma.lifecycle/start]
            [:causal-2 :keechma.on/start]
            [:causal-3 :keechma.lifecycle/start]
            [:causal-3 :keechma.on/start]
            [:causal-1 :inc]
            [:causal-2 :keechma.on/deps-change]
            [:causal-3 :keechma.on/deps-change]
            [:causal-1 :inc]
            [:causal-2 :keechma.on/deps-change]
            [:causal-3 :keechma.on/deps-change]]
           @cmd-log*))))

(derive :user-role :keechma/controller)
(derive :user-posts :keechma/controller)
(derive :public-posts :keechma/controller)
(derive :user-role-tracker :keechma/controller)
(derive :current-post-id :keechma/controller)
(derive :post-detail :keechma/controller)
(derive :static :keechma/controller)

(defmethod ctrl/start :user-role [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :guest)

(defmethod ctrl/handle :user-role [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    :login (reset! state* :user)
    :logout (reset! state* :guest)
    nil))

(defmethod ctrl/start :user-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :user-posts)

(defmethod ctrl/start :public-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :public-posts)

(defmethod ctrl/start :user-role-tracker [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [[(:keechma.controller/name ctrl) (:user-role deps-state)]])

(defmethod ctrl/handle :user-role-tracker [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (swap! state* conj [(:keechma.controller/name ctrl) (:user-role payload)])
    nil))

(defmethod ctrl/handle :current-post-id [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    :open (swap! state* inc)
    :close (reset! state* nil)
    nil))

(defmethod ctrl/start :post-detail [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [:post-detail (:current-post-id deps-state)])

(defmethod ctrl/start :static [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :static)

(deftest subapps-1
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:user-role {:keechma.controller/params true
                                               :cmd-log* cmd-log*}}
             :keechma/apps {:public {:keechma/controllers {:posts {:keechma.controller/params true
                                                                   :keechma.controller/type :public-posts
                                                                   :cmd-log* cmd-log*}}
                                     :keechma.app/should-run? (fn [{:keys [user-role]}] (= :guest user-role))
                                     :keechma.app/deps [:user-role]}
                            :user {:keechma/controllers {:posts {:keechma.controller/params true
                                                                 :keechma.controller/type :user-posts
                                                                 :cmd-log* cmd-log*}}
                                   :keechma.app/should-run? (fn [{:keys [user-role]}] (= :user user-role))
                                   :keechma.app/deps [:user-role]}
                            :always-on {:keechma/controllers {:user-role-tracker {:keechma.controller/params true
                                                                                  :keechma.controller/deps [:user-role]
                                                                                  :cmd-log* cmd-log*}
                                                              :user-role-tracker-guest {:keechma.controller/params (fn [{:keys [user-role]}] (= :guest user-role))
                                                                                        :keechma.controller/deps [:user-role]
                                                                                        :keechma.controller/type :user-role-tracker
                                                                                        :cmd-log* cmd-log*}
                                                              :user-role-tracker-user {:keechma.controller/params (fn [{:keys [user-role]}] (= :user user-role))
                                                                                       :keechma.controller/deps [:user-role]
                                                                                       :keechma.controller/type :user-role-tracker
                                                                                       :cmd-log* cmd-log*}}
                                        :keechma.app/should-run? (fn [{:keys [user-role]}] user-role)
                                        :keechma.app/deps [:user-role]}}}
        app-instance (start! app)]
    (is (= {:user-role :guest,
            :posts :public-posts,
            :user-role-tracker-guest [[:user-role-tracker-guest :guest]],
            :user-role-tracker [[:user-role-tracker :guest]]}
           (get-derived-state app-instance)))
    (dispatch app-instance :user-role :login)
    (is (= {:user-role :user,
            :posts :user-posts,
            :user-role-tracker [[:user-role-tracker :guest] [:user-role-tracker :user]],
            :user-role-tracker-user [[:user-role-tracker-user :user]]}
           (get-derived-state app-instance)))

    (dispatch app-instance :user-role :logout)
    (is (= {:user-role :guest,
            :user-role-tracker-guest [[:user-role-tracker-guest :guest]],
            :user-role-tracker
            [[:user-role-tracker :guest]
             [:user-role-tracker :user]
             [:user-role-tracker :guest]],
            :posts :public-posts}
           (get-derived-state app-instance)))

    (is (= [[:user-role :keechma.lifecycle/start]
            [:user-role :keechma.on/start]
            [:posts :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.on/start]
            [:user-role-tracker :keechma.lifecycle/start]
            [:user-role-tracker :keechma.on/start]
            [:user-role :login]
            [:posts :keechma.lifecycle/start]
            [:user-role-tracker-user :keechma.lifecycle/start]
            [:user-role-tracker-user :keechma.on/start]
            [:user-role-tracker-guest :keechma.on/stop]
            [:user-role-tracker :keechma.on/deps-change]
            [:user-role :logout]
            [:posts :keechma.lifecycle/start]
            [:user-role-tracker-user :keechma.on/stop]
            [:user-role-tracker-guest :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.on/start]
            [:user-role-tracker :keechma.on/deps-change]]
           @cmd-log*))

    (stop! app-instance)))

(deftest subapps-2
  (let [cmd-log* (atom [])
        app {:keechma/controllers {:user-role {:keechma.controller/params true
                                               :cmd-log* cmd-log*}}
             :keechma/apps
             {:user
              {:keechma/controllers {:posts {:keechma.controller/params true
                                             :keechma.controller/type :public-posts
                                             :cmd-log* cmd-log*}
                                     :current-post-id {:keechma.controller/params true
                                                       :cmd-log* cmd-log*}}
               :keechma.app/should-run? (fn [{:keys [user-role]}] (= :user user-role))
               :keechma.app/deps [:user-role]
               :keechma/apps
               {:post-details
                {:keechma/controllers {:post-detail {:keechma.controller/params (fn [{:keys [current-post-id]}] current-post-id)
                                                     :keechma.controller/deps [:current-post-id]
                                                     :cmd-log* cmd-log*}
                                       :static {:keechma.controller/params true
                                                :cmd-log* cmd-log*}}
                 :keechma.app/should-run? (fn [{:keys [current-post-id]}] current-post-id)
                 :keechma.app/deps [:current-post-id]}}}}}
        app-instance (start! app)]
    (is (= {:user-role :guest} (get-derived-state app-instance)))

    (dispatch app-instance :user-role :login)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id nil} (get-derived-state app-instance)))

    (dispatch app-instance :current-post-id :open)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id 1
            :static :static
            :post-detail [:post-detail 1]}
           (get-derived-state app-instance)))

    (dispatch app-instance :current-post-id :open)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id 2
            :static :static
            :post-detail [:post-detail 2]}
           (get-derived-state app-instance)))

    (dispatch app-instance :current-post-id :close)
    (is (= {:user-role :user,
            :posts :public-posts,
            :current-post-id nil}
           (get-derived-state app-instance)))

    (is (= [[:user-role :keechma.lifecycle/start]
            [:user-role :keechma.on/start]
            [:user-role :login]
            [:posts :keechma.lifecycle/start]
            [:current-post-id :keechma.on/start]
            [:current-post-id :open]
            [:static :keechma.lifecycle/start]
            [:post-detail :keechma.lifecycle/start]
            [:current-post-id :open]
            [:post-detail :keechma.lifecycle/start]
            [:current-post-id :close]]
           @cmd-log*))
    (stop! app-instance)))

(deftest subscriptions-1
  (let [cmd-log* (atom [])
        state* (atom {})
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log* cmd-log*}}}
        app-instance (start! app)
        s! (fn [controller] (subscribe app-instance controller #(swap! state* assoc controller %)))]

    (s! :counter-1)
    (s! [:counter-2 1])
    (s! [:counter-2 2])
    (s! [:counter-2 3])
    (s! [:counter-2 4])

    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 1} (get-derived-state app-instance)))
    (is (= {:counter-1 1, [:counter-2 1] nil, [:counter-2 2] 2, [:counter-2 3] 1, [:counter-2 4] nil} @state*))
    (dispatch app-instance :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 1} (get-derived-state app-instance)))
    (is (= {:counter-1 2, [:counter-2 1] nil, [:counter-2 2] nil, [:counter-2 3] 3, [:counter-2 4] 1} @state*))

    (stop! app-instance)))

(derive :causal-a :keechma/controller)
(derive :causal-b :keechma/controller)

(defmethod ctrl/start :causal-a [_ _ _ _]
  1)

(defmethod ctrl/handle :causal-a [{:keys [state* meta-state*] :as _} cmd _]
  (swap! meta-state* update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state* inc)
    nil))

(defmethod ctrl/start :causal-b [_ _ _ _]
  1)

(defmethod ctrl/handle :causal-b [{:keys [state* meta-state*]} cmd _]
  (swap! meta-state* update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state* inc)
    :update-meta (swap! meta-state* assoc :updated-meta? true)
    nil))

(defmethod ctrl/derive-state :causal-b [_ state {:keys [causal-a]}]
  (+ state causal-a))

(deftest subscriptions-2
  (let [state* (atom {})
        meta-sub-called-count* (atom {})
        app {:keechma/controllers {:causal-a {:keechma.controller/params true}
                                   :causal-b {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-a]}}}
        app-instance (start! app)
        s! (fn [controller] (subscribe app-instance controller #(swap! state* assoc controller %)))
        sm! (fn [controller] (subscribe-meta app-instance controller (fn [val]
                                                                        (swap! meta-sub-called-count* update controller inc)
                                                                        (swap! state* assoc [:meta controller] val))))]
    (s! :causal-a)
    (s! :causal-b)
    (sm! :causal-a)
    (sm! :causal-b)

    (is (= {:causal-a 1 :causal-b 2} (get-derived-state app-instance)))

    (dispatch app-instance :causal-a :inc)

    (is (= {:causal-a 2 :causal-b 3} (get-derived-state app-instance)))
    (is (= {:causal-a 2
            :causal-b 3
            [:meta :causal-a] {:commands [:keechma.on/start :inc]}
            [:meta :causal-b] {:commands [:keechma.on/start :keechma.on/deps-change]}}
           @state*))
    (is (= {:causal-a 1 :causal-b 1} @meta-sub-called-count*))

    (dispatch app-instance :causal-b :update-meta)

    (is (= {:causal-a 2,
            :causal-b 3,
            [:meta :causal-a] {:commands [:keechma.on/start :inc]},
            [:meta :causal-b]
            {:commands [:keechma.on/start :keechma.on/deps-change :update-meta],
             :updated-meta? true}}
           @state*))

    (stop! app-instance)))

(derive ::async-no-transaction :keechma/controller)
(derive ::async-no-transaction-follower :keechma/controller)

(defmethod ctrl/handle ::async-no-transaction [{:keys [state*]} cmd _]
  (when (= :inc cmd)
    (js/setTimeout #(swap! state* inc))))

(defmethod ctrl/handle ::async-no-transaction-follower [{:keys [state* deps-state*]} cmd _]
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (inc (::async-no-transaction @deps-state*)))))

(deftest async-no-transaction
  (let [app {:keechma/controllers {::async-no-transaction {:keechma.controller/params true}
                                   ::async-no-transaction-follower {:keechma.controller/deps [::async-no-transaction]
                                                                    :keechma.controller/params true}}}
        app-instance (start! app)]
    (async done
      (is (= {::async-no-transaction nil ::async-no-transaction-follower nil} (get-derived-state app-instance)))
      (dispatch app-instance ::async-no-transaction :inc)
      (js/setTimeout (fn []
                       (is (= {::async-no-transaction 1 ::async-no-transaction-follower 2} (get-derived-state app-instance)))
                       (done))))))

(derive ::ping :keechma/controller)
(derive ::pong :keechma/controller)

(defmethod ctrl/handle ::ping [{:keys [state*]} cmd _]
  (when (= :ping cmd)
    (swap! state* #(vec (conj % :ping)))))

(defmethod ctrl/handle ::pong [{:keys [state* deps-state*] :as ctrl} cmd _]
  (let [ping-count (count (::ping @deps-state*))]
    ;; Testing "run-to-completion" semantics. Receive functions are automatically
    ;; wrapped in the transact block which ensures that this function will complete
    ;; before Keechma resumes control and sends another :keechma.on/deps-change event
    (when (= :keechma.on/deps-change cmd)
      (is (= (count @state*) (dec ping-count)))
      (when (< (count @state*) 2)
        (ctrl/dispatch ctrl ::ping :ping))
      (swap! state* #(vec (conj % [:pong ping-count]))))))

(deftest ping-pong
  (let [app {:keechma/controllers {::ping {:keechma.controller/params true}
                                   ::pong {:keechma.controller/params true
                                           :keechma.controller/deps [::ping]}}}
        app-instance (start! app)
        state* (atom {:count 0 :result []})
        unsub (subscribe app-instance ::pong (fn [state] (swap! state* #(-> % (assoc :result state) (update :count inc)))))]
    (is (= {::ping nil ::pong nil} (get-derived-state app-instance)))
    (dispatch app-instance ::ping :ping)
    (is (= {::ping [:ping :ping :ping]
            ::pong [[:pong 1] [:pong 2] [:pong 3]]}
           (get-derived-state app-instance)))
    ;; Subscription was called only once, although the controller's state was updated multiple times
    (is (= {:count 1 :result [[:pong 1] [:pong 2] [:pong 3]]}
           @state*))
    (unsub)))

(derive :on-change-test/foo :keechma/controller)
(derive :on-change-test/bar :keechma/controller)
(derive :on-change-test/baz :keechma/controller)

(defmethod ctrl/handle :on-change-test/foo [{:keys [state*]} cmd payload]
  (case cmd
    :inc
    (swap! state* inc)

    nil))

(defmethod ctrl/handle :on-change-test/bar [{:keys [state* deps-state*]} cmd payload]
  (case cmd
    :keechma.on/deps-change
    (when (= 2 (:on-change-test/foo @deps-state*))
      (swap! state* inc))

    :inc
    (swap! state* inc)

    nil))

(defmethod ctrl/start :on-change-test/baz [_ _ _ _]
  [])

(defmethod ctrl/handle :on-change-test/baz [{:keys [state*]} cmd payload]
  (case cmd
    :keechma.on/deps-change
    (swap! state* conj (set (keys payload)))

    nil))

(deftest on-change-event-sends-only-changed-as-payload
  (let [app {:keechma/controllers
             {:on-change-test/foo {:keechma.controller/params true}
              :on-change-test/bar {:keechma.controller/params true
                                   :keechma.controller/deps [:on-change-test/foo]}
              :on-change-test/baz {:keechma.controller/params true
                                   :keechma.controller/deps [:on-change-test/foo :on-change-test/bar]}}}
        app-instance (start! app)]
    (is (= {:on-change-test/foo nil
            :on-change-test/bar nil
            :on-change-test/baz []}
           (get-derived-state app-instance)))
    (dispatch app-instance :on-change-test/foo :inc)
    (is (= {:on-change-test/foo 1
            :on-change-test/bar nil
            :on-change-test/baz [#{:on-change-test/foo}]}
           (get-derived-state app-instance)))
    (dispatch app-instance :on-change-test/foo :inc)
    (is (= {:on-change-test/foo 2
            :on-change-test/bar 1
            :on-change-test/baz [#{:on-change-test/foo}
                                 #{:on-change-test/foo :on-change-test/bar}]}
           (get-derived-state app-instance)))
    (dispatch app-instance :on-change-test/bar :inc)
    (is (= {:on-change-test/foo 2
            :on-change-test/bar 2
            :on-change-test/baz [#{:on-change-test/foo}
                                 #{:on-change-test/foo :on-change-test/bar}
                                 #{:on-change-test/bar}]}
           (get-derived-state app-instance)))
    (transact
      app-instance
      (fn []
        (dispatch app-instance :on-change-test/foo :inc)
        (dispatch app-instance :on-change-test/bar :inc)))
    (is (= {:on-change-test/foo 3
            :on-change-test/bar 3
            :on-change-test/baz [#{:on-change-test/foo}
                                 #{:on-change-test/foo :on-change-test/bar}
                                 #{:on-change-test/bar}
                                 #{:on-change-test/foo :on-change-test/bar}]}))))

(defn noop [& args])

(deftest conformer
  (let [app {:keechma/controllers
             {:counter-1        {:keechma.controller/params true}
              [:counter-2]      {:keechma.controller.factory/produce noop
                                 :keechma.controller/deps            [:counter-1]}
              [:counter-2 :foo] {:keechma.controller/params noop
                                 :keechma.controller/deps   [:counter-1]}
              :current-user     {:keechma.controller/params noop
                                 :keechma.controller/deps   [:counter-1]}}

             :keechma/apps
             {:public    {:keechma/controllers     {:posts {:keechma.controller/params true
                                                            :keechma.controller/type   :public-posts}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [:user-role]}
              :dynamic-app {:keechma.app/should-run? noop
                            :keechma.app/deps [:user-role]
                            :keechma.app/load noop}
              :user      {:keechma/controllers     {:posts {:keechma.controller/params true
                                                            :keechma.controller/type   :user-posts}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [:user-role]}
              :always-on {:keechma/controllers     {:user-role-tracker       {:keechma.controller/params true
                                                                              :keechma.controller/deps   [:user-role]}
                                                    :user-role-tracker-guest {:keechma.controller/params noop
                                                                              :keechma.controller/deps   [:user-role]
                                                                              :keechma.controller/type   :user-role-tracker}
                                                    :user-role-tracker-user  {:keechma.controller/params noop
                                                                              :keechma.controller/deps   [:user-role]
                                                                              :keechma.controller/type   :user-role-tracker}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [:user-role]}}}

        conformed-app {:keechma/controllers
                       {:counter-1
                        {:keechma.controller/params true,
                         :keechma.controller/type :counter-1,
                         :keechma.controller.params/variant :static,
                         :keechma.controller/variant :singleton},
                        [:counter-2]
                        {:keechma.controller.factory/produce noop,
                         :keechma.controller/deps [:counter-1],
                         :keechma.controller/type :counter-2,
                         :keechma.controller/variant :factory},
                        [:counter-2 :foo]
                        {:keechma.controller/params noop,
                         :keechma.controller/deps [:counter-1],
                         :keechma.controller/type :counter-2,
                         :keechma.controller.params/variant :dynamic,
                         :keechma.controller/variant :identity},
                        :current-user
                        {:keechma.controller/params noop,
                         :keechma.controller/deps [:counter-1],
                         :keechma.controller/type :current-user,
                         :keechma.controller.params/variant :dynamic,
                         :keechma.controller/variant :singleton}},
                       :keechma/apps
                       {:public
                        {:keechma/controllers {:posts
                                               {:keechma.controller/params true,
                                                :keechma.controller/type :public-posts,
                                                :keechma.controller.params/variant :static,
                                                :keechma.controller/variant :singleton}},
                         :keechma.app/should-run? noop,
                         :keechma.app/deps [:user-role],
                         :keechma.app/variant :static},
                        :dynamic-app
                        {:keechma.app/should-run? noop,
                         :keechma.app/deps [:user-role],
                         :keechma.app/load noop,
                         :keechma.app/variant :dynamic},
                        :user
                        {:keechma/controllers {:posts
                                               {:keechma.controller/params true,
                                                :keechma.controller/type :user-posts,
                                                :keechma.controller.params/variant :static,
                                                :keechma.controller/variant :singleton}},
                         :keechma.app/should-run? noop,
                         :keechma.app/deps [:user-role],
                         :keechma.app/variant :static},
                        :always-on
                        {:keechma/controllers {:user-role-tracker
                                               {:keechma.controller/params true,
                                                :keechma.controller/deps [:user-role],
                                                :keechma.controller/type :user-role-tracker,
                                                :keechma.controller.params/variant :static,
                                                :keechma.controller/variant :singleton},
                                               :user-role-tracker-guest
                                               {:keechma.controller/params noop,
                                                :keechma.controller/deps [:user-role],
                                                :keechma.controller/type :user-role-tracker,
                                                :keechma.controller.params/variant :dynamic,
                                                :keechma.controller/variant :singleton},
                                               :user-role-tracker-user
                                               {:keechma.controller/params noop,
                                                :keechma.controller/deps [:user-role],
                                                :keechma.controller/type :user-role-tracker,
                                                :keechma.controller.params/variant :dynamic,
                                                :keechma.controller/variant :singleton}},
                         :keechma.app/should-run? noop,
                         :keechma.app/deps [:user-role],
                         :keechma.app/variant :static}}}]
    (is (not= :cljs.spec.alpha/invalid (s/conform :keechma/app conformed-app)))
    (is (= conformed-app (conform app)))))

(derive ::buffered :keechma/controller)

(defmethod ctrl/init ::buffered [ctrl]
  (ctrl/dispatch ctrl ::buffered :lifecycle/init)
  ctrl)

(defmethod ctrl/start ::buffered [ctrl]
  (ctrl/dispatch ctrl ::buffered :lifecycle/start)
  [])

(defmethod ctrl/handle ::buffered [{:keys [state*]} cmd _]
  (swap! state* conj cmd))

(deftest event-buffering-before-start []
  (let [app {:keechma/controllers
             {::buffered {:keechma.controller/params true}}}
        app-instance (start! app)]
    (is (= [:keechma.on/start :lifecycle/init :lifecycle/start] (get-derived-state app-instance ::buffered)))))

;; TODO: Write test to ensure that child apps are not reconciling parent controllers