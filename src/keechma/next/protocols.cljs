(ns keechma.next.protocols)

(defprotocol IAppInstance
  (-dispatch [this controller-name event] [this controller-name event payload])
  (-broadcast [this event] [this event payload])
  (-get-api* [this controller-name])
  (-transact [this transaction])
  (-call [this controller-name api-fn args])
  (-get-id [this]))

(defprotocol IRootAppInstance
  (-stop! [this])
  (-subscribe [this controller-name sub-fn])
  (-subscribe-meta [this controller-name sub-fn])
  (-get-derived-state [this] [this controller-name])
  (-get-meta-state [this controller-name])
  (-get-batcher [this])
  (-get-app-state* [this]))

(defn make-api-proxy [api-fn]
  (fn [{:keechma/keys [app]} controller-name & args]
    (let [api* (-get-api* app controller-name)]
      (apply api-fn @api* args))))
