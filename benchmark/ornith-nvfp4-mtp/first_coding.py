import pathlib,urllib.request,json,subprocess,time
E=pathlib.Path.home()/"experiments/ornith-nvfp4-mtp-20261021"
while True:
 state=subprocess.check_output(["systemctl","--user","show","ornith-nvfp4-server","-p","ActiveState","--value"],text=True).strip()
 if state!="active":raise RuntimeError("Server stopped during startup: "+state)
 try:
  with urllib.request.urlopen("http://127.0.0.1:8001/health",timeout=5) as response:
   if response.status==200:break
 except Exception:pass
 time.sleep(5)
headers={"Content-Type":"application/json","Authorization":"Bearer local-benchmark"}
request={"model":"ornith-nvfp4-mtp","messages":[{"role":"user","content":"Think briefly and reply READY."}],"reasoning_effort":"high","temperature":0.6,"top_p":0.95,"seed":42,"max_tokens":256,"stream":False}
req=urllib.request.Request("http://127.0.0.1:8001/v1/chat/completions",data=json.dumps(request).encode(),headers=headers)
with urllib.request.urlopen(req,timeout=600) as response:result=json.load(response)
(E/"preload.json").write_text(json.dumps(result,indent=2))
for path,name in [("/v1/models","models-before-coding.json"),("/metrics","metrics-before-coding.txt")]:
 req=urllib.request.Request("http://127.0.0.1:8001"+path,headers=headers)
 (E/name).write_bytes(urllib.request.urlopen(req,timeout=30).read())
repo=pathlib.Path.home()/"projects/KIROSHI"
cmd=["/usr/bin/python3",str(repo/"benchmark/ornith-nvfp4-mtp/coding.py"),"--task","distinct","--output",str(E/"coding-distinct-1")]
code=subprocess.call(cmd,cwd=repo)
(E/"coding-exit.txt").write_text(str(code))
req=urllib.request.Request("http://127.0.0.1:8001/metrics",headers=headers)
(E/"metrics-after-coding.txt").write_bytes(urllib.request.urlopen(req,timeout=30).read())
raise SystemExit(code)
