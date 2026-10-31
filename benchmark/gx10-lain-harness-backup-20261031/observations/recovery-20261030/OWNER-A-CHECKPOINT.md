# OWNER-A-CHECKPOINT (run 20261030, deadline checkpoint)

## Status: IN PROGRESS, no test-file edits made yet, root cause of remaining failure narrowed.

## Runs done (all cwd=drivers, flock .gate-jvm.lock, one JVM at a time)
1. Supervisor evidence (w23-current-test.log): Ran 3 tests, 26 assertions, 4 fail + 1 error; exit 124 (wall timeout AFTER full summary printed). Lock was free; no live JVM.
2. OwnerA run1: w23-ownerA-run1.log — identical 4 FAILs, then HUNG >4min inside close! (exit 124 at 300s).
3. Diagnostics (w23-ownerA-diag1.log + drivers/test/w23_diag*.clj scratch files, to be deleted):
   - d/rpc round trip against the fake bridge TIMES OUT with reply nil (~5s) → the fake bridge never delivers a reply frame in the driver's reader path.
   - Raw PipedInputStream/PipedOutputStream pair works (write→read OK, same and separate thread).
   - Replicated sink logic: request IS parsed (:GOT-REQ), reply IS written (:REPLIED), but the reader thread's queue never receives it (:queued nil). So: bytes written by the sink synchronously on the WRITER thread into a PipedOutputStream whose PipedInputStream is drained by the driver's daemon reader thread do not surface — the reply path is dead while a bare pipe test works. Difference under isolation still open (next step: instrument the actual reader thread and available()).

## What passes already (good evidence)
- Declaration loads from drivers cwd (production harness_session fix works): first test's cwd/declaration assertions PASS.
- assignments non-empty, prepare effect runs, :expected 7 derived, :harbor/context lands. PASS.
- Tests 2 and 3 assertions pass in the supervisor run (21 passes).

## Remaining failure
- Positive test stalls after :harbor/context: pi launch RPC into fake bridge never gets a reply → rpc waits its 180s bounded timeout → assessed never lands → close fails "Pi disposal did not verify child cleanup" (dispose rpc also unreplied). Fix must be in w23_fake_bridge.clj reply delivery (my file scope), e.g. decouple reply emission from the writer thread (responder thread or direct queue) — allowed, fake is mine to fix; production untouched.

## Files touched so far
- drivers/test/w23_diag.clj, w23_diag2.clj, w23_diag3.clj, w23_diag4.clj (SCRATCH, must delete before finish; suite count unaffected: run_w23 loads only w23_contract_test.clj).
- observations/recovery-20261030/w23-ownerA-run1.log, w23-ownerA-diag1.log.
- drivers/test/w23_contract_test.clj, w23_fake_bridge.clj, run_w23.clj: UNMODIFIED by me so far (owned by me).

## Next step (not started)
Replace synchronous sink reply write with an independent responder thread draining a queue (or writing replies without same-pipe coupling), rerun suite under flock, keep strict run_w23 semantics. No PRODUCTION-SEAM-REQUEST.md needed so far — no production change seen necessary.

## FINAL (same run, after deadline continuation)
- ROOT CAUSE of remaining failure: w23_fake_bridge.clj wrote reply JSON WITHOUT a trailing newline;
  the driver reader BufferedReader/readLine never returns for an unterminated line -> every rpc
  waited its full bounded timeout -> assessed never landed and dispose appeared unverified.
  Fix: reply bytes are now json + "\n" (fake only; production untouched).
- Test file: replaced two malformed artifact lookups (:assessed / :close are not artifacts keys)
  with the real shapes: :harbor/assessed fact carrying a non-empty :assessment map (harbor_extension.clj:139),
  and map? assertion moved onto the actual (hs/close!) result. No behavioral overrides added; live config unchanged.
- RESULT: run3 (w23-ownerA-run3.log) EXIT=0, {:test 3, :pass 26, :fail 0, :error 0}, :W23-TESTS-PASS, 20.9s.
- Scratch diag files deleted; drivers/test/ contains only suite files. No PRODUCTION-SEAM-REQUEST.md (none needed).
