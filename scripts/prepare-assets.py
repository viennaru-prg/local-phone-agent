"""Fetch the pinned local STT inputs omitted from Git; verify every file before replacing it."""
from pathlib import Path
import hashlib
import json
import shutil
import urllib.request

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'speech-model.json').read_text(encoding='utf-8'))
for entry in manifest['files']:
    target = (root / entry['file']).resolve()
    assert target.is_relative_to(root) and entry['source_url'].startswith('https://')
    def matches(path):
        if not path.is_file() or path.stat().st_size != entry['bytes']:
            return False
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest() == entry['sha256']
    if matches(target):
        print('Verified ' + entry['file'], flush=True)
        continue
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.part')
    try:
        print('Fetching ' + entry['file'], flush=True)
        with urllib.request.urlopen(entry['source_url'], timeout=60) as source, temporary.open('wb') as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
        assert matches(temporary), 'Pinned asset checksum mismatch: ' + entry['file']
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
