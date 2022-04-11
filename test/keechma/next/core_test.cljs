(ns keechma.next.core-test
  (:require
   [cljs.test :refer-macros [deftest is testing use-fixtures async]]
   [keechma.next.controller :as ctrl]
   [keechma.next.core :refer [start! stop! subscribe subscribe-meta dispatch broadcast call get-derived-state get-meta-state transact get-app-state*]]
   [keechma.next.fence :refer [make-fence]]
   [keechma.next.conformer :refer [conform]]
   [cljs.spec.alpha :as s]))

#_(use-fixtures :once {:before (fn [] (js/console.clear))})

(def vec-conj (fnil conj []))

(defn log-cmd!
  ([ctrl cmd] (log-cmd! ctrl cmd nil))
  ([{controller-name :keechma.controller/name :keys [cmd-log*]} cmd _]
   (when cmd-log*
     (swap! cmd-log* conj [controller-name cmd]))))

(derive ::counter-1 :keechma/controller)
(derive ::counter-2 :keechma/controller)
(derive ::counter-3 :keechma/controller)

(defmethod ctrl/start ::counter-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/handle ::counter-1 [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    :inc (swap! state* inc)
    nil))

(defmethod ctrl/stop ::counter-2 [ctrl state _]
  (log-cmd! ctrl :keechma.lifecycle/stop)
  (* 2 state))

(defmethod ctrl/start ::counter-2 [ctrl {::keys [counter-1]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-1))

(defmethod ctrl/handle ::counter-2 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (reset! state* (inc (::counter-1 payload)))
    nil))

(defmethod ctrl/start ::counter-3 [ctrl {::keys [counter-2]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-2))

(deftest dispatch-1
  (let [cmd-log* (atom [])
        trace* (atom nil)
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}}
             :keechma/trace (fn [res]
                              (reset! trace* res))}
        app-instance (start! app)]
    (is (= {::counter-1 0} (get-derived-state app-instance)))
    (is (= 0 (get-derived-state app-instance ::counter-1)))
    (is (= [{:type :keechma.app/start, :payload nil}
            {:type :keechma.on/start,
             :payload {:keechma/controller :keechma.next.core-test/counter-1,
                       :event :keechma.on/start,
                       :payload true}}
            {:type :keechma.controller/state*,
             :payload {:keechma/controller :keechma.next.core-test/counter-1,
                       :state.derived/prev 0,
                       :state.derived/next 0,
                       :state/next 0}}
            {:type :keechma.controller/meta-state*,
             :payload {:keechma/controller :keechma.next.core-test/counter-1,
                       :meta-state/prev nil,
                       :meta-state/next nil}}]
           (:log @trace*)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1} (get-derived-state app-instance)))
    (is (= 1 (get-derived-state app-instance ::counter-1)))
    (is (= [[::counter-1 :keechma.lifecycle/start]
            [::counter-1 :keechma.on/start]
            [::counter-1 :inc]]
           @cmd-log*))
    (is (=  [{:type :keechma/dispatch,
              :payload {:keechma/controller :keechma.next.core-test/counter-1,
                        :event :inc,
                        :payload nil}}
             {:type :keechma.controller/state*,
              :payload {:keechma/controller :keechma.next.core-test/counter-1,
                        :state.derived/prev 0,
                        :state.derived/next 1,
                        :state/next 1}}]
            (:log @trace*)))
    (stop! app-instance)))

(deftest dispatch-2
  (let [cmd-log* (atom [])
        trace* (atom nil)
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}
                                   ::counter-2 {:keechma.controller/params true
                                                :cmd-log* cmd-log*
                                                :keechma.controller/deps [::counter-1]}}
             :keechma/trace (fn [res]
                              (reset! trace* res))}
        app-instance (start! app)]
    (is (= {::counter-1 0 ::counter-2 1} (get-derived-state app-instance)))
    (is (= [{:type :keechma.app/start, :payload nil}
            {:type :keechma.on/start,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-1,
              :event :keechma.on/start,
              :payload true}}
            {:type :keechma.controller/state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-1,
              :state.derived/prev 0,
              :state.derived/next 0,
              :state/next 0}}
            {:type :keechma.controller/meta-state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-1,
              :meta-state/prev nil,
              :meta-state/next nil}}
            {:type :keechma.on/start,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-2,
              :event :keechma.on/start,
              :payload true}}
            {:type :keechma.controller/state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-2,
              :state.derived/prev 1,
              :state.derived/next 1,
              :state/next 1}}
            {:type :keechma.controller/meta-state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-2,
              :meta-state/prev nil,
              :meta-state/next nil}}]
           (:log @trace*)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1 ::counter-2 2} (get-derived-state app-instance)))
    (is (= [{:type :keechma/dispatch,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-1,
              :event :inc,
              :payload nil}}
            {:type :keechma.controller/state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-1,
              :state.derived/prev 0,
              :state.derived/next 1,
              :state/next 1}}
            {:type :keechma.on/deps-change,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-2,
              :event :keechma.on/deps-change,
              :payload {:keechma.next.core-test/counter-1 1}}}
            {:type :keechma.controller/state*,
             :payload
             {:keechma/controller :keechma.next.core-test/counter-2,
              :state.derived/prev 1,
              :state.derived/next 2,
              :state/next 2}}]
           (:log @trace*)))
    (stop! app-instance)
    (is (= [[::counter-1 :keechma.lifecycle/start]
            [::counter-1 :keechma.on/start]
            [::counter-2 :keechma.lifecycle/start]
            [::counter-2 :keechma.on/start]
            [::counter-1 :inc]
            [::counter-2 :keechma.on/deps-change]
            [::counter-2 :keechma.on/stop]
            [::counter-2 :keechma.lifecycle/stop]
            [::counter-1 :keechma.on/stop]]
           @cmd-log*))))

(deftest dispatch-3
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}
                                   [::counter-2 1] {:keechma.controller/params true
                                                    :cmd-log* cmd-log*
                                                    :keechma.controller/deps [::counter-1]}}}
        app-instance (start! app)]
    (is (= {::counter-1 0 [::counter-2 1] 1} (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1 [::counter-2 1] 2} (get-derived-state app-instance)))
    (is (= [[::counter-1 :keechma.lifecycle/start]
            [::counter-1 :keechma.on/start]
            [[::counter-2 1] :keechma.lifecycle/start]
            [[::counter-2 1] :keechma.on/start]
            [::counter-1 :inc]
            [[::counter-2 1] :keechma.on/deps-change]]
           @cmd-log*))
    (stop! app-instance)))

(deftest dispatch-4
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}
                                   [::counter-2] {:keechma.controller.factory/produce
                                                  (fn [{::keys [counter-1]}]
                                                    (->> (range counter-1 (+ 2 counter-1))
                                                         (map (fn [i] [(inc i) {:keechma.controller/params {::counter-1 counter-1}}]))
                                                         (into {})))
                                                  :keechma.controller/deps [::counter-1]
                                                  :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {::counter-1 0 [::counter-2 1] 1 [::counter-2 2] 1} (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1 [::counter-2 2] 2 [::counter-2 3] 2} (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 2 [::counter-2 3] 3 [::counter-2 4] 3} (get-derived-state app-instance)))
    (is (= [[::counter-1 :keechma.lifecycle/start]
            [::counter-1 :keechma.on/start]
            [[::counter-2 1] :keechma.lifecycle/start]
            [[::counter-2 1] :keechma.on/start]
            [[::counter-2 2] :keechma.lifecycle/start]
            [[::counter-2 2] :keechma.on/start]
            [::counter-1 :inc]
            [[::counter-2 1] :keechma.on/stop]
            [[::counter-2 1] :keechma.lifecycle/stop]
            [[::counter-2 2] :keechma.on/stop]
            [[::counter-2 2] :keechma.lifecycle/stop]
            [[::counter-2 2] :keechma.lifecycle/start]
            [[::counter-2 2] :keechma.on/start]
            [[::counter-2 3] :keechma.lifecycle/start]
            [[::counter-2 3] :keechma.on/start]
            [::counter-1 :inc]
            [[::counter-2 2] :keechma.on/stop]
            [[::counter-2 2] :keechma.lifecycle/stop]
            [[::counter-2 3] :keechma.on/stop]
            [[::counter-2 3] :keechma.lifecycle/stop]
            [[::counter-2 3] :keechma.lifecycle/start]
            [[::counter-2 3] :keechma.on/start]
            [[::counter-2 4] :keechma.lifecycle/start]
            [[::counter-2 4] :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(deftest dispatch-5
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}
                                   [::counter-2] {:keechma.controller.factory/produce
                                                  (fn [{::keys [counter-1]}]
                                                    (->> (range counter-1 (+ 2 counter-1))
                                                         (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                         (into {})))
                                                  :keechma.controller/deps [::counter-1]
                                                  :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {::counter-1 0 [::counter-2 1] 1 [::counter-2 2] 1} (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1 [::counter-2 2] 2 [::counter-2 3] 1} (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 2 [::counter-2 3] 3 [::counter-2 4] 1} (get-derived-state app-instance)))
    (is (= [[::counter-1 :keechma.lifecycle/start]
            [::counter-1 :keechma.on/start]
            [[::counter-2 1] :keechma.lifecycle/start]
            [[::counter-2 1] :keechma.on/start]
            [[::counter-2 2] :keechma.lifecycle/start]
            [[::counter-2 2] :keechma.on/start]
            [::counter-1 :inc]
            [[::counter-2 1] :keechma.on/stop]
            [[::counter-2 1] :keechma.lifecycle/stop]
            [[::counter-2 2] :keechma.on/deps-change]
            [[::counter-2 3] :keechma.lifecycle/start]
            [[::counter-2 3] :keechma.on/start]
            [::counter-1 :inc]
            [[::counter-2 2] :keechma.on/stop]
            [[::counter-2 2] :keechma.lifecycle/stop]
            [[::counter-2 3] :keechma.on/deps-change]
            [[::counter-2 4] :keechma.lifecycle/start]
            [[::counter-2 4] :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(deftest dispatch-6
  (let [cmd-log* (atom [])
        app {:keechma/controllers
             {::counter-1 {:keechma.controller/params true
                           :cmd-log* cmd-log*}
              [::counter-2] {:keechma.controller.factory/produce
                             (fn [{::keys [counter-1]}]
                               (->> (range counter-1 (+ 2 counter-1))
                                    (map (fn [i] [(inc i) {:keechma.controller/params {::counter-1 counter-1}}]))
                                    (into {})))
                             :keechma.controller/deps [::counter-1]
                             :cmd-log* cmd-log*}
              [::counter-3] {:keechma.controller.factory/produce
                             (fn [deps]
                               (->> deps
                                    (map (fn [[[_ counter-2-id] val]]
                                           [counter-2-id {:keechma.controller/params {::counter-2 val}}]))
                                    (into {})))
                             :keechma.controller/deps [[::counter-2]]
                             :cmd-log* cmd-log*}}}
        app-instance (start! app)]

    (is (= {::counter-1 0
            [::counter-2 1] 1
            [::counter-2 2] 1
            [::counter-3 1] 2
            [::counter-3 2] 2}
           (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1
            [::counter-2 2] 2
            [::counter-2 3] 2
            [::counter-3 2] 3
            [::counter-3 3] 3}
           (get-derived-state app-instance)))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 2
            [::counter-2 3] 3
            [::counter-2 4] 3
            [::counter-3 3] 4
            [::counter-3 4] 4}
           (get-derived-state app-instance)))
    (stop! app-instance)))

(derive ::token :keechma/controller)
(derive ::current-user :keechma/controller)
(derive ::login :keechma/controller)

(defmethod ctrl/handle ::token [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-token (reset! state* payload)
    nil))

(defmethod ctrl/handle ::current-user [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-user (reset! state* payload)
    nil))

(defmethod ctrl/handle ::login [ctrl cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :do-login (js/setTimeout #(ctrl/transact ctrl
                                             (fn []
                                               (ctrl/dispatch ctrl ::token :update-token "TOKEN")
                                               (ctrl/dispatch ctrl ::current-user :update-user {:id 1 :username "retro"}))))
    nil))

(deftest transactions
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::token {:keechma.controller/params true
                                            :cmd-log* cmd-log*}
                                   ::current-user {:keechma.controller/params true
                                                   :keechma.controller/deps [::token]
                                                   :cmd-log* cmd-log*}
                                   ::login {:keechma.controller/params (fn [{::keys [token]}] (not token))
                                            :keechma.controller/deps [::token ::current-user]
                                            :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (async done
           (is (= {::token nil ::current-user nil ::login nil} (get-derived-state app-instance)))
           (is (= [[::token :keechma.on/start]
                   [::current-user :keechma.on/start]
                   [::login :keechma.on/start]]
                  @cmd-log*))
           (dispatch app-instance ::login :do-login)
           (js/setTimeout
            (fn []
              (is (= {::current-user {:id 1 :username "retro"}
                      ::token "TOKEN"}
                     (get-derived-state app-instance)))
              (is (= [;; Start phase
                      [::token :keechma.on/start]
                      [::current-user :keechma.on/start]
                      [::login :keechma.on/start]
                  ;; Sending :do-login cmd to the ::login controller
                      [::login :do-login]
                  ;; Wrapping :do-login action in transact block ensures correct ordering of events
                      [::token :update-token]
                      [::current-user :update-user]
                  ;; Only after the actions in the transact block are done, keechma resumes control and sends pending actions
                      [::current-user :keechma.on/deps-change]
                      [::login :keechma.on/stop]]
                     @cmd-log*))
              (done))))))

(derive ::causal-1 :keechma/controller)
(derive ::causal-2 :keechma/controller)
(derive ::causal-3 :keechma/controller)

(defmethod ctrl/start ::causal-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/handle ::causal-1 [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/start ::causal-2 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (::causal-1 deps-state)))

(defmethod ctrl/handle ::causal-2 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (inc (::causal-1 payload)))))

(defmethod ctrl/start ::causal-3 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (::causal-2 deps-state)))

(defmethod ctrl/handle ::causal-3 [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (inc (::causal-2 payload)))))

