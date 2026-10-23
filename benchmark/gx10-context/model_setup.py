"""Budget, artifact and effective backend configuration checks before agent timing."""
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time
from lifecycle import Failure, GIB, http, memory, save, identities, ollama_runners


def budget_projection(info, weight_bytes, context, available, reserve=None, workspace_allowance=4 * GIB):
    prefix = info['general.architecture'] + '.'
    blocks = info[prefix + 'block_count']
    interval = info[prefix + 'full_attention_interval']
    attention_layers = (blocks + interval - 1) // interval
    per_token = attention_layers * info[prefix + 'attention.head_count_kv'] * (
        info[prefix + 'attention.key_length'] + info[prefix + 'attention.value_length']) * 2
    if reserve is None:
        reserve = memory()['reserve']
    if not isinstance(workspace_allowance, int) or workspace_allowance <= 0:
        raise Failure('runtime_error', 'Workspace allowance must be a positive byte count')
    remaining = available - weight_bytes - per_token * context - workspace_allowance
    return {'available_before_load': available, 'weight_bytes': weight_bytes, 'context': context,
            'kv_bytes_per_token_estimate': per_token, 'cache_estimate_bytes': per_token * context,
            'workspace_allowance_bytes': workspace_allowance, 'host_prompt_cache_bytes': 0,
            'projected_remaining_bytes': remaining,
            'reserve_bytes': reserve, 'allowed': remaining >= reserve,
            'limitations': 'Conservative F16 full-attention cache estimate; excludes architecture-specific recurrent state and allocator variation. Not an observed capacity failure.'}


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(8 * 1024**2), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_ollama_offload(active, journal):
    """Use Ollama's resident accounting and actual layer placement, not file size."""
    layers = re.findall(r'offloaded (\d+)/(\d+) layers to GPU', journal)
    all_layers = bool(layers) and int(layers[-1][0]) == int(layers[-1][1]) and int(layers[-1][1]) > 0
    all_resident = active.get('size', 0) > 0 and active.get('size_vram') == active.get('size')
    evidence = {'resident_bytes': active.get('size'), 'resident_gpu_bytes': active.get('size_vram'),
                'layer_counts': layers, 'all_layers_on_gpu': all_layers, 'all_resident_memory_on_gpu': all_resident}
    if not all_layers or not all_resident:
        raise Failure('runtime_error', 'Full GPU offload not established: ' + repr(evidence))
    return evidence


def vllm_environment(experiment, model):
    environment = {
        'PATH': str(Path.home() / '.local/opt/vllm-ornith-nvfp4/bin') + ':/usr/local/cuda/bin:/usr/bin:/bin',
        'CUDA_HOME': '/usr/local/cuda',
        'CPATH': str(experiment / 'toolchain/extracted/usr/include') + ':' + str(experiment / 'toolchain/extracted/usr/include/python3.12'),
        'CUTE_DSL_ARCH': 'sm_121a', 'MAX_JOBS': '2', 'FLASHINFER_NVCC_THREADS': '1',
        'VLLM_MARLIN_USE_ATOMIC_ADD': '1'}
    allowed = {'VLLM_NVFP4_GEMM_BACKEND': {'marlin'}, 'VLLM_TEST_FORCE_FP8_MARLIN': {'1'},
               'VLLM_USE_FLASHINFER_MOE_FP4': {'0'}}
    overrides = model.get('runtime_environment', {})
    if not isinstance(overrides, dict):
        raise Failure('setup_declined', 'Model runtime environment must be a mapping')
    for key, value in overrides.items():
        if key not in allowed or not isinstance(value, str) or value not in allowed[key]:
            raise Failure('setup_declined', 'Unsupported model runtime override: ' + str(key))
        environment[key] = value
    return environment


