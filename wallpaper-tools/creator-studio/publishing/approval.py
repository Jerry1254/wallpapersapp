"""Consume a short-lived, private operator approval for one exact local task.

No approval is inferred from a post's originality setting. The file is created
only after the operator reviews the native agreement for the named task.
"""
import hashlib
import json
from pathlib import Path
import time
import uuid
import os


def scope(job):
    return hashlib.sha256(json.dumps({
        'id': job['id'], 'accountId': job['accountId'],
        'platformUserId': job['platformUserId'], 'platform': job['platform'],
        'post': job['post'], 'agreement': 'xhs-originality',
    }, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def consume(runtime, job):
    path = Path(runtime) / 'operator-approvals' / (str(uuid.UUID(job['id'])) + '.json')
    if not path.exists() or path.is_symlink():
        return False
    if path.stat().st_mode & 0o077:
        return False
    try:
        value = json.loads(path.read_text())
        valid = (value.get('scope') == scope(job)
                 and isinstance(value.get('expiresAt'), (int, float))
                 and time.time() < value['expiresAt'] <= time.time() + 900)
    except (ValueError, KeyError, TypeError):
        return False
    if valid:
        # Remove the one-use approval before interacting with the agreement.
        path.unlink()
    return valid


def issue(runtime, job):
    if job.get('platform') != 'xhs' or job.get('post', {}).get('originality') != 'original':
        raise ValueError('Only an XHS originality agreement can be approved')
    folder = Path(runtime) / 'operator-approvals'
    folder.mkdir(mode=0o700, exist_ok=True)
    os.chmod(folder, 0o700)
    path = folder / (str(uuid.UUID(job['id'])) + '.json')
    temporary = folder / (str(uuid.uuid4()) + '.tmp')
    with temporary.open('x') as target:
        os.chmod(temporary, 0o600)
        json.dump({'scope': scope(job), 'expiresAt': time.time() + 900}, target)
    temporary.replace(path)
