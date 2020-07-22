# keechma/next

> That makes me know that, we we we we're doin'<br>
  We had the right idea in the beginning<br>
  And and we just need to maintain our focus, and elevate<br>
  We what we do we update our formulas<br>
  We have certain formulas but we update em (oh right)<br>
  with the times, and everything y'know<br>
  And and so, ya know<br>
  The rhyme style is elevated<br>
  The style of beats is elevated<br>
  but it's still Guru and Premier<br>
  And it's always a message involved<br>
  
>  **Gang Starr - You Know My Steez**


Keechma/next is the second iteration of the [Keechma](https://www.github.com/keechma/keechma) framework. In its scope, it's similar to [Integrant](https://github.com/weavejester/integrant) - **a data driven, state management framework for single page apps**.

Keechma/next is built on top of experience of building ~50 (non trivial) apps in the span of 5 years, in agency setting. You could say that its features were forged in infernal flames of agency work. In these 5 years, we've built client facing web apps, B2C mobile apps, back offices and animation heavy landing pages. This allowed us to define features we need to build apps in a sustainable and agile fashion.

Original Keechma framework was based on Reagent, and it was relying on Reagent's reactive atom to keep UI synchronized with the application state. Keechma/next is self contained framework, and has no dependency on any UI frameworks. It can be easily integrated directly with React - through the hooks system. It features automatic state -> UI synchronization in a system inspired by the reactive atom.

## Features

Keechma/next's allows you to build apps that are explicit, deterministic and predictable. This is achieved with the architecture that is somewhat different from the other ClojureScript frameworks:

1. No shared app-state atom
2. Integration of state reads and state writes
3. Automatic state synchronization that can be effectful

In Keechma/next your main building block is the controller. Controllers are composed in the app state graph, and they can depend on each other. UI can subscribe to the controller state. Each controller has it's own state, with read and write access, and each controller has read access to it's parent controllers'. Controllers can react to events. When the controller's state changes all descendant controllers are automatically synchronized. Each controller controls a key in the app-state map.

Controllers are implemented with multi methods which are dispatched on the controller keyword. Controller keywords _must_ be derived from the `:keechma/controller` keyword.

`(derive :counter :keechma/controller)` 

### Example

```clojure
(ns example.controller
  (:require [keechma.next.core :as core]
            [keechma.next.controller :as ctrl])

(derive :counter :keechma/controller)

(defmethod ctrl/start :counter [_ _ _ _]
  0)

(defmethod ctrl/handle :counter [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(def app
  {:keechma/controllers {:counter {:keechma.controller/params true}}})

(def app-instance (core/start! app))

(core/get-derived-state app)                                ;; {:counter 0}

(core/dispatch app :counter :inc)

(core/get-derived-state app)                                ;; {:counter 1}
```

In this example, we've created a very simple counter controller:

1. It's state is initialized with value `0`
2. Whenever the event `:inc` is dispatched to the `:counter` controller it's state value is incremented

Keechma/next app is defined as a map, where we glue together all the controllers in the app. Reconciliation of the state is strict and synchronous. 

Let's expand the example. In this case we'll add the `:counter-2` controller whose value will be `:counter` controller's value multiplied by 2.

```clojure
(ns example.controller
  (:require [keechma.next.core :as core]
            [keechma.next.controller :as ctrl])

(derive :counter :keechma/controller)

(defmethod ctrl/start :counter [_ _ _ _]
  0)

(defmethod ctrl/handle :counter [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(derive :counter-2 :keechma/controller)

(defmethod ctrl/derive-state :counter-2 [_ _ {:keys [counter]]
  (* 2 counter))

(def app
  {:keechma/controllers {:counter   {:keechma.controller/params true}
                         :counter-2 {:keechma.controller/params true
                                     :keechma.controller/deps   [:counter]}}})

(def app-instance (core/start! app))

(core/get-derived-state app)                                ;; {:counter 0 :counter-2 0}

(core/dispatch app :counter :inc)

(core/get-derived-state app)                                ;; {:counter 1 :counter-2 2}
```

In this example, `:counter-2` controller has no internal state. Instead it's state is derived from it's parents' state. `ctrl/derive-state` method allows you to derive the public state based on the controller's internal state and the state of its parents.

Let's add another controller to the mix:

```clojure
(ns example.controller
  (:require [keechma.next.core :as core]
            [keechma.next.controller :as ctrl])

(derive :counter :keechma/controller)

(defmethod ctrl/start :counter [_ _ _ _]
  0)

(defmethod ctrl/handle :counter [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(derive :counter-2 :keechma/controller)

(defmethod ctrl/derive-state :counter-2 [_ _ {:keys [counter]]
  (* 2 counter))


(derive :counter-3 :keechma/controller)

(defmethod ctrl/start :counter-3 [_ _ _ _]
  0)

(defmethod ctrl/handle :counter-3 [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(defmethod ctrl/derive-state :counter-3 [_ state {:keys [counter counter-2]]
  (+ state counter counter-2))

(def app
  {:keechma/controllers {:counter   {:keechma.controller/params true}
                         :counter-2 {:keechma.controller/params true
                                     :keechma.controller/deps   [:counter]}
                         :counter-3 {:keechma.controller/params true
                                     :keechma.controller/deps   [:counter :counter-2]}}})

(def app-instance (core/start! app))

(core/get-derived-state app)                                ;; {:counter 0 :counter-2 0 :counter-3 0}

(core/dispatch app :counter :inc)

(core/get-derived-state app)                                ;; {:counter 1 :counter-2 2 :counter-3 3}

(core/dispatch app :counter-3 :inc)

(core/get-derived-state app)                                ;; {:counter 1 :counter-2 2 :counter-3 4}
```

In this case `:counter-3` has it's own internal state, and it's using it in combination with its parents' states to derive its public state. 

If you look at the app map in the last two examples, you'll notice that controllers define its dependencies. This allows Keechma/next to know which controllers need to be informed of the state change. Although you're not able to access whole app-state atom, you can rely on this mechanism to ensure that your controllers have the correct data pushed to them when they need it.

There are no limitations on what can be stored in the controller state. In these examples, we had simple values stored, but you can store whatever you want - it's a normal Clojure atom. In fact, you'll probably have a few controllers that manage bigger chunks of state, and a bunch of controllers that will only manage smaller pieces.

There are no limitations to effects either. Event handlers can mutate state, perform AJAX requests or do whatever else is required to implement your app. 

In all these examples, controllers' params were set to `true`. Controller's params determine current state of the controller. It can be in `:stopped` or `:running` state (don't confuse it with controller's state atom). When params are set to a truthy value, or to a function that returns a truthy value, controllers will be started. Let's make another small example:

```clojure
(ns example.controller
	(:require [keechma.next.core :as core]
				[keechma.next.controller :as ctrl])

(derive :counter :keechma/controller)

(defmethod ctrl/start :counter [_ _ _ _]
  0)

(defmethod ctrl/handle :counter [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(derive :counter-2 :keechma/controller)

(defmethod ctrl/derive-state :counter-2 [_ _ {:keys [counter]]
  (* 2 counter))


(def app
  {:keechma/controllers {:counter   {:keechma.controller/params true}
                         :counter-2 {:keechma.controller/params (fn [deps] (even? (:counter deps))
                                                                  :keechma.controller/deps [:counter]}}})

(def app-instance (core/start! app))

(core/get-derived-state app)                                ;; {:counter 0 :counter-2 0}

(core/dispatch app :counter :inc)

(core/get-derived-state app)                                ;; {:counter 1}

(core/dispatch app :counter :inc)

(core/get-derived-state app)                                ;; {:counter 2 :counter-2 4}
```

 If you compare the `:counter-2` controller implementation, it is identical to the previous examples. But, in this case we've passed a function as the controller's params. This function will be called whenever any parent state is changed. If that function returns true, controller will be started, otherwise stopped. You can notice that after second `:inc` dispatch, `:counter-2`	key is not present in the derived state map. This is because the controller is not running at that moment (`:counter` value is `1`, so params function returned false). If the params function returned truthy value on previous call, and returns a truthy value again, these values will be compared. If they are different controller will be restarted (stopped and started again). This behavior drastically simplifes your code, because you don't have to handle cases like these in your own code. Params function determines the life time of a controller.
 
| Prev Params | Current Params | Prev == Current | Actions                                                                   |
|-------------|----------------|-----------------|---------------------------------------------------------------------------|
| falsy       | falsy          | -               | Do nothing (controller is in :stopped state)                              |
| truthy      | falsy          | -               | Stop the current controller instance                                      |
| falsy       | truthy         | -               | Start a new controller instance                                           |
| truthy      | truthy         | false           | Stop the current controller instance and start a new one                  |
| truthy      | truthy         | true            | Dispatch :keechma.on/deps-change event to the running controller instance |

### Controller variants

In examples so far, we've used only on controller variant - singleton. Singleton controllers are great when you have only one instance of controller running in the system. For instance, you could have a `:router` controller, `:jwt` or `:current-user` controller. Another variant is identity controllers. Identity controllers use a composite key `[:user :some-identity]`. They are useful when you want to mount sam controller multiple times in the same app. For all other purposes they are identical to singleton controllers. Last variant is factory controller. Factory controllers are not mounted directly, instead they can dynamically produce configuration for controllers that should be mounted. This is useful when you have to dynamically mount controllers based on some data that is not available in development time. For instance you might have a Kanban board, and you want to mount a controller for each column. All these controllers will be of same type, but each will be its own instance. Factory controllers' configuration looks different from the singleton and identity controllers:

```
{:keechma/controllers
  {:counter-1   {:keechma.controller/params true}
   [:counter-2] {:keechma.controller.factory/produce (fn [{:keys [counter-1]}]
                                                       (->> (range counter-1 (+ 2 counter-1))
                                                            (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                            (into {})))
                 :keechma.controller/deps [:counter-1]}}}
```

1. Factory controllers' key is a single element vector. This is controller type.
2. Instead of `:keechma.controller/params` attribute, they have `:keechma.controller.factory/produce` attribute which should be a function.

Produce function should return a map where keys are ids and values are controller config maps. These will be merged with the parent map to produce final configuration. For instance if the produce function returned
`{1 {:keechma.controller/params 1} 2 {:keechma.controller/params 1}}` Keechma/next would mount two controllers: `[:counter-2 1]` and `[:counter-2 2]`. Produce function is called at the same time when the params function would be called (when parents' state changes), and Keechma/next will make a diff between the current and the previous value, and determine which controllers have to be started, stopped and removed. 

Controller variant is determined based on the configuration (controller key and configuration map), and you can have the same controller mounted as a singleton, identity or factory controller without any changes to the controller implementation.

### Controller lifecycle

Controllers have a defined lifecycle which is guaranteed by Keechma/next. Lifecycles reduce need for a lot of boilerplate code that deals with resource initialization and teardown. Each controller is implemented with a small number of multimethods (consult documentation in controller.cljs file). Controllers should be "thin", they should only act as a glue code between the UI, various APIs and domain code. Controllers are unopinionated on purpose, you get full access to the state atom, so you can implement features in any way you need. Keechma/next will call appropriate methods when controller's state changes - you can use these methods to implement your logic.

#### On application start

`ctrl/prep` method is called for each controller. This method should return a modified controller config map. This map will be used to initialize all instances of the controller (controllers are not initialized at this point yet)

#### On controller start

- `ctrl/init` method is called. This method should return a modified controller config map which will be used for that controller instance. Use this method to initialize any resources (for instance start a go-loop or connect to a websocket)
- `ctrl/start` method is called. This method should return initial controller state
- `:keechma.on/start` event is dispatched to the controller. This will result with the call to the `ctrl/handle` method
- `ctrl/derive-state` method is called to compute the derived state

#### On parent state change

_This happens if the params function returned same truthy value like on previous invocation._

- `:keechma.on/deps-change` event is dispatched to the controller. This will result with the call to the `ctrl/handle` method. Payload is a map that contains only the keys that changed from the previous invocation.
- `ctrl/derive-state` method is called to compute new derived state. If this state is different from the current value (cached in the app-state) all descendant controllers are reconciled

#### On controller stop

_This happens if the controller was running and params function returned different value than on the previous invocation_

- `:keechma.on/stop` event is dispatched to the controller. This will result with the call to the `ctrl/handle` method
- `ctrl/stop` method is called. This method should return final state. This state will be passed to the `ctrl/start` function when the next instance is started
- `ctrl/terminate` method is called. Use this method to teardown any resources initialized in the `ctrl/init` method

#### On event dispatch

- `ctrl/handle` method is called with the event and payload
- `ctrl/derive-state` method is called to compute new derived state. If this state is different from the current value (cached in the app-state) all descendant controllers are reconciled

_* This is not 100% correct because there are some optimizations around when the `ctrl/derive-state` method is called, but this method will be called if the controller's state changes_

Lifecycle functions allow you to focus on your domain code and drastically reduce the number of events that are not domain related.

### Subapps

As your app grows, there will be features that are local. For instance, you might have a number of controllers that are related to the user profile app area. Keechma/next allows you to group these controllers into a subapp. Subapps have their own equivalent of `params` - `:keechma.app/should-run?` function. When this function returns a truthy value, Keechma/next will reconcile its child controllers. These controllers still have their own `:keechma.controller/params` and `:keechma.controller/deps` that determine _when_ a controller should run. Use `:keechma.app/should-run?` function to extract the shared params logic. Subapps can be nested so you can create app hierarchies that make sense for your app.

```clojure
{:keechma/controllers {:user-role {:keechma.controller/params true }}
 :keechma/apps        {:public    {:keechma/controllers     {:posts {:keechma.controller/params true
                                                                     :keechma.controller/type   :public-posts}}
                                   :keechma.app/should-run? (fn [{:keys [user-role]}] (= :guest user-role))
                                   :keechma.app/deps        [:user-role]}
                       :user      {:keechma/controllers     {:posts {:keechma.controller/params true
                                                                     :keechma.controller/type   :user-posts}}
                                   :keechma.app/should-run? (fn [{:keys [user-role]}] (= :user user-role))
                                   :keechma.app/deps        [:user-role]}}}
```

In this example we have three apps defined. The main app - with the `:user-role` controller, the `:public` app with the `:posts` controller and the `:user` app with the `:posts` controller. You've probably noticed that both the `:public` and the `:user` apps have the same - `:posts` controller defined. Also, each of these controllers explicitly sets the `:keechma.controller/type` attribute - controllers are registered on the same key, but have a different type. This is possible because these apps are not able to run at the same time (their `:keechma.app/should-run?` function prevents this). Keechma/next's reconciliation is synchronous so these apps will be started / stopped in the same cycle - UI will only notice the _data_ change. This enables simpler controllers, where they can only implement feature set that makes sense for them. UI can stay dumb, and you don't have to have any conditional logic in the UI as long as the controllers react to same events. For instance, upvote event could cause the `:users` `:posts` controller to send the upvote request to the server, while `:public` `:users` controller could redirect the user to the registration page.

In future, we'll add support for the `:keechma.app/load` function which will allow you to return app config map from a function with the support for Promises. This will enable code splitting for subapps.

### Transactions

Keechma/next's reconciliation is transacted. This means that the any controllers that are dirtied as a result of some state change will be reconciled before next round of reconciliation starts. If any events are dispatched _during_ the reconciliation, and these events cause a state change, controllers dirtied by this state change will be reconciled in the next round (run to completion semantics).

_* This is not 100% correct because of some exceptions caused by the optimizations in the reconciliation code_

State changes **must** be wrapped inside a transaction. Synchronous calls to the `ctrl/handle` method are automatically wrapped inside a transaction, but there are cases where you might have an async call that will mutate the state as a result. In that case, wrap this callback in the `ctrl/transact` manually.

```clojure
(defmethod ctrl/handle :login [ctrl cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :do-login (js/setTimeout #(ctrl/transact ctrl
                                (fn []
                                  (ctrl/dispatch ctrl :token :update-token "TOKEN")
                                  (ctrl/dispatch ctrl :current-user :update-user {:id 1 :username "retro"}))))
    nil))
```

### UI Subscriptions

Keechma/next has no integration with the UI layer in the core library. But, Keechma/next has support for subscriptions which can be used to integrate your UI library of choice (these are 100-200 lines of code and can be generalized). Keechma/next provides `core/subscribe` function that can be used to subscribe to the controller's state:

```clojure
(ns example.controller
  (:require [keechma.next.core :as core]
            [keechma.next.controller :as ctrl])

(derive :counter :keechma/controller)

(defmethod ctrl/start :counter [_ _ _ _]
  0)

(defmethod ctrl/handle :counter [{:keys [state*]} event _]
  (case event
    :inc (swap! state* inc)
    nil))

(def app
  {:keechma/controllers {:counter {:keechma.controller/params true}}})

(defn subscription-handler [state] (println state))

(def app-instance (core/start! app))

(def unsubscribe (core/subscribe app-instance :counter subscription-handler))

(core/dispatch app :counter :inc)                           ;; Subscription function will be called here synchronously

(unsubscribe)                                               ;; Removes the subscription

```

Subscriptions are synchronously called after the reconciliation is done.

## Why?

Why prevent access to the whole app-state? Why integrate state reads and writes?

In our experience as apps grow, implicit dependencies make them more and more rigid. You start by managing a piece of data in one place, but as the features are added, more and more places read and write in the same location. You can approach this by having more events flowing in the system, so each handler manages only it's own piece of state, but that doesn't solve the problem it just moves it into a different place. Instead of data dependencies, you now have implicit dependencies between events.

We wanted a way to always manage one piece of data in one place. In Keechma/next controllers manage data under a key in the map. This data is _owned_ by that controller. When we've concluded that data ownership is a good idea, we needed a way to depend on other pieces of data. This is why the controllers are organized in a dependency graph. When controller's state changes all descendant controllers will be reconciled, and their derived state recomputed.

Controllers owning their data allows us to do a lot of repetitive, boilerplate work for you. For instance, if you have some async action that is mutating the state in its callback, and the controller is stopped during the async action, Keechma/next will prevent accidental state change. Each controller instance gets it's own atom, which is detached from the app when the controller is stopped. 

Another benefit is reduction of events flowing in the system. If you have only events as your building block, it's easy to create events that result in more events that result in more events. This kind of implementation can become hard to manage (and to hold in your head while you're developing or debugging). Keechma/next ensures that controllers are started at the right time with right dependencies. You can focus on your domain logic instead on complex event chains. In our opinion, events should be used to communicate domain actions, and their number should be small.

Keechma/next removes the need for manual synchronization which can happen when you have separate state atoms. In our opinion this is best of both worlds - data ownership and automatic synchronization. 

### UI independence

Keechma/next removes dependency on Reagent _and_ React. It's a standalone library that can be integrated with any UI framework that might surface in the future. Reagent is a great library, and we've succesfuly pushed many applications into production with it, but React is becoming better, and a lot of features that were exclusive to Reagent are now possible with raw React. Keechma/next implements subset of reactive features that _we_ need. We believe that this architecture can enable simple, understandable apps that have a very rich feature set. Integrant was a great inspiration, and in our experience with it, it became obvious how powerful it is to be able to have an overview of the app in one place. As mentioned previously, Keechma is developed in an agency context, and our constraints may not be 100% shared with you, but it enabled us to iterate quickly and to learn from our mistakes. Keechma/next is the result of thousands of hours we've spent working on single page and mobile apps in a very dynamic environment.

### Is Keechma/next the right choice for you?

Depending on the size of your app(s), and the rythm of your development, you might not see immediate need for the architecture provided by Keechma/next. But, Keechma/next shouldn't be judged by the code you write, instead think about all the code you _won't have to write_. Predictable and deterministic apps are not trivial to implement, and in our opinion Keechma/next provides everything you need to pull it of. Keechma/next is a contained library, without any global state leaking, so you can try it out without major commitment. If you do try it, feel free to reach out to us on #keechma channel on the Clojurians slack.

## Commercial support

[Very Big Things](https://verybigthings.com) sponsors Keechma development, and is offering design and development services. If you ever need help with your app, feel free to reach out to us, and work with the Keechma core team on your next app.

## Inspiration

Keechma/next is inspired by many libraries in Clojure ecosystem (and beyond). Here's a list of some of the codebases we've researched and studied during the development:

- [Integrant](https://github.com/weavejester/integrant)
- [Reagent](https://github.com/day8/re-frame)
- [re-frame](https://github.com/day8/re-frame)
- [UIx](https://github.com/roman01la/uix)
- [Rum](https://github.com/tonsky/rum)
- [adapton](https://github.com/roman01la/adapton)
- [lentes](https://github.com/funcool/lentes)



## License

Copyright © 2020 Mihael Konjevic, Tibor Kranjcec

Distributed under the MIT License. 