def start(backend, model, context, identity):
    from model_profile import thinking_enabled
    thinking = thinking_enabled(model, backend.manifest['sampling'])
    preload_prompt = 'Think briefly and reply READY.'
    if not thinking:
        preload_prompt = 'Reply READY.'
    if model['backend'] not in ['ollama', 'vllm']:
        raise Failure('runtime_error', 'Unsupported backend: ' + str(model['backend']))
    out = backend.root
    baseline = memory()
    backend.event('budget_start', {'memory': baseline, 'processes': identities()})
    unit = 'gx10-v3-backend-' + identity + '.service'
    backend.unit = unit
    sampling = dict(backend.manifest['sampling'])
    sampling.pop('thinking')
    sampling['seed'] = 42
    started = time.monotonic()
    parallel = backend.manifest['backend_runtime'].get('ollama_parallel_requests', 1)
    if type(parallel) is not int or parallel not in [1, 2, 4]:
        raise Failure('setup_declined', 'Unsupported Ollama parallel request count')
    if model['backend'] == 'ollama':
        backend.launch(unit, [str(Path.home() / '.local/bin/ollama'), 'serve'], {
            'OLLAMA_HOST': '127.0.0.1:11434', 'OLLAMA_NUM_PARALLEL': str(parallel),
            'OLLAMA_MAX_LOADED_MODELS': '1', 'OLLAMA_FLASH_ATTENTION': '1',
            'OLLAMA_NO_CLOUD': '1', 'OLLAMA_GPU_OVERHEAD': str(baseline['reserve']),
            'LLAMA_ARG_CACHE_RAM': str(backend.manifest['backend_runtime']['ollama_prompt_cache_mib']),
            'LLAMA_ARG_CTX_CHECKPOINTS': str(backend.manifest['backend_runtime']['ollama_context_checkpoints']),
            'LLAMA_ARG_BATCH': str(backend.manifest['backend_runtime']['ollama_batch_size']),
            'LLAMA_ARG_UBATCH': str(backend.manifest['backend_runtime']['ollama_ubatch_size']),
            'LLAMA_ARG_ENDPOINT_METRICS': '1', 'OLLAMA_KV_CACHE_TYPE': 'f16'})
        backend.wait_ready('http://127.0.0.1:11434/api/tags')
        show = http('http://127.0.0.1:11434/api/show', {'model': model['source']})
        tags = http('http://127.0.0.1:11434/api/tags')['models']
        source = next((tag for tag in tags if tag['name'] == model['source']), None)
        if source is None or source['digest'].removeprefix('sha256:') != model['digest']:
            raise Failure('runtime_error', 'Source artifact digest differs from frozen manifest')
        save(out / 'source-show.json', show)
        projection = budget_projection(show['model_info'], source['size'], context * parallel,
                                       memory()['available'], baseline['reserve'],
                                       backend.manifest['backend_runtime']['ollama_workspace_allowance_bytes'])
        projection.update(context_per_request=context, parallel_requests=parallel)
        save(out / 'budget.json', projection)
        if not projection['allowed']:
            raise Failure('setup_declined', 'Clean conservative budget check declined; not an observed OOM')
        name = 'gx10-v3-' + identity + ':' + str(context)
        backend.state['aliases'].append(name)
        backend.persist()
        parameters = dict(sampling, num_ctx=context, num_predict=backend.manifest['context']['pi_max_output_tokens'],
                          num_batch=backend.manifest['backend_runtime']['ollama_batch_size'])
        http('http://127.0.0.1:11434/api/create', {'model': name, 'from': model['source'],
             'parameters': parameters, 'stream': False}, timeout=1800)
        effective = http('http://127.0.0.1:11434/api/show', {'model': name})
        save(out / 'effective-show.json', effective)
        # /api/show returns canonical parameter text, including inherited parameters.
        values = {}
        for line in effective.get('parameters', '').splitlines():
            parts = line.split(None, 1)
            if len(parts) == 2:
                values[parts[0]] = parts[1].strip()
        for key, expected in parameters.items():
            try:
                matches = float(values[key]) == float(expected)
            except (KeyError, ValueError):
                matches = False
            if not matches:
                raise Failure('runtime_error', 'Effective Ollama parameter mismatch: ' + key)
        body = {'model': name, 'messages': [{'role': 'user', 'content': preload_prompt}],
                'think': thinking, 'stream': False, 'keep_alive': '30m', 'options': dict(parameters, num_predict=64)}
        backend.event('load_start', {'model': name})
        load_started = time.monotonic()
        load_response = http('http://127.0.0.1:11434/api/generate',
            {'model': name, 'stream': False, 'keep_alive': '30m', 'options': parameters}, timeout=1800)
        save(out / 'load-response.json', load_response)
        load_s = time.monotonic() - load_started
        loaded = http('http://127.0.0.1:11434/api/ps')
        save(out / 'runtime-models.json', loaded)
        if len(loaded['models']) != 1:
            raise Failure('runtime_error', 'Overlapping resident models')
        active = loaded['models'][0]
        if active['name'] != name or active.get('context_length') != context * parallel:
            raise Failure('runtime_error', 'Loaded identity or context mismatch')
        journal = subprocess.check_output(['journalctl', '--user', '-u', unit, '--no-pager'], text=True)
        (out / 'backend-startup.log').write_text(journal)
        save(out / 'gpu-offload.json', verify_ollama_offload(active, journal))
        if backend.manifest['backend_runtime']['ollama_prompt_cache_mib'] != 0:
            raise Failure('setup_declined', 'Only disabled host prompt caching has a validated memory policy')
        if 'prompt cache is disabled' not in journal:
            raise Failure('runtime_error', 'Runner did not verify disabled host prompt caching')
        save(out / 'prompt-cache-verification.json', {'verified': True, 'host_cache_mib': 0,
             'evidence': 'backend-startup.log', 'environment': 'LLAMA_ARG_CACHE_RAM=0'})
        runners = ollama_runners(backend.group_processes(unit))
        save(out / 'runner-identities.json', runners)
        if not runners:
            raise Failure('runtime_error', 'External Ollama runner identity was not established')
        expected_environment = {
            'LLAMA_ARG_CTX_CHECKPOINTS': str(backend.manifest['backend_runtime']['ollama_context_checkpoints']),
            'LLAMA_ARG_BATCH': str(backend.manifest['backend_runtime']['ollama_batch_size']),
            'LLAMA_ARG_UBATCH': str(backend.manifest['backend_runtime']['ollama_ubatch_size']),
            'LLAMA_ARG_ENDPOINT_METRICS': '1'}
        for runner in runners:
            import shlex
            arguments = shlex.split(runner['command'])
            if parallel > 1:
                if '-np' not in arguments or arguments[arguments.index('-np') + 1] != str(parallel):
                    raise Failure('setup_declined', 'Requested parallel runner slots were not established')
                if '-c' not in arguments or arguments[arguments.index('-c') + 1] != str(context * parallel):
                    raise Failure('setup_declined', 'Per-agent context was not established')
            for flag, expected in [('-b', backend.manifest['backend_runtime']['ollama_batch_size']),
                                   ('-ub', backend.manifest['backend_runtime']['ollama_ubatch_size'])]:
                if flag not in arguments or arguments[arguments.index(flag) + 1] != str(expected):
                    raise Failure('runtime_error', 'Effective runner batch differs: ' + flag)
            if '--ctx-checkpoints' in arguments and arguments[arguments.index('--ctx-checkpoints') + 1] != str(backend.manifest['backend_runtime']['ollama_context_checkpoints']):
                raise Failure('runtime_error', 'Effective checkpoint limit differs')
            entries = (Path('/proc') / str(runner['pid']) / 'environ').read_bytes().split(b'\0')
            actual = dict(entry.decode().split('=', 1) for entry in entries if b'=' in entry)
            if any(actual.get(key) != value for key, value in expected_environment.items()):
                raise Failure('runtime_error', 'Runner cache/metrics/batch environment differs')
        save(out / 'backend-runtime-verification.json', {'verified': True, 'environment': expected_environment,
             'kv_cache_precision': 'f16', 'checkpoint_cache_limit': backend.manifest['backend_runtime']['ollama_context_checkpoints']})
        backend.state['runners'] = [{'pid': p['pid'], 'start_ticks': p['start_ticks']} for p in runners]
        backend.persist()
        preload_started = time.monotonic()
        warm = http('http://127.0.0.1:11434/api/chat', body, timeout=1800)
        preload_s = time.monotonic() - preload_started
        if (thinking and not warm.get('message', {}).get('thinking')) or not warm.get('done'):
            raise Failure('runtime_error', 'Required thinking or terminal preload marker missing')
        url = 'http://127.0.0.1:11434/v1'
    else:
        experiment = Path.home() / 'experiments/ornith-nvfp4-mtp-20261021'
        model_path = Path(model.get('model_directory', str(experiment / 'model')))
        artifact_path = Path(model.get('artifact_manifest', str(Path(__file__).resolve().parent.parent / 'ornith-nvfp4-mtp/ornith-artifacts.json')))
        artifact_manifest = json.loads(artifact_path.read_text())
        if artifact_manifest['sha'] != model['revision']:
            raise Failure('runtime_error', 'NVFP4 revision differs from frozen manifest')
        hashes = {}
        for artifact in artifact_manifest['siblings']:
            name = artifact['rfilename']
            actual = file_hash(model_path / name)
            hashes[name] = actual
            if actual != artifact['lfs']['sha256']:
                raise Failure('runtime_error', 'NVFP4 artifact hash mismatch: ' + name)
        save(out / 'artifact-hashes.json', hashes)
        cache_bytes = 4 * GIB * context // 32768
        remaining = memory()['available'] - model['weight_bytes'] - cache_bytes - 4 * GIB
        projection = {'weight_bytes': model['weight_bytes'], 'cache_estimate_bytes': cache_bytes,
                      'workspace_allowance_bytes': 4 * GIB, 'available_before_load': memory()['available'],
                      'projected_remaining_bytes': remaining, 'reserve_bytes': baseline['reserve'],
                      'allowed': remaining >= baseline['reserve'],
                      'limitations': 'Explicit vLLM KV allocation plus 4 GiB allowance; runtime guard remains authoritative.'}
        save(out / 'budget.json', projection)
        if not projection['allowed']:
            raise Failure('setup_declined', 'Clean conservative budget check declined; not an observed OOM')
        command_path = Path(__file__).resolve().parent.parent / model.get('server_command', 'ornith-nvfp4-mtp/gx10-server-command.json')
        command = json.loads(command_path.read_text())
        if command[command.index('--model') + 1] != str(model_path) or command[command.index('--served-model-name') + 1] != model['source']:
            raise Failure('setup_declined', 'vLLM command artifact or served identity differs')
        if model.get('mtp') is False and '--speculative-config' in command:
            raise Failure('setup_declined', 'Speculative decoding must be disabled for this configuration')
        effort = model.get('thinking_level_map', {}).get('high', 'high')
        template_defaults = json.loads(command[command.index('--default-chat-template-kwargs') + 1])
        if thinking:
            if template_defaults.get('reasoning_effort') != effort:
                raise Failure('setup_declined', 'Chat template reasoning effort differs from model policy')
        elif (template_defaults.get('enable_thinking') is not False or 'reasoning_effort' in template_defaults
              or '--reasoning-parser' in command):
            raise Failure('setup_declined', 'Non-thinking model must disable reasoning settings explicitly')
        command[command.index('--max-model-len') + 1] = str(context)
        command[command.index('--kv-cache-memory-bytes') + 1] = str(cache_bytes)
        generation = dict(sampling)
        generation.pop('seed')
        generation['repetition_penalty'] = generation.pop('repeat_penalty')
        command[command.index('--override-generation-config') + 1] = json.dumps(generation)
        save(out / 'server-command.json', command)
        load_started = time.monotonic()
        backend.launch(unit, command, vllm_environment(experiment, model))
        registered = backend.wait_ready('http://127.0.0.1:8001/v1/models')
        load_s = time.monotonic() - load_started
        save(out / 'runtime-models.json', registered)
        active = next((m for m in registered['data'] if m['id'] == model['source']), None)
        if active is None or active.get('max_model_len') != context:
            raise Failure('runtime_error', 'vLLM requested context or identity not established')
        preload_started = time.monotonic()
        preload_request = {'model': model['source'], 'messages': [{'role': 'user', 'content': preload_prompt}],
                           'max_tokens': 64, 'stream': False, 'seed': 42}
        if thinking:
            preload_request['reasoning_effort'] = effort
        warm = http('http://127.0.0.1:8001/v1/chat/completions', preload_request, timeout=1800)
        preload_s = time.monotonic() - preload_started
        choice = warm['choices'][0]
        message = choice['message']
        reasoning = message.get('reasoning_content') or message.get('reasoning')
        if not choice.get('finish_reason') or (thinking and not reasoning):
            raise Failure('runtime_error', 'vLLM required thinking or preload completion missing')
        if not thinking and (reasoning or not message.get('content')):
            raise Failure('runtime_error', 'vLLM non-thinking preload differs from declared model capability')
        journal = subprocess.check_output(['journalctl', '--user', '-u', unit, '--no-pager'], text=True)
        (out / 'backend-startup.log').write_text(journal)
        if 'GPU' not in journal or '--cpu-offload-gb' in command or '--max-num-seqs' not in command or command[command.index('--max-num-seqs') + 1] != '1':
            raise Failure('runtime_error', 'GPU execution or concurrency verification failed')
        name = model['source']
        url = 'http://127.0.0.1:8001/v1'
    save(out / 'phase-durations.json', {'load_s': load_s, 'preload_s': preload_s,
         'setup_s': time.monotonic() - started - load_s - preload_s})
    save(out / 'preload.json', warm)
    save(out / 'configuration-verification.json', {'verified': True, 'model': model, 'context': context,
         'sampling': sampling, 'thinking': thinking, 'concurrency': parallel, 'unit': unit,
         'duration_s': time.monotonic() - started, 'physical_memory': baseline})
    backend.event('preload_verified', {'model': name, 'duration_s': time.monotonic() - started})
    return name, url, unit
