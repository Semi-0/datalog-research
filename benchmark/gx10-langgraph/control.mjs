import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const CONTROL_TOOLS = new Set(['subagent_spawn_batch', 'subagent_list', 'subagent_result', 'subagent_output', 'subagent_stop']);

export function atomicJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${crypto.randomUUID()}.tmp`;
  const fd = fs.openSync(temporary, 'wx', 0o600);
  try {
    fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n');
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(temporary, file);
}

export function within(root, candidate) {
  const relative = path.relative(fs.realpathSync(root), fs.realpathSync(candidate));
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}

export function createController(pi, policy) {
  const definitions = new Map();
  const root = fs.realpathSync(policy.root);
  const statePath = path.join(root, 'controller.json');
  let state = { phase: 'admission', runs: [], pid: process.pid };
  if (fs.existsSync(statePath)) {
    state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
    if (state.pid !== process.pid) {
      state.phase = 'recovery_required';
    }
  }
  const persist = () => atomicJson(statePath, state);
  const workspaces = policy.workspaces.map((entry) => fs.realpathSync(entry));
  for (let index = 0; index < workspaces.length; index += 1) {
    for (let other = index + 1; other < workspaces.length; other += 1) {
      if (within(workspaces[index], workspaces[other]) || within(workspaces[other], workspaces[index])) {
        throw new Error('Worker workspaces must be independent');
      }
    }
  }
  const facade = new Proxy(pi, {
    get(target, key) {
      if (key === 'registerTool') {
        return (definition) => {
          if (CONTROL_TOOLS.has(definition.name)) {
            definitions.set(definition.name, definition);
          }
          return target.registerTool(definition);
        };
      }
      const value = Reflect.get(target, key);
      if (typeof value === 'function') {
        return value.bind(target);
      }
      return value;
    },
  });

  async function execute(request, ctx) {
    if (!/^[a-zA-Z0-9_-]{1,80}$/.test(request.id ?? '')) {
      throw new Error('A bounded receipt id is required');
    }
    const receipt = path.join(root, 'control', `${request.id}.json`);
    const digest = crypto.createHash('sha256').update(JSON.stringify(request)).digest('hex');
    if (fs.existsSync(receipt)) {
      const previous = JSON.parse(fs.readFileSync(receipt, 'utf8'));
      if (previous.digest !== digest) {
        throw new Error('Receipt id reused with different request');
      }
      if (previous.state !== 'completed') {
        throw new Error('Uncertain or failed operation; reconcile durable plugin state before retry');
      }
      return previous;
    }
    atomicJson(receipt, { state: 'started', digest, request, epoch: Date.now() });
    try {
      let result;
      if (request.operation === 'phase') {
        if (state.phase === 'recovery_required') {
          throw new Error('Reconcile live processes and receipts before changing phase');
        }
        if (!['admission', 'planning', 'workers', 'integration', 'cleanup'].includes(request.phase)) {
          throw new Error('Unsupported phase');
        }
        if (request.phase === 'integration' && state.runs.length > 0) {
          throw new Error('Stop and collect workers before integration');
        }
        state.phase = request.phase;
        persist();
        result = { phase: state.phase };
      } else if (request.operation === 'inventory') {
        result = { ...state, controls: [...definitions.keys()], tools: pi.getAllTools().map((tool) => tool.name) };
      } else {
        if (!CONTROL_TOOLS.has(request.operation)) {
          throw new Error('Unsupported control operation');
        }
        const definition = definitions.get(request.operation);
        if (!definition) {
          throw new Error(`Pinned plugin did not register ${request.operation}`);
        }
        let args;
        if (request.operation === 'subagent_spawn_batch') {
          if (state.phase !== 'workers') {
            throw new Error('Worker spawning is allowed only during worker phase');
          }
          if (!Array.isArray(request.jobs) || request.jobs.length < 1 || request.jobs.length > workspaces.length) {
            throw new Error('One or two bounded assignments are required');
          }
          if (!policy.tools?.length || policy.tools.some((tool) => tool.startsWith('subagent_'))) {
            throw new Error('Explicit non-delegating worker tools are required');
          }
          const available = new Set(pi.getAllTools().map((tool) => tool.name));
          const missing = policy.tools.filter((tool) => !available.has(tool));
          if (missing.length > 0) {
            throw new Error(`setup_declined: required tools missing: ${missing.join(',')}`);
          }
          const seen = new Set();
          const jobs = request.jobs.map((job) => {
            if (!Number.isInteger(job.worker) || job.worker < 0 || job.worker >= workspaces.length || seen.has(job.worker)) {
              throw new Error('Unknown or duplicate worker');
            }
            if (typeof job.prompt !== 'string' || job.prompt.trim() === '') {
              throw new Error('Worker prompt is required');
            }
            seen.add(job.worker);
            return { prompt: job.prompt, name: `worker-${job.worker + 1}`, model: policy.workerModel,
              thinking: 'high', tools: policy.tools.join(','), callback: false, allow_nested: false,
              sandbox: true, sandbox_dir: workspaces[job.worker], cwd: workspaces[job.worker], approve: false };
          });
          args = { jobs, onCapacity: 'reject' };
        } else if (request.operation === 'subagent_list') {
          args = {};
        } else {
          if (typeof request.runId !== 'string' || !state.runs.includes(request.runId)) {
            throw new Error('Run is not owned by this controller');
          }
          args = { id: request.runId };
        }
        result = await definition.execute(`gx10-${request.id}`, args, undefined, undefined, ctx);
        if (request.operation === 'subagent_spawn_batch') {
          const launch = result?.structuredContent;
          if (!Array.isArray(launch?.launched)) {
            throw new Error('Missing typed batch receipt; reconcile plugin state');
          }
          state.runs.push(...launch.launched.map((run) => run.id));
          persist();
          // Preserve partial launch receipts before reporting failure; never relaunch silently.
          atomicJson(path.join(root, 'control', `${request.id}-launch.json`), result);
          if (launch.failed.length > 0 || launch.skipped.length > 0 || result.isError) {
            throw new Error('Partial or failed batch launch; reconcile recorded workers');
          }
        } else if (result?.isError) {
          throw new Error(JSON.stringify(result));
        }
        if (request.operation === 'subagent_stop') {
          state.runs = state.runs.filter((id) => id !== request.runId);
          persist();
        }
      }
      const value = { state: 'completed', digest, result, epoch: Date.now() };
      atomicJson(receipt, value);
      return value;
    } catch (error) {
      atomicJson(receipt, { state: 'failed', digest, error: String(error), epoch: Date.now() });
      throw error;
    }
  }
  return { facade, execute };
}
