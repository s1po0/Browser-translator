"""Reproduce Sipan's Latin refinement model from the pinned PP-OCRv6 Small ONNX export.

Requires numpy and onnx (used only to prepare assets, not an app dependency).
Usage: python3 tools/prepare-comic-recognizer.py PP-OCRv6_rec_small.onnx OUTPUT_DIR
"""
import hashlib
from pathlib import Path
import sys
import unicodedata

import onnx
from onnx import numpy_helper

SOURCE_SHA256 = '6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884'


def latin_token(token):
    if len(token) != 1:
        return False
    folded = unicodedata.normalize('NFKC', token)
    return (token in '‘’“”«»…–—‐‑−×÷°©®™•' or unicodedata.category(token) == 'Sc'
            or 33 <= ord(token) <= 126 or 'LATIN' in unicodedata.name(token, '')
            or len(folded) == 1 and 33 <= ord(folded) <= 126)


def prepare(source, out):
    blob = source.read_bytes()
    if hashlib.sha256(blob).hexdigest() != SOURCE_SHA256:
        sys.exit('Unexpected source model SHA-256')
    model = onnx.load_from_string(blob)
    upstream = next(prop.value for prop in model.metadata_props if prop.key == 'character').splitlines()
    assert len(upstream) == 18708 and ' ' not in upstream
    alphabet = [''] + upstream + [' ']
    keep = [0] + [i for i, token in enumerate(alphabet) if latin_token(token)] + [len(alphabet) - 1]
    # Same convolutional encoder and CTC head. Slice the final classifier's columns for Latin-only refinement.
    for tensor in model.graph.initializer:
        if tensor.name in ('linear_8.w_0', 'linear_8.b_0'):
            values = numpy_helper.to_array(tensor)
            assert values.shape[-1] == len(alphabet)
            tensor.CopyFrom(numpy_helper.from_array(values[..., keep], tensor.name))
    for value in [*model.graph.output, *model.graph.value_info]:
        shape = value.type.tensor_type.shape.dim
        if len(shape) == 3 and shape[2].dim_value == len(alphabet):
            shape[2].dim_value = len(keep)
    del model.metadata_props[:]
    model.doc_string = 'PP-OCRv6 Small Latin-only CTC refinement for Sipan. Encoder unchanged; sliced classifier.'
    onnx.checker.check_model(model)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'rec.onnx').write_bytes(model.SerializeToString(deterministic=True))
    (out / 'alphabet.txt').write_text(''.join(alphabet[i] + '\n' for i in keep[1:-1]), encoding='utf-8')
    print(f'{len(keep)} classes including CTC blank and space')
    for path in sorted(out.glob('*')):
        print(path.name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == '__main__':
    prepare(Path(sys.argv[1]), Path(sys.argv[2]))
