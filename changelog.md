# Changelog

## [0.1.2] - 2021-05-27

- `:keechma.controller/name` is assoc-ed before controller map is passed to the `keechma.next.controller/params` method.

## [0.1.1] - 2021-05-27

### Bugfix

- `keechma.next.controller/broadcast` was previously called `keechma.next.controller/dispatch` when called as a 2-arity method. It's now fixed to correctly call `keechma.next.controller/broadcast` with 3rd argument set to `nil`.

## [0.1.0] - 2021-05-13

### Breaking

- If you define `:keechma.controller/type` as a function, it will receive the deps map directly. Previously it was recevieing value of the `:keechma.controller/params` key.

### Added

- `keechma.next.controller/params` method which will receive controller map and params as its arguments. Use it to validate or coerce params returned from the `:keechma.controller/params` key. If this function returns a falsy value, controller will not be started.
