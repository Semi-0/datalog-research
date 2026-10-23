#!/usr/bin/env python3
"""Verify the pinned checkpoint and record a conservative MTP-enabled launch."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import urllib.request

from coding import memory, RESERVE, save


def prepare(experiment):
    config = json.loads((Path(__file__).parent / "config.json").read_text())
    metadata = json.loads((experiment / "ornith-artifacts.json").read_text())
    assert metadata["sha"] == config["revision"]
    hashes = {}
    for artifact in metadata["siblings"]:
        if not artifact["rfilename"].endswith(".safetensors"):
            continue
        path = experiment / "model" / artifact["rfilename"]
        assert path.stat().st_size == artifact["size"]
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        assert digest == artifact["lfs"]["sha256"], path.name
        hashes[path.name] = digest
    save(experiment / "verified-weights.json", hashes)
    python = Path.home() / ".local/opt/vllm-ornith-nvfp4/bin/python"
    command = [str(python), "-m", "vllm.entrypoints.openai.api_server",
        "--model", str(experiment / "model"), "--served-model-name", config["served_model_name"],
        "--host", "127.0.0.1", "--port", str(config["port"]),
        "--tensor-parallel-size", "1", "--language-model-only",
        "--moe-backend", "marlin", "--linear-backend", "auto",
        "--attention-backend", "triton_attn", "--gdn-prefill-backend", "triton",
        "--max-model-len", "32768", "--gpu-memory-utilization", "0.60",
        "--kv-cache-memory-bytes", "4294967296", "--kv-cache-dtype", "fp8_e4m3",
        "--max-num-seqs", "1", "--max-num-batched-tokens", "4096",
        "--max-cudagraph-capture-size", "4", "--enable-chunked-prefill",
        "--enable-prefix-caching", "--prefix-match-unit", "16", "--async-scheduling",
        "--speculative-config", json.dumps(config["speculative_config"]),
        "--reasoning-parser", "qwen3", "--tool-call-parser", "qwen3_xml",
        "--enable-auto-tool-choice",
        "--default-chat-template-kwargs", json.dumps({"enable_thinking": True, "preserve_thinking": True, "reasoning_effort": "high"}),
        "--override-generation-config", json.dumps({"temperature": 0.6, "top_p": 0.95, "top_k": 20, "min_p": 0, "presence_penalty": 0, "repetition_penalty": 1}),
        "--api-key", "local-benchmark"]
    save(experiment / "server-command.json", command)
    assert importlib.metadata.version("vllm") == config["target_runtime_version"]
    request = urllib.request.Request("http://127.0.0.1:11434/api/ps")
    loaded = json.load(urllib.request.urlopen(request))["models"]
    assert all(m["name"].startswith("gx10-") for m in loaded), "A non-experiment model is loaded; preserve it"
    save(experiment / "ollama-before-unload.json", loaded)
    for model in loaded:
        request = urllib.request.Request("http://127.0.0.1:11434/api/generate",
            data=json.dumps({"model": model["name"], "keep_alive": 0}).encode(),
            headers={"Content-Type": "application/json"})
        urllib.request.urlopen(request, timeout=120).read()
    assert memory()["available"] >= RESERVE
    save(experiment / "server-preflight.json", {"memory": memory(), "reserve": RESERVE,
        "weight_hashes_verified": True, "vllm": importlib.metadata.version("vllm"),
        "mtp": config["speculative_config"], "context": 32768, "kv_cache_bytes": 4294967296})
    print("Pinned weights verified; launch command saved; previous experiment model unloaded.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", type=Path, required=True)
    prepare(parser.parse_args().experiment.resolve())
