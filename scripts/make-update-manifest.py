"""Create the companion Release manifest for an already verified, consistently signed APK."""
from pathlib import Path
import argparse
import hashlib
import json
import re

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--version-code', type=int, required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--output', type=Path, default=Path('update.json'))
args = parser.parse_args()
assert args.apk.name == 'LocalPhoneAgent-automation.apk'
assert 0 < args.version_code < 2**31 and re.fullmatch(r'\d+\.\d+\.\d+', args.version)
with args.apk.open('rb') as stream:
    sha = hashlib.file_digest(stream, 'sha256').hexdigest()
args.output.write_text(json.dumps({'schemaVersion': 1, 'packageName': 'dev.localphone.agent', 'channel': 'automation',
    'versionCode': args.version_code, 'versionName': args.version + '-automation', 'minSdk': 26,
    'apk': {'name': args.apk.name, 'bytes': args.apk.stat().st_size, 'sha256': sha}}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Created ' + str(args.output))
