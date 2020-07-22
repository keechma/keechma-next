# Controllers

Controllers are the heart of a Keechma/next app. They have a few responsibilities:

- They control the data under a key in the global app state
- They derive state for the UI layer and other controllers (based on its internal state and derived state of its deps)
- They communicate with the outer world and react to events

In most of the other frameworks, you will see these responsibilities handled by different entities. For instance in
Keechma/classic controllers were writing to the app-state, and subscriptions were reading from it. In practice it caused the logic to be repeated in multiple places. This also introduced the question of data ownership as multiple controllers could write to the same place in the app-state.

With combining these two we can ensure two things:

1. It is clear who owns the data
2. Controllers can now form a dependency tree where other controllers can react to changes in ancestor data

In Keechma/classic controllers were started or stopped on route change. While this concept was powerful, it wasn't powerful enough to handle all the cases. There are other "tectonic" events that cause big changes in the app's internal state - for instance going from logged out to the logged in state. This resulted in a parallel system(s) that could react to
those events and then load the data or do some work. One of those systems was a data loader lib which was able to receive
a graph of data sources and then automatically resolve them. This was better, and it helped us to write more declarative
code, but then we had an issue of synchronization between controllers and the data loader - controllers were started
but had to wait until the data loader is done to be able to perform work.

In Keechma/next we've decided to solve the problems of timing and synchronization on the framework level. Controllers
can declare their dependencies, and the controller manager will ensure that the correct controllers are started and
stopped. When we introduced that change, the problem that was left was one of data ownership. If multiple controllers can write to the same place in the app-state this introduces another layer of the synchronization problem - controllers must be aware of each other. By allowing controllers to derive their state, it is now clear who owns the data under each key in the app-state map. To ensure that a controller can't change data it doesn't own we are not exposing the whole app-state atom to the controllers anymore. Each controller can read and write its own state and read the state of its parents. This keeps controllers' behavior deterministic and predictable.

Controllers are started when the params function associated with them (in the app config map) returns a non-nil value (false is ok). Keechma/next does the following on each controller state change:

- It calls the params function of all descendant controllers. Params functions receive derived values of all parent controllers.
- It will compare the returned value to the previous value (returned on the previous run of the params function)
- Based on the return value, it will do one of the following actions:

| Prev Params | Current Params | Prev == Current | Actions                                                                   |
|-------------|----------------|-----------------|---------------------------------------------------------------------------|
| falsy       | falsy          | -               | Do nothing (controller is in :stopped state)                              |
| truthy      | falsy          | -               | Stop the current controller instance                                      |
| falsy       | truthy         | -               | Start a new controller instance                                           |
| truthy      | truthy         | false           | Stop the current controller instance and start a new one                  |
| truthy      | truthy         | true            | Dispatch :keechma.on/deps-change event to the running controller instance |

In Keechma/classic the params function was defined on the controller level. In Keechma/next params function is defined when the controller is registered in the app. This allows us to separate the behavior of the concrete instance and allows you to write more generalized controllers that can be used multiple times in an app.