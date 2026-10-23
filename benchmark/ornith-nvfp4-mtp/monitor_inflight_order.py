"""Companion for the already-running, pre-upgrade order attempt only."""
import json,pathlib,time,sys,os,signal,subprocess
repo=pathlib.Path.home()/'projects/KIROSHI/benchmark/ornith-nvfp4-mtp'
sys.path.insert(0,str(repo))
from coding import stop_process_tree,save
from nontermination import infinite_test_input
root=pathlib.Path.home()/'experiments/gx10-coding-context-20261021-r2/ornith-nvfp4-order-coding'
a=root/'artifacts'
while not (root/'result.json').exists() and not (root/'failure.json').exists():
 rows=[json.loads(x) for x in (a/'events.jsonl').read_text().splitlines(keepends=True) if x.endswith('\n') and x.strip()]
 pending={}
 for r in rows:
  e=r['event'];key=e.get('toolCallId')
  if e.get('type')=='tool_execution_start' and e.get('toolName')=='bash':
   cmd=e.get('args',{}).get('command','')
   if 'clojure' in cmd and 'benchmark.dependency-order-test' in cmd:pending[key]=r['elapsed_s']
  if e.get('type')=='tool_execution_end':pending.pop(key,None)
 elapsed=time.time()-json.loads((a/'process.json').read_text())['started_epoch']
 test=root/'checkout/test/benchmark/dependency_order_test.clj'
 if pending and elapsed-min(pending.values())>60 and test.exists() and infinite_test_input(test.read_text(),'dependency-order'):
  suite=int(subprocess.check_output(['systemctl','--user','show','gx10-coding-context-suite','-p','MainPID','--value'],text=True))
  pi=json.loads((a/'process.json').read_text())['pid']
  status=dict(line.split(':',1) for line in pathlib.Path(f'/proc/{pi}/status').read_text().splitlines() if ':' in line)
  runner=int(status['PPid'])
  os.kill(suite,signal.SIGSTOP);os.kill(runner,signal.SIGSTOP)
  try:
   class Process:
    pid=pi
   stop_process_tree(Process())
   os.kill(runner,signal.SIGTERM);os.kill(runner,signal.SIGCONT)
   reason='Direct unbounded collection in agent-written tests; observed active test >60 seconds. Companion stopped pre-upgrade runner.'
   save(a/'termination-decision.json',{'classification':'nonterminating','reason':reason,'epoch':time.time()})
   for name,cmd in [('changes.patch',['git','diff','--binary']),('status.txt',['git','status','--porcelain','--untracked-files=all'])]:
    (a/name).write_text(subprocess.check_output(cmd,cwd=root/'checkout',text=True))
   result={'model':'ornith-nvfp4-mtp','task':'order','context_tokens':32768,'outcome':'nonterminating','termination_reason':reason,'coding_wall_s':elapsed,'coding_wall_limit_seconds':None,'correct':False,'acceptance_exit':None,'implementation_correctness':'unmeasured at termination','protocol_pass':False,'timing_valid':False,'model_calls':sum(r['event'].get('type')=='message_end' and r['event'].get('message',{}).get('role')=='assistant' for r in rows),'tool_calls':sum(r['event'].get('type')=='tool_execution_start' for r in rows)}
   save(root/'result.json',result);save(root/'status.json',{'state':'nonterminating','result':result})
  finally:os.kill(suite,signal.SIGCONT)
  break
 time.sleep(2)
