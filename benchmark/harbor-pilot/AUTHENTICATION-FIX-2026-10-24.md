# Harbor Pi authentication fix — verified on GX10

The fresh unscored Harbor pilot passed. Qwen3.6 NVFP4 generated **4,426 tokens** across 15 assistant messages, finished with a normal response and no assistant/provider errors, and received Harbor reward **1.0**. The unchanged independent verifier ran **4 assertions, 0 failures, 0 errors**. The reference/oracle trial also passed.

The original failure used `OPENAI_API_KEY=local-gx10-no-cloud` while the backend required `local-benchmark`. `validate.py` now derives the client key from the loaded backend's saved command. Before launching Pi, `pilot_checks.py` verifies the endpoint and served model, checks that the correct key succeeds, and checks that a deliberately invalid key receives 401. Actual live results were 200 and 401 respectively. Pi's existing `$OPENAI_API_KEY` interpolation then worked without modifying the installed Harbor package.

Success now requires a normal Pi transcript with generated tokens, a successful Harbor process exit, the independent reward and the expected four assertion counts. Authentication/provider errors, aborted or truncated responses, missing settlement, malformed transcripts and zero assertions cannot be hidden by exit zero, `agent_settled` or a reward alone. Correctness is recorded separately from execution.

Fourteen focused tests passed on both the Mac and GX10:

```sh
python3 -m unittest discover -s benchmark/harbor-pilot -p 'test_*.py' -v
```

The existing experiment lock, 200 ms RAM monitoring, model artifact checks and unloading/settlement gates were retained. The run retained 1,279 memory samples; minimum available RAM was 81,695,637,504 bytes against a 26,118,804,275-byte reserve. Swap-in/out deltas were zero. Cleanup verified no resident model, no old model processes, a closed inference endpoint and no owned Docker containers. The controller exited zero.

Repository evidence:

- [Final result](evidence/authfix-1/result.json)
- [Live authentication checks](evidence/authfix-1/authentication.json)
- [Normalized Pi execution](evidence/authfix-1/pi-execution.json)
- [Independent verifier output](evidence/authfix-1/verifier.log)
- [Cleanup, memory and preservation audit](evidence/authfix-1/verification-audit.json)
- [Deployment source hashes](evidence/authfix-1/deployment.json)

Full Harbor jobs, transcripts and lifecycle logs remain at `/home/lain/experiments/gx10-harbor-pi-authfix-1`. The deployed fix is frozen under `/home/lain/experiments/harbor-pilot-authfix-code-20261024-1`. The original failed trial at `/home/lain/experiments/gx10-harbor-pi-pilot-1` and its task/evaluator files were preserved; the recorded file hashes were checked before and after this run.

To run another independent pilot on GX10, use a fresh output directory:

```sh
python3 validate.py \
  --harness /home/lain/experiments/gx10-delegation-python-harness-20 \
  --previous /home/lain/experiments/gx10-qwen36-priority-1 \
  --task /home/lain/experiments/harbor-pilot-20261023 \
  --output /home/lain/experiments/gx10-harbor-pi-NEW
```

This verifies the standalone four-assertion Harbor pilot. The full LangGraph delegation workflow and original 14-assertion Distinct assessment remain incomplete. Ordering and Events were not started.
