"""Harbor 0.24 adapter: one local Pi control parent and one plugin worker per task."""
import hashlib
import json
import shlex
from pathlib import Path

from harbor.agents.base import BaseAgent


TOOLS = ['read', 'bash', 'edit', 'write', 'clj_forms', 'clj_tree', 'clj_get', 'clj_draft', 'clj_edit',
         'lens_diagnostics', 'module_report', 'read_symbol', 'symbol_search', 'lsp_navigation',
         'pi_lens_activate_tools', 'web_search', 'fetch_content', 'get_search_content',
         'capabilities', 'change', 'gortex_edit', 'explore', 'gortex_read', 'refactor',
         'relations', 'search', 'trace', 'workspace', 'response']


def contract_hash(text):
    return hashlib.sha256(text.strip().encode()).hexdigest()


class AssistedPi(BaseAgent):
    @staticmethod
    def name():
        return 'codex-assisted-pi'

    def version(self):
        return 'pi-1.3.0+subagents-0.19.1'

    async def setup(self, environment):
        self.pilot = json.loads(Path(self._get_env('GX10_PILOT_MANIFEST')).read_text())
        if self.pilot['tools_admitted'] is not True:
            raise RuntimeError('Required-tool admission is missing')
        source = Path(self.pilot['source_dir'])
        for name in ['parent.mjs', 'execution.mjs', 'companion.ts', 'tool-environment.ts']:
            await environment.upload_file(source / name, '/usr/local/lib/pilot/' + name)
        result = await environment.exec('test -x /usr/local/lib/pilot/bin/node && mkdir -p /logs/agent && chown -R agent:agent /logs/agent', user='root', timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Pinned tool runtime is missing')

    async def run(self, instruction, environment, context):
        digest = contract_hash(instruction)
        selected = [(name, task) for name, task in self.pilot['tasks'].items()
                    if contract_hash(task['public_contract']) == digest]
        if len(selected) != 1:
            raise RuntimeError('Unknown or ambiguous public contract: ' + digest)
        name, task = selected[0]
        prompt = instruction
        if task['outline']:
            prompt += '\n\nFrozen Codex outline (no later coaching):\n' + task['outline']
        config = {'task': name, 'instruction': prompt, 'proxy': self.pilot['proxy'],
                  'tools': TOOLS, 'toolsAdmitted': True,
                  'contract_sha256': digest, 'outline_sha256': task.get('outline_sha256')}
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        config_path = self.logs_dir / 'worker-config.json'
        config_path.write_text(json.dumps(config, indent=2) + '\n')
        target = str(self.environment_logs_dir / 'worker-config.json')
        await environment.upload_file(config_path, target)
        # Verifiers retain normal container capabilities for their unchanged chown/
        # grader setup. Drop every capability before the agent parent/child begins.
        argv = ['setpriv', '--reuid=agent', '--regid=agent', '--init-groups',
                '--bounding-set=-all', '--no-new-privs', '/usr/local/lib/pilot/bin/node',
                '/usr/local/lib/pilot/parent.mjs', target]
        command = shlex.join(argv) + ' > ' + shlex.quote(str(self.environment_logs_dir / 'parent.stdout'))
        command += ' 2> ' + shlex.quote(str(self.environment_logs_dir / 'parent.stderr'))
        result = await environment.exec(command, cwd='/app', user='root', timeout_sec=None)
        (self.logs_dir / 'driver-exit.json').write_text(json.dumps({'exit_code': result.return_code}) + '\n')
        if result.return_code != 0:
            raise RuntimeError('Pi worker did not complete; see parent/failure.json and durable receipts')
