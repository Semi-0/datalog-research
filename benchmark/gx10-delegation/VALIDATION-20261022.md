# Enhanced queue validation, 2026-10-22

Final user-selected roles: Flash plans, delegates, integrates and reviews; BF16 workers do bounded implementation, repair and assessment with Gortex/Clojure LSP. BF16 solo runs precede delegation.

Deployed runtime snapshot: `/home/lain/experiments/gx10-delegation-python-harness-4`.
Archive SHA-256: `d8d5a0e85f86413c2d0058a6e1f4369bc845a98a56f3b340661bb03bd890ec67`.
Baseline source commit: `2e2e26a6f6e5a1e4cb57ae4e69d10e569365f959`; this is a working-tree snapshot, not a claim that the new harness has been committed. deployment.json records both identifiers. Earlier staging snapshots are retained and are not scheduled.

On GX10, using Node and Clojure on PATH and JAVA_HOME=/home/lain/.local/opt/java21:

```sh
python3 -m unittest discover -s benchmark/gx10-delegation -p 'test_*.py'
python3 -m unittest discover -s benchmark/gx10-context -p 'test_*.py'
python3 -m unittest discover -s benchmark/ornith-nvfp4-mtp -p 'test_*.py'
```

Observed: delegation 39 tests, one skipped; core 81 passed; coding runner 10 passed. Total 129 passed and one skipped. The initially minimal SSH PATH lacked Node/Clojure; rerunning with the installed runtime paths passed. No code change was used to conceal those environment failures. Thirteen additional network-policy assertions passed for public hosts, private/reserved addresses, mixed public/private DNS answers, local names and denied ports.

Full Pi tool-flow evidence: `/home/lain/experiments/gx10-tools-flow-approved-zd1g2krl/`.
The actual Pi process used the final runtime invocation against a scripted mock inference endpoint. It completed seven requests, all at temperature 1.0, and successfully exercised:

- private Gortex workspace_info;
- clj_kondo;
- lens_diagnostics with source=lsp and an actual Clojure server;
- lsp_navigation documentSymbol;
- autonomous web_search through DuckDuckGo and direct fetch_content.

`attempt/artifacts/tooling-verification.json` says passed. Request bodies, raw Pi events, tool details, private config and hashes are retained there. The check's private Gortex daemon was stopped afterward. The inference endpoint was scripted: this validates tool integration and configuration, not model reasoning, benchmark correctness, concurrent performance or the OS sandbox.

This check found and corrected the --no-approve/LSP trust conflict. Production enhanced workers now use Pi's per-run --approve plus --no-extensions and explicit pinned extension paths. The temporary trust shim was removed. Flash remains lightweight and uses --no-approve.

Queue launch verified through the existing background launcher with Linger=yes:

- service: gx10-delegation-python-queue-2.service;
- invocation: bf637eaad6564e4ab557c701ee67b8c8;
- output: /home/lain/experiments/gx10-delegation-python-queue-2;
- observed checkpoint: waiting_for_benchmark, next=bf16-solo;
- previous queue 5 still running, PID 183285;
- no solo/pilot successor directories created at verification.

No competing model was loaded. After queue 5's durable completion, the three solo cells run under the existing host lock and RAM guard. Delegation remains gated by a real Bubblewrap probe; current uid-map permission refusal still requires the administrator setup documented in ENHANCED-QUEUE.md. No model-backed enhanced BF16 or sandboxed concurrent result is claimed yet.

Gortex impact, test selection, guards and contract checks were run. No guard rules were configured. Contract analysis warns about the existing broad runner call graph; the runtime changes are opt-in for enhanced solo cells, and the covering suites passed. Git-based change detection does not include these untracked benchmark directories, so validation also used explicit symbol targets and the transferred archive identity. Unrelated tracked working-tree changes were preserved.
