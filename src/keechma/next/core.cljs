(ns keechma.next.core
  (:require
   [keechma.next.controller :as ctrl]
   [keechma.next.graph :refer [subgraph-reachable-from-set]]
   [keechma.next.util :refer [get-dirty-deps get-lowest-common-ancestor-for-paths]]
   [keechma.next.protocols :as protocols :refer [IAppInstance IRootAppInstance]]
   [keechma.next.conformer :refer [conform conform-factory-produced]]
   [medley.core :refer [dissoc-in]]
   [com.stuartsierra.dependency :as dep]
   [clojure.set :as set]))

(declare -dispatch)
(declare reconcile-after-transaction!)
(declare reconcile-initial!)

(def ^:dynamic *transaction-depth* 0)
(def ^:dynamic *stopping* false)

(defn transacting? []
  (pos? *transaction-depth*))

(defn factory-produced-deps-valid? [app-db factory-deps factory-produced-deps]
  (let [expanded-factory-deps
        (reduce
         (fn [acc d]
           (let [dep-produced-keys (get-in app-db [d :produced-keys])]
             (if (seq dep-produced-keys)
               (set/union acc #{d} dep-produced-keys)
               (conj acc d))))
         #{}
         factory-deps)]
    (set/subset? factory-produced-deps expanded-factory-deps)))

(defn keechma-ex-info
  ([message anomaly] (keechma-ex-info message anomaly {}))
  ([message anomaly props]
   (ex-info message (assoc props
                           :keechma.anomalies/category anomaly
                           :keechma.anomalies/message message))))

(defn default-batcher [batched-fn]
  (batched-fn))

(defn build-controllers-graph [controllers]
  (reduce
   (fn [acc [k c]]
     (reduce
      (fn [acc' d]
        (dep/depend acc' k d))
      acc
      (:keechma.controller/deps c)))
   (dep/graph)
   controllers))

(defn determine-actions [old-params new-params old-type new-type]
  (cond
    ;; controller types are different
    (and (not= old-type new-type) old-params new-params)
    #{:stop :start}

    (and (not= old-type new-type) (not old-params) new-params)
    #{:start}

    (and (not= old-type new-type) old-params (not new-params))
    #{:stop}

    ;; controller types are same
    (and (= old-type new-type) (not old-params) new-params)
    #{:start}

    (and (= old-type new-type) old-params (not new-params))
    #{:stop}

    (and (= old-type new-type) old-params new-params (not= old-params new-params))
    #{:stop :start}

    (and (= old-type new-type) old-params new-params (= new-params old-params))
    #{:deps-change}

    :else nil))

(defn controller-state-relevant? [controller]
  (->> controller
       :phase
       (contains? #{:starting :running})))

(defn get-derived-deps-state
  ([app-state controllers deps] (get-derived-deps-state app-state controllers deps nil))
  ([app-state controllers deps renamed-deps]
   (let [app-db (:app-db app-state)]
     (when (seq deps)
       (-> (reduce
            (fn [acc dep-controller-name]
              (if (= :factory (get-in controllers [dep-controller-name :keechma.controller/variant]))
                (let [renamed-dep (get renamed-deps dep-controller-name)]
                  (reduce
                   (fn [acc' dep-controller-name']
                     (if (controller-state-relevant? (get app-db dep-controller-name'))
                       (let [dep-controller-name'' (if renamed-dep
                                                     (vec (concat renamed-dep (rest dep-controller-name')))
                                                     dep-controller-name')]
                         (assoc! acc' dep-controller-name'' (get-in app-db [dep-controller-name' :derived-state])))
                       acc'))
                   acc
                   (get-in app-db [dep-controller-name :produced-keys])))
                (if (controller-state-relevant? (get app-db dep-controller-name))
                  (let [dep-controller-name' (or (get renamed-deps dep-controller-name) dep-controller-name)]
                    (assoc! acc dep-controller-name' (get-in app-db [dep-controller-name :derived-state])))
                  acc)))
            (transient {})
            deps)
           persistent!)))))

(defn get-controller-derived-deps-state [app-state controller-name]
  (let [controllers      (:controllers app-state)
        controller       (get controllers controller-name)
        controller-name' (or (get controller :keechma.controller/factory) controller-name)
        deps             (get-in controllers [controller-name' :keechma.controller/deps])
        renamed-deps     (get-in controllers [controller-name :keechma.controller.deps/renamed])]
    (get-derived-deps-state app-state controllers deps renamed-deps)))

(defn get-controller-running-deps-map [app-state controller-name]
  (let [app-db           (:app-db app-state)
        controllers      (:controllers app-state)
        controller       (get controllers controller-name)
        controller-name' (or (get controller :keechma.controller/factory) controller-name)
        deps             (get-in controllers [controller-name' :keechma.controller/deps])
        renamed-deps     (get-in controllers [controller-name :keechma.controller.deps/renamed])]
    (-> (reduce
         (fn [acc dep-controller-name]
           (if (= :factory (get-in controllers [dep-controller-name :keechma.controller/variant]))
             (let [renamed-dep (get renamed-deps dep-controller-name)]
               (reduce
                (fn [acc' dep-controller-name']
                  (if (contains? #{:starting :running} (get-in app-db [dep-controller-name' :phase]))
                    (let [dep-controller-name'' (if renamed-dep
                                                  (vec (concat renamed-dep (rest dep-controller-name')))
                                                  dep-controller-name')]
                      (assoc! acc' dep-controller-name' dep-controller-name''))
                    acc'))
                acc
                (get-in app-db [dep-controller-name :produced-keys])))
             (if (contains? #{:starting :running} (get-in app-db [dep-controller-name :phase]))
               (let [dep-controller-name' (or (get renamed-deps dep-controller-name) dep-controller-name)]
                 (assoc! acc dep-controller-name dep-controller-name'))
               acc)))
         (transient {})
         deps)
        persistent!)))

(defn get-params-and-type [app-state controller-name]
  (let [controller (get-in app-state [:controllers controller-name])
        derived-deps-state (get-controller-derived-deps-state app-state controller-name)
        params     (:keechma.controller/params controller)
        type (:keechma.controller/type controller)]
    {:params (if (= :static (:keechma.controller.params/variant controller))
               params
               (params derived-deps-state))
     :type (if (= :static (:keechma.controller.type/variant controller))
             type
             (type derived-deps-state))}))

(defn get-factory-produced [app-state controller-name]
  (let [controller (get-in app-state [:controllers controller-name])
        produce    (:keechma.controller.factory/produce controller)]
    (produce (get-controller-derived-deps-state app-state controller-name))))

(defn get-app-store-path [app-path]
  (->> app-path
       (concat [:apps])
       (interpose :apps)
       vec))

(defn get-sorted-controllers-for-app [app-state path]
  (let [{:keys [controllers controllers-graph]} (get-in app-state (get-app-store-path path))
        nodeset              (dep/nodes controllers-graph)
        sorted-controllers   (->> controllers-graph
                                  dep/topo-sort
                                  (filterv #(contains? controllers %)))
        isolated-controllers (->> (keys controllers)
                                  (filter #(not (contains? nodeset %))))]
    (concat isolated-controllers sorted-controllers)))

(defn unsubscribe! [app-state* controller-name sub-id]
  (swap! app-state* dissoc-in [:subscriptions controller-name sub-id]))

(defn unsubscribe-meta! [app-state* controller-name sub-id]
  (swap! app-state* dissoc-in [:subscriptions-meta controller-name sub-id]))

(defn merge-is-global-proxied-controller-with-proxy-controller [controller context]
  (let [parent-app (:keechma.root/parent context)
        proxied-controller (:keechma.controller/proxy controller)
        is-proxied-controller-global (-> parent-app
                                         protocols/-get-app-state*
                                         deref
                                         (get-in [:controllers proxied-controller :keechma.controller/is-global]))]
    (merge {:keechma.controller/is-global is-proxied-controller-global} controller)))

;; TODO: validate deps - controllers can't depend on non-existing controller

(defn prepare-controllers [controllers context]
  (->> controllers
       (map (fn [[k v]]
              (let [v' (cond-> v
                         (= :static (:keechma.controller.type/variant v))
                         ctrl/prep

                         (:keechma.controller/proxy v)
                         (merge-is-global-proxied-controller-with-proxy-controller context))]
                [k (merge context v')])))
       (into {})))

(defn prepare-apps [apps]
  (->> apps
       (map (fn [[k v]] [k (update v :keechma.app/deps set)]))
       (into {})))

(defn register-controllers-app-index [app-state path controllers]
  (let [app-controllers (->> controllers (map (fn [[k _]] [k path])) (into {}))]
    (-> app-state
        (assoc-in [:app->controllers-index path] (set (keys controllers)))
        (update :controller->app-index #(merge % app-controllers)))))

(defn deregister-controllers-app-index [app-state path controller-names]
  (-> app-state
      (dissoc-in [:app->controllers-index path])
      (update :controller->app-index #(apply dissoc % controller-names))))

(defn controller-exists? [{:keys [controllers]} controller-name]
  ;; If we have a controller with composite key (e.g. `[:controller-name 1]`) we need to
  ;; check if the factory controller with the name `[:controller-name]` exists.
  (if (vector? controller-name)
    (or (contains? controllers controller-name)
        (contains? controllers (subvec controller-name 0 1)))
    (contains? controllers controller-name)))

(defn validate-controller-exists! [app-state controller-name]
  (when-not (controller-exists? app-state controller-name)
    (throw (keechma-ex-info
            "Attempted to subscribe to a non existing controller"
            :keechma.controller.errors/missing
            {:keechma/controller controller-name}))))

(defn validate-controllers-deps! [app-controllers visible-controllers]
  (let [errors
        (reduce-kv
         (fn [acc k v]
           (let [deps (:keechma.controller/deps v)
                 visible-controllers' (disj visible-controllers k)
                 missing-deps (set/difference (set deps) visible-controllers')]
             (if (seq missing-deps)
               (update acc k conj {:keechma.anomalies/category :keechma.controller.errors/missing-deps
                                   :keechma.controller/missing-deps missing-deps})
               acc)))
         {}
         app-controllers)]
    (when-not (empty? errors)
      (throw (keechma-ex-info
              "Invalid controllers"
              :keechma.controllers.errors/invalid
              {:keeechma.controllers/invalid errors})))))

(defn validate-controllers-on-app-register! [controllers existing-controllers path]
  (let [errors
        (reduce-kv
         (fn [acc k _]
           (if (contains? existing-controllers k)
             (update acc k conj {:keechma.anomalies/category :keechma.controller.errors/duplicate-name
                                 :keechma.app/path path})
             acc))
         {}
         controllers)]
    (when-not (empty? errors)
      (throw (keechma-ex-info
              "Invalid controllers"
              :keechma.controllers.errors/invalid
              {:keeechma.controllers/invalid errors})))))

(defn register-app [app-state {:keys [path controllers] :as app-ctx}]
  (validate-controllers-on-app-register! controllers (:controllers app-state) path)

  (-> app-state
      (assoc-in (get-app-store-path path) app-ctx)
      (update :controllers merge controllers)
      (register-controllers-app-index path controllers)))

(defn deregister-app [app-state path]
  (let [app-store-path     (get-app-store-path path)
        app-ctx            (get-in app-state app-store-path)
        controller-names   (-> app-ctx :controllers keys set)
        remove-controllers #(apply dissoc % controller-names)]
    (-> app-state
        (dissoc-in app-store-path)
        (update :controllers remove-controllers)
        (update :app-db remove-controllers)
        (deregister-controllers-app-index path controller-names)
        (update-in [:transaction :dirty] set/difference controller-names))))

(def keechma-keys-to-dissoc
  #{:keechma/apps
    :keechma/controllers
    :keechma.subscriptions/batcher
    :keechma/is-transacting
    :keechma.app/should-run?
    :keechma.app/deps
    :keechma.app/variant})

(defn dissoc-keechma-keys [app]
  (apply dissoc app keechma-keys-to-dissoc))

(defn make-ctx
  [app {:keys [context] :as initial-ctx}]
  (let [context'             (merge context (dissoc-keechma-keys app))
        apps                 (prepare-apps (:keechma/apps app))
        controllers          (prepare-controllers (:keechma/controllers app) context')
        ancestor-controllers (:visible-controllers initial-ctx)
        visible-controllers  (set/union ancestor-controllers (set (keys controllers)))
        controllers-graph    (build-controllers-graph controllers)]

    (validate-controllers-deps! controllers visible-controllers)

    (merge
     initial-ctx
     {:keechma/apps         apps
      :controllers          controllers
      :context              context'
      :visible-controllers  visible-controllers
      :controllers-graph    controllers-graph
      :apps                 nil})))

(defn assoc-empty-transaction [app-state]
  (assoc app-state :transaction {:dirty #{} :dirty-meta #{}}))

(defn get-controller-instance [app-state controller-name]
  (get-in app-state [:app-db controller-name :instance]))

(defn stopped? [app-state]
  (= ::stopped (:keechma.app/state app-state)))

(defn -transact [app-state* transaction]
  (when-not (stopped? @app-state*)
    (let [res (binding [*transaction-depth* (inc *transaction-depth*)] (transaction))]
      (reconcile-after-transaction! app-state*)
      res)))

(defn -call [app-state* controller-name api-fn & args]
  (when-not (stopped? @app-state*)
    (let [controller-instance (get-controller-instance @app-state* controller-name)]
      (if (:keechma.controller/proxy controller-instance)
        (let [app (:keechma.root/parent controller-instance)
              controller-name (:keechma.controller/proxy controller-instance)]
          (protocols/-call app controller-name api-fn args))
        (let [api (:keechma.controller/api controller-instance)]
          (apply api-fn api args))))))

(defn -get-api* [app-state* controller-name]
  (reify
    IDeref
    (-deref [_] (get-in @app-state* [:app-db controller-name :instance :keechma.controller/api]))))

(defn -dispatch
  ([app-state* controller-name event] (-dispatch app-state* controller-name event))
  ([app-state* controller-name event payload]
   (when-not (stopped? @app-state*)
     (let [controller (get-in @app-state* [:app-db controller-name])
           controller-instance (:instance controller)
           controller-phase (:phase controller)]

       (cond
         (and (not= controller-phase :proxied-controller-stopped)
              (:keechma.controller/proxy controller-instance))
         (let [app (:keechma.root/parent controller-instance)
               controller-name (:keechma.controller/proxy controller-instance)]
           (protocols/-dispatch app controller-name event payload))

         (= :initializing controller-phase)
         (swap! app-state* update-in [:app-db controller-name :events-buffer] conj [event payload])

         controller-instance
         (-transact app-state* #(ctrl/handle (assoc controller-instance :keechma/is-transacting true) event payload)))))))

(defn get-derived-app-state [app-state]
  (->> app-state
       :app-db
       (map (fn [[k v]]
              (when
               (and (not (and (vector? k) (= 1 (count k))))
                    (controller-state-relevant? v))
                [k (:derived-state v)])))
       (filter identity)
       (into {})))

(defn get-app-meta-state [app-state]
  (->> app-state
       :app-db
       (map (fn [[k v]]
              (when
               (and (not (and (vector? k) (= 1 (count k))))
                    (controller-state-relevant? v))
                [k (:meta-state v)])))
       (filter identity)
       (into {})))

(defn app-broadcast [app-state* path event payload is-broadcasting-from-proxied-controller]
  (let [app-state                @app-state*
        ordered-controller-names (get-sorted-controllers-for-app app-state path)
        app-ctx                  (get-in app-state (get-app-store-path path))
        apps-definitions         (:keechma/apps app-ctx)]
    (binding [*transaction-depth* (inc *transaction-depth*)]
      (doseq [controller-name ordered-controller-names]
        (let [is-proxy-controller (get-in app-state [:app-db controller-name :instance :keechma.controller/proxy])]
          (when (or (not is-broadcasting-from-proxied-controller)
                    (and is-broadcasting-from-proxied-controller (not is-proxy-controller)))
            (-dispatch app-state* controller-name event payload))))
      (doseq [[app-name _] apps-definitions]
        (let [path    (conj path app-name)
              app-ctx (get-in @app-state* (get-app-store-path path))]
          (when (:is-running app-ctx)
            (app-broadcast app-state* path event payload is-broadcasting-from-proxied-controller)))))))

(defn -broadcast
  ([app-state* event]
   (-broadcast app-state* event nil false))
  ([app-state* event payload]
   (-broadcast app-state* event payload false))
  ([app-state* event payload is-broadcasting-from-proxied-controller]
   (when-not (stopped? @app-state*)
     (app-broadcast app-state* [] event payload is-broadcasting-from-proxied-controller)
     (reconcile-after-transaction! app-state*))))

(defn prep [controller]
  ;; TODO: Should we cache this?
  (if (= :dynamic (:keechma.controller.type/variant controller))
    (ctrl/prep controller)
    controller))

(defn notify-proxied-controllers-apps-on-dispatch [app-state* controller-name target-controller-name event payload]
  (let [subscriptions (-> app-state*
                          deref
                          (get-in [:subscriptions-on-controller-dispatch controller-name])
                          vals)]
    (doseq [app-subs subscriptions]
      (let [subscribed-controllers (-> app-subs keys set)
            sub-fn (-> app-subs vals first)]
        (sub-fn subscribed-controllers target-controller-name event payload)))))

(defn notify-proxied-controllers-apps-on-broadcast [app-state* controller-name event payload]
  (let [subscriptions (-> app-state*
                          deref
                          (get-in [:subscriptions-on-controller-broadcast controller-name])
                          vals)]
    (doseq [app-subs subscriptions]
      (let [subscribed-controllers (-> app-subs keys set)
            sub-fn (-> app-subs vals first)]
        (sub-fn subscribed-controllers event payload)))))

(defn make-controller-instance [app-state* controller-name controller-type params]
  (let [{:keys [batcher] :as app-state} @app-state*
        controller  (-> app-state
                        (get-in [:controllers controller-name])
                        (assoc :keechma.controller/type controller-type)
                        prep)
        params' (ctrl/params (assoc controller :keechma.controller/name controller-name) params)]

    (when params'
      (let [state*      (atom nil)
            meta-state* (atom nil)
            id          (keyword (gensym 'controller-instance-))]
        (assoc controller
               :keechma.controller/name controller-name
               :keechma.controller/params params'
               :keechma.controller/id id
               :keechma/app (reify
                              IAppInstance
                              (-dispatch [_ target-controller-name event]
                                (batcher
                                 (fn []
                                   (-dispatch app-state* target-controller-name event nil)
                                   (notify-proxied-controllers-apps-on-dispatch app-state* controller-name target-controller-name event nil))))
                              (-dispatch [_ target-controller-name event payload]
                                (batcher
                                 (fn []
                                   (-dispatch app-state* target-controller-name event payload)
                                   (notify-proxied-controllers-apps-on-dispatch app-state* controller-name target-controller-name event payload))))
                              (-broadcast [_ event]
                                (batcher
                                 (fn []
                                   (-broadcast app-state* event nil)
                                   (notify-proxied-controllers-apps-on-broadcast app-state* controller-name event nil))))
                              (-broadcast [_ event payload]
                                (batcher
                                 (fn []
                                   (-broadcast app-state* event payload)
                                   (notify-proxied-controllers-apps-on-broadcast app-state* controller-name event payload))))
                              (-call [_ target-controller-name api-fn args]
                                (let [controller-running-deps-map (get-controller-running-deps-map @app-state* controller-name)
                                      deps-name->renamed (set/map-invert controller-running-deps-map)
                                      real-target-controller-name (get deps-name->renamed target-controller-name)]
                                  (if (not real-target-controller-name)
                                    (let [controller (get-in @app-state* [:controllers target-controller-name])]
                                      (if (:keechma.controller/is-global controller)
                                        (apply -call app-state* target-controller-name api-fn args)
                                        (throw (keechma-ex-info
                                                "Attempted to call an API function on non-existing dep"
                                                :keechma.controller.deps/missing
                                                {:keechma.controller.api/fn api-fn
                                                 :keechma.controller.api/args args
                                                 :keechma.controller/dep target-controller-name}))))
                                    (apply -call app-state* real-target-controller-name api-fn args))))
                              (-get-api* [_ controller-name]
                                (-get-api* app-state* controller-name))
                              (-transact [_ transaction]
                                (-transact app-state* transaction))
                              (-get-id [_]
                                (:keechma.app/id @app-state*)))
               :meta-state* meta-state*
               :state* state*
               :deps-state* (reify
                              IDeref
                              (-deref [_] (get-controller-derived-deps-state @app-state* controller-name))))))))

(defn notify-subscriptions-meta
  ([app-state] (notify-subscriptions-meta app-state nil))
  ([app-state dirty-meta]
   (let [app-db                      (:app-db app-state)
         subscriptions-meta          (:subscriptions-meta app-state)
         selected-subscriptions-meta (if dirty-meta
                                       (select-keys subscriptions-meta dirty-meta)
                                       subscriptions-meta)]
     (doseq [[controller-key subs] selected-subscriptions-meta]
       (let [meta-state (get-in app-db [controller-key :meta-state])]
         (doseq [sub (vals subs)]
           (sub meta-state)))))))

(defn notify-subscriptions [app-state]
  (let [app-db        (:app-db app-state)
        subscriptions (:subscriptions app-state)]
    (doseq [[controller-key subs] subscriptions]
      (let [derived-state (get-in app-db [controller-key :derived-state])]
        (doseq [sub (vals subs)]
          (sub derived-state))))
    (notify-subscriptions-meta app-state)))

(defn notify-boundaries [app-state]
  (let [boundaries-sub-fns (-> app-state :boundaries vals)]
    (when (seq boundaries-sub-fns)
      (let [derived-app-state (get-derived-app-state app-state)
            app-meta-state (get-app-meta-state app-state)]
        (doseq [boundary-sub-fn boundaries-sub-fns]
          (boundary-sub-fn derived-app-state app-meta-state))))))

(defn batched-notify-subscriptions-meta
  ([{:keys [batcher] :as app-state}]
   (batcher #(notify-subscriptions-meta app-state)))
  ([{:keys [batcher] :as app-state} dirty-meta]
   (batcher #(notify-subscriptions-meta app-state dirty-meta))))

(defn batched-notify-subscriptions [{:keys [batcher] :as app-state}]
  (batcher #(notify-subscriptions app-state)))

(defn sync-controller->app-db! [app-state* controller-name]
  (let [app-state  @app-state*
        app-db     (:app-db app-state)
        controller (get app-db controller-name)
        instance   (:instance controller)
        state      (deref (:state* instance))
        deps-state (get-controller-derived-deps-state app-state controller-name)
        new-app-db
        (-> app-db
            (dissoc-in [controller-name :events-buffer])
            (assoc-in [controller-name :state] state)
            (assoc-in [controller-name :derived-state]
                      (ctrl/derive-state instance state deps-state)))]
    (swap! app-state* assoc :app-db new-app-db)))

(defn sync-controller-meta->app-db! [app-state* controller-name]
  (let [app-state   @app-state*
        meta-state* (get-in app-state [:app-db controller-name :instance :meta-state*])]
    (swap! app-state* assoc-in [:app-db controller-name :meta-state] @meta-state*)))

(defn transaction-mark-dirty! [app-state* controller-name]
  (swap! app-state* update-in [:transaction :dirty] conj controller-name))

(defn transaction-unmark-dirty! [app-state* controller-name]
  (swap! app-state* update-in [:transaction :dirty] disj controller-name))

(defn transaction-mark-dirty-meta! [app-state* controller-name]
  (swap! app-state* update-in [:transaction :dirty-meta] conj controller-name))

(defn on-controller-state-change! [app-state* controller-name]
  (when-not *stopping*
    (sync-controller->app-db! app-state* controller-name)
    (if (transacting?)
      (transaction-mark-dirty! app-state* controller-name)
      (do
        (when ^boolean goog.DEBUG
          (js/console.warn "Controller state updated outside transact block. Controller:" (str controller-name)))
        (transaction-mark-dirty! app-state* controller-name)
        (reconcile-after-transaction! app-state*)))))

(defn on-controller-meta-state-change! [app-state* controller-name]
  (when-not *stopping*
    (sync-controller-meta->app-db! app-state* controller-name)
    (if (transacting?)
      (transaction-mark-dirty-meta! app-state* controller-name)
      (do
        (notify-boundaries @app-state*)
        (batched-notify-subscriptions-meta @app-state* #{controller-name})))))

(defn get-proxied-controller [app-state controller-name]
  (let [controller-instance (get-in app-state [:app-db controller-name :instance])
        parent-app (:keechma.root/parent controller-instance)
        proxied-controller-name (:keechma.controller/proxy controller-instance)]
    (-> parent-app
        protocols/-get-app-state*
        deref
        (get-in [:app-db proxied-controller-name]))))

(defn on-proxied-controller-change! [app-state* controller-name]
  (when-not *stopping*
    (let [app-state @app-state*
          controller (get-in app-state [:app-db controller-name])
          proxied-controller (get-proxied-controller app-state controller-name)
          {:keys [phase derived-state meta-state]} controller
          {proxied-phase :phase proxied-derived-state :derived-state proxied-meta-state :meta-state} proxied-controller
          is-derived-state-identical (identical? derived-state proxied-derived-state)
          is-meta-state-identical (identical? meta-state proxied-meta-state)
          is-phase-identical (identical? phase proxied-phase)]

      (if proxied-controller
        (swap! app-state* update-in [:app-db controller-name] merge {:phase proxied-phase :derived-state proxied-derived-state :meta-state proxied-meta-state})
        (swap! app-state* update-in [:app-db controller-name] merge {:phase :proxied-controller-stopped :derived-state nil :meta-state nil}))

      (when (or (not is-derived-state-identical) (not is-phase-identical))
        (if (transacting?)
          (transaction-mark-dirty! app-state* controller-name)
          (do
            (transaction-mark-dirty! app-state* controller-name)
            (reconcile-after-transaction! app-state*))))

      (when-not is-meta-state-identical
        (if (transacting?)
          (transaction-mark-dirty-meta! app-state* controller-name)
          (do
            (notify-boundaries @app-state*)
            (batched-notify-subscriptions-meta @app-state* #{controller-name})))))))

(defn on-proxied-controller-dispatch [app-state* subscribed-controllers target-controller-name event payload]
  (let [{:keys [batcher app-db]} @app-state*
        controller-name (->> app-db
                             (filter (fn [[controller-name controller]]
                                       (and (contains? subscribed-controllers controller-name) (= :running (:phase controller)))))
                             ffirst)]
    (println controller-name subscribed-controllers)
    (when controller-name
      (batcher
       (fn []
         (-dispatch app-state* target-controller-name event payload)
         (notify-proxied-controllers-apps-on-dispatch app-state* controller-name target-controller-name event payload))))))

(defn on-proxied-controller-broadcast [app-state* subscribed-controllers event payload]
  (let [{:keys [batcher app-db]} @app-state*
        controller-name (->> app-db
                             (filter (fn [[controller-name controller]]
                                       (and (contains? subscribed-controllers controller-name) (= :running (:phase controller)))))
                             ffirst)]
    (when controller-name
      (batcher
       (fn []
         (-broadcast app-state* event payload true)
         (notify-proxied-controllers-apps-on-broadcast app-state* controller-name event payload))))))

(defn controller-start! [app-state* controller-name controller-type params]
  ;; Based on params so far, we're going to try to start the controller. There is one last chance to prevent it
  ;; and it will happen in the `make-controller-instance`. In that function, we'll assoc the real :keechma.controller/type for
  ;; the controllers with the dynamic type (fn based), and then the call `ctrl/params` function. If that function returns falsy value, we'll
  ;; abort the starting, and restore the app-state* to the previous state.
  (let [prev-controller-state (get-in @app-state* [:app-db controller-name])
        prev-state (:state prev-controller-state)]
    (swap! app-state* assoc-in [:app-db controller-name] {:params params :type controller-type :phase :initializing :events-buffer []})
    (let [controller-instance (make-controller-instance app-state* controller-name controller-type params)]
      (if-not controller-instance
        (swap! app-state* assoc-in [:app-db controller-name] prev-controller-state)
        (let [config   (make-controller-instance app-state* controller-name controller-type params)
              inited   (ctrl/init config)
              api      (ctrl/api inited)
              instance (assoc inited :keechma.controller/api api)]
          (swap! app-state* assoc-in [:app-db controller-name :instance] instance)
          (let [state*      (:state* instance)
                meta-state* (:meta-state* instance)
                deps-state  (get-controller-derived-deps-state @app-state* controller-name)
                state       (ctrl/start instance params deps-state prev-state)]
            (reset! state* state)
            (swap! app-state* update-in [:app-db controller-name] #(merge % {:state state :phase :starting :prev-deps-state deps-state}))
            (-dispatch app-state* controller-name :keechma.on/start params)
            (swap! app-state* assoc-in [:app-db controller-name :phase] :running)
            (doseq [[event payload] (get-in @app-state* [:app-db controller-name :events-buffer])]
              (-dispatch app-state* controller-name event payload))
            (sync-controller->app-db! app-state* controller-name)
            (sync-controller-meta->app-db! app-state* controller-name)
            (add-watch meta-state* :keechma/app #(on-controller-meta-state-change! app-state* controller-name))
            (add-watch state* :keechma/app #(on-controller-state-change! app-state* controller-name))))))))

(defn controller-stop! [app-state* controller-name]
  (let [instance (get-controller-instance @app-state* controller-name)]
    (if (:keechma.controller/proxy instance)
      (let [{:keys [unsubscribe unsubscribe-meta unsubscribe-on-controller-dispatch unsubscribe-on-controller-broadcast]} instance]
        (unsubscribe)
        (unsubscribe-meta)
        (unsubscribe-on-controller-dispatch)
        (unsubscribe-on-controller-broadcast)
        (swap! app-state* dissoc-in [:app-db controller-name]))
      (let [params   (:keechma.controller/params instance)
            state*   (:state* instance)]
        (swap! app-state* assoc-in [:app-db controller-name :phase] :stopping)
        (remove-watch state* :keechma/app)
        (remove-watch (:meta-state* instance) :keechma/app)
        (-dispatch app-state* controller-name :keechma.on/stop nil)
        (let [deps-state (get-controller-derived-deps-state @app-state* controller-name)
              state      (ctrl/stop instance params @state* deps-state)]
          (reset! state* state)
          (swap! app-state* assoc-in [:app-db controller-name] {:state state}))
        (ctrl/terminate instance)))))

(defn controller-on-deps-change! [app-state* controller-name]
  (let [app-state       @app-state*
        prev-deps-state (get-in app-state [:app-db controller-name :prev-deps-state])
        deps-state      (get-controller-derived-deps-state @app-state* controller-name)
        instance        (get-in app-state [:app-db controller-name :instance])
        dirty-deps      (get-dirty-deps prev-deps-state deps-state)]
    (when dirty-deps
      (swap! app-state* assoc-in [:app-db controller-name :prev-deps-state] deps-state)
      (-dispatch app-state* controller-name :keechma.on/deps-change dirty-deps)
      (let [state         (-> instance :state* deref)
            derived-state (ctrl/derive-state instance state deps-state)]
        (swap! app-state* assoc-in [:app-db controller-name :derived-state] derived-state)))))

(defn idempotently-proxy-controller-start! [app-state* controller-name]
  (let [app-state @app-state*]
    (when-not (get-in app-state [:app-db controller-name :instance])
      (let [controller (get-in app-state [:controllers controller-name])
            proxied-controller-name (:keechma.controller/proxy controller)
            parent-app (:keechma.root/parent controller)]
        (when-not parent-app
          (throw (keechma-ex-info
                  "Attempted to proxy controller from a non-existing parent app"
                  :keechma.root.parent/missing
                  {:keechma/controller controller-name
                   :keechma.controller/proxy proxied-controller-name})))
        (let [derived-state (protocols/-get-derived-state parent-app proxied-controller-name)
              meta-state (protocols/-get-meta-state parent-app proxied-controller-name)
              handler (fn [_] (on-proxied-controller-change! app-state* controller-name))
              unsubscribe (protocols/-subscribe parent-app proxied-controller-name handler)
              unsubscribe-meta (protocols/-subscribe-meta parent-app proxied-controller-name handler)
              app-id (:keechma.app/id app-state)
              unsubscribe-on-controller-dispatch (protocols/-subscribe-on-controller-dispatch
                                                  parent-app
                                                  app-id
                                                  proxied-controller-name
                                                  controller-name
                                                  (fn [subscribed-controllers target-controller-name event payload]
                                                    (on-proxied-controller-dispatch app-state* subscribed-controllers target-controller-name event payload)))
              unsubscribe-on-controller-broadcast (protocols/-subscribe-on-controller-broadcast
                                                   parent-app
                                                   app-id
                                                   proxied-controller-name
                                                   controller-name
                                                   (fn [subscribed-controllers event payload]
                                                     (on-proxied-controller-broadcast app-state* subscribed-controllers event payload)))
              controller' (assoc controller
                                 :unsubscribe unsubscribe
                                 :unsubscribe-meta unsubscribe-meta
                                 :unsubscribe-on-controller-dispatch unsubscribe-on-controller-dispatch
                                 :unsubscribe-on-controller-broadcast unsubscribe-on-controller-broadcast)]
          (swap! app-state* update-in [:app-db controller-name] merge {:derived-state derived-state
                                                                       :meta-state meta-state
                                                                       :instance controller'
                                                                       :phase :running}))))))

(defn reconcile-controller-lifecycle-state! [app-state* controller-name]

  (if (get-in @app-state* [:controllers controller-name :keechma.controller/proxy])
    (idempotently-proxy-controller-start! app-state* controller-name)
  ;; For each controller in the to-reconcile vector do what is needed based on the return value of
  ;; the params and the type function
  ;;
  ;;
  ;; +---------------------------+-------------+----------------+-------------------------------+---------------------------------------------------------------------------+
  ;; | Prev Type == Current Type | Prev Params | Current Params | Prev Params == Current Params |                                  Actions                                  |
  ;; +---------------------------+-------------+----------------+-------------------------------+---------------------------------------------------------------------------+
  ;; | false                     | falsy       | falsy          | -                             | Do nothing                                                                |
  ;; | false                     | truthy      | falsy          | -                             | Stop the current controller instance                                      |
  ;; | false                     | falsy       | truthy         | -                             | Start a new controller instance                                           |
  ;; | false                     | truthy      | truthy         | -                             | Stop the current controller instance and start a new one                  |
  ;; | true                      | falsy       | falsy          | -                             | Do nothing                                                                |
  ;; | true                      | truthy      | falsy          | -                             | Stop the current controller instance                                      |
  ;; | true                      | falsy       | truthy         | -                             | Start a new controller instance                                           |
  ;; | true                      | truthy      | truthy         | false                         | Stop the current controller instance and start a new one                  |
  ;; | true                      | truthy      | truthy         | true                          | Dispatch :keechma.on/deps-change event to the running controller instance |
  ;; +---------------------------+-------------+----------------+-------------------------------+---------------------------------------------------------------------------+

    (let [app-state @app-state*
          {params :params controller-type :type} (get-params-and-type app-state controller-name)]

      (assert (isa? controller-type :keechma/controller)
              (str "Controller " controller-name " has type " controller-type " which is not derived from :keechma/controller"))

      (let [current-params          (get-in app-state [:app-db controller-name :params])
            current-controller-type (get-in app-state [:app-db controller-name :type])
            actions                 (determine-actions current-params params current-controller-type controller-type)]

        (when (contains? actions :stop)
          (controller-stop! app-state* controller-name))
        (when (contains? actions :start)
          (controller-start! app-state* controller-name controller-type params))
        (when (contains? actions :deps-change)
          (controller-on-deps-change! app-state* controller-name))))))

(defn reconcile-controller-factory! [app-state* controller-name]
  (let [app-state          @app-state*
        controllers        (:controllers app-state)
        app-db             (:app-db app-state)
        config             (get controllers controller-name)
        prev-produced-keys (get-in app-db [controller-name :produced-keys])
        factory-deps       (:keechma.controller/deps config)
        produced
        (->> (get-factory-produced @app-state* controller-name)
             (reduce
              (fn [acc [k produced-config]]
                (let [produced-controller-name  (conj controller-name k)
                      conformed-produced-config (conform-factory-produced produced-config)
                      factory-produced-deps     (:keechma.controller/deps conformed-produced-config)]
                  (assert (factory-produced-deps-valid? app-db factory-deps factory-produced-deps))
                  (assoc!
                   acc
                   (conj controller-name k)
                   (-> (merge config conformed-produced-config)
                       (assoc :keechma.controller/variant :identity
                              :keechma.controller/name produced-controller-name
                              :keechma.controller/factory controller-name)))))
              (transient {}))
             persistent!)
        produced-keys      (set (keys produced))
        running-keys       (->> @app-state*
                                :app-db
                                (filter (fn [[k v]] (and (contains? prev-produced-keys k) (= :running (:phase v)))))
                                (map first)
                                set)
        to-remove          (set/difference running-keys produced-keys)]

    (doseq [controller-name to-remove]
      (controller-stop! app-state* controller-name))

    (swap! app-state*
           (fn [app-state]
             (let [{:keys [controllers app-db]} app-state
                   app-db'      (-> (apply dissoc app-db to-remove)
                                    (assoc-in [controller-name :produced-keys] produced-keys))
                   controllers' (-> (apply dissoc controllers to-remove)
                                    (merge produced))]
               (assoc app-state :app-db app-db' :controllers controllers'))))

    (loop [produced-keys' produced-keys]
      (when (seq produced-keys')
        (let [[controller-name & rest-produced-keys] produced-keys']
          (reconcile-controller-lifecycle-state! app-state* controller-name)
          (recur rest-produced-keys))))))

(defn reconcile-controllers! [app-state* to-reconcile]
  (loop [to-reconcile' to-reconcile]
    (when (seq to-reconcile')
      (let [[current & rest-to-reconcile] to-reconcile'
            controller-variant (get-in @app-state* [:controllers current :keechma.controller/variant])]
        (if (= :factory controller-variant)
          (reconcile-controller-factory! app-state* current)
          (reconcile-controller-lifecycle-state! app-state* current))
        (transaction-unmark-dirty! app-state* current)
        (recur rest-to-reconcile)))))

(defn stop-app! [app-state* path]
  (let [app-state @app-state*
        app-ctx   (get-in app-state (get-app-store-path path))]
    (when app-ctx
      (let [apps         (:apps app-ctx)
            to-reconcile (reverse (get-sorted-controllers-for-app app-state path))]
        (doseq [[app-name _] apps]
          (stop-app! app-state* (conj path app-name)))
        (doseq [controller-name to-reconcile]
          (when (get-controller-instance @app-state* controller-name)
            (controller-stop! app-state* controller-name)))
        (swap! app-state* deregister-app path)))))

(defn reconcile-app! [app-state* path dirty]
  (let [app-state    @app-state*
        {:keys [controllers-graph]} (get-in app-state (get-app-store-path path))
        subgraph     (subgraph-reachable-from-set controllers-graph dirty)
        ;; TODO: Write test for this. Only reconcile controllers that belong to this app
        to-reconcile (filterv #(= path (get-in app-state [:controller->app-index %])) (dep/topo-sort subgraph))]

    (reconcile-controllers! app-state* to-reconcile)

    (let [app-state        @app-state*
          app-ctx          (get-in app-state (get-app-store-path path))
          apps-definitions (:keechma/apps app-ctx)
          apps-by-should-run
          (reduce-kv
           (fn [acc app-name app]
             (let [should-run? (:keechma.app/should-run? app)
                   deps        (get-derived-deps-state app-state (:controllers app-state) (:keechma.app/deps app))]
               (update acc (boolean (should-run? deps)) conj app-name)))
           {true #{} false #{}}
           apps-definitions)]

      (doseq [app-name (get apps-by-should-run false)]
        (stop-app! app-state* (conj path app-name)))

      (doseq [app-name (get apps-by-should-run true)]
        (let [app-state     @app-state*
              path          (conj path app-name)
              child-app-ctx (get-in app-state (get-app-store-path path))]
          (if (:is-running child-app-ctx)
            (reconcile-app! app-state* path (set/union dirty (get-in app-state [:transaction :dirty]) (set to-reconcile)))
            (do
              (swap! app-state* register-app (make-ctx (get apps-definitions app-name) (merge app-ctx {:path path :is-running true})))
              (reconcile-initial! app-state* path))))))))

(defn reconcile-after-transaction! [app-state*]
  (when-not (transacting?)
    (let [app-state   @app-state*
          transaction (:transaction app-state)
          dirty       (:dirty transaction)
          dirty-meta  (:dirty-meta transaction)]
      (cond
        (seq dirty)
        (let [controller-apps (->> dirty (map #(get-in app-state [:controller->app-index %])) set)
              lca-path        (get-lowest-common-ancestor-for-paths controller-apps)]
          (swap! app-state* assoc-empty-transaction)
          (binding [*transaction-depth* (inc *transaction-depth*)]
            (reconcile-app! app-state* lca-path dirty))
          (let [app-state @app-state*
                dirty     (get-in app-state [:transaction :dirty])]
            (if (seq dirty)
              (recur app-state*)
              (do
                (notify-boundaries @app-state*)
                (batched-notify-subscriptions @app-state*)))))

        (seq dirty-meta)
        (do
          (swap! app-state* assoc-empty-transaction)
          (notify-boundaries @app-state*)
          (batched-notify-subscriptions-meta @app-state* dirty-meta))))))

(defn reconcile-initial!
  ([app-state* path] (reconcile-initial! app-state* path 0))
  ([app-state* path depth]
   (let [app-state        @app-state*
         to-reconcile     (get-sorted-controllers-for-app app-state path)
         app-ctx          (get-in app-state (get-app-store-path path))
         apps-definitions (:keechma/apps app-ctx)]
     (binding [*transaction-depth* (inc *transaction-depth*)]
       (reconcile-controllers! app-state* to-reconcile)
       (doseq [[app-name app] apps-definitions]
         (let [app-state   @app-state*
               should-run? (:keechma.app/should-run? app)
               deps        (get-derived-deps-state app-state (:controllers app-state) (:keechma.app/deps app))]
           (when (should-run? deps)
             (let [path    (conj path app-name)
                   app-ctx (make-ctx app (merge app-ctx {:path path :is-running true}))]
               (swap! app-state* register-app app-ctx)
               (reconcile-initial! app-state* path (inc depth)))))))
     (when (zero? depth)
       (reconcile-after-transaction! app-state*)))))

(defn start!
  ([app] (start! app nil))
  ([app parent-app]
   (let [app'       (cond-> app
                      parent-app (assoc :keechma.root/parent parent-app)
                      true conform)
         app-id     (str (gensym 'app-id))
         parent-app-batcher (when parent-app
                              (-> parent-app
                                  protocols/-get-app-state*
                                  deref
                                  :batcher))
         batcher    (or (:keechma.subscriptions/batcher app') parent-app-batcher default-batcher)
         ctx        (make-ctx app' {:path [] :is-running true})
         app-state* (atom (-> {:batcher           batcher
                               :keechma.app/state ::running
                               :keechma.app/id    app-id
                               :keechma.app.reconciliation/generation 0}
                              (assoc-empty-transaction)
                              (register-app ctx)))]

     (reconcile-initial! app-state* [])

     (reify
       IAppInstance
       (-dispatch [_ controller-name event]
         (-dispatch app-state* controller-name event nil))
       (-dispatch [_ controller-name event payload]
         (-dispatch app-state* controller-name event payload))
       (-broadcast [_ event]
         (-broadcast app-state* event nil))
       (-broadcast [app-state* event payload]
         (-broadcast app-state* event payload))
       (-call [_ controller-name api-fn args]
         (apply -call app-state* controller-name api-fn args))
       (-get-api* [_ controller-name]
         (-get-api* app-state* controller-name))
       (-transact [_ transaction]
         (-transact app-state* transaction))
       (-get-id [_] app-id)
       IRootAppInstance
       (-stop! [_]
         (binding [*stopping* true]
           (stop-app! app-state* []))
         (swap! app-state* assoc :keechma.app/state ::stopped))
       (-get-batcher [_]
         batcher)
       (-subscribe [_ controller-name sub-fn]
         (validate-controller-exists! @app-state* controller-name)
         (let [sub-id (-> 'sub-id gensym keyword)]
           (swap! app-state* assoc-in [:subscriptions controller-name sub-id] sub-fn)
           (partial unsubscribe! app-state* controller-name sub-id)))
       (-subscribe-meta [_ controller-name sub-fn]
         (validate-controller-exists! @app-state* controller-name)
         (let [sub-id (-> 'sub-meta-id gensym keyword)]
           (swap! app-state* assoc-in [:subscriptions-meta controller-name sub-id] sub-fn)
           (partial unsubscribe-meta! app-state* controller-name sub-id)))
       (-subscribe-boundary [_ sub-fn]
         (let [boundary-sub-id (-> 'boundary-sub-id gensym keyword)]
           (swap! app-state* assoc-in [:boundaries boundary-sub-id] sub-fn)
           #(swap! app-state* dissoc-in [:boundaries boundary-sub-id])))
       (-subscribe-on-controller-dispatch [_ app-id controller-name subscribing-controller-name sub-fn]
         (validate-controller-exists! @app-state* controller-name)
         (swap! app-state* assoc-in [:subscriptions-on-controller-dispatch controller-name app-id subscribing-controller-name] sub-fn)
         #(swap! app-state* dissoc-in [:subscriptions-on-controller-dispatch controller-name app-id subscribing-controller-name]))
       (-subscribe-on-controller-broadcast [_ app-id controller-name subscribing-controller-name sub-fn]
         (validate-controller-exists! @app-state* controller-name)
         (swap! app-state* assoc-in [:subscriptions-on-controller-broadcast controller-name app-id subscribing-controller-name] sub-fn)
         #(swap! app-state* dissoc-in [:subscriptions-on-controller-broadcast controller-name app-id subscribing-controller-name]))
       (-get-derived-state [_]
         (get-derived-app-state @app-state*))
       (-get-derived-state [_ controller-name]
         (get-in @app-state* [:app-db controller-name :derived-state]))
       (-get-meta-state [_]
         (get-app-meta-state @app-state*))
       (-get-meta-state [_ controller-name]
         (get-in @app-state* [:app-db controller-name :meta-state]))
       (-get-app-state* [_]
         app-state*)))))

(defn make-app-proxy [proxied-fn]
  (fn [& args]
    (apply proxied-fn args)))

(def stop!
  ^{:doc      "Stops the running application instance. This will stop and terminate all running controllers."
    :arglists '([app-instance])}
  (make-app-proxy protocols/-stop!))

(def dispatch
  ^{:doc      "Dispatches an event to a controller."
    :arglists '([app-instance controller-name event] [app-instance controller-name event payload])}
  (make-app-proxy protocols/-dispatch))

(def broadcast
  ^{:doc      "Broadcasts an event to all running controllers."
    :arglists '([app-instance event] [app-instance event payload])}
  (make-app-proxy protocols/-broadcast))

(def transact
  ^{:doc      "Runs the transaction fn inside the transact block."
    :arglists '([app-instance transaction])}
  (make-app-proxy protocols/-transact))

(def subscribe
  ^{:doc      "Subscribes to the controller's state. Subscription fn will be called whenever the controller's state changes."
    :arglists '([app-instance controller-name sub-fn])}
  (make-app-proxy protocols/-subscribe))

(def subscribe-meta
  ^{:doc      "Subscribes to the controller's meta state. Subscription fn will be called whenever the controller's state changes."
    :arglists '([app-instance controller-name sub-fn])}
  (make-app-proxy protocols/-subscribe-meta))

(def get-derived-state
  ^{:doc      "Returns the current state of all running controllers, or of the controller whose name is passed as the argument."
    :arglists '([app-instance] [app-instance controller-name])}
  (make-app-proxy protocols/-get-derived-state))

(def get-meta-state
  ^{:doc      "Returns the current meta state of the controller."
    :arglists '([app-instance controller-name])}
  (make-app-proxy protocols/-get-meta-state))

(def get-batcher
  ^{:doc      "Returns the batcher fn. Subscription fns are called inside the batcher - this is useful for React integration, to ensure that there are no unnecessary re-renderings."
    :arglists '([app-instance])}
  (make-app-proxy protocols/-get-batcher))

(def get-id
  ^{:doc      "Returns the app-instance's id."
    :arglists '([app-instance])}
  (make-app-proxy protocols/-get-id))

(def get-api*
  ^{:doc      "Returns controller's exposed API object wrapped in a derefable object."
    :arglists '([app-instance controller-name])}
  (make-app-proxy protocols/-get-api*))

(def get-app-state*
  ^{:doc      "Returns the atom holding the app-state"
    :arglists '([app-instance])}
  (make-app-proxy protocols/-get-app-state*))

(defn call
  "Calls an API fn on the controller's exposed API object."
  [app controller-name api-fn & args]
  (protocols/-call app controller-name api-fn args))

(defn get-running-controllers
  "Returns a map of running controllers. Useful for debugging."
  [app-instance]
  (let [app-state* (get-app-state* app-instance)
        app-state  @app-state*]
    (:app-db app-state)))
