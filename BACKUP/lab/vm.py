import numpy as np
W,H=720,1560;fs=W*H*3
mm=np.memmap('/tmp/f2/f50.rgb',dtype=np.uint8,mode='r');N=len(mm)//fs
print('frames',N)
def g(i):
    a=np.asarray(mm[i*fs:(i+1)*fs]).reshape(H,W,3)[::2,::2].astype(np.float32).mean(2);return a  # 780x360
B=30 # band rows (60px)
step=1
res=[];shifts=[]
for i in range(0,N-step,6):
    a,b=g(i),g(i+step)
    best=None
    for s in range(-300,301,2):
        if s>=0:d=np.abs(a[s:]-b[:len(b)-s]) 
        else:d=np.abs(a[:s]-b[-s:])
        m=d.mean()
        if best is None or m<best[0]:best=(m,s)
    m,s=best
    if s>=0:d=np.abs(a[s:]-b[:len(b)-s])
    else:d=np.abs(a[:s]-b[-s:])
    bands=[d[j:j+B].mean() for j in range(0,len(d)-B+1,B)][:20]
    res.append(bands);shifts.append(s)
shifts_a=np.array(shifts);res=np.array([r for r in res if len(r)==20])
print('pairs',len(res),'shifts(px at 1/2 res)',shifts)
print('overall resid mean',round(res.mean(),2),'max band',round(res.max(),2))
for th in (8,12,20,30):print('band residual >',th,'-> masked bands',int((res>th).sum()),'of',res.size,'=',round(100*(res>th).mean(),2),'%')
