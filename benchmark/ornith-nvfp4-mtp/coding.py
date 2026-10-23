#!/usr/bin/env python3
"""One isolated coding task against an already preloaded local server. Bounded Pi execution with inspected recovery."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time
from nontermination import infinite_test_input
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'gx10-context'))
from lifecycle import Failure, memory, save, identities
from outcomes import classify, apply_recovery_evidence
from pi_extensions import prepare_vigilant
from gortex_profile import prepare_gortex, verify_gortex, GRAPH_TOOLS
from supervision import ProgressSupervisor
from inference_metrics import InferenceProxy, metrics_endpoint

HERE = Path(__file__).resolve().parent
CONTRACTS = HERE.parent / "gx10-context"
PIN = json.loads((CONTRACTS / "config.json").read_text())["pi_phase"]["source_commit"]
RESERVE = 26118804275


def stop_process_tree(process):
    """Pi bash tools can create separate process groups; include descendants."""
    parents = {}
    for entry in Path("/proc").iterdir():
        if entry.name.isdigit():
            try:
                fields = dict(line.split(":", 1) for line in (entry / "status").read_text().splitlines() if ":" in line)
                parents[int(entry.name)] = int(fields["PPid"])
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                pass
    owned = {process.pid}
    while True:
        children = {pid for pid, parent in parents.items() if parent in owned}
        expanded = owned | children
        if expanded == owned:
            break
        owned = expanded
    groups = set()
    for pid in owned:
        try:
            group = os.getpgid(pid)
            if group != os.getpgrp():
                groups.add(group)
        except ProcessLookupError:
            pass
    for sig in [signal.SIGTERM, signal.SIGKILL]:
        for group in groups:
            try:
                os.killpg(group, sig)
            except ProcessLookupError:
                pass
        if sig == signal.SIGTERM:
            time.sleep(0.5)


def environment(root):
    home = Path.home()
    return {
        "HOME": str(home),
        "USER": home.name,
        "LOGNAME": home.name,
        "PATH": str(home / ".local/bin") + ":/usr/local/cuda/bin:/usr/bin:/bin",
        "JAVA_HOME": str(home / ".local/opt/java21"),
        "LANG": "C.UTF-8",
        "TMPDIR": str(root / "tmp"),
        "PI_CODING_AGENT_DIR": str(root / "agent"),
        "PI_CODING_AGENT_SESSION_DIR": str(root / "sessions"),
        "PI_WEB_ACCESS_CACHE_ROOT": str(root / "web-cache"),
        "PI_TELEMETRY": "0",
    }


def enforce_deadline(process, elapsed, limit, artifacts, active_command):
    """Stop only the owned attempt at its deadline; elapsed time proves no loop."""
    if elapsed < limit:
        return False
    save(artifacts / "deadline.json", {"outcome": "timed_out", "limit_s": limit,
         "elapsed_s": elapsed, "active_command": active_command, "processes": identities()})
    if process is not None and process.poll() is None:
        stop_process_tree(process)
    return True


def prepare(root, model, base_url, context=None):
    from model_profile import thinking_enabled
    root.mkdir(parents=True, exist_ok=False)
    for name in ["agent", "sessions", "web-cache", "tmp", "artifacts"]:
        (root / name).mkdir()
    provider = "local-benchmark"
    protocol = json.loads((HERE.parent / "gx10-context/config.json").read_text())
    if context is None:
        context = protocol["coding"]["context"]
    cell_protocol = {}
    cell_manifest = root.parent / 'manifest.json'
    if cell_manifest.is_file():
        cell_protocol = json.loads(cell_manifest.read_text())
    selected = next((item for item in cell_protocol.get('models', []) if item.get('source') == model), {})
    sampling = cell_protocol.get('sampling', protocol['sampling'])
    thinking = thinking_enabled(selected, sampling)
    thinking_level = 'high'
    if not thinking:
        thinking_level = 'off'
    model_config = {
        "id": model, "name": model, "reasoning": thinking, "input": ["text"],
        "contextWindow": context, "maxTokens": protocol["context"]["pi_max_output_tokens"],
        "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
        "samplingParams": {"temperature": sampling["temperature"], "top_p": sampling["top_p"], "seed": protocol["seeds"][0], "reasoning_effort": protocol["pi_phase"]["thinking_level"]},
        "compat": {"maxTokensField": "max_tokens", "supportsStore": False, "supportsReasoningEffort": thinking, "supportsFinishReason": True},
    }
    effort_map = selected.get('thinking_level_map', {})
    if thinking and effort_map:
        model_config['thinkingLevelMap'] = effort_map
        requested = protocol['pi_phase']['thinking_level']
        model_config['samplingParams']['reasoning_effort'] = effort_map.get(requested, requested)
    if not thinking:
        model_config['samplingParams'].pop('reasoning_effort')
    save(root / 'artifacts/model-capabilities.json', {'model': model, 'thinking_required': thinking,
         'supports_thinking': selected.get('supports_thinking', True)})
    save(root / "agent/models.json", {"providers": {provider: {
        "baseUrl": base_url, "api": "openai-completions", "apiKey": "local-benchmark",
        "models": [model_config],
    }}})
    save(root / "agent/settings.json", {
        "defaultProvider": provider, "defaultModel": model, "defaultThinkingLevel": thinking_level,
        "compaction": {"enabled": True, "reserveTokens": protocol["context"]["pi_compaction_reserve_tokens"],
                       "keepRecentTokens": protocol["context"]["pi_keep_recent_tokens"]},
        "retry": protocol["pi_retry"],
        "httpIdleTimeoutMs": protocol['runtime_phase']['socket_idle_timeout_seconds'] * 1000,
        "cacheWarming": "off",
        "extensions": [], "packages": [],
    })
    save(root / "agent/benchmark-policy.json", protocol["coding"])
    save(root / "agent/web-search.json", {
        "provider": "duckduckgo", "workflow": "none", "allowBrowserCookies": False,
        "webSearch": {"enabled": True, "allowedProviders": ["duckduckgo"]},
        "searchRouting": {"providers": ["duckduckgo"], "useCurrentModel": False, "fallbackOn": ["network"]},
        "fetchRouting": {"providers": ["http"], "allowRemoteHostedProviders": False},
        "fetch": {"defaultMode": "readable", "allowedModes": ["readable", "raw"]},
        "tools": {"webSearch": {"enabled": True}, "fetchContent": {"enabled": True},
                  "getSearchContent": {"enabled": True}, "sourceCheck": {"enabled": False}},
    })
    return provider


def run(args):
    root = args.output.resolve()
    existed = root.exists()
    try:
        return execute_attempt(args)
    except BaseException as error:
        if not existed and root.exists() and not (root / 'result.json').exists():
            outcome = getattr(error, 'outcome', 'runtime_error')
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                outcome = 'interrupted'
            save(root / 'result.json', {'outcome': outcome, 'failure_kind': 'attempt_preparation',
                 'error': repr(error), 'task': args.task, 'model': args.model,
                 'correct': None, 'workflow_pass': False, 'protocol_pass': False,
                 'timing_valid': False, 'elapsed_censored': True})
        raise


def protocol_commit():
    deployment = HERE.parent.parent / 'deployment.json'
    if deployment.is_file():
        return json.loads(deployment.read_text())['source_commit']
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=HERE.parent.parent, text=True).strip()


def agent_command(root, args, provider, prompt, extensions, enhanced=False):
    """Build the exact standalone Pi invocation from the effective tool policy."""
    policy = json.loads((root / 'agent/benchmark-policy.json').read_text())
    tools = list(dict.fromkeys(policy['allowed_tools'] + GRAPH_TOOLS))
    trust = '--no-approve'
    if enhanced:
        trust = '--approve'
    from model_profile import prepared_thinking
    thinking_level = 'high'
    if not prepared_thinking(root):
        thinking_level = 'off'
    command = ['pi', '--mode', 'json', '--provider', provider, '--model', args.model,
               '--thinking', thinking_level, '--no-extensions']
    for extension in extensions:
        command.extend(['--extension', str(extension)])
    command.extend(['--no-mcp', '--no-skills', '--no-prompt-templates', '--no-context-files',
                    trust, '--offline', '--tools', ','.join(tools),
                    '--session-dir', str(root / 'sessions'), prompt])
    return command


def execute_attempt(args):
    from model_profile import prepared_thinking
    root = args.output.resolve()
    enhanced = os.environ.get('GX10_TOOL_PROFILE') == 'enhanced'
    admission = None
    if enhanced:
        sys.path.insert(0, str(HERE.parent / 'gx10-delegation'))
        from tooling import configure, require_ready, verify as verify_tooling
        admission = lambda: require_ready(root)
    provider = prepare(root, args.model, args.base_url, args.context)
    artifacts = root / "artifacts"
    endpoint, backend_kind = metrics_endpoint(args.base_url, root.parent)
    inference = InferenceProxy(args.base_url, endpoint, backend_kind, artifacts,
        budget=json.loads((HERE.parent / 'gx10-context/config.json').read_text())['coding']['supervision'],
        admission=admission)
    models_path = root / 'agent/models.json'
    models_config = json.loads(models_path.read_text())
    models_config['providers'][provider]['baseUrl'] = inference.base_url
    save(models_path, models_config)
    env = environment(root)
    task = next(t for t in json.loads((CONTRACTS / "tasks.json").read_text()) if t["id"] == args.task)
    manifest = json.loads((CONTRACTS / "evaluator-manifest.json").read_text())
    evaluator = CONTRACTS / "fixtures" / task["acceptance"]
    expected = manifest[task["acceptance"]]
    assert hashlib.sha256(evaluator.read_bytes()).hexdigest() == expected
    checkout = root / "checkout"
    subprocess.run(["git", "clone", "--shared", "--no-checkout", str(args.source), str(checkout)], env=env, check=True)
    subprocess.run(["git", "checkout", "--detach", PIN], cwd=checkout, env=env, check=True)
    source = checkout / task["path"]
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(f'(ns {task["namespace"]})\n\n(defn {task["function"]} [{task["args"]}]\n  (throw (ex-info "Implement this function" {{}})))\n')
    test = checkout / task["test_path"]
    test.parent.mkdir(parents=True, exist_ok=True)
    test.write_text(f'(ns {task["test_namespace"]}\n (:require [clojure.test :refer [deftest is]]\n           [{task["namespace"]} :refer [{task["function"]}]]))\n')
    subprocess.run(["git", "add", "-N", task["path"], task["test_path"]], cwd=checkout, env=env, check=True)
    prompt = (CONTRACTS / "prompts" / (args.task + ".txt")).read_text().replace(" You have ten minutes.", "")
    protocol = json.loads((HERE.parent / "gx10-context/config.json").read_text())
    limits = protocol["coding"]
    prompt += "\n" + (HERE.parent / "gx10-context/prompts/agent-workflow.txt").read_text()
    vigilant = prepare_vigilant(root, protocol)
    gortex = prepare_gortex(root, checkout, env)
    extra_extensions = []
    if enhanced:
        protocol['sampling']['temperature'] = 1.0
        env['GX10_TOOL_ROLE'] = 'implementor'
        extra_extensions = configure(root, checkout, env, protocol)
        extra_extensions = [p for p in extra_extensions if p not in [str(gortex), str(args.web_extension)]]
        extra_extensions += [str(HERE.parent / 'gx10-delegation/tooling.mjs'),
                             str(HERE.parent / 'gx10-delegation/observe.mjs')]
        prompt += '\nUse your private Gortex graph, clj_kondo, lens_diagnostics with source=lsp on a changed .clj file, and lsp_navigation documentSymbol before final tests. Repair concrete errors and report measured assertions.'
        if protocol['coding'].get('structural_editing') is not None:
            from structural_editing import GUIDANCE
            prompt += '\n' + GUIDANCE
        save(artifacts / 'enhanced-protocol.json', protocol)
    extensions = [args.web_extension, args.web_guard, vigilant, gortex,
                  HERE.parent / 'gx10-context/pi-runtime.mjs'] + extra_extensions
    command = agent_command(root, args, provider, prompt, extensions, enhanced)
    assert not any(flag in command for flag in ["--session", "--continue", "--resume", "--fork"])
    assert not list((root / "sessions").iterdir())
    save(artifacts / "command.json", command)
    save(artifacts / "environment.json", env)
    (artifacts / "prompt.txt").write_text(prompt)
    save(artifacts / "isolation.json", {
        "fresh_directories": True, "prior_conversation_entries": 0,
        "source_commit": PIN, "evaluator_sha256": expected,
        "protocol_git_commit": protocol_commit(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "config_hashes": {str(p.relative_to(root / "agent")): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in (root / "agent").rglob("*") if p.is_file()},
        "extension_sha256": hashlib.sha256(args.web_extension.read_bytes()).hexdigest(),
        "guard_sha256": hashlib.sha256(args.web_guard.read_bytes()).hexdigest(),
        "wall_clock_limit_seconds": limits["wall_limit_seconds"],
        "runtime_extension_sha256": hashlib.sha256((HERE.parent / "gx10-context/pi-runtime.mjs").read_bytes()).hexdigest(),
    })
    save(artifacts / "runtime-versions.json", {name: subprocess.run(cmd, env=env, text=True, capture_output=True).stdout.strip()
        for name, cmd in {"pi": ["pi", "--version"], "java": ["java", "--version"],
                          "clojure": ["clojure", "-Sdescribe"], "node": ["node", "--version"]}.items()})
    messages = []
    compaction_started = None
    compaction_seconds = 0
    compaction_count = 0
    stream_failures = []
    recoveries = []
    agent_end = False
    last_progress = [time.monotonic()]
    last_warning = [0]
    active_command = [None]
    tool_names = []
    web = []
    session_id = None
    first = None
    started = time.monotonic()
    done = threading.Event()
    stopped_for_memory = threading.Event()
    stopped_for_nontermination = threading.Event()
    supervision_stopped = threading.Event()
    supervision_decision = [None]
    progress = ProgressSupervisor(limits['supervision'], started)
    evaluating = threading.Event()
    active_test = [None]
    current = [None]
    samples = []

    def watch():
        with (artifacts / "memory.jsonl").open("w") as output:
            while not done.is_set():
                sample = {"elapsed_s": time.monotonic() - started, **memory()}
                samples.append(sample)
                if enhanced and (artifacts / 'startup-failure.json').exists():
                    process = current[0]
                    if process is not None and process.poll() is None:
                        stop_process_tree(process)
                    return
                output.write(json.dumps(sample) + "\n")
                output.flush()
                if not evaluating.is_set() and not supervision_stopped.is_set():
                    decision = progress.check(time.monotonic(), *inference.totals())
                    if decision is not None:
                        decision['elapsed_s'] = sample['elapsed_s']
                        save(artifacts / ('supervision-' + decision['action'] + '.json'), decision)
                        if decision['action'] == 'steer':
                            save(artifacts / 'supervision-steer-request.json', {'message':
                                 'Progress supervision: no new qualifying evidence or improved test result for fifteen minutes. Stop repeating analysis or compaction. Inspect the current files, run the focused finite tests, repair concrete errors, and report the measured result. Rewriting alone is not verified progress.'})
                        if decision['action'] == 'stop':
                            supervision_decision[0] = decision
                            supervision_stopped.set()
                            process = current[0]
                            if process is not None and process.poll() is None:
                                stop_process_tree(process)
                if time.monotonic() - last_progress[0] >= 600 and time.monotonic() - last_warning[0] >= 600:
                    last_warning[0] = time.monotonic()
                    save(artifacts / ("stall-" + str(int(time.time())) + ".json"), {
                        "warning": "No model/tool progress for ten minutes; termination is unknown",
                        "processes": identities(), "active_command": active_command[0], "memory": sample})
                running_test = active_test[0]
                if not stopped_for_nontermination.is_set() and running_test is not None and time.monotonic() - running_test > 60:
                    if infinite_test_input(test.read_text(), task["function"]):
                        stopped_for_nontermination.set()
                        save(artifacts / "termination-decision.json", {
                            "classification": "nonterminating", "epoch": time.time(),
                            "reason": "Direct unbounded sequence passed to a finite-collection benchmark function in agent tests while a test command remained active for at least 60 seconds.",
                            "detector": "direct-infinite-input-v1", "coding_deadline": limits["wall_limit_seconds"],
                            "test_sha256": hashlib.sha256(test.read_bytes()).hexdigest(),
                            "test_source": test.read_text(), "active_command": active_command[0],
                            "processes": identities()})
                        process = current[0]
                        if process is not None and process.poll() is None:
                            stop_process_tree(process)
                        active_test[0] = None
                if sample["available"] < sample["reserve"]:
                    stopped_for_memory.set()
                    process = current[0]
                    if process is not None and process.poll() is None:
                        stop_process_tree(process)
                    subprocess.run(["systemctl", "--user", "stop", args.server_unit], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                    if process is not None and process.poll() is None:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    return
                done.wait(0.2)

    if memory()["available"] < memory()["reserve"]:
        raise RuntimeError("Available RAM is below reserve before coding")
    watcher = threading.Thread(target=watch)
    watcher.start()
    try:
        if stopped_for_memory.is_set():
            raise RuntimeError("Memory guard stopped preparation")
        with (artifacts / "events.jsonl").open("w") as events, (artifacts / "stderr.txt").open("w") as errors:
            process = subprocess.Popen(command, cwd=checkout, env=env, stdout=subprocess.PIPE,
                                       stderr=errors, text=True, start_new_session=True)
            current[0] = process
            save(artifacts / "process.json", {"pid": process.pid, "started_epoch": time.time()})
            for line in process.stdout:
                event = json.loads(line)
                last_progress[0] = time.monotonic()
                if event.get("type") == "auto_retry_end":
                    recoveries.append(event)
                    if event.get("success"):
                        for failure in stream_failures:
                            if failure.get("stop_reason") == "error":
                                failure["recovered"] = True
                if event.get("type") == "agent_end":
                    agent_end = True
                if event.get("type") in ["auto_compaction_end", "compaction_end", "auto_retry_end"] and (event.get("errorMessage") or event.get("error") or event.get("finalError")):
                    stream_failures.append({"type": event.get("type"),
                        "timestamp": event.get("message", {}).get("timestamp"),
                        "stop_reason": event.get("message", {}).get("stopReason"),
                        "error": event.get("errorMessage") or event.get("error") or event.get("finalError") or event.get("message", {}).get("errorMessage")})
                if event.get("type") == "message_end" and event.get("message", {}).get("stopReason") in ["length", "aborted", "error"]:
                    stream_failures.append({"type": event.get("type"),
                        "timestamp": event.get("message", {}).get("timestamp"),
                        "stop_reason": event.get("message", {}).get("stopReason"),
                        "error": event.get("errorMessage") or event.get("error") or event.get("finalError") or event.get("message", {}).get("errorMessage")})
                elapsed = time.monotonic() - started
                if event.get('type') in ['compaction_start', 'auto_compaction_start']:
                    compaction_started = elapsed
                if event.get('type') in ['compaction_end', 'auto_compaction_end'] and compaction_started is not None:
                    compaction_seconds += elapsed - compaction_started
                    compaction_count += 1
                    compaction_started = None
                events.write(json.dumps({"elapsed_s": elapsed, "event": event}) + "\n")
                events.flush()
                if event.get("type") == "session":
                    session_id = event.get("id")
                if first is None and event.get("type") == "message_update":
                    first = elapsed
                if event.get("type") == "message_end" and event.get("message", {}).get("role") == "assistant":
                    messages.append(event["message"])
                if event.get("type") == "tool_execution_start":
                    tool_names.append(event.get("toolName"))
                    command_text = event.get("args", {}).get("command", "")
                    if event.get("toolName") == "bash" and "clojure" in command_text and task["test_namespace"] in command_text:
                        active_test[0] = time.monotonic()
                        active_command[0] = command_text
                if event.get('type') == 'tool_execution_end':
                    result_text = '\n'.join(block.get('text', '') for block in event.get('result', {}).get('content', [])
                                            if block.get('type') == 'text')
                    reason = progress.observe_tool(event.get('toolName'), result_text, event.get('isError', False), time.monotonic())
                    if reason is not None:
                        with (artifacts / 'progress.jsonl').open('a') as log:
                            log.write(json.dumps({'elapsed_s': elapsed, 'reason': reason}) + '\n')
                    if event.get('toolName') == 'bash':
                        active_test[0] = None
                        active_command[0] = None
                if event.get("type") == "tool_execution_end" and not event.get("isError"):
                    if event.get("toolName") in ["web_search", "fetch_content"]:
                        web.append(event["toolName"])
                save(root / "status.json", {"state": "coding", "elapsed_s": elapsed,
                    "last_event": event.get("type"), "model_calls": len(messages), "tool_calls": len(tool_names)})
            process.stdout.close()
            process.wait()
            evaluating.set()
        if enhanced and (artifacts / 'startup-failure.json').exists():
            failure = json.loads((artifacts / 'startup-failure.json').read_text())
            raise Failure('setup_declined', failure['error'])
        wall = time.monotonic() - started
        result = {"model": args.model, "task": args.task, "context_tokens": args.context,
                  "session_id": session_id, "agent_end_observed": agent_end,
                  "stream_failures": stream_failures, "nonterminating": stopped_for_nontermination.is_set(),
                  "coding_wall_limit_seconds": limits["wall_limit_seconds"], "coding_wall_s": wall,
                  'compaction_wall_s': compaction_seconds, 'completed_compactions': compaction_count,
                  'compaction_unfinished': compaction_started is not None,
                  "timed_out": False, "supervision_stop": supervision_decision[0],
                  "first_response_s": first, "pi_exit": process.returncode,
                  "resource_stopped": stopped_for_memory.is_set(),
                  "model_calls": len(messages), "tool_calls": len(tool_names), "tool_names": tool_names,
                  "thinking_required": prepared_thinking(root),
                  "thinking_observed": any(c.get("thinking") for m in messages for c in m.get("content", []) if c.get("type") == "thinking"),
                  "search_succeeded": "web_search" in web, "fetch_succeeded": "fetch_content" in web,
                  "assistant_errors": [m.get("errorMessage") for m in messages if m.get("stopReason") == "error"],
                  "agent_final_response": bool(messages) and messages[-1].get("stopReason") == "stop",
                  "usage": {k: sum(m.get("usage", {}).get(k, 0) for m in messages) for k in ["input", "output", "cacheRead", "cacheWrite", "totalTokens"]}}
        verification_started = time.monotonic()
        evaluating.set()
        result['workflow_diagnostics'] = []
        if not stopped_for_memory.is_set():
            try:
                verify_gortex(root)
                if enhanced:
                    verify_tooling(root)
            except Failure as error:
                result['workflow_diagnostics'].append({'check': 'post_run_tooling', 'error': str(error),
                                                       'classification': error.outcome})
                save(artifacts / 'workflow-diagnostics.json', result['workflow_diagnostics'])
        if not stopped_for_memory.is_set():
            for name, cmd in [("own_tests", ["clojure", "-M:test", task["test_namespace"]]),
                              ("acceptance", ["clojure", "-M", str(evaluator)])]:
                if stopped_for_memory.is_set():
                    break
                if name == "own_tests" and stopped_for_nontermination.is_set():
                    (artifacts / "own_tests.txt").write_text("Not rerun: nonterminating agent-written test input.\n")
                    continue
                save(root / "status.json", {"state": "evaluation", "operation": name, "command": cmd})
                evaluation_started = time.monotonic()
                with (artifacts / (name + ".txt")).open("w") as output:
                    check = subprocess.Popen(cmd, cwd=checkout, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                    current[0] = check
                    if name == "own_tests":
                        active_test[0] = time.monotonic()
                        active_command[0] = cmd
                    last_progress[0] = time.monotonic()
                    try:
                        result[name + "_exit"] = check.wait(timeout=limits["evaluation_timeout_seconds"])
                        result[name + '_completed'] = result[name + '_exit'] >= 0
                    except subprocess.TimeoutExpired:
                        stop_process_tree(check)
                        check.wait(timeout=10)
                        result[name + "_exit"] = 124
                        result[name + '_completed'] = False
                        result.setdefault("evaluation_timeouts", []).append(name)
                        save(artifacts / (name + "-timeout.json"), {"outcome": "timed_out", "command": cmd,
                             "limit_s": limits["evaluation_timeout_seconds"], "elapsed_s": time.monotonic() - evaluation_started})
                    active_test[0] = None
                result[name + "_wall_s"] = time.monotonic() - evaluation_started
        assert hashlib.sha256(evaluator.read_bytes()).hexdigest() == expected
        for name, cmd in [("changes.patch", ["git", "diff", "--binary"]), ("status.txt", ["git", "status", "--porcelain", "--untracked-files=all"])]:
            (artifacts / name).write_text(subprocess.check_output(cmd, cwd=checkout, env=env, text=True))
        result["confined"] = all(line[3:] in {task["path"], task["test_path"]} for line in (artifacts / "status.txt").read_text().splitlines())
        result["minimum_available_bytes"] = min(s["available"] for s in samples)
        result["swap_in_pages_delta"] = samples[-1]["swap_in"] - samples[0]["swap_in"]
        result["swap_out_pages_delta"] = samples[-1]["swap_out"] - samples[0]["swap_out"]
        own_output = ""
        acceptance_output = ""
        if (artifacts / "own_tests.txt").exists():
            own_output = (artifacts / "own_tests.txt").read_text()
        if (artifacts / "acceptance.txt").exists():
            acceptance_output = (artifacts / "acceptance.txt").read_text()
        result["timed_out"] = bool(result.get("evaluation_timeouts"))
        result['inference'] = inference.summary()
        save(artifacts / 'inference-summary.json', result['inference'])
        result["elapsed_censored"] = result["timed_out"]
        result["recovery_events"] = recoveries
        apply_recovery_evidence(result, artifacts / "pi-runtime.jsonl")
        classify(result, own_output, acceptance_output)
        result["verification_wall_s"] = time.monotonic() - verification_started
        result["total_elapsed_s"] = time.monotonic() - started
        save(root / "result.json", result)
        save(root / "status.json", {"state": "complete", "result": result})
        print(json.dumps(result, indent=2), flush=True)
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = "interrupted"
        if supervision_stopped.is_set():
            outcome = supervision_decision[0]['outcome']
        if stopped_for_memory.is_set():
            outcome = "resource_stopped"
        # An exception before evaluation does not establish incorrectness.
        correct = None
        save(root / "result.json", {"outcome": outcome, "task": args.task, "model": args.model,
             "error": repr(error), "correct": correct, "workflow_pass": False, "protocol_pass": False,
             "startup_failure": (artifacts / 'startup-failure.json').is_file(),
             "timing_valid": False, "elapsed_censored": True, "coding_wall_s": time.monotonic() - started})
        raise
    finally:
        active = current[0]
        if active is not None and active.poll() is None:
            stop_process_tree(active)
        done.set()
        watcher.join()
        inference.close()
        for name, cmd in [("changes.patch", ["git", "diff", "--binary"]),
                          ("status.txt", ["git", "status", "--porcelain", "--untracked-files=all"])]:
            captured = subprocess.run(cmd, cwd=checkout, env=env, text=True, capture_output=True)
            (artifacts / name).write_text(captured.stdout)



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", type=Path, default=Path.home() / "projects/datalog-research")
    parser.add_argument("--task", choices=["distinct", "order", "events"], default="distinct")
    parser.add_argument("--context", type=int, default=json.loads((CONTRACTS / 'config.json').read_text())['coding']['context'])
    parser.add_argument("--model", default="ornith-nvfp4-mtp")
    parser.add_argument("--base-url", default="http://127.0.0.1:8001/v1")
    parser.add_argument("--server-unit", default="ornith-nvfp4-server")
    previous = Path.home() / "experiments/pi-gx10-capability-20261021"
    parser.add_argument("--web-extension", type=Path, default=previous / "web-packages/node_modules/pi-web-access/dist/index.js")
    parser.add_argument("--web-guard", type=Path, default=previous / "benchmark-web-policy.ts")
    arguments = parser.parse_args()
    def interrupted(signum, frame):
        raise KeyboardInterrupt("Signal " + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        run(arguments)
    except Exception as error:
        if arguments.output.exists():
            save(arguments.output / "failure.json", {"error": repr(error)})
        raise
