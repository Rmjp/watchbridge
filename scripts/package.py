"""Package source + APK for copying to a Mac, excluding SDKs, caches, secrets and APK analysis."""
from pathlib import Path
import zipfile,hashlib,json
root=Path(__file__).resolve().parent.parent
dist=root/'dist';dist.mkdir(exist_ok=True)
apk=root/'android/app/build/outputs/apk/debug/app-debug.apk'
if not apk.exists(): raise SystemExit('Build the debug APK first')
out=dist/'WatchBridge-0.2-debug.apk';out.write_bytes(apk.read_bytes())
excluded={'build','.gradle','.build','xcuserdata','__pycache__','data'}
with zipfile.ZipFile(dist/'WatchBridge-0.2-source.zip','w',zipfile.ZIP_DEFLATED) as z:
 for name in ['android','watchos','server','protocol','test-vectors','scripts','docs','.github']:
  for p in (root/name).rglob('*'):
   if p.is_file() and not (set(p.relative_to(root).parts)&excluded) and p.name!='local.properties' and not p.name.endswith(('.sqlite','.sqlite-wal','.sqlite-shm','.p8','.jks')):
    z.write(p,'WatchBridge/'+p.relative_to(root).as_posix())
 for name in ['README.md','COMPANION_APP_PLAN.md','.gitignore']:
  z.write(root/name,'WatchBridge/'+name)
 z.write(out,'WatchBridge/dist/'+out.name)
manifest={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in [out,dist/'WatchBridge-0.2-source.zip']}
(dist/'SHA256.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps(manifest,indent=2))
