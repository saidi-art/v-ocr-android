import re,difflib,sys,collections
exec(open('/tmp/acc.py').read().split('for fn')[0])
out=[l for l in open('/tmp/sw_base_dump2.txt').read().split('\n')]
n=lambda s:re.sub(r'[^a-z0-9]','',s.lower())
for W in (0,30,100,400):
  for th in (0.8,0.9):
    if W==0 and th==0.9: continue
    keep=[];recent=[]
    for l in out:
        k=n(l)
        if len(k)<6: keep.append(l);continue
        dup=False
        if W:
            for r in recent[-W:]:
                if abs(len(r)-len(k))<=3 and difflib.SequenceMatcher(None,r,k).ratio()>=th:dup=True;break
        if not dup: keep.append(l);recent.append(k)
    open(f'/tmp/fz_{W}_{th}.txt','w').write('\n'.join(keep))
    ow=[t for l in keep for t in toks(l)]
    sm=difflib.SequenceMatcher(None,gtw,ow,autojunk=False);m=sum(b.size for b in sm.get_matching_blocks())
    print('W',W,'th',th,'lines',len(keep),'recall',round(m/len(gtw),3),flush=True)
