# Compiler 2 live environment and application boundary

Status: implemented by flat GUR. See
[Flat GUR and Compiler 2 Application](flat-gur-compiler-2-application.md).

Compiler 2 stores lexical environments as live compound objects. A closure
captures the lexical environment cell ID. Application creates a child frame by
declaring `env/parent`, the complete local-name set, and canonical local binding
slots. It never materializes or copies the environment into an application host
frame.

```text
captured environment cell
  -> scope-frame
  -> child environment cell
  -> local binding slot
  -> binding descriptor
  -> bound value cell
```

Local-first lookup is a flat recursive GUR declaration:

```clojure
(i/when-named :local-binding
              :local-present
              (local-binding sym :frame :out))

(i/when-named :parent-frame
              :local-missing
              (parent-binding :frame :out))
```

A declared local prevents parent traversal even while its value is unavailable.
A missing declaration recurs to the parent. Late descriptors, values, parent
frames, and parent values wake existing topology.

Application uses the same active `Net`:

```clojure
(gur/apply-closure-effect
 operator-id
 (into [context-id] argument-ids)
 result-id)
```

Named concrete boundaries connect outer arguments to frame locals and frame
outputs to caller cells. The connected named graph is the inspectable
application declaration. There is no retained application object, accumulated
child runner, argument descriptor compound, or application-layer adapter in
this path.
