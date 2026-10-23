# Earlier Clojure coding tasks and results

These are historical experiments, not maximum-context measurements. Exact
prompts are frozen in `prompts/`, contracts in `tasks.json`, and independent
evaluators in `fixtures/`; `evaluator-manifest.json` records their SHA-256 values.
All tasks used datalog-research commit
`85010635cf887cf5e3eba1179b5181a5c31ed389`. Each task allowed only its source
file and corresponding test file to change, with no new dependencies.

## Task 1: stable-distinct-by

`[key-fn coll]` returns a vector of the first item for each distinct key, in
original order. Nil/empty input yields `[]`; nil and false are valid keys.
Support lists, finite lazy sequences, repeated structured keys, no mutation,
and exactly one key call per input item. The independent performance case
uses 200,000 items with 10,000 distinct keys and a five-second bound.
Evaluator: 3 tests / 14 assertions. Test namespace:
`benchmark.stable-distinct-test`.

## Task 2: dependency-order

`[nodes edges]` returns all distinct input nodes in topological order. At each
step choose the earliest node in the original nodes vector among all currently
ready nodes, including newly ready nodes. Example: nodes `[:a :b :c :d]`, edges
`[[:c :a]]`, result `[:b :c :a :d]`. Nodes may include nil, false, maps, and
vectors. Deduplicate edges. Unknown endpoints throw `ex-info` with
`:type :unknown-node`; self/disconnected/other cycles throw `:type :cycle`.
Handle empty input; preserve inputs; avoid quadratic scans on a 10,000-node
chain. Evaluator: 4 tests / 17 assertions. Test namespace:
`benchmark.dependency-order-test`.

## Task 3: project-events

`[initial events]` folds event vectors into a map. Require map initial state
or throw `:type :invalid-state`. `[:put k v]` stores any value; `[:delete k]`
removes a key, with absent keys a no-op; `[:increment k delta]` adds an integer
to an integer, treating only an absent key as zero. Present nil/false cannot
be incremented. Keys may be nil, false, or structured. Reject non-vector
events, wrong arities, unknown operations, and invalid increments using
`{:type :invalid-event :index i :event event}` for the first invalid event.
Support nil events and finite lazy sequences without mutation. The evaluator
includes 100,000 increments. Evaluator: 3 tests / 42 assertions. Test namespace:
`benchmark.event-projection-test`.

## Original Ornith Q4 run

8K context, thinking off, no web extension, temperature 0.2, top_p 0.9, seed 42,
600-second coding limit. Only stable-distinct-by was attempted, three times.
All three failed independent evaluation. They are not comparable to the later
thinking/web/32K setup. Elapsed time for an unsuccessful task is not a speed
score for a correct solution.

| Attempt | Seconds | Model calls | Tool calls | Independent exit | Success |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 19.14 | 6 | 6 | 1 | False |
| 2 | 38.18 | 11 | 7 | 1 | False |
| 3 | 17.74 | 5 | 4 | 1 | False |

## Thinking/web capability run

16K context, thinking high, DuckDuckGo via pi-web-access 0.43.0, temperature
0.6, top_p 0.95, paired seeds 42/43/44, and a 600-second coding limit. BF16
ran all nine attempts below; none passed the independent evaluator. Four of
nine sessions contain repeated `Stream ended without finish_reason` errors.
Some other attempts stopped without writing implementation/tests. Do not
interpret 0/9 as a clean capability score or as evidence about quantization.
Pi exiting zero did not guarantee a completed agent task.

Q4 and Flash-Next were skipped after preflight detected system swap activity,
even though their thinking and web tools worked. Those are missing coding
measurements, not failed coding answers. System-wide swap counters alone do
not identify model-caused memory pressure.

| Task | Repeat | Seconds | Model calls | Tool calls | Own assertions | Independent exit | Timing memory-valid |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| distinct | 1 | 152.76 | 11 | 10 | 0 | 1 | True |
| distinct | 2 | 102.86 | 5 | 9 | 0 | 1 | True |
| distinct | 3 | 99.01 | 6 | 5 | 0 | 1 | True |
| order | 1 | 72.50 | 8 | 12 | 0 | 1 | True |
| order | 2 | 316.29 | 14 | 9 | 0 | 1 | True |
| order | 3 | 64.39 | 5 | 8 | 0 | 1 | True |
| events | 1 | 105.91 | 7 | 7 | 0 | 1 | True |
| events | 2 | 266.60 | 13 | 12 | 0 | 1 | False |
| events | 3 | 54.90 | 4 | 7 | 0 | 1 | True |

Full attempt metrics and assistant error classifications are retained in
`results/previous-coding-attempts.json`; the remote source directories are
recorded there. This record intentionally excludes private session content
and hidden reasoning text.

## Monitored BF16 follow-up

The 32K stable-distinct-by diagnostic passed all 20 agent-written assertions
and all 14 independent assertions. Coding took 576.97 seconds. Autonomous
research follow-ups and final verification brought recorded Pi time to
912.81 seconds. A URL-guided branch was cancelled and excluded; the retained
agent chose its successful URL. The follow-up had supervisor reminders,
resumes, and a 300-second diagnostic timeout. It is not an isolated unassisted
success under the new protocol. See `RESULTS.md` for the detailed limitations.

## User-directed next coding phase

All configurations must use 32K in Pi and their server, with thinking enabled.
There is **no coding wall-clock cutoff**. Measure elapsed time, model/tool calls,
compaction, token usage, repairs, final response, and both test suites instead.
Each scored task starts in a fresh environment with no prior histories, cached
web pages, or resumed session. Memory limits and stop conditions remain active.
Run coding before the maximum-context scan. Do not silently lower Flash-Next's
context if its 32K resource preflight fails. No MTP-off comparison is requested;
Ornith NVFP4 uses MTP enabled in a separate vLLM experiment.
