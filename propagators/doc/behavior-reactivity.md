# Behavior Reactivity

Source files:

- `propagators/datastructures/behavior.clj`
- `propagators/datastructures/compound_object.clj`
- `propagators/cells/cell_protocol.clj`
- `test/propagators_behavior_test.clj`

## Status

This is the first reactive behavior experiment. It does not add compiler syntax
or change the scheduler kernel. Behavior support is installed as a normal
network-local cell protocol, like intensity and dependency values.

## Model

A behavior source is a sparse compound object of timestamped events. Events are
attached with `p:event`, which is just `obj/p:slot` under an internal event slot
key. The source cell therefore stays an ordinary compound-object collection.

`p:behavior` reduces that sparse event collection into a retained history view:

```clojure
(p:behavior source-id merge-net-id init-id out-id)
```

The reducer is responsible for retention. It may keep every point event, emit
constant/open intervals, or keep a bounded window. Nothing in the kernel deletes
history; a newer retained view supersedes an older one through behavior merge
evidence.

## Content vs Strongest

Behavior cells use the existing cell split deliberately:

| Cell field | Behavior meaning |
|------------|------------------|
| `content` | retained sparse history compound object |
| `strongest` | layered summary with current/latest value in `:base` |

The retained history itself is not a Clojure map as a public value. It is a
compound-object map whose public slots are temporal records:

```clojure
6  -> {:at 6 :value :x}
10 -> {:at 10 :value :y}
```

For constant histories, the public slots are segment starts:

```clojure
0 -> {:from 0 :to 6 :value value/nothing}
6 -> {:from 6 :to :infinity :value :x}
```

The strongest projection is also a compound-object layered value:

```clojure
{:base latest-value
 :behavior/summary {:behavior/reducer reducer-id
                    :behavior/source-keys #{...}
                    :behavior/source-count n
                    :behavior/retained-count m
                    :behavior/history-keys [...]}}
```

This summary is necessary because the scheduler wakes and stores cells based on
strongest changes. If a late event expands history but does not change the latest
value, the summary still changes through source keys and retained count. That
lets the kernel remain unchanged while behavior content grows monotonically.

## Reducer State

The reducer accumulator is also slot-addressable. `empty-history-state` returns a
compound-object layered value with:

```clojure
:behavior/events  -> compound-object event evidence
:behavior/history -> compound-object retained history
```

Reducer helper code may convert these slots to Clojure maps internally for
sorting and windowing, but the accumulator value passed between reducer rounds is
a compound object. Tests assert this with `behavior-history-state-is-slot-addressable`.

## Reducer Policies

Current reducer helpers:

- `event-history-reducer-net`: retained point-event history. Sparse events stay
  point facts; an event at `6` does not imply `6 -> infinity`.
- `constant-history-reducer-net`: explicit constant/open intervals. If the first
  observed event is after `0`, the reducer emits an explicit `value/nothing`
  interval before it.
- `window-history-reducer-net n`: keeps the last `n` retained point records while
  preserving source-key evidence in the strongest summary.

Different reducer ids in one behavior output cell contradict in v1. Equal source
evidence with unequal retained history also contradicts. A behavior update whose
source evidence is a superset replaces the older retained view.

## Kernel Boundary

This design intentionally does not change `propagators/core.clj`.

The kernel contract remains:

1. merge a message into cell content
2. compute strongest
3. store the merged cell only when strongest changes
4. wake downstream propagators only when strongest changes

Behavior therefore makes knowledge growth visible in strongest by carrying a
summary layer. History-aware operators can inspect cell content directly when
they need the retained sparse history. Ordinary operators can consume the
current value from `:base`.

If a future behavior domain needs to retain content changes whose strongest
summary does not change, that is a new kernel discussion and should not be
introduced as a hidden behavior change.

## Tests

`test/propagators_behavior_test.clj` covers:

- reducer state is slot-addressable via compound-object layers
- event before reducer installation
- reducer before later event
- late out-of-order events updating retained history without changing latest
- duplicate and conflicting same-tick events
- point-event histories do not imply continuation
- constant histories emit explicit open intervals
- window retention is reducer behavior
- behavior merge/strongest protocol rules

Regression command:

```sh
clojure -M:test propagators-behavior-test
clojure -M:test propagators
```