(deftest causal
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::causal-1 {:keechma.controller/params true
                                               :cmd-log* cmd-log*}
                                   ::causal-2 {:keechma.controller/params true
                                               :keechma.controller/deps [::causal-1]
                                               :cmd-log* cmd-log*}
                                   ::causal-3 {:keechma.controller/params true
                                               :keechma.controller/deps [::causal-2]
                                               :cmd-log* cmd-log*}}}
        app-instance (start! app)]
    (is (= {::causal-1 0 ::causal-2 1 ::causal-3 2} (get-derived-state app-instance)))
    (dispatch app-instance ::causal-1 :inc)
    (is (= {::causal-1 1 ::causal-2 2 ::causal-3 3} (get-derived-state app-instance)))
    (dispatch app-instance ::causal-1 :inc)
    (is (= {::causal-1 2 ::causal-2 3 ::causal-3 4} (get-derived-state app-instance)))
    (is (= [[::causal-1 :keechma.lifecycle/start]
            [::causal-1 :keechma.on/start]
            [::causal-2 :keechma.lifecycle/start]
            [::causal-2 :keechma.on/start]
            [::causal-3 :keechma.lifecycle/start]
            [::causal-3 :keechma.on/start]
            [::causal-1 :inc]
            [::causal-2 :keechma.on/deps-change]
            [::causal-3 :keechma.on/deps-change]
            [::causal-1 :inc]
            [::causal-2 :keechma.on/deps-change]
            [::causal-3 :keechma.on/deps-change]]
           @cmd-log*))))

(derive ::user-role :keechma/controller)
(derive ::user-posts :keechma/controller)
(derive ::public-posts :keechma/controller)
(derive ::user-role-tracker :keechma/controller)
(derive ::current-post-id :keechma/controller)
(derive ::post-detail :keechma/controller)
(derive ::static :keechma/controller)

(defmethod ctrl/start ::user-role [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :guest)

(defmethod ctrl/handle ::user-role [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    ::login (reset! state* :user)
    :logout (reset! state* :guest)
    nil))

(defmethod ctrl/start ::user-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  ::user-posts)

(defmethod ctrl/start ::public-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  ::public-posts)

(defmethod ctrl/start ::user-role-tracker [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [[(:keechma.controller/name ctrl) (::user-role deps-state)]])

(defmethod ctrl/handle ::user-role-tracker [{:keys [state*] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (swap! state* conj [(:keechma.controller/name ctrl) (::user-role payload)])
    nil))

(defmethod ctrl/handle ::current-post-id [{:keys [state*] :as ctrl} cmd _]
  (log-cmd! ctrl cmd)
  (case cmd
    :open (swap! state* inc)
    :close (reset! state* nil)
    nil))

(defmethod ctrl/start ::post-detail [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [::post-detail (::current-post-id deps-state)])

(defmethod ctrl/start ::static [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  ::static)

(deftest subapps-1
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::user-role {:keechma.controller/params true
                                                :cmd-log* cmd-log*}}
             :keechma/apps {:public {:keechma/controllers {:posts {:keechma.controller/params true
                                                                   :keechma.controller/type ::public-posts
                                                                   :cmd-log* cmd-log*}}
                                     :keechma.app/should-run? (fn [{::keys [user-role]}] (= :guest user-role))
                                     :keechma.app/deps [::user-role]}
                            :user {:keechma/controllers {:posts {:keechma.controller/params true
                                                                 :keechma.controller/type ::user-posts
                                                                 :cmd-log* cmd-log*}}
                                   :keechma.app/should-run? (fn [{::keys [user-role]}] (= :user user-role))
                                   :keechma.app/deps [::user-role]}
                            :always-on {:keechma/controllers {::user-role-tracker {:keechma.controller/params true
                                                                                   :keechma.controller/deps [::user-role]
                                                                                   :cmd-log* cmd-log*}
                                                              ::user-role-tracker-guest {:keechma.controller/params (fn [{::keys [user-role]}] (= :guest user-role))
                                                                                         :keechma.controller/deps [::user-role]
                                                                                         :keechma.controller/type ::user-role-tracker
                                                                                         :cmd-log* cmd-log*}
                                                              ::user-role-tracker-user {:keechma.controller/params (fn [{::keys [user-role]}] (= :user user-role))
                                                                                        :keechma.controller/deps [::user-role]
                                                                                        :keechma.controller/type ::user-role-tracker
                                                                                        :cmd-log* cmd-log*}}
                                        :keechma.app/should-run? (fn [{::keys [user-role]}] user-role)
                                        :keechma.app/deps [::user-role]}}}
        app-instance (start! app)]
    (is (= {::user-role :guest
            :posts ::public-posts
            ::user-role-tracker-guest [[::user-role-tracker-guest :guest]]
            ::user-role-tracker [[::user-role-tracker :guest]]}
           (get-derived-state app-instance)))
    (dispatch app-instance ::user-role ::login)
    (is (= {::user-role :user
            :posts ::user-posts
            ::user-role-tracker [[::user-role-tracker :guest] [::user-role-tracker :user]]
            ::user-role-tracker-user [[::user-role-tracker-user :user]]}
           (get-derived-state app-instance)))

    (dispatch app-instance ::user-role :logout)
    (is (= {::user-role :guest
            ::user-role-tracker-guest [[::user-role-tracker-guest :guest]]
            ::user-role-tracker
            [[::user-role-tracker :guest]
             [::user-role-tracker :user]
             [::user-role-tracker :guest]]
            :posts ::public-posts}
           (get-derived-state app-instance)))

    (is (= [[::user-role :keechma.lifecycle/start]
            [::user-role :keechma.on/start]
            [:posts :keechma.lifecycle/start]
            [::user-role-tracker-guest :keechma.lifecycle/start]
            [::user-role-tracker-guest :keechma.on/start]
            [::user-role-tracker :keechma.lifecycle/start]
            [::user-role-tracker :keechma.on/start]
            [::user-role ::login]
            [:posts :keechma.lifecycle/start]
            [::user-role-tracker-user :keechma.lifecycle/start]
            [::user-role-tracker-user :keechma.on/start]
            [::user-role-tracker-guest :keechma.on/stop]
            [::user-role-tracker :keechma.on/deps-change]
            [::user-role :logout]
            [:posts :keechma.lifecycle/start]
            [::user-role-tracker-user :keechma.on/stop]
            [::user-role-tracker-guest :keechma.lifecycle/start]
            [::user-role-tracker-guest :keechma.on/start]
            [::user-role-tracker :keechma.on/deps-change]]
           @cmd-log*))
    (stop! app-instance)))

(deftest subapps-2
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::user-role {:keechma.controller/params true
                                                :cmd-log* cmd-log*}}
             :keechma/apps
             {:user
              {:keechma/controllers {:posts {:keechma.controller/params true
                                             :keechma.controller/type ::public-posts
                                             :cmd-log* cmd-log*}
                                     ::current-post-id {:keechma.controller/params true
                                                        :cmd-log* cmd-log*}}
               :keechma.app/should-run? (fn [{::keys [user-role]}] (= :user user-role))
               :keechma.app/deps [::user-role]
               :keechma/apps
               {::post-details
                {:keechma/controllers {::post-detail {:keechma.controller/params (fn [{::keys [current-post-id]}] current-post-id)
                                                      :keechma.controller/deps [::current-post-id]
                                                      :cmd-log* cmd-log*}
                                       ::static {:keechma.controller/params true
                                                 :cmd-log* cmd-log*}}
                 :keechma.app/should-run? (fn [{::keys [current-post-id]}] current-post-id)
                 :keechma.app/deps [::current-post-id]}}}}}
        app-instance (start! app)]
    (is (= {::user-role :guest} (get-derived-state app-instance)))

    (dispatch app-instance ::user-role ::login)
    (is (= {::user-role :user
            :posts ::public-posts
            ::current-post-id nil} (get-derived-state app-instance)))

    (dispatch app-instance ::current-post-id :open)
    (is (= {::user-role :user
            :posts ::public-posts
            ::current-post-id 1
            ::static ::static
            ::post-detail [::post-detail 1]}
           (get-derived-state app-instance)))

    (dispatch app-instance ::current-post-id :open)
    (is (= {::user-role :user
            :posts ::public-posts
            ::current-post-id 2
            ::static ::static
            ::post-detail [::post-detail 2]}
           (get-derived-state app-instance)))

    (dispatch app-instance ::current-post-id :close)
    (is (= {::user-role :user
            :posts ::public-posts
            ::current-post-id nil}
           (get-derived-state app-instance)))

    (is (= [[::user-role :keechma.lifecycle/start]
            [::user-role :keechma.on/start]
            [::user-role ::login]
            [:posts :keechma.lifecycle/start]
            [::current-post-id :keechma.on/start]
            [::current-post-id :open]
            [::static :keechma.lifecycle/start]
            [::post-detail :keechma.lifecycle/start]
            [::current-post-id :open]
            [::post-detail :keechma.lifecycle/start]
            [::current-post-id :close]]
           @cmd-log*))
    (stop! app-instance)))

