"""Download pinned public Korean ASR assets. Never downloads user data or private models."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.request

project = Path(__file__).resolve().parent.parent
destination = project / 'app/src/main/assets/speech-model-ko'
destination.mkdir(parents=True, exist_ok=True)
revision = 'ba6078bca4daf3f0dd37f79d0ab505af71df14a6'
base = f'https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/{revision}/'
names = ['encoder-epoch-99-avg-1.int8.onnx', 'decoder-epoch-99-avg-1.onnx', 'joiner-epoch-99-avg-1.int8.onnx', 'tokens.txt', 'README.md']
items = [(base + name, destination / name) for name in names]
items.append(('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.20/sherpa-onnx-1.12.20.aar',
              project / 'app/libs/sherpa-onnx-1.12.20.aar'))
license_file = project / 'app/src/main/assets/licenses/Sherpa-Apache-2.0.txt'
items.append(('https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.12.20/LICENSE', license_file))
upstream_card = project / 'app/src/main/assets/licenses/Korean-model-card.md'
upstream_url = 'https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12/raw/55c8e681ffc8d74b862a84282e73e99a2ca0a60c/README.md'
items.append((upstream_url, upstream_card))

def download(item):
    url, path = item
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_name(path.name + '.download')
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as target:
            while chunk := source.read(1024 * 1024): target.write(chunk)
        temporary.replace(path)
    result = {'file': path.relative_to(project).as_posix(), 'source_url': url,
              'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    print(json.dumps({'file': path.name, 'bytes': result['bytes']}), flush=True)
    return result

with ThreadPoolExecutor(max_workers=4) as pool: files = list(pool.map(download, items))
(project / 'speech-model.json').write_text(json.dumps({
    'name': 'sherpa-onnx-streaming-zipformer-korean-2024-06-16', 'runtime': 'sherpa-onnx 1.12.20',
    'revision': revision, 'license': 'Apache-2.0', 'upstream_model_card': upstream_url,
    'catalog_url': 'https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html',
    'checksum_note': 'SHA-256 computed locally, not publisher signatures', 'files': files,
}, indent=2) + '\n', encoding='utf-8')
(license_file.parent / 'NOTICE.txt').write_text(
    'Local Korean speech recognition: sherpa-onnx by the k2-fsa contributors (Apache-2.0).\n'
    'Model converted by k2-fsa from John Bamma, trained with icefall on KsponSpeech.\n'
    'Upstream model is Apache-2.0; see Korean-model-card.md. Model files are unchanged.\n'
    'https://github.com/k2-fsa/sherpa-onnx\n', encoding='utf-8')
