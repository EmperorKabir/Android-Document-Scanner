# Deep logger

On-device diagnostic logging for debug builds only. The logger auto-starts at
process launch (App Startup) and writes a fresh NDJSON file per launch to
`/sdcard/Android/data/com.kabirbhasin.docscanner.debug/files/deeplog/`. It is
absent from release builds (a no-op variant is linked instead).

Captured without touching feature code: activity/fragment lifecycle, touch
input, dropped frames, periodic memory/GC, uncaught exceptions, session header.

## Pull and analyse

```bash
bash tools/deeplog/pull_logs.sh                       # newest session -> deeplogs/
python tools/deeplog/parse_logs.py deeplogs/<file> --summary
python tools/deeplog/parse_logs.py deeplogs/<file> --errors
python tools/deeplog/parse_logs.py deeplogs/<file> --category lifecycle.activity
```

Never open a raw session file directly — query it through `parse_logs.py`.
