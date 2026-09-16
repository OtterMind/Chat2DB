#!/usr/bin/env python3
"""Exercise publication against a local gh fixture; never contact GitHub."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'publish_beta_update_index.sh'
GH = '''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
root = pathlib.Path(os.environ['GH_FIXTURE'])
args = sys.argv[1:]
with (root / 'calls').open('a') as log:
    log.write(json.dumps(args) + '\\n')
if os.environ.get('FAIL_GH') == args[1]:
    sys.exit(1)
assert args[0] == 'release'
if args[1] == 'list':
    if (root / 'exists').exists(): print('community-beta')
elif args[1] == 'view':
    if (root / 'remote.json').exists(): print('release-index.json')
elif args[1] == 'create':
    assert '--prerelease' in args and '--latest=false' in args
    (root / 'exists').touch()
elif args[1] == 'download':
    shutil.copyfile(root / 'remote.json', pathlib.Path(args[args.index('--dir') + 1]) / 'release-index.json')
elif args[1] == 'upload':
    shutil.copyfile(args[3], root / 'remote.json')
else:
    sys.exit(2)
'''


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='beta-index-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gh').write_text(GH)
        (self.root / 'gh').chmod(0o755)
        self.index = self.root / 'release-index.json'

    def publish(self, epoch, channel='BETA', fail=''):
        self.index.write_text(json.dumps({
            'schemaVersion': 2, 'channel': channel, 'status': 'ACTIVE',
            'releaseEpoch': epoch, 'releases': [{'version': f'5.3.7-beta.{epoch}'}],
        }))
        return subprocess.run(
            ['bash', str(SCRIPT), str(self.index), 'a' * 40],
            env={**os.environ, 'PATH': f'{self.root}:{os.environ["PATH"]}',
                 'GH_FIXTURE': str(self.root), 'FAIL_GH': fail},
            capture_output=True, text=True,
        )

    def test_first_publish_and_higher_epoch(self):
        self.assertEqual(0, self.publish(1).returncode)
        self.assertTrue((self.root / 'exists').exists())
        self.assertEqual(0, self.publish(2).returncode)
        self.assertEqual(2, json.loads((self.root / 'remote.json').read_text())['releaseEpoch'])

    def test_replay_is_idempotent_and_older_epoch_is_rejected(self):
        self.assertEqual(0, self.publish(2).returncode)
        original = (self.root / 'remote.json').read_bytes()
        self.assertEqual(0, self.publish(2).returncode)
        self.assertNotEqual(0, self.publish(1).returncode)
        self.assertEqual(original, (self.root / 'remote.json').read_bytes())
        calls = [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]
        self.assertEqual(1, sum(call[1] == 'upload' for call in calls))

    def test_conflicting_epoch_does_not_replace_index(self):
        self.assertEqual(0, self.publish(2).returncode)
        remote = json.loads((self.root / 'remote.json').read_text())
        remote['releases'][0]['version'] = '5.3.8-beta.1'
        (self.root / 'remote.json').write_text(json.dumps(remote))
        self.assertNotEqual(0, self.publish(2).returncode)
        self.assertEqual(remote, json.loads((self.root / 'remote.json').read_text()))

    def test_invalid_channel_and_sequence_do_not_write(self):
        for epoch, channel in [(1, 'STABLE'), (0, 'BETA'), (1.5, 'BETA')]:
            self.assertNotEqual(0, self.publish(epoch, channel).returncode)
        self.assertFalse((self.root / 'calls').exists())

    def test_network_failure_does_not_create_channel(self):
        self.assertNotEqual(0, self.publish(1, fail='list').returncode)
        self.assertFalse((self.root / 'exists').exists())
        self.assertFalse((self.root / 'remote.json').exists())

    def test_existing_channel_without_asset_can_publish(self):
        (self.root / 'exists').touch()
        self.assertEqual(0, self.publish(1).returncode)


if __name__ == '__main__':
    unittest.main()
