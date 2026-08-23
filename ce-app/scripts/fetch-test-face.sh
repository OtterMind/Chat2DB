#!/usr/bin/env bash
# One photograph of a face, for the auto-reframe tests.
#
# It is not committed: we do not have redistribution rights to an arbitrary
# portrait, and a repository is not the place for someone's face. The tests skip
# themselves when it is missing, so this script is only needed when you want to
# *measure* auto-reframe rather than assume it.
#
# Any frontal portrait works. Put it here:
#     tests/assets/face.jpg
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
target="$root/tests/assets/face.jpg"
mkdir -p "$(dirname "$target")"
if [ -f "$target" ]; then echo "already there: $target"; exit 0; fi
echo "Place a frontal portrait photograph at:"
echo "  $target"
echo
echo "Then run:  cd ce-app/backend && python -m pytest tests/test_reframe.py"
