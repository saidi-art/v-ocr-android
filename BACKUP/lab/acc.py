import re,difflib,collections,sys
msgs=eval(open('/tmp/msgs.txt').read())
norm=lambda s: re.sub(r'[^a-z0-9]','',s.lower()); toks=lambda s:[t for t in (norm(x) for x in re.split(r'\s+',s)) if t]
gtw=[t for m in msgs[25:38] for t in toks(m[2])]
for fn in sys.argv[1:]:
    out=open(fn).read().split('\n'); ow=[t for l in out for t in toks(l)]
    sm=difflib.SequenceMatcher(None,gtw,ow,autojunk=False); m=sum(b.size for b in sm.get_matching_blocks())
    cl=collections.Counter(l.strip() for l in out if len(l.strip())>=25)
    print(fn,len(out),round(m/len(gtw),3),'excess dup',sum(v-1 for v in cl.values()))
