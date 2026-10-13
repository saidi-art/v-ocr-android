# Builds /tmp/msgs.txt from the ground-truth chat text (stored privately in the user's Drive, NOT in this repo).
import re
gt=open('/tmp/gt.txt').read()
parts=re.split(r'(?m)^(?=[A-Z][a-z]+day, October \d+, 2026, )',gt)
msgs=[]
for p in parts:
    m=re.match(r'[A-Za-z]+, October (\d+), 2026, (\d+:\d+:\d+ [AP]M) CEST \[(\w+)\] ?(.*)',p,re.S)
    if m: msgs.append((m.group(2),m.group(3),m.group(4).strip()))
open('/tmp/msgs.txt','w').write(repr(msgs))
