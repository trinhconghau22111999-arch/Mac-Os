import glob, os, re, subprocess, zipfile, sys
out = []
def P(s=''): out.append(s)

apk = glob.glob('app/build/outputs/apk/release/*.apk')[0]
z = zipfile.ZipFile(apk)
P('APK: %s (%.1f MB)' % (os.path.basename(apk), os.path.getsize(apk)/1048576))
abis = {}
for i in z.infolist():
    if i.filename.startswith('lib/') and i.filename.count('/') >= 2:
        abis.setdefault(i.filename.split('/')[1], []).append(i.filename.split('/')[-1])
for a, l in abis.items(): P('lib/%s: %d tệp (%s)' % (a, len(l), ', '.join(sorted(l)[:8])))
if not abis: P('KHÔNG có thư mục lib/ trong APK')

# mapping.txt của R8
mp = 'app/build/outputs/mapping/release/mapping.txt'
cls = {}
if os.path.exists(mp):
    for line in open(mp, encoding='utf-8', errors='ignore'):
        if not line.startswith(' ') and ' -> ' in line and line.rstrip().endswith(':'):
            a, b = line.rstrip()[:-1].split(' -> ')
            cls[a] = b
P('mapping.txt: %d lớp' % len(cls))
def chk(name):
    if name not in cls: return 'BỊ XÓA (không còn trong APK)'
    return 'giữ nguyên tên' if cls[name] == name else 'BỊ ĐỔI TÊN -> ' + cls[name]
for n in ['org.mozilla.gecko.GeckoThread','org.mozilla.gecko.GeckoAppShell','org.mozilla.gecko.mozglue.GeckoLoader','org.mozilla.gecko.mozglue.JNIObject','org.mozilla.geckoview.GeckoRuntime','org.mozilla.geckoview.GeckoSession','org.mozilla.geckoview.GeckoView','com.desktopmini.launcher.BrowserHost','com.desktopmini.launcher.MainActivity$Bridge','com.google.crypto.tink.aead.AesGcmKey','androidx.webkit.WebViewCompat']:
    P('  %-62s %s' % (n, chk(n)))

# so sánh với toàn bộ lớp trong AAR GeckoView
aars = glob.glob(os.path.expanduser('~/.gradle/caches/**/geckoview-omni-*.aar'), recursive=True)
P('AAR GeckoView: %s' % (aars[0] if aars else 'không tìm thấy'))
if aars:
    za = zipfile.ZipFile(aars[0])
    P('proguard.txt trong AAR: ' + ('CÓ' if 'proguard.txt' in za.namelist() else 'KHÔNG'))
    if 'proguard.txt' in za.namelist():
        P('--- nội dung proguard.txt (đầu) ---')
        P(za.read('proguard.txt').decode('utf-8', 'ignore')[:1500])
    import io
    zj = zipfile.ZipFile(io.BytesIO(za.read('classes.jar')))
    names = [n[:-6].replace('/', '.') for n in zj.namelist() if n.endswith('.class')]
    g = [n for n in names if n.startswith(('org.mozilla.gecko', 'org.mozilla.geckoview'))]
    missing = [n for n in g if n not in cls]
    renamed = [n for n in g if n in cls and cls[n] != n]
    P('Lớp Gecko trong AAR: %d | còn trong APK: %d | bị xóa: %d | bị đổi tên: %d' % (len(g), len(g)-len(missing), len(missing), len(renamed)))
    P('Ví dụ lớp bị đổi tên: ' + ', '.join(renamed[:8]))
    P('Ví dụ lớp bị xóa: ' + ', '.join(missing[:8]))
text = '\n'.join(out)
print(text)
open('diag-report.txt', 'w', encoding='utf-8').write(text)
msg = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::notice title=BAO CAO R8::' + msg[:60000])
