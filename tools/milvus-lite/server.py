#!/usr/bin/env python3
"""Minimal infrastructure launcher for Milvus Lite; contains no business logic."""

import argparse
import signal
import threading
from pathlib import Path

from milvus_lite.server import Server


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--address", default="127.0.0.1:19530")
    args = parser.parse_args()

    db_path = Path(args.db).expanduser().resolve()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    server = Server(str(db_path), args.address)
    if not server.init() or not server.start():
        raise RuntimeError("MILVUS_LITE_START_FAILED")

    stopped = threading.Event()

    def shutdown(_signum: int, _frame: object) -> None:
        stopped.set()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        stopped.wait()
    finally:
        server.stop()


if __name__ == "__main__":
    main()
