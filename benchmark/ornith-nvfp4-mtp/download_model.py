import pathlib,json,subprocess,time,shutil,concurrent.futures,urllib.request,os,hashlib,argparse
parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True,type=pathlib.Path)
E=parser.parse_args().output.resolve()
E.mkdir(parents=True,exist_ok=True)
d=json.loads((pathlib.Path(__file__).parent/'ornith-artifacts.json').read_text()); quant='NVFP4'
assert d['sha']=='94e431d9cc47fa1986a7a1a4e9a80f7f118b03aa'
files=[x for x in d['siblings'] if x['rfilename'].endswith('.safetensors')]
mem={k:int(v.split()[0])*1024 for k,v in (line.split(':',1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())}
size=sum(x['size'] for x in files); reserve=max(16*1024**3,mem['MemTotal']//5)
assert size < mem['MemTotal']-reserve
assert size*2+10*1024**3 < shutil.disk_usage(E).free
(E/'download-check.json').write_text(json.dumps({'time':time.time(),'quant':quant,'size':size,'reserve':reserve,'mem':mem,'disk_free':shutil.disk_usage(E).free,'revision':d['sha'],'files':files},indent=2))
out=E/'model'; out.mkdir(exist_ok=True)
(E/'ornith-artifacts.json').write_text(json.dumps(d,indent=2))
config_hashes={}
for name in ['chat_template.jinja','config.json','generation_config.json','hf_quant_config.json','merges.txt','model.safetensors.index.json','preprocessor_config.json','processor_config.json','tokenizer.json','tokenizer_config.json','video_preprocessor_config.json','vocab.json']:
 url=f"https://huggingface.co/ornith-ai/Ornith-1.5-35B-A3B-NVFP4/resolve/{d['sha']}/{name}"
 data=urllib.request.urlopen(url,timeout=120).read()
 (out/name).write_bytes(data)
 config_hashes[name]=hashlib.sha256(data).hexdigest()
(E/'model-config-hashes.json').write_text(json.dumps(config_hashes,indent=2))
for f in files:
 mem={k:int(v.split()[0])*1024 for k,v in (line.split(':',1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())}
 assert mem['MemAvailable']>=reserve,'Available memory below reserve before download'
 assert size+10*1024**3<shutil.disk_usage(E).free,'Insufficient disk before download'
 target=out/pathlib.Path(f['rfilename']).name
 expected=f.get('lfs',{}).get('sha256')
 if target.exists() and target.stat().st_size==f['size']:
  print('already complete',target.name,flush=True); continue
 temp=target.with_suffix('.parts-download'); donefile=target.with_suffix('.parts-done.json')
 fd=os.open(temp,os.O_RDWR|os.O_CREAT,0o644); os.ftruncate(fd,f['size'])
 done=set(json.loads(donefile.read_text())) if donefile.exists() else set()
 chunk=16*1024**2
 url=f"https://huggingface.co/ornith-ai/Ornith-1.5-35B-A3B-NVFP4/resolve/{d['sha']}/{f['rfilename']}"
 def fetch(index):
  start=index*chunk; end=min(f['size']-1,start+chunk-1)
  if index in done: return index
  for attempt in range(6):
   try:
    req=urllib.request.Request(url+f'?download=true&benchmark_chunk={index}',headers={'Range':f'bytes={start}-{end}'})
    with urllib.request.urlopen(req,timeout=120) as response:
     assert response.status==206,(response.status,response.headers)
     assert response.headers['Content-Range'].startswith(f'bytes {start}-{end}/'),response.headers['Content-Range']
     pos=start
     while True:
      data=response.read(1024**2)
      if not data: break
      os.pwrite(fd,data,pos); pos+=len(data)
     assert pos==end+1,(pos,end)
    return index
   except Exception as exc:
    if attempt==5: raise
    time.sleep(2**attempt)
 with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
  futures=[pool.submit(fetch,i) for i in range((f['size']+chunk-1)//chunk)]
  for future in concurrent.futures.as_completed(futures):
   done.add(future.result()); donefile.write_text(json.dumps(sorted(done)))
   if len(done)%64==0: print(target.name,len(done)*chunk,'/',f['size'],time.time(),flush=True)
 os.close(fd)
 print('hashing',target.name,flush=True)
 digest=hashlib.file_digest(temp.open('rb'),'sha256').hexdigest()
 if expected: assert digest==expected,(digest,expected)
 (target.with_suffix('.sha256')).write_text(digest+'\n'); temp.rename(target)
 print('complete',target.name,digest,time.time(),flush=True)
