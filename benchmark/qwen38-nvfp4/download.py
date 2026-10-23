"""Download the immutable public checkpoint and verify every file before admission."""
import hashlib
import json
from pathlib import Path
import urllib.request
from huggingface_hub import snapshot_download

HERE = Path(__file__).resolve().parent


def digest(path, algorithm='sha256', git_blob=False):
    result = hashlib.new(algorithm)
    if git_blob:
        result.update(('blob ' + str(path.stat().st_size) + '\0').encode())
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def download():
    download_model(json.loads((HERE / 'model.json').read_text()))


def download_model(model):
    root = Path(model['model_directory']).parent
    root.mkdir(parents=True, exist_ok=True)
    url = 'https://huggingface.co/api/models/' + model['repository'] + '/revision/' + model['revision'] + '?blobs=true'
    with urllib.request.urlopen(url, timeout=60) as response:
        metadata = json.load(response)
    if metadata['sha'] != model['revision']:
        raise RuntimeError('Artifact revision mismatch')
    (root / 'upstream-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    snapshot_download(repo_id=model['repository'], revision=model['revision'],
                      local_dir=model['model_directory'], max_workers=2)
    artifacts = []
    weight_bytes = 0
    for entry in metadata['siblings']:
        path = Path(model['model_directory']) / entry['rfilename']
        actual = digest(path)
        if 'lfs' in entry:
            valid = actual == entry['lfs']['sha256']
        else:
            valid = digest(path, 'sha1', git_blob=True) == entry['blobId']
        if not valid or path.stat().st_size != entry['size']:
            raise RuntimeError('Artifact mismatch: ' + entry['rfilename'])
        if path.suffix == '.safetensors':
            weight_bytes += path.stat().st_size
        artifacts.append({'rfilename': entry['rfilename'], 'lfs': {'sha256': actual}})
        print('Verified', entry['rfilename'], flush=True)
    if weight_bytes != model['weight_bytes']:
        raise RuntimeError('Weight size differs from budget declaration')
    destination = Path(model['artifact_manifest'])
    temporary = destination.with_suffix('.tmp')
    temporary.write_text(json.dumps({'sha': metadata['sha'], 'siblings': artifacts}, indent=2) + '\n')
    temporary.replace(destination)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, default=HERE / 'model.json')
    args = parser.parse_args()
    download_model(json.loads(args.model.read_text()))
