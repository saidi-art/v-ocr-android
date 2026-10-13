import re,sys,difflib,collections
def load(fn):
    d={}
    for l in open(fn,errors='replace'):
        m=re.search(r'DUMP (\d+) (\d+) (-?\d+) ([\d.]+) ?(.*)',l)
        if m: d[(int(m.group(1)),int(m.group(3)))]=(float(m.group(4)),m.group(5).strip())
    return d
b=load(sys.argv[1]); v=load(sys.argv[2])
norm=lambda s: re.sub(r'[^a-z0-9]','',s.lower()); toks=lambda s:[t for t in (norm(x) for x in s.split()) if t]
same=sub=miss=0; wsub=wmiss=0; nb=0
confb=collections.defaultdict(lambda:[0,0])
for k,(cb,tb) in b.items():
    wb=toks(tb)
    if not wb: continue
    nb+=1
    if k in v: cv,tv=v[k]
    else:
        # nearest y0 within 4 px in same strip
        c=[kk for kk in v if kk[0]==k[0] and abs(kk[1]-k[1])<=4]
        if c: cv,tv=v[min(c,key=lambda kk:abs(kk[1]-k[1]))]
        else: cv,tv=0,''
    wv=toks(tv)
    bucket=min(int(cb*10),9)
    if wv==wb: same+=1; confb[bucket][0]+=1; continue
    confb[bucket][1]+=1
    sm=difflib.SequenceMatcher(None,wb,wv,autojunk=False); lost=len(wb)-sum(x.size for x in sm.get_matching_blocks())
    if not wv: miss+=1; wmiss+=lost
    else: sub+=1; wsub+=lost
print('base lines',nb,'identical',same,'substituted',sub,'missing/empty',miss)
print('words lost: substituted',wsub,'missing',wmiss,'missing share',round(wmiss/max(1,wsub+wmiss),3))
print('agreement by base conf bucket (identical/total):')
for k in sorted(confb): a,d=confb[k]; print(' conf %.1f-%.1f: %d/%d = %.3f'%(k/10,(k+1)/10,a,a+d,a/(a+d)))
