import onnx, numpy as np
from onnx import numpy_helper as nh
A='/home/sandbox/vocr-android/app/src/main/assets/ocr/'
keys=open(A+'keys.txt',encoding='utf8').read().split("\n")
chars=['']+keys+[' ']
def head(m):
    g=m.graph
    def find(name):
        for t in g.initializer:
            if t.name==name: return ('i',t)
        for n in g.node:
            if n.output[0]==name and n.op_type=='Constant': return ('c',n)
    return g,find
def arr(x): return nh.to_array(x[1]) if x[0]=='i' else nh.to_array(x[1].attribute[0].t)
def setv(x,a,name):
    t=nh.from_array(a.astype(np.float32),name)
    if x[0]=='i': x[1].CopyFrom(t)
    else: x[1].attribute[0].t.CopyFrom(t)
def trim(keep_chars, out_onnx, out_keys):
    m=onnx.load(A+'rec.onnx'); g,find=head(m)
    w=find('linear_85.w_0'); b=find('linear_85.b_0')
    ks=set(keep_chars)
    keep=[0]+[i for i,c in enumerate(chars) if 0<i<len(chars)-1 and c in ks]+[len(chars)-1]
    W=arr(w)[:,keep]; B=arr(b)[keep]
    setv(w,W,'linear_85.w_0'); setv(b,B,'linear_85.b_0')
    g.output[0].type.tensor_type.shape.dim[2].dim_value=len(keep)
    onnx.save(m,out_onnx)
    open(out_keys,'w',encoding='utf8').write("\n".join(chars[i] for i in keep[1:-1]))
    print(out_onnx,len(keep),'classes')
ascii_=[c for c in chars if c and all(ord(ch)<128 for ch in c)]
trim(ascii_,'rec_en.onnx','keys_en.txt')
fr=set("ËéèÜä»É®™üà•¥—…±§×°·“”‘’")
trim(ascii_+list(fr),'rec_trim.onnx','keys_trim.txt')