(deftest subscriptions-1
  (let [cmd-log* (atom [])
        state* (atom {})
        app {:keechma/controllers {::counter-1 {:keechma.controller/params true
                                                :cmd-log* cmd-log*}
                                   [::counter-2] {:keechma.controller.factory/produce
                                                  (fn [{::keys [counter-1]}]
                                                    (->> (range counter-1 (+ 2 counter-1))
                                                         (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                         (into {})))
                                                  :keechma.controller/deps [::counter-1]
                                                  :cmd-log* cmd-log*}}}
        app-instance (start! app)
        s! (fn [controller] (subscribe app-instance controller #(swap! state* assoc controller %)))]

    (s! ::counter-1)
    (s! [::counter-2 1])
    (s! [::counter-2 2])
    (s! [::counter-2 3])
    (s! [::counter-2 4])

    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 1 [::counter-2 2] 2 [::counter-2 3] 1} (get-derived-state app-instance)))
    (is (= {::counter-1 1, [::counter-2 1] nil, [::counter-2 2] 2, [::counter-2 3] 1, [::counter-2 4] nil} @state*))
    (dispatch app-instance ::counter-1 :inc)
    (is (= {::counter-1 2 [::counter-2 3] 3 [::counter-2 4] 1} (get-derived-state app-instance)))
    (is (= {::counter-1 2, [::counter-2 1] nil, [::counter-2 2] nil, [::counter-2 3] 3, [::counter-2 4] 1} @state*))

    (stop! app-instance)))

(derive ::causal-a :keechma/controller)
(derive ::causal-b :keechma/controller)

(defmethod ctrl/start ::causal-a [_ _ _ _]
  1)

(defmethod ctrl/handle ::causal-a [{:keys [state* meta-state*] :as _} cmd _]
  (swap! meta-state* update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state* inc)
    nil))

(defmethod ctrl/start ::causal-b [_ _ _ _]
  1)

(defmethod ctrl/handle ::causal-b [{:keys [state* meta-state*]} cmd _]
  (swap! meta-state* update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state* inc)
    :update-meta (swap! meta-state* assoc :updated-meta? true)
    nil))

(defmethod ctrl/derive-state ::causal-b [_ state {::keys [causal-a]}]
  (+ state causal-a))

