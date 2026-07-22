# Local Qwen Lain Tail-Recursive Fibonacci Experiment

## Setup

- Model: `qwen3.5:9b`
- Ollama model blob: `sha256-dec52a44569a2a25341c4e4d3fee25846eed4f6f0b936278e3a3c900bb99d37c`
- Sampling: temperature `0.7`, seeds `101`, `202`, `303`, `404`, `505`
- Maximum generated tokens per attempt: `1200`
- Thinking output: disabled
- Prompt: [`prompt.txt`](prompt.txt)

The prompt describes the available Lain building blocks and states that an
ordinary named `def-net` can call itself in a lazy `when`. It contains no
Fibonacci implementation and no recursive code example.

Before grading model output, the validator was calibrated against a private
known-good tail-recursive Lain program. The real block runtime loaded it and
reported `fib-result` as `55` with no runtime errors. That reference source was
not included in the model prompt.

## Grading

An attempt passes only if all of these checks succeed:

1. The source parses as Lain forms.
2. It does not use `recur`, `def-recursive`, `loop`, `if`, `cond`, or `fn`.
3. A named `def-net` has exactly one self-call, in tail position under `when`.
4. The actual Lain block runtime loads the file successfully.
5. The named `fib-result` cell has strongest value `55` and no runtime errors.

## Results

| Attempt | Seed | Parse | Tail recursion | Runtime result | Pass |
| --- | ---: | --- | --- | --- | --- |
| 1 | 101 | Failed: unmatched `]` | Not checked | Not loaded | No |
| 2 | 202 | Failed: unmatched `]` | Not checked | Not loaded | No |
| 3 | 303 | Failed: invalid leading character | Not checked | Not loaded | No |
| 4 | 404 | Passed | None; also contains forbidden `if` | `fib-result` absent | No |
| 5 | 505 | Failed: EOF while reading | Not checked | Not loaded | No |

Success rate: **0/5 (0%)**.

The model understood parts of the requested intent in prose, especially the
need for accumulator state and lazy branching, but did not reliably constrain
itself to the supplied language. It invented host-language forms, malformed
bindings, unsupported operators, placeholder code, and long self-correction
comments. On this prompt alone, this local model cannot yet independently
compose a working tail-recursive Lain program.

## Reproduction

Run the deterministic checker over the preserved raw generations:

```sh
clojure -M experiments/ollama-lain-tail-fib/validate.clj \
  experiments/ollama-lain-tail-fib/attempt-{1..5}.lain
```
