# Clock-driven functional networks and XR

Experiment documentation is collected here. Runnable code and data remain in
[`examples/lain/overtone_synth/`](../../examples/lain/overtone_synth/).

This opt-in case study uses functional `(network (arguments) body)` expressions,
ordinary TTMS input, existing `xr:io`, and runtime-owned audio effects. It does
not change the default compiler, `Net`, runner kernel, compound synchronization,
or TTMS policy.

## Run silently

From the repository root:

```sh
clojure -M:example/synth --silent --host 0.0.0.0 --port 45673
```

Visit `http://127.0.0.1:45673/relationships` on this computer, or replace the
address with this computer's LAN IP on your phone. The test machine's address
on 2026-10-06 was `192.168.178.96`; addresses may change.

Binding `0.0.0.0` exposes the existing unauthenticated XR runtime controls and
network inspection on your LAN. Use trusted Wi-Fi only. Omit `--host` for a
loopback-only server. Stop with Ctrl-C; shutdown closes the clock, watcher,
server, and owned audio resources.

## Audio

The optional backend uses Overtone 0.16.3331 and an external SuperCollider
`scsynth` installation. No audio server starts merely by requiring the example.
With SuperCollider installed, replace the silent launch with:

```sh
clojure -M:example/overtone:example/synth --host 0.0.0.0 --port 45673
```

Audio plays on the computer, not the phone. The runtime creates one owned synth
group and short, conservatively limited sine notes. Reload clears that group's
voices; close stops only owned resources and an audio server started by this
backend. Do not run both launches on the same port.

## What the four windows mean

1. **Dataflow:** `pulse → clock → melody → envelope → voice → output`, plus the
   observation/presentation chain. Triangles are propagators; circles are cells.
   Arrows show the projected dataflow.
2. **Melody body:** the selected melody occurrence's internal `note-hz` network
   and its declared input/result cells. This is not a second unrelated program.
3. **Current note:** note payload and retained content, refreshed through TTMS.
4. **Activation history:** completed application activations, grouped by occurrence,
   over a rolling 30-second interval. Marks expose tick and measured duration.
   Click a node or history mark to inspect its current full-network context.

Windows use the existing drag, resize, graph zoom, and inspector controls.
On narrow screens the **Views** button opens the menu; windows stack vertically.
Window layout and drawing are renderer responsibilities, not Lain view semantics.

## Edit and reload

The watched file is `examples/lain/overtone_synth/synth.lain`. For example, change
the melody body from `(note-hz tick)` to `(+ 100 (note-hz tick))`. An external
editor or XR file editor writing that file is equally observable.

The watcher polls file text every 250 ms, compiles a complete fresh environment,
and replaces the live environment only after successful compilation. A failed
edit reports an error on stderr and in the application's `:errors` atom and keeps
the last good environment. Successful reload resets the generation, source cells,
tick sequence, and history; old-generation callbacks cannot advance the new one.

Both the file loader and website **Extend graph** console use the canonical
production compiler. Edit the watched file to replace the complete case study;
the console extends the current environment instead. A custom file can be selected with `--file path/to/program.lain` and
must declare `pulse` and `activation-log`; it must not request audio before input.

## Tests and benchmark

```sh
clojure -M:test recovered-slices
node --test test/graph/*test.mjs
clojure -M -m examples.lain.overtone-synth.benchmark 200
```

The benchmark uses a silent backend, one warm-up tick, and recording off/on.
It reports elapsed time, installed topology, bounded history, and projected view
characters. It excludes browser rendering and full-context transport; run without
other tests or active clock sessions for a useful performance comparison.

This is a **control-rate** experiment: the clock waits 250 ms after each completed
iteration. It is neither an audio-rate scheduler nor a hard-real-time metronome.
DSP stays inside SuperCollider. Already performed sound is irreversible; input
withdrawal prevents future note requests, not past sound. See `REPORT.md` for
verification evidence and unresolved limits.
