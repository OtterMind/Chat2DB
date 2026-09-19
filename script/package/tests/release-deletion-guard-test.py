#!/usr/bin/env python3
"""Guard the release workflows and packaging scripts against destructive release commands.

Deleting a published release destroys its tag: an immutable release keeps the tag name
reserved, so that channel can never be recreated under the same name. Deleting a release
tag has the same effect. Both operations are forbidden here.
"""
import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = sorted((ROOT / '.github' / 'workflows').glob('*.yml'))
SCRIPTS = sorted((ROOT / 'script' / 'package').glob('*.sh'))

FORBIDDEN = (
    (re.compile(r'\bgh\s+release\s+delete\b'), 'gh release delete'),
    (re.compile(r'\bgh\s+api\b[^\n]*(-X|--method)[ =]DELETE[^\n]*/releases'), 'gh api DELETE releases'),
    (re.compile(r'\bgit\s+push\b[^\n]*--delete[^\n]*refs/tags/'), 'git push --delete refs/tags'),
)


def scanned_files():
    return [path for path in WORKFLOWS + SCRIPTS if path.is_file()]


def violations():
    found = []
    for path in scanned_files():
        text = path.read_text(encoding='utf-8')
        for pattern, label in FORBIDDEN:
            for match in pattern.finditer(text):
                line = text.count('\n', 0, match.start()) + 1
                found.append(f'{path.relative_to(ROOT)}:{line}: {label}')
    return found


class ReleaseDeletionGuardTest(unittest.TestCase):
    def test_workflows_and_scripts_are_discovered(self):
        names = {path.name for path in scanned_files()}
        self.assertIn('jcef_release.yml', names)
        self.assertIn('ci.yml', names)

    def test_no_destructive_release_commands(self):
        self.assertEqual([], violations(), 'destructive release commands are forbidden')


if __name__ == '__main__':
    unittest.main()