(deftest subscriptions-2
  (let [state* (atom {})
        meta-sub-called-count* (atom {})
        app {:keechma/controllers {::causal-a {:keechma.controller/params true}
                                   ::causal-b {:keechma.controller/params true
                                               :keechma.controller/deps [::causal-a]}}}
        app-instance (start! app)
        s! (fn [controller] (subscribe app-instance controller #(swap! state* assoc controller %)))
        sm! (fn [controller] (subscribe-meta app-instance controller (fn [val]
                                                                       (swap! meta-sub-called-count* update controller inc)
                                                                       (swap! state* assoc [:meta controller] val))))]
    (s! ::causal-a)
    (s! ::causal-b)
    (sm! ::causal-a)
    (sm! ::causal-b)

    (is (= {::causal-a 1 ::causal-b 2} (get-derived-state app-instance)))

    (dispatch app-instance ::causal-a :inc)

    (is (= {::causal-a 2 ::causal-b 3} (get-derived-state app-instance)))
    (is (= {::causal-a 2
            ::causal-b 3
            [:meta ::causal-a] {:commands [:keechma.on/start :inc]}
            [:meta ::causal-b] {:commands [:keechma.on/start :keechma.on/deps-change]}}
           @state*))
    (is (= {::causal-a 1 ::causal-b 1} @meta-sub-called-count*))

    (dispatch app-instance ::causal-b :update-meta)

    (is (= {::causal-a 2
            ::causal-b 3
            [:meta ::causal-a] {:commands [:keechma.on/start :inc]}
            [:meta ::causal-b]
            {:commands [:keechma.on/start :keechma.on/deps-change :update-meta]
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
             {::counter-1        {:keechma.controller/params true}
              [::counter-2]      {:keechma.controller.factory/produce noop
                                  :keechma.controller/deps            [::counter-1]}
              [::counter-2 :foo] {:keechma.controller/params noop
                                  :keechma.controller/deps   [::counter-1]}
              ::current-user     {:keechma.controller/params noop
                                  :keechma.controller/deps   [::counter-1]}}

             :keechma/apps
             {:public    {:keechma/controllers     {:posts {:keechma.controller/params true
                                                            :keechma.controller/type   ::public-posts}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [::user-role]}
              :dynamic-app {:keechma.app/should-run? noop
                            :keechma.app/deps [::user-role]
                            :keechma.app/load noop}
              :user      {:keechma/controllers     {:posts {:keechma.controller/params true
                                                            :keechma.controller/type   ::user-posts}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [::user-role]}
              :always-on {:keechma/controllers     {::user-role-tracker       {:keechma.controller/params true
                                                                               :keechma.controller/deps   [::user-role]}
                                                    ::user-role-tracker-guest {:keechma.controller/params noop
                                                                               :keechma.controller/deps   [::user-role]
                                                                               :keechma.controller/type   ::user-role-tracker}
                                                    ::user-role-tracker-user  {:keechma.controller/params noop
                                                                               :keechma.controller/deps   [::user-role]
                                                                               :keechma.controller/type   ::user-role-tracker}}
                          :keechma.app/should-run? noop
                          :keechma.app/deps        [::user-role]}}}


        conformed-app {:keechma/controllers
                       {::counter-1
                        {:keechma.controller/params true
                         :keechma.controller/type ::counter-1
                         :keechma.controller/variant :singleton
                         :keechma.controller.params/variant :static
                         :keechma.controller.type/variant :static}
                        [::counter-2]
                        {:keechma.controller.factory/produce
                         noop
                         :keechma.controller/deps [::counter-1]
                         :keechma.controller/type ::counter-2
                         :keechma.controller/variant :factory
                         :keechma.controller.deps/renamed {}
                         :keechma.controller.type/variant :static}
                        [::counter-2 :foo]
                        {:keechma.controller/params noop
                         :keechma.controller/deps [::counter-1]
                         :keechma.controller/type ::counter-2
                         :keechma.controller/variant :identity
                         :keechma.controller.deps/renamed {}
                         :keechma.controller.params/variant :dynamic
                         :keechma.controller.type/variant :static}
                        ::current-user
                        {:keechma.controller/params noop
                         :keechma.controller/deps [::counter-1]
                         :keechma.controller/type ::current-user
                         :keechma.controller/variant :singleton
                         :keechma.controller.deps/renamed {}
                         :keechma.controller.params/variant :dynamic
                         :keechma.controller.type/variant :static}}
                       :keechma/apps
                       {:public
                        {:keechma/controllers
                         {:posts
                          {:keechma.controller/params true
                           :keechma.controller/type ::public-posts
                           :keechma.controller/variant :singleton
                           :keechma.controller.params/variant :static
                           :keechma.controller.type/variant :static}}
                         :keechma.app/should-run? noop
                         :keechma.app/deps [::user-role]
                         :keechma.app/variant :static}
                        :dynamic-app
                        {:keechma.app/should-run? noop
                         :keechma.app/deps [::user-role]
                         :keechma.app/load noop
                         :keechma.app/variant :dynamic}
                        :user
                        {:keechma/controllers
                         {:posts
                          {:keechma.controller/params true
                           :keechma.controller/type ::user-posts
                           :keechma.controller/variant :singleton
                           :keechma.controller.params/variant :static
                           :keechma.controller.type/variant :static}}
                         :keechma.app/should-run? noop
                         :keechma.app/deps [::user-role]
                         :keechma.app/variant :static}
                        :always-on
                        {:keechma/controllers
                         {::user-role-tracker
                          {:keechma.controller/params true
                           :keechma.controller/deps [::user-role]
                           :keechma.controller/type ::user-role-tracker
                           :keechma.controller/variant :singleton
                           :keechma.controller.deps/renamed {}
                           :keechma.controller.params/variant :static
                           :keechma.controller.type/variant :static}
                          ::user-role-tracker-guest
                          {:keechma.controller/params noop
                           :keechma.controller/deps [::user-role]
                           :keechma.controller/type ::user-role-tracker
                           :keechma.controller/variant :singleton
                           :keechma.controller.deps/renamed {}
                           :keechma.controller.params/variant :dynamic
                           :keechma.controller.type/variant :static}
                          ::user-role-tracker-user
                          {:keechma.controller/params noop
                           :keechma.controller/deps [::user-role]
                           :keechma.controller/type ::user-role-tracker
                           :keechma.controller/variant :singleton
                           :keechma.controller.deps/renamed {}
                           :keechma.controller.params/variant :dynamic
                           :keechma.controller.type/variant :static}}
                         :keechma.app/should-run? noop
                         :keechma.app/deps [::user-role]
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

(derive ::merged-1 :keechma/controller)
(derive ::merged-2 :keechma/controller)

(defmethod ctrl/start ::merged-1 [_ _ _ _]
  true)

(defmethod ctrl/handle ::merged-1 [{:keys [log*] :as controller} ev _]
  (when (= :keechma.on/start ev)
    (reset! log* (set (keys controller)))))

(defmethod ctrl/handle ::merged-2 [{:keys [log*] :as controller} ev _]
  (when (= :keechma.on/start ev)
    (reset! log* (set (keys controller)))))

(deftest merging-configs
  (let [log-1* (atom nil)
        log-2* (atom nil)
        app {:keechma/controllers {::merged-1 {:keechma.controller/params true
                                               :log* log-1*}}
             :keechma/apps {:test {:keechma.app/should-run? (fn [_] true)
                                   :keechma.app/deps [::merged-1]
                                   :keechma/controllers {::merged-2 {:keechma.controller/params true
                                                                     :log* log-2*}}
                                   :test-context true}}
             :main-context true}
        app-instance (start! app)]
    (is (= #{:keechma/is-transacting
             :keechma.controller/type
             :keechma.controller/name
             :keechma.controller/variant
             :keechma.controller/api
             :keechma/app
             :meta-state*
             :log*
             :keechma.controller/params
             :keechma.controller/id
             :deps-state*
             :state*
             :keechma.controller.params/variant
             :keechma.controller.type/variant
             :main-context}
           @log-1*))

    (is (= #{:keechma/is-transacting
             :keechma.controller/type
             :keechma.controller/name
             :keechma.controller/variant
             :keechma.controller/api
             :keechma/app
             :meta-state*
             :log*
             :keechma.controller/params
             :keechma.controller/id
             :deps-state*
             :state*
             :keechma.controller.params/variant
             :keechma.controller.type/variant
             :main-context
             :test-context}
           @log-2*))
    (stop! app-instance)))

(derive ::update-state-without-transaction :keechma/controller)
(derive ::update-state-without-transaction-sub :keechma/controller)

(defmethod ctrl/handle ::update-state-without-transaction [{:keys [state*]} cmd _]
  (when (= :inc cmd)
    (js/setTimeout #(swap! state* inc))))

(defmethod ctrl/handle ::update-state-without-transaction-sub [{:keys [state*]} cmd payload]
  (when (= :keechma.on/deps-change cmd)
    (reset! state* (::update-state-without-transaction payload))))

(deftest updating-controller-state-without-transaction-triggers-subscriptions
  (let [sub-1* (atom nil)
        sub-2* (atom nil)
        app          {:keechma/controllers
                      {::update-state-without-transaction {:keechma.controller/params true}
                       ::update-state-without-transaction-sub {:keechma.controller/params true
                                                               :keechma.controller/deps [::update-state-without-transaction]}}}
        app-instance (start! app)
        unsub-1 (subscribe app-instance ::update-state-without-transaction #(reset! sub-1* %))
        unsub-2 (subscribe app-instance ::update-state-without-transaction-sub #(reset! sub-2* %))]
    (async done
           (dispatch app-instance ::update-state-without-transaction :inc)
           (js/setTimeout (fn []
                            (is (= 1 (get-derived-state app-instance ::update-state-without-transaction)))
                            (is (= 1 (get-derived-state app-instance ::update-state-without-transaction-sub)))
                            (is (= 1 @sub-1* @sub-2*))
                            (unsub-1)
                            (unsub-2)
                            (done))))))

(derive ::dynamic-parent :keechma/controller)
(derive ::dynamic-child-1 :keechma/controller)
(derive ::dynamic-child-2 :keechma/controller)

(defmethod ctrl/start ::dynamic-parent [_ _ _ _]
  0)

(defmethod ctrl/handle ::dynamic-parent [{:keys [state*]} cmd _]
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/start ::dynamic-child-1 [_ _ _ _]
  ::dynamic-child-1)

(defmethod ctrl/start ::dynamic-child-2 [_ _ _ _]
  ::dynamic-child-2)

(deftest dynamic-controller-type
  (let [app {:keechma/controllers
             {::dynamic-parent {:keechma.controller/params true}
              ::dynamic-child {:keechma.controller/deps [::dynamic-parent]
                               :keechma.controller/params #(::dynamic-parent %)
                               :keechma.controller/type #(if (even? (::dynamic-parent %)) ::dynamic-child-1 ::dynamic-child-2)}}}
        app-instance (start! app)]
    (is (= ::dynamic-child-1 (get-derived-state app-instance ::dynamic-child)))
    (dispatch app-instance ::dynamic-parent :inc)
    (is (= ::dynamic-child-2 (get-derived-state app-instance ::dynamic-child)))
    (dispatch app-instance ::dynamic-parent :inc)
    (is (= ::dynamic-child-1 (get-derived-state app-instance ::dynamic-child)))
    (stop! app-instance)))

(derive ::dynamic-controller :keechma/controller)

(defmethod ctrl/prep ::dynamic-controller [ctrl]
  (assoc ctrl :answer 41))

(defmethod ctrl/start ::dynamic-controller [ctrl _ _]
  (-> ctrl :answer inc))

(deftest dynamic-controller-type-calls-prep-correctly
  (let [app {:keechma/controllers
             {::dynamic-controller {:keechma.controller/params true
                                    :keechma.controller/type (constantly ::dynamic-controller)}}}
        app-instance (start! app)]
    (is (= 42 (get-derived-state app-instance ::dynamic-controller)))))

(deftest factory-produced-controllers-with-dynamic-type
  (let [app {:keechma/controllers
             {::dynamic-parent {:keechma.controller/params true}
              [::dynamic-child-1] {:keechma.controller/deps [::dynamic-parent]
                                   :keechma.controller.factory/produce
                                   (fn [deps]
                                     (let [v (::dynamic-parent deps)]
                                       {:instance {:keechma.controller/type (if (even? v) ::dynamic-child-1 ::dynamic-child-2)
                                                   :keechma.controller/params true}}))}}}
        app-instance (start! app)]

    (is (= ::dynamic-child-1 (get-derived-state app-instance [::dynamic-child-1 :instance])))
    (dispatch app-instance ::dynamic-parent :inc)
    (is (= ::dynamic-child-2 (get-derived-state app-instance [::dynamic-child-1 :instance])))
    (dispatch app-instance ::dynamic-parent :inc)
    (is (= ::dynamic-child-1 (get-derived-state app-instance [::dynamic-child-1 :instance])))
    (stop! app-instance)))

(derive ::deps-renaming-parent-1 :keechma/controller)
(derive ::deps-renaming-parent-2 :keechma/controller)
(derive ::deps-renaming-child :keechma/controller)

(defmethod ctrl/start ::deps-renaming-parent-1 [_ _ _ _]
  0)

(defmethod ctrl/start ::deps-renaming-parent-2 [_ _ _ _]
  100)

(defmethod ctrl/handle ::deps-renaming-parent-1 [{:keys [state*]} cmd _]
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/handle ::deps-renaming-parent-2 [{:keys [state*]} cmd _]
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/start ::deps-renaming-child [{:keys [log*]} _ deps-state _]
  (reset! log* [[:lifecycle/start deps-state]])
  nil)

(defmethod ctrl/handle ::deps-renaming-child [{:keys [log* deps-state*] :as ctrl} cmd payload]
  (swap! log* conj [cmd payload @deps-state*]))

(defmethod ctrl/derive-state ::deps-renaming-child [{:keys [log*] :as ctrl} _ deps-state]
  (swap! log* conj [:lifecycle/derive-state deps-state]))

(deftest deps-renaming-1
  (let [log* (atom nil)
        app  {:keechma/controllers
              {::deps-renaming-parent-1 {:keechma.controller/params true}
               ::deps-renaming-parent-2 {:keechma.controller/params true}
               ::deps-renaming-child {:keechma.controller/params true
                                      :keechma.controller/deps [{::deps-renaming-parent-1 :parent-1} ::deps-renaming-parent-2]
                                      :log* log*}}}
        app-instance (start! app)]
    (dispatch app-instance ::deps-renaming-parent-1 :inc)
    (dispatch app-instance ::deps-renaming-parent-2 :inc)
    (is (= [[:lifecycle/start
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:keechma.on/start
             true
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:lifecycle/derive-state
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:keechma.on/deps-change
             {:parent-1 1}
             {:parent-1 1
              ::deps-renaming-parent-2 100}]
            [:lifecycle/derive-state
             {:parent-1 1
              ::deps-renaming-parent-2 100}]
            [:keechma.on/deps-change
             {::deps-renaming-parent-2 101}
             {:parent-1 1
              ::deps-renaming-parent-2 101}]
            [:lifecycle/derive-state
             {:parent-1 1
              ::deps-renaming-parent-2 101}]]
           (get-derived-state app-instance ::deps-renaming-child)))))

(deftest deps-renaming-2
  (let [log* (atom nil)
        app  {:keechma/controllers
              {::deps-renaming-parent-1 {:keechma.controller/params true}
               ::deps-renaming-parent-2 {:keechma.controller/params true}
               ::deps-renaming-child {:keechma.controller/params true
                                      :keechma.controller/deps {::deps-renaming-parent-1 :parent-1
                                                                ::deps-renaming-parent-2 ::deps-renaming-parent-2}
                                      :log* log*}}}
        app-instance (start! app)]
    (dispatch app-instance ::deps-renaming-parent-1 :inc)
    (dispatch app-instance ::deps-renaming-parent-2 :inc)
    (is (= [[:lifecycle/start
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:keechma.on/start
             true
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:lifecycle/derive-state
             {:parent-1 0
              ::deps-renaming-parent-2 100}]
            [:keechma.on/deps-change
             {:parent-1 1}
             {:parent-1 1
              ::deps-renaming-parent-2 100}]
            [:lifecycle/derive-state
             {:parent-1 1
              ::deps-renaming-parent-2 100}]
            [:keechma.on/deps-change
             {::deps-renaming-parent-2 101}
             {:parent-1 1
              ::deps-renaming-parent-2 101}]
            [:lifecycle/derive-state
             {:parent-1 1
              ::deps-renaming-parent-2 101}]]
           (get-derived-state app-instance ::deps-renaming-child)))))

(derive ::rename-factory-parent :keechma/controller)
(derive ::rename-factory :keechma/controller)
(derive ::rename-factory-2 :keechma/controller)
(derive ::rename-factory-2-child :keechma/controller)

(defmethod ctrl/handle ::rename-factory-parent [{:keys [state*] :as ctrl} cmd _]
  (when (= :inc cmd)
    (swap! state* inc)))

(defmethod ctrl/handle ::rename-factory [{:keys [state* deps-state*] :as ctrl} cmd payload]
  (when (= :keechma.on/start cmd)
    (reset! state* @deps-state*)))

(defmethod ctrl/handle ::rename-factory-2 [{:keys [state* deps-state*] :as ctrl} cmd payload]
  (when (= :keechma.on/start cmd)
    (reset! state* @deps-state*)))

(defmethod ctrl/handle ::rename-factory-2-child [{:keys [state* deps-state*] :as ctrl} cmd payload]
  (when (= :keechma.on/start cmd)
    (reset! state* @deps-state*)))

(deftest deps-renaming-3
  (let [app          {:keechma/controllers
                      {::rename-factory-parent {:keechma.controller/params true}
                       [::rename-factory] {:keechma.controller.factory/produce
                                           (fn [{:keys [parent]}]
                                             (-> (map (fn [v] [v {:keechma.controller/params true}]) (range 0 parent))
                                                 (into {})))
                                           :keechma.controller/deps {::rename-factory-parent :parent}}
                       [::rename-factory-2] {:keechma.controller.factory/produce
                                             (fn [deps]
                                               (->> (map (fn [[[type idx]] _]
                                                           [[type idx] {:keechma.controller/params true}]) deps)
                                                    (into {})))
                                             :keechma.controller/deps {[::rename-factory] [:factory]}}
                       ::rename-factory-2-child {:keechma.controller/params
                                                 (fn [deps]
                                                   (seq deps))
                                                 :keechma.controller/deps {[::rename-factory-2] [:factory-2]}}}}
        app-instance (start! app)]
    (dispatch app-instance ::rename-factory-parent :inc)
    (is (= {::rename-factory-parent 1
            [::rename-factory 0] {:parent 1}
            [::rename-factory-2 [:factory 0]]
            {[:factory 0] {:parent 1}}
            ::rename-factory-2-child
            {[:factory-2 [:factory 0]] {[:factory 0] {:parent 1}}}}
           (get-derived-state app-instance)))
    (dispatch app-instance ::rename-factory-parent :inc)
    (is (= {::rename-factory-parent 2
            [::rename-factory 0] {:parent 1}
            [::rename-factory-2 [:factory 0]]
            {[:factory 0] {:parent 1}}
            ::rename-factory-2-child
            {[:factory-2 [:factory 0]] {[:factory 0] {:parent 1}}
             [:factory-2 [:factory 1]]
             {[:factory 0] {:parent 1}, [:factory 1] {:parent 2}}}
            [::rename-factory 1] {:parent 2}
            [::rename-factory-2 [:factory 1]]
            {[:factory 0] {:parent 1}, [:factory 1] {:parent 2}}}
           (get-derived-state app-instance)))))

(derive ::api-provider :keechma/controller)
(derive ::api-consumer :keechma/controller)

(defprotocol TestApi
  (test-api-call [this arg]))

(defmethod ctrl/api ::api-provider [_]
  (reify TestApi
    (test-api-call [_ arg]
      [::called arg])))

(defmethod ctrl/start ::api-consumer [_ _ _ _]
  [])

(defmethod ctrl/handle ::api-consumer [{:keys [state*] :as ctrl} cmd payload]
  (when (= :call cmd)
    (swap! state* conj (ctrl/call ctrl ::api-provider test-api-call payload))))

(deftest controller-api-1
  (let [app {:keechma/controllers {::api-provider {:keechma.controller/params true}
                                   ::api-consumer-1 {:keechma.controller/params true
                                                     :keechma.controller/type ::api-consumer
                                                     :keechma.controller/deps [::api-provider]}
                                   ::api-consumer-2 {:keechma.controller/params true
                                                     :keechma.controller/type ::api-consumer}}}
        app-instance (start! app)]
    (dispatch app-instance ::api-consumer-1 :call {:foo :bar})
    (is (= [[::called {:foo :bar}]]
           (get-derived-state app-instance ::api-consumer-1)))
    (is (thrown? js/Error (dispatch app-instance ::api-consumer-2 :call {:foo :bar})))))

(deftest controller-api-2
  (let [app {:keechma/controllers {::api-provider {:keechma.controller/params true
                                                   :keechma.controller/is-global true}
                                   ::api-consumer-1 {:keechma.controller/params true
                                                     :keechma.controller/type ::api-consumer
                                                     :keechma.controller/deps [::api-provider]}
                                   ::api-consumer-2 {:keechma.controller/params true
                                                     :keechma.controller/type ::api-consumer}}}
        app-instance (start! app)]
    (dispatch app-instance ::api-consumer-1 :call {:foo :bar})
    (dispatch app-instance ::api-consumer-2 :call {:baz :qux})
    (is (= [[::called {:foo :bar}]]
           (get-derived-state app-instance ::api-consumer-1)))
    (is (= [[::called {:baz :qux}]]
           (get-derived-state app-instance ::api-consumer-2)))))

(deftest calling-api-from-controller-that-is-not-in-deps-should-throw
  (let [app {:keechma/controllers {::api-provider {:keechma.controller/params true}
                                   ::api-consumer-1 {:keechma.controller/params true
                                                     :keechma.controller/type ::api-consumer
                                                     :keechma.controller/deps []}}}
        app-instance (start! app)]
    (is (thrown? js/Error (dispatch app-instance ::api-consumer-1 :call {:foo :bar})))))

(derive ::c1 :keechma/controller)
(derive ::c2 :keechma/controller)

(deftest controller-deps-validation-1
  (let [app {:keechma/controllers {::c1 {:keechma.controller/params true}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/deps [::c1 ::c3 ::c4]}}}]
    (is (thrown? js/Error (start! app)))))

(deftest controller-deps-validation-2
  (let [app {:keechma/controllers {::c1 {:keechma.controller/params true}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/deps [::c1 ::c2]}}}]
    (is (thrown? js/Error (start! app)))))

(deftest controller-deps-validation-3
  (let [app {:keechma/controllers {::c1 {:keechma.controller/params true}}
             :keechma/apps
             {:foo {:keechma.app/should-run? (constantly true)
                    :keechma.app/deps [::c1]
                    :keechma/controllers {::c2 {:keechma.controller/params true}}}
              :bar {:keechma.app/should-run? (constantly true)
                    :keechma.app/deps [::c1]
                    :keechma/controllers {::c2 {:keechma.controller/params true}}}}}]
    (is (thrown? js/Error (start! app)))))



(derive ::router :keechma/controller)
(derive ::offers :keechma/controller)
(derive ::profile :keechma/controller)
(derive ::profile-app-should-run? :keechma/controller)
(derive ::profile-details :keechma/controller)

(defmethod ctrl/start ::router [_ _ _ _]
  {:page "offers"})

(defmethod ctrl/handle ::router [{:keys [state*] :as ctrl} cmd val]
  (log-cmd! ctrl cmd)
  (case cmd
    :redirect (reset! state* val)
    nil))

(defmethod ctrl/start ::offers [_ _ _ _]
  {:state "foo"})

(defmethod ctrl/handle ::offers [ctrl cmd _]
  (log-cmd! ctrl cmd))

(defmethod ctrl/stop ::offers [_ _ _ _]
  {:state nil})

(defmethod ctrl/start ::profile [_ _ _ _]
  {:name "john"})

(defmethod ctrl/handle ::profile [ctrl cmd _]
  (log-cmd! ctrl cmd))

(defmethod ctrl/derive-state ::profile-app-should-run? [ctrl state {::keys [router]}]
  (= "profile" (:page router)))

(defmethod ctrl/handle ::profile-details [ctrl cmd _]
  (log-cmd! ctrl cmd))

;; Testing reconciliation behavior with sub apps.
;; - First all controllers in the parent app are reconciled
;; - Then any sub apps are stopped (based on their :keechma.app/should-run? fn)
;; - Then any sub apps are started (based on their :keechma.app/should-run? fn)
(deftest subapps-stop-start-lifecycle
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::router {:keechma.controller/params true
                                             :cmd-log* cmd-log*}
                                   ::profile {:keechma.controller/params (fn [{::keys [router]}] (= "profile" (:page router)))
                                              :keechma.controller/deps [::router]
                                              :cmd-log* cmd-log*}
                                   ::profile-app-should-run? {:keechma.controller/params true
                                                              :keechma.controller/deps [::router]}}
             :keechma/apps {::offers {:keechma/controllers {::offers {:keechma.controller/params true
                                                                      :keechma.controller/deps [::router]
                                                                      :cmd-log* cmd-log*}}
                                      :keechma.app/should-run? (fn [{::keys [router]}] (= "offers" (:page router)))
                                      :keechma.app/deps [::router]}
                            ::profile {:keechma.app/should-run? #(::profile-app-should-run? %)
                                       :keechma.app/deps [::profile-app-should-run?]
                                       :keechma/controllers {::profile-details {:keechma.controller/params true
                                                                                :cmd-log* cmd-log*}}}}}
        app-instance (start! app)]

    (dispatch app-instance ::router :redirect {:page "profile"})
    (is (= [[::router :keechma.on/start]
            [::offers :keechma.on/start]
            [::router :redirect]
            [::profile :keechma.on/start]
            [::offers :keechma.on/stop]
            [::profile-details :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(deftest stop-start-lifecycle
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::router {:keechma.controller/params true
                                             :cmd-log* cmd-log*}
                                   ::profile {:keechma.controller/params (fn [{::keys [router]}] (= "profile" (:page router)))
                                              :keechma.controller/deps [::router]
                                              :cmd-log* cmd-log*}
                                   ::offers {:keechma.controller/params (fn [{::keys [router]}] (= "offers" (:page router)))
                                             :keechma.controller/deps [::router]
                                             :cmd-log* cmd-log*}}}
        app-instance (start! app)]

    (dispatch app-instance ::router :redirect {:page "profile"})
    (is (= [[::router :keechma.on/start]
            [::offers :keechma.on/start]
            [::router :redirect]
            [::offers :keechma.on/stop]
            [::profile :keechma.on/start]]
           @cmd-log*))
    (stop! app-instance)))

(derive ::with-params-parent :keechma/controller)
(derive ::with-params :keechma/controller)

(defmethod ctrl/start ::with-params-parent [_ _ _]
  1)

(defmethod ctrl/handle ::with-params-parent [{:keys [state*]} ev _]
  (when (= :inc ev)
    (swap! state* inc)))

(defmethod ctrl/params ::with-params [_ params]
  (when (even? params) params))

(defmethod ctrl/start ::with-params [_ params _]
  params)

(deftest ctrl-params-1
  (let [app {:keechma/controllers {::with-params-parent {:keechma.controller/params true}
                                   ::with-params {:keechma.controller/deps [::with-params-parent]
                                                  :keechma.controller/params #(::with-params-parent %)}}}
        app-instance (start! app)]
    (is (= {::with-params-parent 1} (get-derived-state app-instance)))
    (dispatch app-instance ::with-params-parent :inc)
    (is (= {::with-params-parent 2
            ::with-params 2} (get-derived-state app-instance)))
    (dispatch app-instance ::with-params-parent :inc)
    (is (= {::with-params-parent 3} (get-derived-state app-instance)))))

(deftest ctrl-params-2
  (let [app {:keechma/controllers {::with-params {:keechma.controller/params 1}}}
        app-instance (start! app)]
    (is (= {} (get-derived-state app-instance)))))

(deftest ctrl-params-3
  (let [app {:keechma/controllers {::with-params {:keechma.controller/params 2}}}
        app-instance (start! app)]
    (is (= {::with-params 2} (get-derived-state app-instance)))))

(derive ::with-params-2 :keechma/controller)

(defmethod ctrl/params ::with-params-2 [ctrl params]
  (is (-> ctrl :keechma.controller/name boolean))
  params)

(defmethod ctrl/start ::with-params-2 [_ params _]
  params)

(deftest ctrl-params-has-access-to-controller-name
  (let [app {:keechma/controllers {::with-params-2 {:keechma.controller/params true}}}
        app-instance (start! app)]
    (is (= {::with-params-2 true} (get-derived-state app-instance)))))

(derive ::start-stop :keechma/controller)
(derive ::start-stop-counter :keechma/controller)


(defmethod ctrl/start ::start-stop [ctrl params deps-state prev-state]
  (vec-conj prev-state [:start deps-state]))

(defmethod ctrl/stop ::start-stop [ctrl params state deps-state]
  (vec-conj state [:stop deps-state]))

(defmethod ctrl/handle ::start-stop-counter [{:keys [state*]} ev _]
  (case ev
    :inc (swap! state* inc)
    :reset (reset! state* nil)
    nil))

(deftest ctrl-start-stop
  (let [app {:keechma/controllers {::start-stop-counter #:keechma.controller{:params true}
                                   ::start-stop #:keechma.controller{:params #(::start-stop-counter %)
                                                                     :deps [::start-stop-counter]}}}
        app-instance (start! app)]
    (is (= {::start-stop-counter nil} (get-derived-state app-instance)))

    (dispatch app-instance ::start-stop-counter :inc)
    (is (= {::start-stop-counter 1
            ::start-stop [[:start {::start-stop-counter 1}]]}
           (get-derived-state app-instance)))

    (dispatch app-instance ::start-stop-counter :inc)
    (is (= {::start-stop-counter 2
            ::start-stop [[:start {::start-stop-counter 1}]
                          [:stop {::start-stop-counter 2}]
                          [:start {::start-stop-counter 2}]]}
           (get-derived-state app-instance)))

    (dispatch app-instance ::start-stop-counter :reset)
    (is (= {::start-stop-counter nil} (get-derived-state app-instance)))

    (dispatch app-instance ::start-stop-counter :inc)
    (is (= {::start-stop-counter 1
            ::start-stop  [[:start {::start-stop-counter 1}]
                           [:stop {::start-stop-counter 2}]
                           [:start {::start-stop-counter 2}]
                           [:stop {::start-stop-counter nil}]
                           [:start {::start-stop-counter 1}]]}
           (get-derived-state app-instance)))))

(deftest multiple-apps-with-same-child-controllers-should-throw
  (let [app {:keechma/controllers {::counter-1 {:keechma.controller/params true}}
             :keechma/apps {:app-1 {:keechma.app/should-run? (constantly true)
                                    :keechma.app/deps [::counter-1]
                                    :keechma/controllers {::counter-2 {:keechma.controller/params true}}}
                            :app-2 {:keechma.app/should-run? (constantly true)
                                    :keechma.app/deps [:counter-1]
                                    :keechma/controllers {::counter-2 {:keechma.controller/params true}}}}}]
    (is (thrown? js/Error (start! app)))))

(deftest subscribing-to-non-existing-controller-throws
  (let [app {:keechma/controllers {::counter-1 {:keechma.controller/params true}}}
        app-instance (start! app)]
    (is (thrown? js/Error (subscribe app-instance :non-existing-controller (constantly nil))))
    (is (thrown? js/Error (subscribe-meta app-instance :non-existing-controller (constantly nil))))))

(derive ::meta-controller :keechma/controller)

(defmethod ctrl/handle ::meta-controller [{:keys [meta-state*]} ev payload]
  (when (= :ping ev)
    (reset! meta-state* payload)))

(deftest meta-state
  (let [meta-state* (atom nil)
        app {:keechma/controllers {::meta-controller {:keechma.controller/params true}}}
        app-instance (start! app)]
    (subscribe-meta app-instance ::meta-controller #(reset! meta-state* %))
    (is (= {::meta-controller nil} (get-meta-state app-instance)))
    (is (nil? (get-meta-state app-instance ::meta-controller)))
    (dispatch app-instance ::meta-controller :ping :foo)
    (is (= {::meta-controller :foo} (get-meta-state app-instance)))
    (is (= :foo (get-meta-state app-instance ::meta-controller)))
    (is (= :foo @meta-state*))))

(deftest fence
  (let [app {:keechma/controllers {::causal-a {:keechma.controller/params true}
                                   ::causal-b {:keechma.controller/params true
                                               :keechma.controller/deps [::causal-a]}}}
        causal-b* (atom nil)
        app-instance (start! app)
        app-fence (make-fence app-instance (fn [app-state _]
                                             (-> app-state ::causal-a odd?)))]
    (subscribe app-fence ::causal-b #(reset! causal-b* %))
    (is (= {::causal-a 1 ::causal-b 2}
           (get-derived-state app-instance)
           (get-derived-state app-fence)))
    (is (= 1
           (get-derived-state app-instance ::causal-a)
           (get-derived-state app-fence ::causal-a)))
    (is (nil? @causal-b*))
    (dispatch app-instance ::causal-a :inc)
    (is (= {::causal-a 2 ::causal-b 3}
           (get-derived-state app-instance)))
    (is (= {::causal-a 1 ::causal-b 2}
           (get-derived-state app-fence)))
    (is (nil? @causal-b*))
    (dispatch app-instance ::causal-a :inc)
    (is (= {::causal-a 3 ::causal-b 4}
           (get-derived-state app-instance)
           (get-derived-state app-fence)))
    (is (= 3
           (get-derived-state app-instance ::causal-a)
           (get-derived-state app-fence ::causal-a)))
    (is (= 4 @causal-b*))))

(derive ::proxy-counter :keechma/controller)

(defmethod ctrl/start ::proxy-counter [_ _ _ _]
  1)

(defmethod ctrl/handle ::proxy-counter [{:keys [meta-state* state*]} ev _]
  (swap! meta-state* update :log vec-conj ev)
  (case ev
    :inc (swap! state* inc)
    :dec (swap! state* dec)
    nil))

(derive ::proxy-counter-event-producer :keechma/controller)

(defmethod ctrl/start ::proxy-counter-event-producer [_ _ _ _]
  1)

(defmethod ctrl/handle ::proxy-counter-event-producer [{:keys [state*] :as ctrl} ev _]
  (case ev
    :inc (do
           (swap! state* inc)
           (ctrl/dispatch ctrl ::proxy-counter :inc))
    :dec (do
           (swap! state* dec)
           (ctrl/dispatch ctrl ::proxy-counter :dec))
    nil))

(derive ::proxy-switch :keechma/controller)

(defmethod ctrl/start ::proxy-switch [_ _ _ _]
  true)

(defmethod ctrl/handle ::proxy-switch [{:keys [state*]} ev _]
  (case ev
    :off (reset! state* false)
    :on (reset! state* true)
    :toggle (swap! state* not)
    nil))

(derive ::proxy-derived-counter :keechma/controller)

(defmethod ctrl/derive-state ::proxy-derived-counter [_ _ {::keys [proxy-counter]}]
  (* 2 proxy-counter))

(defprotocol ProxyTestApi
  (proxy-test-api-call [this arg]))

(derive ::proxy-api-provider :keechma/controller)

(defmethod ctrl/api ::proxy-api-provider [_]
  (reify ProxyTestApi
    (proxy-test-api-call [_ arg]
      [::called arg])))

(derive ::proxy-api-consumer :keechma/controller)

(defmethod ctrl/handle ::proxy-api-consumer [{:keys [state*] :as ctrl} ev payload]
  (case ev
    :call (swap! state* vec-conj (ctrl/call ctrl ::proxy-api-provider proxy-test-api-call payload))
    nil))

(derive ::proxy-event-producer :keechma/controller)

(defmethod ctrl/handle ::proxy-event-producer [ctrl ev _]
  (case ev
    :dispatch (ctrl/dispatch ctrl ::proxy-event-receiver :inc)
    :broadcast (ctrl/broadcast ctrl :inc)
    nil))

(derive ::proxy-event-receiver :keechma/controller)

(defmethod ctrl/start ::proxy-event-receiver [_ _ _ _]
  0)

(defmethod ctrl/handle ::proxy-event-receiver [{:keys [state*]} ev _]
  (case ev
    :inc (swap! state* inc)
    nil))

(deftest proxy-controllers-conform
  (let [app {:keechma/controllers {:proxy-counter {:keechma.controller/proxy ::proxy-counter}}}]
    (is (= {:keechma/controllers {:proxy-counter {:keechma.controller/proxy ::proxy-counter}}
            :keechma/apps {}}
           (conform app)))))

(deftest proxy-controllers-1
  (let [app-1 {:keechma/controllers {::proxy-counter
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-3-instance (start! app-3 app-1-instance)
        app-4 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-4-instance (start! app-4 app-3-instance)
        app-1-proxy* (atom nil)
        app-2-proxy* (atom nil)
        app-3-proxy* (atom nil)
        app-4-proxy* (atom nil)]

    (subscribe app-1-instance ::proxy-counter #(reset! app-1-proxy* %))
    (subscribe app-2-instance ::proxy-counter #(reset! app-2-proxy* %))
    (subscribe app-3-instance ::proxy-counter #(reset! app-3-proxy* %))
    (subscribe app-4-instance ::proxy-counter #(reset! app-4-proxy* %))

    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start :inc]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))
    (is (= 2 @app-1-proxy* @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-2-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start :inc :dec]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))
    (is (= 1 @app-1-proxy* @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-3-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start :inc :dec :inc]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))
    (is (= 2 @app-1-proxy* @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-4-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start :inc :dec :inc :dec]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))
    (is (= 1 @app-1-proxy* @app-2-proxy* @app-3-proxy* @app-4-proxy*))))

(deftest proxy-controllers-2
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-counter
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :deps [::proxy-switch]}
                                     ::proxy-derived-counter
                                     #:keechma.controller {:params #(::proxy-counter %)
                                                           :deps [::proxy-counter]}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}
                                     ::proxy-derived-counter
                                     #:keechma.controller {:params #(::proxy-counter %)
                                                           :deps [::proxy-counter]}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)

        app-1-state* (atom {})
        app-2-state* (atom {})
        app-3-state* (atom {})

        subscribe' (fn [app-instance controller-name state*]
                     (subscribe app-instance controller-name #(swap! state* assoc controller-name %)))]

    (subscribe' app-1-instance ::proxy-switch app-1-state*)
    (subscribe' app-1-instance ::proxy-counter app-1-state*)
    (subscribe' app-1-instance ::proxy-derived-counter app-1-state*)

    (subscribe' app-2-instance ::proxy-counter app-2-state*)
    (subscribe' app-2-instance ::proxy-derived-counter app-2-state*)

    (subscribe' app-3-instance ::proxy-counter app-3-state*)
    (subscribe' app-3-instance ::proxy-derived-counter app-3-state*)

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-switch true
            ::proxy-counter 2
            ::proxy-derived-counter 4}
           @app-1-state*
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 2
            ::proxy-derived-counter 4}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-counter :dec)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)
    (is (= {::proxy-switch false}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch false
            ::proxy-counter nil
            ::proxy-derived-counter nil}
           @app-1-state*))

    (is (= {}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (is (= {::proxy-counter nil
            ::proxy-derived-counter nil}
           @app-2-state*
           @app-3-state*))

    (dispatch app-1-instance ::proxy-switch :on)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))))


(deftest proxy-controllers-3
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-counter
                                                           #:keechma.controller {:params true}
                                                           ::proxy-derived-counter
                                                           #:keechma.controller {:params #(::proxy-counter %)
                                                                                 :deps [::proxy-counter]}}}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}
                                     ::proxy-derived-counter
                                     #:keechma.controller {:params #(::proxy-counter %)
                                                           :deps [::proxy-counter]}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)

        app-1-state* (atom {})
        app-2-state* (atom {})
        app-3-state* (atom {})

        subscribe' (fn [app-instance controller-name state*]
                     (subscribe app-instance controller-name #(swap! state* assoc controller-name %)))]

    (subscribe' app-1-instance ::proxy-switch app-1-state*)
    (subscribe' app-1-instance ::proxy-counter app-1-state*)
    (subscribe' app-1-instance ::proxy-derived-counter app-1-state*)

    (subscribe' app-2-instance ::proxy-counter app-2-state*)
    (subscribe' app-2-instance ::proxy-derived-counter app-2-state*)

    (subscribe' app-3-instance ::proxy-counter app-3-state*)
    (subscribe' app-3-instance ::proxy-derived-counter app-3-state*)

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-switch true
            ::proxy-counter 2
            ::proxy-derived-counter 4}
           @app-1-state*
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 2
            ::proxy-derived-counter 4}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-counter :dec)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)
    (is (= {::proxy-switch false}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch false
            ::proxy-counter nil
            ::proxy-derived-counter nil}
           @app-1-state*))

    (is (= {}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (is (= {::proxy-counter nil
            ::proxy-derived-counter nil}
           @app-2-state*
           @app-3-state*))

    (dispatch app-1-instance ::proxy-switch :on)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-derived-counter 2}
           @app-2-state*
           @app-3-state*
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))))

(deftest proxy-controllers-4
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-counter
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :deps [::proxy-switch]}
                                     ::proxy-derived-counter
                                     #:keechma.controller {:params #(::proxy-counter %)
                                                           :deps [::proxy-counter]}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}
                                                           ::proxy-derived-counter
                                                           #:keechma.controller {:params #(::proxy-counter %)
                                                                                 :deps [::proxy-counter]}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)

        app-1-state* (atom {})
        app-2-state* (atom {})
        app-3-state* (atom {})

        subscribe' (fn [app-instance controller-name state*]
                     (subscribe app-instance controller-name #(swap! state* assoc controller-name %)))]

    (subscribe' app-1-instance ::proxy-switch app-1-state*)
    (subscribe' app-1-instance ::proxy-counter app-1-state*)
    (subscribe' app-1-instance ::proxy-derived-counter app-1-state*)

    (subscribe' app-2-instance ::proxy-switch app-2-state*)
    (subscribe' app-2-instance ::proxy-counter app-2-state*)
    (subscribe' app-2-instance ::proxy-derived-counter app-2-state*)

    (subscribe' app-3-instance ::proxy-switch app-3-state*)
    (subscribe' app-3-instance ::proxy-counter app-3-state*)
    (subscribe' app-3-instance ::proxy-derived-counter app-3-state*)

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-switch :off)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))
    (is (= {::proxy-switch false}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true}
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-switch :on)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)
    (is (= {::proxy-switch false}
           (get-derived-state app-1-instance)))
    (is (= {::proxy-switch true}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))))


(deftest proxy-controllers-5
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-counter
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :deps [::proxy-switch]}
                                     ::proxy-derived-counter
                                     #:keechma.controller {:params #(::proxy-counter %)
                                                           :deps [::proxy-counter]}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}
                                                           ::proxy-derived-counter
                                                           #:keechma.controller {:params #(::proxy-counter %)
                                                                                 :deps [::proxy-counter]}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)

        app-1-state* (atom {})
        app-2-state* (atom {})
        app-3-state* (atom {})

        subscribe' (fn [app-instance controller-name state*]
                     (subscribe app-instance controller-name #(swap! state* assoc controller-name %)))]

    (subscribe' app-1-instance ::proxy-switch app-1-state*)
    (subscribe' app-1-instance ::proxy-counter app-1-state*)
    (subscribe' app-1-instance ::proxy-derived-counter app-1-state*)

    (subscribe' app-2-instance ::proxy-switch app-2-state*)
    (subscribe' app-2-instance ::proxy-counter app-2-state*)
    (subscribe' app-2-instance ::proxy-derived-counter app-2-state*)

    (subscribe' app-3-instance ::proxy-switch app-3-state*)
    (subscribe' app-3-instance ::proxy-counter app-3-state*)
    (subscribe' app-3-instance ::proxy-derived-counter app-3-state*)

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-switch :off)
    (dispatch app-2-instance ::proxy-counter :inc)
    (dispatch app-3-instance ::proxy-counter :inc)
    (is (= {::proxy-switch true
            ::proxy-counter 1
            ::proxy-derived-counter 2}
           (get-derived-state app-1-instance)))
    (is (= {::proxy-switch false}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true}
           (get-derived-state app-3-instance)))))


(deftest proxy-controllers-6a
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-api-provider
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :deps [::proxy-switch]}
                                     ::proxy-api-consumer
                                     #:keechma.controller {:params true
                                                           :deps [::proxy-api-provider]}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-api-provider {:keechma.controller/proxy ::proxy-api-provider}
                                                           ::proxy-api-consumer
                                                           #:keechma.controller {:params true
                                                                                 :deps [::proxy-api-provider]}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)]

    (is (= [::called :foo]
           (call app-1-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-2-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-3-instance ::proxy-api-provider proxy-test-api-call :foo)))

    (dispatch app-1-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)

    (is (= {::proxy-switch false
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (is (thrown? js/Error (dispatch app-1-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-2-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-1-instance ::proxy-switch :on)

    (dispatch app-1-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-switch :off)

    (is (thrown? js/Error (call app-2-instance ::proxy-api-provider proxy-test-api-call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-2-instance ::proxy-switch :on)

    (dispatch app-2-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :bar]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-3-instance)))))

(deftest proxy-controllers-6b
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-api-provider
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :is-global true
                                                           :deps [::proxy-switch]}
                                     ::proxy-api-consumer
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-api-provider {:keechma.controller/proxy ::proxy-api-provider}
                                                           ::proxy-api-consumer
                                                           #:keechma.controller {:params true}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)]

    (is (= [::called :foo]
           (call app-1-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-2-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-3-instance ::proxy-api-provider proxy-test-api-call :foo)))

    (dispatch app-1-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)

    (is (= {::proxy-switch false
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (is (thrown? js/Error (dispatch app-1-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-2-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-1-instance ::proxy-switch :on)

    (dispatch app-1-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-switch :off)

    (is (thrown? js/Error (call app-2-instance ::proxy-api-provider proxy-test-api-call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-2-instance ::proxy-switch :on)

    (dispatch app-2-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :bar]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-3-instance)))))

(deftest proxy-controllers-6c
  (let [app-1 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}
                                     ::proxy-api-provider
                                     #:keechma.controller {:params #(::proxy-switch %)
                                                           :deps [::proxy-switch]}
                                     ::proxy-api-consumer
                                     #:keechma.controller {:params true
                                                           :deps [::proxy-api-provider]}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-api-provider
                                                           #:keechma.controller{:proxy ::proxy-api-provider
                                                                                :is-global true}
                                                           ::proxy-api-consumer
                                                           #:keechma.controller {:params true}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)]



    (is (= [::called :foo]
           (call app-1-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-2-instance ::proxy-api-provider proxy-test-api-call :foo)
           (call app-3-instance ::proxy-api-provider proxy-test-api-call :foo)))

    (dispatch app-1-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :foo)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-switch :off)

    (is (= {::proxy-switch false
            ::proxy-api-consumer [[::called :foo]]}
           (get-derived-state app-1-instance)))

    (is (thrown? js/Error (dispatch app-1-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-2-instance ::proxy-api-consumer :call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-1-instance ::proxy-switch :on)

    (dispatch app-1-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-1-instance)))

    (dispatch app-2-instance ::proxy-switch :off)

    (is (thrown? js/Error (call app-2-instance ::proxy-api-provider proxy-test-api-call :bar)))
    (is (thrown? js/Error (dispatch app-3-instance ::proxy-api-consumer :call :bar)))

    (dispatch app-2-instance ::proxy-switch :on)

    (dispatch app-2-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :bar]]}
           (get-derived-state app-2-instance)))

    (dispatch app-3-instance ::proxy-api-consumer :call :bar)
    (is (= {::proxy-switch true
            ::proxy-api-provider nil
            ::proxy-api-consumer [[::called :foo]
                                  [::called :bar]]}
           (get-derived-state app-3-instance)))))

(deftest proxy-controllers-7
  (let [app-1 {:keechma/controllers {::proxy-event-producer
                                     #:keechma.controller {:params true}
                                     ::proxy-event-receiver
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-switch
                                     #:keechma.controller {:params true}}
               :keechma/apps {:app1 {:keechma.app/should-run? #(::proxy-switch %)
                                     :keechma.app/deps [::proxy-switch]
                                     :keechma/controllers {::proxy-event-producer
                                                           #:keechma.controller{:proxy ::proxy-event-producer}
                                                           ::proxy-event-receiver
                                                           #:keechma.controller {:params true}}}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3-instance (start! app-2 app-2-instance)]

    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 0}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 0}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-event-producer :dispatch)

    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 1}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-switch :off)
    (dispatch app-1-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 3}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch false}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-receiver 2}
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 4}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch false}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-receiver 2}
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-switch :on)

    (dispatch app-2-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 5}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 1}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 3}
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 6}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 2}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 4}
           (get-derived-state app-3-instance)))

    (dispatch app-3-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 7}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 3}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 5}
           (get-derived-state app-3-instance)))

    (dispatch app-3-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 8}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 4}
           (get-derived-state app-2-instance)))

    (is (= {::proxy-switch true
            ::proxy-event-producer nil
            ::proxy-event-receiver 6}
           (get-derived-state app-3-instance)))))


