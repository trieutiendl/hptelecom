import hashlib
import importlib.util
import json
from pathlib import Path
import sys

helpers = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('simulator_verifier', helpers / 'verify-simulator.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
app = module.verify_product(Path(sys.argv[1]))
manifest = json.loads((helpers / 'release.json').read_text())
value = {
    'schema': 1, 'purpose': 'DIAGNOSTIC_ONLY_NOT_FOR_STORE',
    'bundleIdentifier': 'vn.hptelecom.fieldwork', 'version': '1.0.69', 'build': '2',
    'sourceSha256': manifest['sourceSha256'],
    'jsBundleSha256': hashlib.sha256((app / 'main.jsbundle').read_bytes()).hexdigest(),
    'simulatorSignatureVerified': True, 'deviceFamily': [1],
}
Path(sys.argv[2]).write_text(json.dumps(value, sort_keys=True) + '\n')
print('Verified isolated simulator product; no distributable build is produced.')
