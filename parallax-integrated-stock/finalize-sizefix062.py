from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

replacements = [
    ("private static final long MESH_GLOBAL_TRIANGLE_BUDGET = 1_200_000L;",
     "private static final long MESH_GLOBAL_TRIANGLE_BUDGET = 300_000L;"),
    ("private static final long MESH_CENTER_TRIANGLE_BUDGET = 600_000L;",
     "private static final long MESH_CENTER_TRIANGLE_BUDGET = 150_000L;"),
    ("private static final long MESH_SECONDARY_TRIANGLE_BUDGET = 150_000L;",
     "private static final long MESH_SECONDARY_TRIANGLE_BUDGET = 40_000L;"),
    ('\\"zstd_level\\": 0, \\"zstd_storage\\": \\"raw-block-frame\\"',
     '\\"zstd_level\\": 3, \\"zstd_storage\\": \\"compressed-stream-frame\\"'),
]

for old, new in replacements:
    if old in s:
        s = s.replace(old, new)
    elif new not in s:
        raise SystemExit(f"Expected source pattern missing: {old}")

p.write_text(s, encoding="utf-8")

assert "MESH_GLOBAL_TRIANGLE_BUDGET = 300_000L" in s
assert "MESH_CENTER_TRIANGLE_BUDGET = 150_000L" in s
assert "MESH_SECONDARY_TRIANGLE_BUDGET = 40_000L" in s
assert "raw-block-frame" not in s
assert s.count("compressed-stream-frame") >= 2
assert "ZstdOutputStream" in s
print("Final sizefix applied: real Zstd + 300k global triangle cap")
