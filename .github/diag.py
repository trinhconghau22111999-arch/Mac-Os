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

# ======== PHẦN 2: kiểm tra sâu ========
out2 = []
def Q(s=''): out2.append(s)
Q('--- lib/x86 đầy đủ ---')
Q(', '.join(sorted(abis.get('x86', []))))
Q('libxul.so: ' + ('CÓ' if 'libxul.so' in abis.get('x86', []) else 'KHÔNG'))

# thành viên (trường / hàm) trong mapping
members = {}
cur = None
for line in open(mp, encoding='utf-8', errors='ignore'):
    if not line.startswith(' ') and line.rstrip().endswith(':') and ' -> ' in line:
        cur = line.rstrip()[:-1].split(' -> ')[0]; members[cur] = []
    elif cur and line.startswith('    ') and ' -> ' in line:
        members[cur].append(line.strip())
def ren_members(c):
    n = 0
    for m in members.get(c, []):
        left, right = m.split(' -> ')
        nm = left.split(' ')[-1].split('(')[0]
        if nm != right.strip(): n += 1
    return n, len(members.get(c, []))

Q('--- JNI: lớp có chú thích @WrapForJNI ---')
if aars:
    ann = [n for n in names if n in set(g) and b'WrapForJNI' in zj.read(n.replace('.', '/') + '.class')]
    a_removed = [n for n in ann if n not in cls]
    a_renamed = [n for n in ann if n in cls and cls[n] != n]
    Q('Lớp WrapForJNI: %d | bị xóa: %d | bị đổi tên: %d' % (len(ann), len(a_removed), len(a_renamed)))
    Q('Bị xóa: ' + ', '.join(a_removed[:15]))
    Q('Bị đổi tên: ' + ', '.join(a_renamed[:15]))
    # thành viên bị đổi tên trong lớp WrapForJNI còn giữ tên
    bad = []
    for n in ann:
        if n in cls and cls[n] == n:
            r, t = ren_members(n)
            if r: bad.append('%s (%d/%d hàm/trường bị đổi tên)' % (n, r, t))
    Q('Lớp WrapForJNI giữ tên nhưng có thành viên bị đổi tên: %d' % len(bad))
    Q('; '.join(bad[:12]))
    pg = za.read('proguard.txt').decode('utf-8', 'ignore')
    Q('--- dòng liên quan JNI trong proguard.txt của Gecko ---')
    Q('\n'.join(l for l in pg.split('\n') if 'WrapForJNI' in l or 'JNI' in l or 'keep @' in l)[:1500] or '(không có dòng nào nhắc WrapForJNI)')

Q('--- androidx.webkit / boundary interface ---')
bc = [c for c in cls if c.startswith('org.chromium.support_lib_boundary')]
Q('Lớp boundary: %d | bị đổi tên: %d' % (len(bc), sum(1 for c in bc if cls[c] != c)))
tot_r = sum(ren_members(c)[0] for c in bc)
Q('Hàm/trường boundary bị đổi tên: %d' % tot_r)
Q('--- Tink proto (EncryptedSharedPreferences) ---')
for c in ['com.google.crypto.tink.proto.AesGcmKey', 'com.google.crypto.tink.proto.KeysetInfo']:
    if c in members:
        fl = [m for m in members[c] if '(' not in m][:6]
        Q('%s -> %s | trường: %s' % (c, cls.get(c), ' ; '.join(fl)))
t2 = '\n'.join(out2)
print(t2)
open('diag-report2.txt', 'w', encoding='utf-8').write(t2)
print('::notice title=BAO CAO R8 PHAN 2::' + t2.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')[:60000])

# ======== PHẦN 3: lớp giao tiếp WebView (Duyệt web) ========
out3 = []
def R(s=''): out3.append(s)
R('--- Chi tiết org.chromium.support_lib_boundary ---')
for c in sorted(bc):
    r, t = ren_members(c)
    if cls[c] != c or r:
        R('%s | lớp: %s | thành viên đổi tên: %d/%d' % (c, 'ĐỔI TÊN' if cls[c] != c else 'giữ', r, t))
ifc = [c for c in bc if c.endswith('BoundaryInterface')]
R('Số interface *BoundaryInterface: %d | trong đó có thành viên bị đổi tên: %d' % (len(ifc), sum(1 for c in ifc if ren_members(c)[0])))
for n in ['androidx.webkit.WebViewClientCompat','androidx.webkit.ProcessGlobalConfig','androidx.webkit.WebViewFeature','androidx.webkit.internal.WebViewGlueCommunicator','androidx.webkit.WebMessageListener','androidx.webkit.JavaScriptReplyProxy']:
    R('  %-58s %s' % (n, chk(n)))
t3 = '\n'.join(out3)
print(t3)
print('::notice title=BAO CAO R8 PHAN 3::' + t3.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')[:60000])
