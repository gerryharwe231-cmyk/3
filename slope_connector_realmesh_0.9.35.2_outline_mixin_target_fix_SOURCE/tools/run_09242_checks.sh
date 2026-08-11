#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/run_static_checks.sh
python3 tests/test_panel_and_geometry_parity.py
