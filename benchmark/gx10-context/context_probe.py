#!/usr/bin/env python3
"""Exercise a preloaded context with a calibrated synthetic retrieval request."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import re
import subprocess
import threading
import time
import urllib.request
from lifecycle import save, identities


def mem():
    fields = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
    counters = dict(line.split() for line in Path("/proc/vmstat").read_text().splitlines())
    total = int(fields["MemTotal"].split()[0]) * 1024
    return {"available": int(fields["MemAvailable"].split()[0]) * 1024,
            "reserve": max(16 * 1024**3, total // 5),
            "swap_in": int(counters["pswpin"]), "swap_out": int(counters["pswpout"])}


def fixture(tokenizer, target, seed):
    """Choose complete unique records; query values spread across the document."""
    rng = random.Random(seed)
    records = [(f"key_{i:06d}_{rng.getrandbits(48):012x}", f"value_{rng.getrandbits(64):016x}")
               for i in range(target // 8 + 64)]

    def render(count):
        selected = [records[(count - 1) * i // 15] for i in range(16)]
        text = "Retrieve the exact values for the requested keys from these records. Return only a JSON object mapping each requested key to its value.\nRECORDS\n"
        text += "\n".join(key + " = " + value for key, value in records[:count])
        text += "\nEND RECORDS\nREQUESTED KEYS\n" + "\n".join(key for key, _ in selected)
        return text, dict(selected)

    lower = 16
    upper = len(records)
    best = None
    while lower <= upper:
        count = (lower + upper) // 2
        text, expected = render(count)
        tokens = len(tokenizer.encode(text, add_special_tokens=False).ids)
        if tokens <= target - 128:
            best = (text, expected, tokens)
            lower = count + 1
        else:
            upper = count - 1
    if best is None:
        raise ValueError("Input budget cannot hold the retrieval fixture")
    return best


def answer_mapping(text):
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text).strip()
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def probe(args):
    from tokenizers import Tokenizer

    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    target = args.context - 8192 - 2048
    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    prompt, expected, encoded = fixture(tokenizer, target, args.seed)
    (root / "prompt.txt").write_text(prompt)
    (root / "expected.json").write_text(json.dumps(expected, indent=2))
    model_options = {"num_ctx": args.context, "num_predict": 8192, "temperature": 0.6,
        "top_p": 0.95, "top_k": 20, "min_p": 0, "repeat_penalty": 1,
        "presence_penalty": 0, "seed": args.seed}
    if args.backend == "ollama":
        url = "http://127.0.0.1:11434/api/chat"
        body = {"model": args.model, "messages": [{"role": "user", "content": prompt}],
                "think": True, "stream": True, "keep_alive": "30m", "options": model_options}
    else:
        url = "http://127.0.0.1:8001/v1/chat/completions"
        body = {"model": args.model, "messages": [{"role": "user", "content": prompt}],
                "reasoning_effort": "high", "stream": True, "stream_options": {"include_usage": True},
                "max_tokens": 8192, "temperature": 0.6, "top_p": 0.95, "seed": args.seed}
    (root / "request.json").write_text(json.dumps(body))
    baseline = mem()
    if baseline["available"] < baseline["reserve"]:
        raise RuntimeError("RAM is below reserve before context request")
    done = threading.Event()
    breached = threading.Event()
    samples = []
    started = time.monotonic()
    last_progress = [started]
    last_warning = [0]

    def watch():
        with (root / "memory.jsonl").open("w") as log:
            while not done.is_set():
                sample = {"elapsed_s": time.monotonic() - started, **mem()}
                samples.append(sample)
                if time.monotonic() - last_progress[0] >= 600 and time.monotonic() - last_warning[0] >= 600:
                    last_warning[0] = time.monotonic()
                    save(root / ("stall-" + str(int(time.time())) + ".json"), {
                        "warning": "No context stream progress for ten minutes; termination unknown",
                        "processes": identities(), "memory": sample})
                log.write(json.dumps(sample) + "\n")
                log.flush()
                if sample["available"] < sample["reserve"]:
                    breached.set()
                    subprocess.run(["systemctl", "--user", "stop", args.server_unit], timeout=15, check=False)
                    return
                done.wait(0.2)

    watcher = threading.Thread(target=watch)
    watcher.start()
    first = None
    first_answer = None
    response_text = ""
    reasoning = ""
    native = {}
    usage = {}
    finish = None
    stream_terminal = False
    failure = None
    try:
        request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
            "Content-Type": "application/json", "Authorization": "Bearer local-benchmark"})
        with urllib.request.urlopen(request, timeout=1800) as response, (root / "response.jsonl").open("w") as log:
            for line in response:
                line = line.decode().strip()
                if not line:
                    continue
                if line.startswith("data: "):
                    line = line[6:]
                if line == "[DONE]":
                    stream_terminal = True
                    break
                value = json.loads(line)
                last_progress[0] = time.monotonic()
                elapsed = time.monotonic() - started
                log.write(json.dumps({"elapsed_s": elapsed, "response": value}) + "\n")
                log.flush()
                if args.backend == "ollama":
                    message = value.get("message", {})
                    content = message.get("content", "")
                    thought = message.get("thinking", "")
                    if value.get("done"):
                        stream_terminal = True
                        native = value
                        usage = {"prompt_tokens": value.get("prompt_eval_count"), "completion_tokens": value.get("eval_count")}
                        finish = value.get("done_reason")
                else:
                    content = ""
                    thought = ""
                    for choice in value.get("choices", []):
                        delta = choice.get("delta", {})
                        content += delta.get("content") or ""
                        thought += delta.get("reasoning_content") or delta.get("reasoning") or ""
                        if choice.get("finish_reason") is not None:
                            finish = choice["finish_reason"]
                    if value.get("usage"):
                        usage = value["usage"]
                if first is None and (content or thought):
                    first = elapsed
                if first_answer is None and content:
                    first_answer = elapsed
                response_text += content
                reasoning += thought
        actual = answer_mapping(response_text)
        correct = actual == expected
    except BaseException as error:
        failure = repr(error)
        correct = False
    finally:
        done.set()
        watcher.join()
    observed = usage.get("prompt_tokens")
    calibrated = isinstance(observed, int) and target - 512 <= observed <= target
    result = {"model": args.model, "backend": args.backend, "context_tokens": args.context,
        "seed": args.seed, "target_prompt_tokens": target, "encoded_text_tokens": encoded,
        "observed_prompt_tokens": observed, "input_calibrated": calibrated,
        "input_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
        "wall_s": time.monotonic() - started, "first_token_s": first,
        "first_answer_s": first_answer, "thinking_observed": bool(reasoning),
        "stream_terminal": stream_terminal, "finish_reason": finish, "usage": usage, "native": native,
        "retrieval_correct": correct, "failure": failure, "resource_stopped": breached.is_set(),
        "minimum_available_bytes": min(s["available"] for s in samples),
        "swap_in_pages_delta": samples[-1]["swap_in"] - baseline["swap_in"],
        "swap_out_pages_delta": samples[-1]["swap_out"] - baseline["swap_out"]}
    result["timing_valid"] = not breached.is_set() and result["swap_in_pages_delta"] == 0 and result["swap_out_pages_delta"] == 0
    result["protocol_pass"] = calibrated and correct and bool(reasoning) and stream_terminal and finish == "stop" and not breached.is_set()
    if breached.is_set():
        result["outcome"] = "resource_stopped"
    elif failure or not stream_terminal or finish != "stop":
        result["outcome"] = "runtime_error"
    elif result["protocol_pass"]:
        result["outcome"] = "passed"
    else:
        result["outcome"] = "task_failed"
    result["timing_valid"] = result["timing_valid"] and stream_terminal and finish == "stop" and failure is None
    save(root / "result.json", result)
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=["ollama", "vllm"], required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--context", type=int, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--server-unit", required=True)
    probe(parser.parse_args())
