# Editor frontend boundary

The independently maintained frontend architecture is documented in [lain-editor](https://github.com/Semi-0/lain-editor/blob/main/docs/reactive-architecture.md).

This repository owns the JVM host, execution, inspection, layout and filesystem effects. The frontend owns domain/UI separation, Missionary-derived view state, pure Replicant views, event interpretation and browser effects. Shared portable contracts are pinned Git dependencies; HTTP messages preserve the existing protocol.

The prior source model's candidate Javelin decision is historical context. Repository code now uses Replicant/Missionary, but no model facts were approved, superseded or persisted during this extraction. Any authorized model update must preserve prior identities/history and include the exported backup in its related commit.