(deftest proxy-controllers-8
  (let [app-1 {:keechma/controllers {::proxy-event-producer
                                     #:keechma.controller {:params true}
                                     ::proxy-event-receiver
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-2 {:keechma/controllers {::proxy-event-producer
                                     #:keechma.controller {:proxy ::proxy-event-producer}
                                     [::proxy-event-receiver :proxy-1]
                                     #:keechma.controller {:proxy ::proxy-event-receiver}
                                     [::proxy-event-receiver :proxy-2]
                                     #:keechma.controller {:proxy ::proxy-event-receiver}
                                     ::proxy-event-receiver
                                     #:keechma.controller {:params true}
                                     ::proxy-event-receiver-foo
                                     #:keechma.controller {:params true
                                                           :type ::proxy-event-receiver}}}
        app-2-instance (start! app-2 app-1-instance)
        app-3 {:keechma/controllers {::proxy-event-producer
                                     #:keechma.controller {:proxy ::proxy-event-producer}
                                     [::proxy-event-receiver :proxy-1]
                                     #:keechma.controller {:proxy [::proxy-event-receiver :proxy-1]}
                                     [::proxy-event-receiver :proxy-2]
                                     #:keechma.controller {:proxy [::proxy-event-receiver :proxy-2]}
                                     ::proxy-event-receiver
                                     #:keechma.controller {:params true}
                                     ::proxy-event-receiver-foo
                                     #:keechma.controller {:params true
                                                           :type ::proxy-event-receiver}}}
        app-3-instance (start! app-3 app-2-instance)]

    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 0}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 0
            [::proxy-event-receiver :proxy-2] 0
            ::proxy-event-receiver 0
            ::proxy-event-receiver-foo 0}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 1}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 1
            [::proxy-event-receiver :proxy-2] 1
            ::proxy-event-receiver 1
            ::proxy-event-receiver-foo 0}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-1-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 2
            [::proxy-event-receiver :proxy-2] 2
            ::proxy-event-receiver 2
            ::proxy-event-receiver-foo 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 3}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 3
            [::proxy-event-receiver :proxy-2] 3
            ::proxy-event-receiver 3
            ::proxy-event-receiver-foo 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-2-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 4}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 4
            [::proxy-event-receiver :proxy-2] 4
            ::proxy-event-receiver 4
            ::proxy-event-receiver-foo 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-3-instance ::proxy-event-producer :dispatch)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 5}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 5
            [::proxy-event-receiver :proxy-2] 5
            ::proxy-event-receiver 5
            ::proxy-event-receiver-foo 2}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (dispatch app-3-instance ::proxy-event-producer :broadcast)
    (is (= {::proxy-event-producer nil
            ::proxy-event-receiver 6}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-event-producer nil
            [::proxy-event-receiver :proxy-1] 6
            [::proxy-event-receiver :proxy-2] 6
            ::proxy-event-receiver 6
            ::proxy-event-receiver-foo 3}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))))


