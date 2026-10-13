import re,sys,difflib,collections
import numpy as np
fn=sys.argv[1]
th={};tx={}
for l in open(fn,errors='replace'):
    m=re.search(r'THUMB (\d+) (\d+) (-?\d+) (-?\d+) ([0-9a-f]{512})',l)
    if m: th[(int(m.group(1)),int(m.group(2)))]=(int(m.group(3)),int(m.group(4)),np.frombuffer(bytes.fromhex(m.group(5)),dtype=np.uint8).astype(np.int16))
    m=re.search(r'DUMP (\d+) (\d+) (-?\d+) ([\d.]+) ?(.*)',l)
    if m: tx[(int(m.group(1)),int(m.group(2)))]=m.group(5).strip()
norm=lambda s: re.sub(r'[^a-z0-9]','',s.lower())
strips=collections.defaultdict(list)
for (s,k),(y0,y1,t) in th.items(): strips[s].append((k,t,norm(tx.get((s,k),''))))
rows=[]  # per line in strip s+1: min SAD to strip s, the matched text, same?
for s in sorted(strips):
    if s+1 not in strips: continue
    for k,t,txt in strips[s+1]:
        if len(txt)<6: continue
        best=None
        for k2,t2,txt2 in strips[s]:
            sad=int(np.abs(t-t2).sum())
            if best is None or sad<best[0]: best=(sad,txt2)
        if best is None: continue
        same=difflib.SequenceMatcher(None,txt,best[1],autojunk=False).ratio()>=0.9
        # does a true duplicate exist at all in strip s?
        dup=any(difflib.SequenceMatcher(None,txt,t2,autojunk=False).ratio()>=0.9 for _,_,t2 in strips[s])
        rows.append((best[0],same,dup))
n=len(rows); nd=sum(r[2] for r in rows)
print('lines checked',n,'with a true duplicate in previous strip',nd,'(%.1f%%)'%(100*nd/n))
print('SAD(sum of 256 abs diffs) | flagged | correct dup | WRONG (real line deleted) | precision | recall of dups | FP rate of non-dups')
for thr in [100,200,400,600,800,1200,1800,2500,3500,5000]:
    fl=[r for r in rows if r[0]<thr]; tp=sum(r[1] for r in fl); fp=len(fl)-tp
    print('%5d | %4d | %4d | %4d | %.3f | %.3f | %.4f'%(thr,len(fl),tp,fp,tp/max(1,len(fl)),tp/max(1,nd),fp/max(1,n-nd)))
