"""Publishes the built release APK as a GitHub release, in the format the app's updater reads.

usage: python scripts/release.py <version e.g. 1.0.7> <notes.md> [core tests passed]

Before: versionCode/versionName bumped in app/build.gradle.kts, committed, tag v<version> pushed, and
`gradlew :app:assembleRelease` built. Assets: LocalPhoneAgent.apk, update-agent.json, SHA256SUMS.txt,
validation.json, LocalPhoneAgent-source.zip. The GitHub token comes from git's credential manager and
is never printed.
"""
import hashlib, json, os, subprocess, sys, tempfile, urllib.request, urllib.error

REPO = 'viennaru-prg/local-phone-agent'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIGNER = '7f93e9c7c975879fd07fb5921140589c1dfafe182cdd1c1bb8c25215acb22727'

version, notes = sys.argv[1], sys.argv[2]
tests = int(sys.argv[3]) if len(sys.argv) > 3 else None
gradle =open(os.path.join(ROOT, 'app', 'build.gradle.kts'), encoding='utf-8').read()
code = int(gradle.split('versionCode = ')[1].split()[0])
assert f'versionName = "{version}"' in gradle, 'build.gradle.kts versionName differs'
tag = f'v{version}'
out = tempfile.mkdtemp(prefix=f'release-{version}-')

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def git(*args):
    return subprocess.run(['git', '-C', ROOT, *args], capture_output=True, text=True, check=True).stdout.strip()

apk = os.path.join(out, 'LocalPhoneAgent.apk')
with open(os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'), 'rb') as src, open(apk, 'wb') as dst:
    dst.write(src.read())
source = os.path.join(out, 'LocalPhoneAgent-source.zip')
git('archive', '--format=zip', '-o', source, tag)
manifest = {"schemaVersion": 1, "packageName": "dev.localphone.agent", "channel": "agent-v1", "versionCode": code,
            "versionName": version, "minSdk": 30,
            "apk": {"name": "LocalPhoneAgent.apk", "bytes": os.path.getsize(apk), "sha256": sha(apk)}}
with open(os.path.join(out, 'update-agent.json'), 'w', encoding='utf-8', newline='\n') as f:
    json.dump(manifest, f, indent=2); f.write('\n')
validation = {"versionName": version, "versionCode": code, "sourceCommit": git('rev-list', '-n', '1', tag),
              "packageName": "dev.localphone.agent", "apkSha256": sha(apk), "apkBytes": os.path.getsize(apk),
              "sourceSha256": sha(source), "signerSha256": SIGNER, "sameSignerAsPreviousAgentReleases": True,
              "coreTestsPassed": tests, "androidTestsRun": False,
              "updates": "install by USB (adb install -r); the in-app update is blocked by Google's verifier"}
with open(os.path.join(out, 'validation.json'), 'w', encoding='utf-8', newline='\n') as f:
    json.dump(validation, f, indent=2, ensure_ascii=False); f.write('\n')
names = ['LocalPhoneAgent.apk', 'update-agent.json', 'LocalPhoneAgent-source.zip', 'validation.json']
with open(os.path.join(out, 'SHA256SUMS.txt'), 'w', encoding='utf-8', newline='\n') as f:
    for n in names:
        f.write(f"{sha(os.path.join(out, n))}  {n}\n")

cred = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
                      capture_output=True, text=True, check=True).stdout
token = dict(line.split('=', 1) for line in cred.splitlines() if '=' in line).get('password')

def call(url, data=None, method='GET', ctype='application/json'):
    req = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json', 'Content-Type': ctype,
        'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'local-phone-agent-release'})
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read().decode()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f'{method} {url} -> {e.code} {e.read().decode()[:300]}')

body = open(notes, encoding='utf-8').read()
title = body.splitlines()[0].lstrip('# ').strip() if body.startswith('#') else f'Local Phone Agent {version}'
if body.startswith('#'):
    body = '\n'.join(body.splitlines()[1:]).lstrip()
release = call(f'https://api.github.com/repos/{REPO}/releases', json.dumps(
    {'tag_name': tag, 'name': title, 'body': body, 'draft': False, 'prerelease': False}).encode(), 'POST')
upload = release['upload_url'].split('{')[0]
types = {'.apk': 'application/vnd.android.package-archive', '.json': 'application/json', '.txt': 'text/plain', '.zip': 'application/zip'}
for n in ['LocalPhoneAgent.apk', 'update-agent.json', 'SHA256SUMS.txt', 'validation.json', 'LocalPhoneAgent-source.zip']:
    with open(os.path.join(out, n), 'rb') as f:
        call(f'{upload}?name={n}', f.read(), 'POST', types[os.path.splitext(n)[1]])
latest = call(f'https://api.github.com/repos/{REPO}/releases/latest')['tag_name']
print(release['html_url'])
print('latest:', latest, '| apk sha256:', manifest['apk']['sha256'])
print('apk:', apk)