(deftest proxy-controllers+fence-1
  (let [app-1 {:keechma/controllers {::proxy-counter
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-1-fence (make-fence app-1-instance (fn [app-state _]
                                                 (-> app-state ::proxy-counter odd?)))
        app-2 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-2-instance (start! app-2 app-1-fence)
        app-3 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-3-instance (start! app-3 app-1-fence)
        app-3-fence (make-fence app-2-instance (fn [app-state _]
                                                 (= 3 (::proxy-counter app-state))))
        app-4 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-4-instance (start! app-4 app-3-fence)
        app-1-proxy* (atom nil)
        app-2-proxy* (atom nil)
        app-3-proxy* (atom nil)
        app-4-proxy* (atom nil)]

    (subscribe app-1-instance ::proxy-counter #(reset! app-1-proxy* %))
    (subscribe app-2-instance ::proxy-counter #(reset! app-2-proxy* %))
    (subscribe app-3-instance ::proxy-counter #(reset! app-3-proxy* %))
    (subscribe app-4-instance ::proxy-counter #(reset! app-4-proxy* %))

    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))
    (is (= {::proxy-counter nil}
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)))
    (is (= {::proxy-counter nil}
           (get-meta-state app-4-instance)))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (is (= {::proxy-counter nil}
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc]}}
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)))

    (is (= {::proxy-counter nil}
           (get-meta-state app-4-instance)))

    (is (= 2 @app-1-proxy*))
    (is (= nil @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 3}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (dispatch app-1-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 3}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc :dec]}}
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (is (= 2 @app-1-proxy*))
    (is (= 3 @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-1-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (is (= {::proxy-counter 3}
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc :dec :dec]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-4-instance)))

    (is (= 1 @app-1-proxy* @app-2-proxy* @app-3-proxy*))
    (is (= 3 @app-4-proxy*))))


