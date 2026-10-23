#!/bin/bash
set -euo pipefail
VENV="$HOME/.local/opt/vllm-ornith-nvfp4"
"$VENV/bin/python" -m pip install --upgrade pip uv
"$VENV/bin/uv" pip install --python "$VENV/bin/python" "vllm==0.28.0"
"$VENV/bin/uv" pip install --python "$VENV/bin/python" --no-deps 'https://github.com/flashinfer-ai/flashinfer/releases/download/v0.6.16.post3/flashinfer_jit_cache-0.6.16.post3+cu130-cp39-abi3-manylinux_2_28_aarch64.whl#sha256=9bd5529b1b2c774206bde78a65d84aff29583a51ce2804226cb45d6261af43ad'
"$VENV/bin/python" -m pip freeze > "$HOME/experiments/ornith-nvfp4-mtp-20261021/installed-requirements.txt"
