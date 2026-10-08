"""Fetch exact model weights for a FULL dual-model APK; never download at app runtime.
Gemma requires an account approved on its model page. Use `hf auth login` locally;
CI can use an explicitly configured HF_TOKEN secret. Credentials are never printed.
"""
from pathlib import Path
import hashlib
import json
import os
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'agent-models.json').read_text(encoding='utf-8'))
class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = super().redirect_request(req, fp, code, msg, headers, newurl)
        if target and urllib.parse.urlparse(newurl).hostname != 'huggingface.co': target.remove_header('Authorization')
        return target
opener = urllib.request.build_opener(SafeRedirect())
for model in manifest['models']:
    destination = (root / 'app/src/main/assets' / model['asset']).resolve()
    assert destination.is_relative_to(root / 'app/src/main/assets/agent_models')
    def matches(path):
        if not path.is_file() or path.stat().st_size != model['bytes']: return False
        with path.open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest() == model['sha256']
    if matches(destination): print('Verified ' + model['id'], flush=True); continue
    headers = {'User-Agent': 'LocalPhoneAgent-model-build'}
    if model['id'] == 'functiongemma':
        cache = Path(os.environ.get('HF_HOME', str(Path.home() / '.cache/huggingface'))) / 'token'
        token = os.getenv('HF_TOKEN') or (cache.read_text().strip() if cache.is_file() else '')
        if token: headers['Authorization'] = 'Bearer ' + token
    filename = model['asset'].split('/')[-1]
    url = f"https://huggingface.co/{model['repository']}/resolve/{model['revision']}/{filename}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.part')
    try:
        print('Fetching licensed, pinned ' + model['id'], flush=True)
        with opener.open(urllib.request.Request(url, headers=headers), timeout=90) as response, temporary.open('wb') as out:
            copied = 0
            while chunk := response.read(4 * 1024 * 1024):
                copied += len(chunk); assert copied <= model['bytes'], 'Weight size exceeds pinned input'
                out.write(chunk)
        assert matches(temporary), 'Pinned SHA-256/size mismatch: ' + model['id']
        temporary.replace(destination)
        print('Verified ' + model['id'], flush=True)
    except urllib.error.HTTPError as failure:
        raise SystemExit(f"{model['id']}: HTTP {failure.code}. Obtain authorized model access; no empty-model APK will be built.")
    finally:
        temporary.unlink(missing_ok=True)
