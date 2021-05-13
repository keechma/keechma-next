# Changelog

## [0.1.0] - 2021-05-13

### Breaking

- If you define `:keechma.controller/type` as a function, it will receive the deps map directly. Previously it was recevieing value of the `:keechma.controller/params` key.

### Added

- `keechma.next.controller/params` method which will receive controller map and params as its arguments. Use it to validate or coerce params returned from the `:keechma.controller/params` key. If this function returns a falsy value, controller will not be started.
