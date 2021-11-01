(ns keechma.next.fence
  ^{:doc "Fence allows you to wrap an app instance and synchronize changes only when a predicate-fn returns true. If the predicate-fn returns false
          subscriptions will return last value captured while the predicate-fn was returning true. Broadcasts and dispatches will be no-op while the
          predicate-fn returns false."}
  (:require [keechma.next.protocols :as pt]))

(defn wrap-sub-fn [sub-fn is-running*]
  (fn [& args]
    (when @is-running*
      (apply sub-fn args))))

(defn make-fence [app-instance predicate-fn]
  (let [fence-id (-> 'fence-sub-id gensym str)

        derived-app-state (-> app-instance pt/-get-derived-state)
        app-meta-state (-> app-instance pt/-get-meta-state)
        is-running (predicate-fn derived-app-state app-meta-state)

        derived-app-state* (volatile! (when is-running derived-app-state))
        app-meta-state* (volatile! (when is-running app-meta-state))
        is-running* (volatile! is-running)

        subscription (fn [derived-app-state app-meta-state]
                       (let [is-running (predicate-fn derived-app-state app-meta-state)]
                         (when is-running
                           (vreset! derived-app-state* derived-app-state)
                           (vreset! app-meta-state* app-meta-state))
                         (vreset! is-running* is-running)))
        unsubscribe (pt/-subscribe-fence app-instance subscription)]
    (reify
      pt/IRootAppInstance
      (-stop! [_]
        (unsubscribe))
      (-subscribe [_ controller-name sub-fn]
        (let [wrapped-sub-fn (wrap-sub-fn sub-fn is-running*)]
          (pt/-subscribe app-instance controller-name wrapped-sub-fn)))
      (-subscribe-meta [_ controller-name sub-fn]
        (let [wrapped-sub-fn (wrap-sub-fn sub-fn is-running*)]
          (pt/-subscribe-meta app-instance controller-name wrapped-sub-fn)))
      (-subscribe-fence [_ sub-fn]
        (let [wrapped-sub-fn (fn [derived-app-state app-meta-state]
                               (when (predicate-fn derived-app-state app-meta-state)
                                 (sub-fn derived-app-state app-meta-state)))]
          (pt/-subscribe-fence app-instance wrapped-sub-fn)))
      (-subscribe-on-controller-dispatch [this app-id controller-name subscribing-controller-name sub-fn]
        (let [wrapped-sub-fn (wrap-sub-fn sub-fn is-running*)]
          (pt/-subscribe-on-controller-dispatch app-instance app-id controller-name subscribing-controller-name wrapped-sub-fn)))
      (-subscribe-on-controller-broadcast [this app-id controller-name subscribing-controller-name sub-fn]
        (let [wrapped-sub-fn (wrap-sub-fn sub-fn is-running*)]
          (pt/-subscribe-on-controller-broadcast app-instance app-id controller-name subscribing-controller-name wrapped-sub-fn)))
      (-get-derived-state [_]
        @derived-app-state*)
      (-get-derived-state [_ controller-name]
        (get @derived-app-state* controller-name))
      (-get-meta-state [_ controller-name]
        (get @app-meta-state* controller-name))
      (-get-batcher [_]
        (pt/-get-batcher app-instance))
      (-get-app-state* [_]
        (pt/-get-app-state* app-instance))
      pt/IAppInstance
      (-dispatch [_ controller-name event]
        (when @is-running*
          (pt/-dispatch app-instance controller-name event)))
      (-dispatch [_ controller-name event payload]
        (when @is-running*
          (pt/-dispatch app-instance controller-name event payload)))
      (-broadcast [_  event]
        (when @is-running*
          (pt/-broadcast app-instance event)))
      (-broadcast [_  event payload]
        (when @is-running*
          (pt/-broadcast app-instance event payload)))
      (-get-api* [_ controller-name]
        ;; TODO: Figure out if we can somehow wrap returned API
        (pt/-get-api* app-instance controller-name))
      (-transact [_ transaction]
        (when @is-running*
          (pt/-transact app-instance transaction)))
      (-call [_ controller-name api-fn args]
        (when @is-running*
          (pt/-call app-instance controller-name api-fn args)))
      (-get-id [this]
        fence-id))))