(deftest proxy-controllers+fence-2
  (let [app-1 {:keechma/controllers {::proxy-counter
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-1-fence (make-fence app-1-instance (fn [app-state _]
                                                 (-> app-state ::proxy-counter odd?)))
        app-2 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-2-instance (start! app-2 app-1-fence)
        app-3 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-3-instance (start! app-3 app-1-fence)
        app-3-fence (make-fence app-2-instance (fn [app-state _]
                                                 (= 3 (::proxy-counter app-state))))
        app-4 {:keechma/controllers {::proxy-counter {:keechma.controller/proxy ::proxy-counter}}}
        app-4-instance (start! app-4 app-3-fence)
        app-1-proxy* (atom nil)
        app-2-proxy* (atom nil)
        app-3-proxy* (atom nil)
        app-4-proxy* (atom nil)]

    (subscribe app-1-instance ::proxy-counter #(reset! app-1-proxy* %))
    (subscribe app-2-instance ::proxy-counter #(reset! app-2-proxy* %))
    (subscribe app-3-instance ::proxy-counter #(reset! app-3-proxy* %))
    (subscribe app-4-instance ::proxy-counter #(reset! app-4-proxy* %))

    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))
    (is (= {::proxy-counter nil}
           (get-derived-state app-4-instance)))
    (is (= {::proxy-counter {:log [:keechma.on/start]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)))
    (is (= {::proxy-counter nil}
           (get-meta-state app-4-instance)))

    (dispatch app-2-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)))

    (is (= {::proxy-counter nil}
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc]}}
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)))

    (is (= {::proxy-counter nil}
           (get-meta-state app-4-instance)))

    (is (= 2 @app-1-proxy*))
    (is (= nil @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    (dispatch app-1-instance ::proxy-counter :inc)
    (is (= {::proxy-counter 3}
           (get-derived-state app-1-instance)
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-1-instance)
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (dispatch app-3-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 3}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc :dec]}}
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (is (= 2 @app-1-proxy*))
    (is (= 3 @app-2-proxy* @app-3-proxy* @app-4-proxy*))

    ;; These dispatches should be no-op because fences should prevent it
    (dispatch app-2-instance ::proxy-counter :dec)
    (dispatch app-3-instance ::proxy-counter :dec)
    (is (= {::proxy-counter 2}
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 3}
           (get-derived-state app-2-instance)
           (get-derived-state app-3-instance)
           (get-derived-state app-4-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc :dec]}}
           (get-meta-state app-1-instance)))

    (is (= {::proxy-counter {:log [:keechma.on/start :inc :inc]}}
           (get-meta-state app-2-instance)
           (get-meta-state app-3-instance)
           (get-meta-state app-4-instance)))

    (is (= 2 @app-1-proxy*))
    (is (= 3 @app-2-proxy* @app-3-proxy* @app-4-proxy*))))

