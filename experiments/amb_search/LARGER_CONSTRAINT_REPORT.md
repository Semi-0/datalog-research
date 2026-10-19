# Indexed withdrawal on larger constraint graphs

The inverse premise index gains more on these larger graphs: elapsed time falls
22% on the original opaque Pythagorean question, 31% on its decomposed version,
and 36% on four queens. This is a reduction in propagation work for the same
search, rather than additional branch elimination.

Measured on 2026-10-19, Java 27, with one warmup per mode and three measured
samples in alternating order. Each pair starts from the same immutable network.
Namespace loading, network construction and oracle enumeration are outside the
timer; index construction is included. The shared network cache is disabled in
both modes. Activation counting and the cooperative budget check are enabled in
both modes, so compare paired results here rather than absolute timings from
earlier reports. No other benchmark JVM ran concurrently.

| Question | Choices / combinations | Initial cells / propagators | Ordinary median | Indexed median | Time reduction |
| --- | ---: | ---: | ---: | ---: | ---: |
| Opaque Pythagorean, domain 5 | 3 / 125 | 31 / 22 | 1,136 ms | 884 ms | 22.2% |
| Decomposed Pythagorean, domain 5 | 3 / 125 | 76 / 58 | 2,220 ms | 1,521 ms | 31.5% |
| Four queens | 4 / 256 | 178 / 139 | 7,264 ms | 4,618 ms | 36.4% |

| Question | Ordinary activations | Indexed activations | Activation reduction | Ordinary / indexed allocation |
| --- | ---: | ---: | ---: | ---: |
| Opaque Pythagorean | 12,223 | 8,065 | 34.0% | 1.415 / 1.085 GB |
| Decomposed Pythagorean | 31,686 | 19,780 | 37.6% | 3.272 / 2.233 GB |
| Four queens | 108,798 | 65,631 | 39.7% | 10.730 / 6.831 GB |

Allocation is cumulative thread allocation, not retained heap or peak memory.
Activation counts include temporary nested networks; topology counts describe
the initial outer graph only.

The decomposed fixture uses the existing scalar Pythagorean question. It keeps
the same three choices and solution `[3 4 5]` while expanding the arithmetic and
Boolean operations. This isolates a larger constraint graph without increasing
the Cartesian search space. Both versions perform 54 withdrawals. Direct
projections increase from 216 to 504, and avoided outer derivations from 162 to
450. Their search paths need not match each other; each fixture's two runners
must match exactly.

Queens uses one AMB column choice per row, pairwise column and diagonal checks,
and an explicit conjunction graph with the existing completion gate and
require/switch controls. Four queens returns `[2 4 1 3]` after 71 withdrawals.
The indexed runner performs 1,437 direct projections and avoids 1,366 outer
derivations, plus their nested propagation. It still takes seconds: active
replacements continue through ordinary layered evaluation, and this experiment
does not optimize temporary graph construction or slot reuse.

These runs stop at the first answer. The combination counts describe the full
domain, not how many complete assignments were visited. More constraints alone
do not guarantee a larger gain: benefit depends on how much downstream work
each withdrawn premise would otherwise trigger. A larger graph can also cost
more to index and project.

Five queens (five choices, 3,125 possible combinations) was also attempted in
a separate JVM with the same budget. The ordinary warmup stopped at 45,002 ms
after 651,008 activations. The indexed warmup found the oracle-valid answer
`[1 3 5 2 4]` in 31,909 ms with 449,045 activations and 360 withdrawals. Since
the ordinary run did not finish, this case has no repeated medians or exact
final-state parity check, and is not included in the speedup table. Its 46.76 GB
cumulative allocation also shows that substantial overhead remains.
Raw bounded result: [QUEENS5_BOUNDED_RESULTS.edn](QUEENS5_BOUNDED_RESULTS.edn).

## Correctness and reproduction

Every completed pair of warmups and measured runs checks exact equality of the final solution,
choice records, learned knowledge and repair state between the two runners.
Solutions are checked against independent exhaustive host oracles. Focused
tests also check unsatisfiable queens and decomposed Pythagorean networks, so
parity includes exhaustion rather than only finding an answer.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk clojure -M -m experiments.amb-search.constraint-benchmark 3 opaque-5 decomposed-5 queens-4
```

Run in an isolated JVM: the benchmark temporarily redefines activation
evaluation to count work and impose a 45-second / one-million-activation budget
per sample. Budget exhaustion is reported explicitly during warmup; an
incomplete measured sample fails the comparison.

Raw measurements: [LARGER_CONSTRAINT_RESULTS.edn](LARGER_CONSTRAINT_RESULTS.edn).
Production runner behavior and AMB search policy are unchanged.

Verification: `clojure -M:test propagators.experimental.runner.amb-constraint-test`
passed two tests with 14 assertions and zero failures/errors. Clj-kondo reported
zero warnings/errors for the two new experiment namespaces and the test
namespace; `git diff --check` passed. The full repository suite was not rerun.
