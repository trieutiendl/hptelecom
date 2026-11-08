"""Bound a build subprocess while preserving logs and the caller's EXIT trap."""
import os
import signal
import subprocess
import sys

timeout = float(sys.argv[1])
assert 0 < timeout <= 3600 and len(sys.argv) >= 3
process = subprocess.Popen(sys.argv[2:], start_new_session=os.name == 'posix')
try:
    status = process.wait(timeout=timeout)
except subprocess.TimeoutExpired:
    print('Build subprocess exceeded its bounded timeout.', file=sys.stderr, flush=True)
    try:
        if os.name == 'posix':
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            if os.name == 'posix':
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.wait(timeout=15)
    except ProcessLookupError:
        pass
    status = 124
sys.exit(status)
