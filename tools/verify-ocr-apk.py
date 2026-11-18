"""Verify shipped OCR assets, runtime ABIs and ELF alignment. Run with Python 3 and binutils."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

EXPECTED = {
    'det.onnx': '193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8',
    'rec.onnx': '9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6',
    'alphabet.txt': 'c5cbe34ef40c29c4df07ed012bf96569cb69a2d2a01a07027e9f13cb832bd9cd',
}
NATIVE = ('libonnxruntime.so', 'libonnxruntime4j_jni.so')
ABIS = ('arm64-v8a', 'x86_64')


def alignments(path):
    lines = subprocess.check_output(['readelf', '-lW', path], text=True).splitlines()
    return [int(parts[-1], 16) for line in lines if (parts := line.split()) and parts[0] == 'LOAD']


def verify(apk):
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as directory:
        root = 'assets/ocr/ppocrv6-tiny/'
        for name, expected in EXPECTED.items():
            digest = hashlib.sha256(archive.read(root + name)).hexdigest()
            if digest != expected:
                sys.exit(f'Invalid OCR asset {name}: {digest}')
        for notice in ('ppocrv6-tiny/LICENSE.txt', 'ppocrv6-tiny/NOTICE.txt', 'onnxruntime/LICENSE.txt', 'onnxruntime/ThirdPartyNotices.txt'):
            archive.getinfo('assets/ocr/' + notice)
        members = [item for item in archive.infolist() if item.filename.startswith('lib/') and item.filename.endswith('.so')]
        names = {item.filename for item in members}
        total = sum(item.compress_size for item in members if item.filename.split('/')[-1] in NATIVE)
        present = sorted({path.split('/')[1] for path in names})
        for abi in ABIS:
            for library in NATIVE:
                path = f'lib/{abi}/{library}'
                if path not in names:
                    sys.exit(f'Missing ONNX runtime {path}')
                elf = archive.extract(path, directory)
                segments = alignments(elf)
                if not segments or min(segments) < 16384:
                    sys.exit(f'Unsupported page alignment in {path}: {segments}')
        existing = []
        for path in sorted(names):
            _, abi, library = path.split('/')
            if abi not in ABIS or library in NATIVE:
                continue
            segments = alignments(archive.extract(path, directory))
            if not segments or min(segments) < 16384:
                existing.append(path)
    print(f'- `{apk.name}`: {apk.stat().st_size:,} bytes ({apk.stat().st_size / 2**20:.1f} MiB)')
    print(f'- APK ABIs: {", ".join(present)}; compressed ONNX Runtime libraries: {total:,} bytes')
    print('- PP-OCRv6 weights/dictionary match pins. ONNX Runtime arm64-v8a and x86_64 ELF LOADs align to 16 KiB.')
    if existing:
        print(f'- Other 64-bit libraries without 16 KiB alignment: {", ".join(existing)}')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
