import numpy as np, onnxruntime as ort, difflib
from PIL import Image, ImageDraw, ImageFont
A='/home/sandbox/vocr-android/app/src/main/assets/ocr/'
S=[
"Où êtes-vous allé hier soir après le dîner ?",
"Elle a préféré rester à la maison pour étudier.",
"Les élèves ont reçu leurs résultats ce matin.",
"Nous avons réservé une table à l'étage pour huit personnes.",
"C'est déjà l'été, et les journées sont plus longues.",
"Pourriez-vous m'envoyer le dossier avant midi ?",
"Il faut être très prudent près de la rivière.",
"Le café est fermé le dimanche, mais ouvert à Noël.",
"Mon frère travaille à Genève depuis trois ans.",
"Voilà une idée intéressante à développer ensemble.",
"Ça m'étonne qu'il n'ait pas répondu à ton message.",
"La réunion est reportée à jeudi à 14 h 30.",
"Écoute-moi bien : on ne peut pas tout régler en une journée.",
"Les prévisions météo annoncent de la pluie sur la région.",
"J'ai oublié mon téléphone chez l'ami de ma sœur.",
"Quelle belle surprise de vous retrouver ici !",
"Le projet sera livré à la fin du trimestre, sauf imprévu.",
"Où est passée la clé de la voiture garée derrière l'église ?",
"Après la pause, nous reprendrons avec l'étude de cas.",
"Cet été, ils iront à la mer avec leurs cousins.",
"Merci d'avoir répondu si rapidement à ma demande.",
"Un grand défi nous attend : réduire les délais de moitié.",
"Je suis désolé, mais je dois m'absenter quelques minutes.",
"Les créateurs ont présenté une version améliorée du logiciel.",
"Êtes-vous prêts à tout recommencer demain matin ?",
]
fonts=['/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf','/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf']
def render(t,fp,size=34):
    f=ImageFont.truetype(fp,size); w=int(f.getlength(t))+24
    im=Image.new('RGB',(w,56),'white'); ImageDraw.Draw(im).text((12,8),t,fill='black',font=f); return im
def prep(im):
    h=48; w=max(16,int(np.ceil(h*im.width/im.height))); a=np.asarray(im.resize((w,h),Image.BILINEAR),dtype=np.float32)
    return ((a/255-0.5)/0.5).transpose(2,0,1)[None]
def decode(o,chars):
    ix=o[0].argmax(1); prev=-1; s=''
    for i in ix:
        if i!=prev and i!=0: s+=chars[i]
        prev=i
    return s
keys=open(A+'keys.txt',encoding='utf8').read().split("\n")
models={'full':(A+'rec.onnx',keys),
 'enfr':('/tmp/spike2/rec_trim.onnx',open('/tmp/spike2/keys_trim.txt',encoding='utf8').read().split("\n")),
 'en':('/tmp/spike2/rec_en.onnx',open('/tmp/spike2/keys_en.txt',encoding='utf8').read().split("\n"))}
acc=set("éèêëàâùûüîïôçÉÈÀÇœ")
for name,(mp,ks) in models.items():
    s=ort.InferenceSession(mp); chars=['']+ks+[' ']
    tot=ok=0; at=ao=0; lines_exact=0; n=0
    for fp in fonts:
        for t in S:
            o=s.run(None,{s.get_inputs()[0].name:prep(render(t,fp))})[0]; d=decode(o,chars)
            sm=difflib.SequenceMatcher(None,t,d,autojunk=False); m=sum(b.size for b in sm.get_matching_blocks())
            tot+=len(t); ok+=m; n+=1; lines_exact+=(d==t)
            for ch in t:
                if ch in acc: at+=1
            sm2=sm.get_matching_blocks()
            matched=set()
            for b in sm2:
                for i in range(b.a,b.a+b.size): matched.add(i)
            ao+=sum(1 for i,ch in enumerate(t) if ch in acc and i in matched)
    print(name,'char match',round(ok/tot,4),'accented-char recall',round(ao/at,3),'(%d)'%at,'exact lines',lines_exact,'/',n)
