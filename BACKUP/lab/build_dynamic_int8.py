import onnx
from onnx import numpy_helper as nh
A='/home/sandbox/vocr-android/app/src/main/assets/ocr/'
m=onnx.load(A+'rec.onnx'); g=m.graph
keep=[]; n_conv=0
for n in g.node:
    if n.op_type=='Constant' and n.attribute and n.attribute[0].t.dims and n.attribute[0].t.ByteSize()>0:
        t=onnx.TensorProto(); t.CopyFrom(n.attribute[0].t); t.name=n.output[0]; g.initializer.append(t); n_conv+=1
    else: keep.append(n)
del g.node[:]; g.node.extend(keep)
onnx.save(m,'rec_init.onnx'); print('converted',n_conv)
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic('rec_init.onnx','rec_dynmm.onnx',weight_type=QuantType.QInt8,op_types_to_quantize=['MatMul'])
import onnxruntime as ort, numpy as np
s=ort.InferenceSession('rec_dynmm.onnx'); print(s.run(None,{s.get_inputs()[0].name:np.zeros((1,3,48,160),np.float32)})[0].shape)
