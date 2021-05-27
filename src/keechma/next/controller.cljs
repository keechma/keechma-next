(ns keechma.next.controller
  (:require [keechma.next.protocols :as protocols]))

(declare transact)

(defn controller-dispatcher
  "Dispatcher for controller multimethods that receive the controller config map as it's first argument. Returns the
  value of the `:keechma.controller/type` key from the controller map (passed in as the first argument)."
  [c & _]
  (:keechma.controller/type c))

(defn transacted-controller-dispatcher [c & _]
  (if (:keechma/is-transacting c)
    (:keechma.controller/type c)
    :keechma.controller/wrap-transaction))

(defmulti prep
  "Prep is called once when the app is started (for each controller). It will receive the controller map as it's only argument. It should return a new controller config. This allows you to prep the map for all controller instances that could be created during the app's lifetime."
  controller-dispatcher)

(defmulti params
  "Params is called before controller is started. It can be used to validate or process value returned from the `:keechma.controller/params` key in the application map. It will receive the controller map (as returned from the `keechma.next.controller/prep` function) and params as the arguments. This function will be called only if the value returned from `:keecma.controller/params` is truthy. If this function returns a falsy value, the controller will not be started."
  controller-dispatcher)

(defmulti init
  "Called before the controller is started (for each instance of controller, unlike prep). It will receive a controller map as it's only argument. This map will contain controller's config and will be passed to all controller functions as the first argument. This function should return a new controller config. This is a place where you can setup any stateful processes that will be used by the controller. For instance if you want to use `go-loop` to handle commands sent to controller, this is a place where you would create the incoming channel"
  controller-dispatcher)

(defmulti api
  "Called after `init` and before `start`. This function can expose an object that will be passed as a first argument to the API calls. API calls are not wrapped in the transact block automatically."
  controller-dispatcher)

(defmulti terminate
  "Called on the controller shutdown (after stop). This function is used to cleanup any resources created in the `init` method. This function is used for side-effects and it's return value is ignored."
  controller-dispatcher)

(defmulti start
  "Called once on the controller start. This function will receive the controller config map and a previous state and should return the initial state synchronously."
  controller-dispatcher)

(defmulti stop
  "Called once when the controller is stopped. This function will receive the controller config map and the current state and should return the final state. This allows you to preserve any state (if you need to) while the controller is not running. This state will not be visible to any descendants or subscriptions. State returned from the stop function will be passed to the _next_ instance's start function (as the fourth argument)"
  controller-dispatcher)

(defmulti handle
  "Called whenever an event is sent to the controller. It receives the controller config map, the command and the command payload as arguments. There is a good possibility that the controller will receive an event it's not handling, so make sure to properly manage this case. Other controllers and UIs can dispatch or broadcast events, and there is no way of knowing if a particular controller handles a particular event."
  transacted-controller-dispatcher)

(defmulti derive-state
  "This function is called whenever the controller's internal state is changed, or whenever the public state of any of the ancestor controller states is changes. Use this function to calculate the controller's public state. Public state is visible to the subscriptions and other controllers that depend on the controller. It will receive controller's internal state and a map with the states of ancestors. Use this function to derive the state if needed. All descendants and subscriptions will receive the returned value as a payload on the controller's key whenever the value changes"
  controller-dispatcher)

(defmethod prep :default [controller]
  controller)

(defmethod params :default [controller params]
  params)

(defmethod api :default [controller])

(defmethod init :default [controller]
  controller)

(defmethod terminate :default [controller])

(defmethod start :default [controller params deps-state prev-state])

(defmethod stop :default [controller params state deps-state])

(defmethod handle :default [controller event payload])

(defmethod handle :keechma.controller/wrap-transaction [controller event payload]
  (transact controller #(handle (assoc controller :keechma/is-transacting true) event payload)))

(defmethod derive-state :default [controller state deps-state]
  state)

(defn dispatch
  "Dispatches an event to a controller."
  ([controller controller-name event] (dispatch controller controller-name event nil))
  ([controller controller-name event payload]
   (let [app (:keechma/app controller)]
     (protocols/-dispatch app controller-name event payload)
     nil)))

(defn broadcast
  "Broadcasts an event to all running controllers."
  ([controller event] (broadcast controller event nil))
  ([controller event payload]
   (let [app (:keechma/app controller)]
     (protocols/-broadcast app event payload)
     nil)))

(defn transact
  "Runs the transaction fn inside the transact block."
  [controller transaction]
  (let [app (:keechma/app controller)]
    (protocols/-transact app transaction)))

(defn call
  "Calls an API fn on the controller's exposed API object."
  [controller controller-name api-fn & args]
  (let [app (:keechma/app controller)]
    (protocols/-call app controller-name api-fn args)))

(defn get-api*
  "Returns controller's exposed API object wrapped in a derefable object."
  [controller controller-name]
  (let [app (:keechma/app controller)]
    (protocols/-get-api* app controller-name)))
