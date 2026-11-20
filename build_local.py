"""Builds TalkText-debug.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner. Runs the logic tests first."""
from pathlib import Path
import shutil, subprocess, sys, zipfile, xml.etree.ElementTree as ET

VERSION_CODE, VERSION_NAME = '20', '0.19'
root = Path(__file__).resolve().parent
tools = Path(r'C:\Users\AdrianRADUARTINOX\Desktop\WORK\My\Lynk&Co900_engeneering\apk_translation_study\ro_4.2.8\tools')
bt = tools / 'sdk-build-tools/android-15'
platform = tools / 'sdk-platform/android-35/android.jar'
jdk = Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin')
out = root / 'local-build'
shutil.rmtree(out, ignore_errors=True)
for name in ['classes', 'dex', 'gen', 'test']: (out / name).mkdir(parents=True)

def run(args):
    print('RUN', Path(str(args[0])).name, flush=True)
    subprocess.run([str(x) for x in args], check=True, cwd=root)

java = root / 'app/src/main/java/ro/talktext'
run([jdk / 'javac.exe', '-encoding', 'UTF-8', '-d', out / 'test', java / 'Rules.java', java / 'Chunker.java', java / 'Spacing.java', java / 'KeyLayout.java', java / 'Lexicon.java', java / 'Corrector.java', java / 'ClipHistory.java', java / 'Personal.java', root / 'tests/RulesTest.java', root / 'tests/CorrectorTest.java'])
run([jdk / 'java.exe', '-cp', out / 'test', 'ro.talktext.RulesTest'])
run([jdk / 'java.exe', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', out / 'test', 'ro.talktext.CorrectorTest'])

run([bt / 'aapt2.exe', 'compile', '--dir', root / 'app/src/main/res', '-o', out / 'resources.zip'])
ns = 'http://schemas.android.com/apk/res/android'; ET.register_namespace('android', ns)
tree = ET.parse(root / 'app/src/main/AndroidManifest.xml'); m = tree.getroot()
m.set('package', 'ro.talktext'); m.set('{%s}versionCode' % ns, VERSION_CODE); m.set('{%s}versionName' % ns, VERSION_NAME)
m.insert(0, ET.Element('uses-sdk', {'{%s}minSdkVersion' % ns: '33', '{%s}targetSdkVersion' % ns: '35'}))
tree.write(out / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run([bt / 'aapt2.exe', 'link', '-o', out / 'resources.apk', '-I', platform, '--manifest', out / 'AndroidManifest.xml', '--java', out / 'gen', out / 'resources.zip'])

sources = list((root / 'app/src/main/java').rglob('*.java')) + list((out / 'gen').rglob('*.java'))
run([jdk / 'javac.exe', '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-Xlint:-options', '-classpath', platform, '-d', out / 'classes', *sources])
with zipfile.ZipFile(out / 'classes.jar', 'w') as z:
    for p in (out / 'classes').rglob('*.class'): z.write(p, p.relative_to(out / 'classes').as_posix())
run([jdk / 'java.exe', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--lib', platform, '--min-api', '33', '--output', out / 'dex', out / 'classes.jar'])

with zipfile.ZipFile(out / 'resources.apk') as source, zipfile.ZipFile(out / 'unsigned.apk', 'w') as target:
    for i in source.infolist(): target.writestr(i, source.read(i.filename))
    for p in (out / 'dex').glob('*.dex'): target.write(p, p.name)
run([bt / 'zipalign.exe', '-f', '4', out / 'unsigned.apk', out / 'aligned.apk'])

# Personal debug key (keep debug.keystore: future versions must be signed with it to install as updates).
ks = root / 'debug.keystore'
apk = root / 'TalkText-debug.apk'
run([jdk / 'java.exe', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, out / 'aligned.apk'])
run([jdk / 'java.exe', '-jar', bt / 'lib/apksigner.jar', 'verify', apk])
run([bt / 'aapt.exe', 'dump', 'badging', apk])
print('APK:', apk, flush=True)

# Only `python build_local.py --release` archives; working builds never touch ../Versiuni.
# Bump VERSION_* above for each release.
archive = root.parent / 'Versiuni'
archive.mkdir(exist_ok=True)
target = archive / f'TalkText-{VERSION_NAME}.apk'
if '--release' not in sys.argv:
    print('Build de lucru (nearhivat). Pentru arhivare: python build_local.py --release', flush=True)
elif target.exists() and target.read_bytes() != apk.read_bytes():
    # A released version is never overwritten: bump VERSION_CODE / VERSION_NAME first.
    print('NEARHIVAT: exista deja', target.name, '- mareste numarul versiunii inainte de o versiune noua.', flush=True)
else:
    shutil.copyfile(apk, target)
    print('Arhivat:', target, flush=True)
