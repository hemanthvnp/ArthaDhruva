import sys
from pathlib import Path

# The scripts under test live in backend/ and import each other as top-level modules.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