(deftest proxy-controllers+fence-3
  (let [app-1 {:keechma/controllers {::proxy-counter
                                     #:keechma.controller {:params true}}}
        app-1-instance (start! app-1)
        app-1-fence (make-fence app-1-instance (fn [app-state _]
                                                 (-> app-state ::proxy-counter odd?)))
        app-2 {:keechma/controllers {::proxy-counter
                                     #:keechma.controller {:proxy ::proxy-counter}
                                     ::proxy-counter-event-producer
                                     #:keechma.controller {:params true}}}
        app-2-instance (start! app-2 app-1-fence)

        subscribe' (fn [app-instance controller-name state*]
                     (subscribe app-instance controller-name #(swap! state* assoc controller-name %)))

        app-1-proxy* (atom {})
        app-2-proxy* (atom {})]

    (subscribe' app-1-instance ::proxy-counter app-1-proxy*)
    (subscribe' app-2-instance ::proxy-counter app-2-proxy*)
    (subscribe' app-2-instance ::proxy-counter-event-producer app-2-proxy*)

    (is (= {::proxy-counter 1}
           (get-derived-state app-1-instance)))
    (is (= {::proxy-counter 1
            ::proxy-counter-event-producer 1}
           (get-derived-state app-2-instance)))

    (dispatch app-2-instance ::proxy-counter-event-producer :inc)
    (is (= {::proxy-counter 2}
           @app-1-proxy*
           (get-derived-state app-1-instance)))

    (is (= {::proxy-counter 1
            ::proxy-counter-event-producer 2}
           @app-2-proxy*
           (get-derived-state app-2-instance)))

    (dispatch app-2-instance ::proxy-counter-event-producer :inc)
    (is (= {::proxy-counter 2}
           @app-1-proxy*
           (get-derived-state app-1-instance)))
    (is (= {::proxy-counter 1
            ::proxy-counter-event-producer 3}
           @app-2-proxy*
           (get-derived-state app-2-instance)))))

(derive ::c-dispatch :keechma/controller)
(derive ::c-broadcast :keechma/controller)
(derive ::c-target :keechma/controller)

(defmethod ctrl/start ::c-dispatch [_ _ _ _]
  0)

(defmethod ctrl/handle ::c-dispatch [{:keys [state*] :as ctrl} ev _]
  (case ev
    :from-c2 (do
               (swap! state* inc)
               (ctrl/dispatch ctrl ::c-target :from-c1)
               (swap! state* inc))
    nil))

(defmethod ctrl/start ::c-broadcast [_ _ _ _]
  0)

(defmethod ctrl/handle ::c-broadcast [{:keys [state*] :as ctrl} ev _]
  (case ev
    :from-c2 (do
               (swap! state* inc)
               (ctrl/broadcast ctrl :from-c1)
               (swap! state* inc))
    nil))

(defmethod ctrl/start ::c-target [_ _ _ _]
  0)

(defmethod ctrl/handle ::c-target [{:keys [state* to-controller] :as ctrl} ev _]
  (case ev
    :from-app (do
                (ctrl/dispatch ctrl to-controller :from-c2)
                (swap! state* inc))
    :from-c1 (swap! state* inc)
    nil))

(deftest subscription-call-count-dispatch
  (let [app {:keechma/controllers {::c-dispatch #:keechma.controller {:params true}
                                   ::c-target #:keechma.controller {:params true
                                                                    :_/to-controller ::c-dispatch}}}
        subscription-call-count* (atom {})
        app-instance (start! app)]
    (subscribe app-instance ::c-dispatch #(swap! subscription-call-count* update ::c-dispatch inc))
    (subscribe app-instance ::c-target #(swap! subscription-call-count* update ::c-target inc))

    (dispatch app-instance ::c-target :from-app)

    (is (= {::c-dispatch 1
            ::c-target 1}
           @subscription-call-count*))
    (is (= {::c-dispatch 2
            ::c-target 2}
           (get-derived-state app-instance)))))

(deftest subscription-call-count-proxy-controllers-dispatch
  (let [app-1 {:keechma/controllers {::c-dispatch #:keechma.controller {:params true}}}
        app-2 {:keechma/controllers {::c-dispatch #:keechma.controller {:proxy ::c-dispatch}
                                     ::c-target #:keechma.controller {:params true
                                                                      :_/to-controller ::c-dispatch}}}
        subscription-call-count* (atom {})
        subs* (atom {})
        app-1-instance (start! app-1)
        app-2-instance (start! app-2 app-1-instance)]
    (subscribe app-1-instance ::c-dispatch #(swap! subscription-call-count* update ::c-dispatch inc))
    (subscribe app-2-instance ::c-dispatch #(swap! subscription-call-count* update ::c-dispatch-proxy inc))
    (subscribe app-2-instance ::c-target #(swap! subscription-call-count* update ::c-target inc))

    (subscribe app-1-instance ::c-dispatch #(swap! subs* assoc ::c-dispatch %))
    (subscribe app-2-instance ::c-dispatch #(swap! subs* assoc ::c-dispatch-proxy %))
    (subscribe app-2-instance ::c-target #(swap! subs* assoc ::c-target %))

    (dispatch app-2-instance ::c-target :from-app)

    (is (= {::c-dispatch 1
            ::c-dispatch-proxy 1
            ::c-target 1}
           @subscription-call-count*))
    (is (= {::c-dispatch 2
            ::c-target 2}
           (get-derived-state app-2-instance)))
    (is (= {::c-dispatch 2
            ::c-dispatch-proxy 2
            ::c-target 2}
           @subs*))))


(deftest subscription-call-count-broadcast
  (let [app {:keechma/controllers {::c-broadcast #:keechma.controller {:params true}
                                   ::c-target #:keechma.controller {:params true
                                                                    :_/to-controller ::c-broadcast}}}
        subscription-call-count* (atom {})
        app-instance (start! app)]
    (subscribe app-instance ::c-broadcast #(swap! subscription-call-count* update ::c-broadcast inc))
    (subscribe app-instance ::c-target #(swap! subscription-call-count* update ::c-target inc))

    (dispatch app-instance ::c-target :from-app)

    (is (= {::c-broadcast 1
            ::c-target 1}
           @subscription-call-count*))
    (is (= {::c-broadcast 2
            ::c-target 2}
           (get-derived-state app-instance)))))

(deftest subscription-call-count-proxy-controllers-broadcast
  (let [app-1 {:keechma/controllers {::c-broadcast #:keechma.controller {:params true}}}
        app-2 {:keechma/controllers {::c-broadcast #:keechma.controller {:proxy ::c-broadcast}
                                     ::c-target #:keechma.controller {:params true
                                                                      :_/to-controller ::c-broadcast}}}
        subscription-call-count* (atom {})
        subs* (atom {})
        app-1-instance (start! app-1)
        app-2-instance (start! app-2 app-1-instance)]

    (subscribe app-1-instance ::c-broadcast #(swap! subscription-call-count* update ::c-broadcast inc))
    (subscribe app-2-instance ::c-broadcast #(swap! subscription-call-count* update ::c-broadcast-proxy inc))
    (subscribe app-2-instance ::c-target #(swap! subscription-call-count* update ::c-target inc))

    (subscribe app-1-instance ::c-broadcast #(swap! subs* assoc ::c-broadcast %))
    (subscribe app-2-instance ::c-broadcast #(swap! subs* assoc ::c-broadcast-proxy %))
    (subscribe app-2-instance ::c-target #(swap! subs* assoc ::c-target %))

    (dispatch app-2-instance ::c-target :from-app)

    (is (= {::c-broadcast 1
            ::c-broadcast-proxy 1
            ::c-target 1}
           @subscription-call-count*))
    (is (= {::c-broadcast 2
            ::c-target 2}
           (get-derived-state app-2-instance)))
    (is (= {::c-broadcast 2
            ::c-broadcast-proxy 2
            ::c-target 2}
           @subs*))))

(deftest global-controllers-are-started-first-1
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::c1 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c3 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c4 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/is-global true
                                         :cmd-log* cmd-log*}}}]
    (start! app)
    (is (= [::c4 :keechma.lifecycle/start]
           (-> cmd-log* deref first)))))

(deftest global-controllers-are-started-first-1a
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::c1 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c3 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c4 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c5 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c6 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c7 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c8 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c9 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c10 {:keechma.controller/params true
                                          :keechma.controller/type ::counter-1
                                          :keechma.controller/is-global true
                                          :cmd-log* cmd-log*}}}]
    (start! app)
    (is (= [::c10 :keechma.lifecycle/start]
           (-> cmd-log* deref first)))))

(deftest global-controllers-are-started-first-2
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::c1 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c3 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c4 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/is-global true
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}}}]
    (start! app)
    (is (= [[::c1 :keechma.lifecycle/start]
            [::c1 :keechma.on/start]
            [::c4 :keechma.lifecycle/start]]
           (take 3 @cmd-log*)))))

(deftest global-controllers-are-started-first-2a
  (let [cmd-log* (atom [])
        app {:keechma/controllers {::c1 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :cmd-log* cmd-log*}
                                   ::c2 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c3 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c4 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c5 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c6 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c7 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c8 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c9 {:keechma.controller/params true
                                         :keechma.controller/type ::counter-1
                                         :keechma.controller/deps [::c1]
                                         :cmd-log* cmd-log*}
                                   ::c10 {:keechma.controller/params true
                                          :keechma.controller/type ::counter-1
                                          :keechma.controller/is-global true
                                          :keechma.controller/deps [::c1]
                                          :cmd-log* cmd-log*}}}]
    (start! app)
    (is (= [[::c1 :keechma.lifecycle/start]
            [::c1 :keechma.on/start]
            [::c10 :keechma.lifecycle/start]]
           (take 3 @cmd-log*)))))