from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


SCRIPT = Path(__file__).with_name("audit_token_usage.py")


class AuditTokenUsageTest(unittest.TestCase):
    def test_reports_active_sessions_without_double_counting_provider_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            codex_home = Path(directory)
            sessions = codex_home / "sessions"
            archived = codex_home / "archived_sessions"
            sessions.mkdir()
            archived.mkdir()
            now = datetime.now(timezone.utc)
            old = now - timedelta(days=10)
            active = sessions / "active.jsonl"
            inactive = archived / "inactive.jsonl"
            active.write_text(
                "\n".join(
                    json.dumps(event)
                    for event in (
                        self.event(now, "session_meta", {"id": "session-a", "model_provider": "provider-a"}),
                        self.event(now, "session_meta", {"id": "session-a", "model_provider": "provider-a"}),
                        self.event(now, "context_compacted"),
                        self.token_event(now, 120_000, 110_000, 400),
                    )
                ),
                encoding="utf-8",
            )
            inactive.write_text(json.dumps(self.token_event(old, 50_000, 40_000, 100)), encoding="utf-8")

            output = subprocess.check_output(
                ["python3", str(SCRIPT), "--days", "1", "--codex-home", str(codex_home), "--json"],
                text=True,
            )
            report = json.loads(output)

            self.assertEqual(report["files"], 1)
            self.assertEqual(report["scanned_files"], 2)
            self.assertEqual(report["providers"], {"provider-a": 1})
            self.assertEqual(report["top_sessions"][0]["session_id"], "session-a")
            self.assertEqual(report["top_sessions"][0]["compactions"], 1)
            self.assertEqual(report["top_sessions"][0]["max_input_tokens"], 120_000)

    @staticmethod
    def event(timestamp: datetime, event_type: str, extra: dict | None = None) -> dict:
        return {
            "timestamp": timestamp.isoformat(),
            "payload": {"type": event_type, **(extra or {})},
        }

    @classmethod
    def token_event(cls, timestamp: datetime, input_tokens: int, cached_tokens: int, output_tokens: int) -> dict:
        return cls.event(
            timestamp,
            "token_count",
            {
                "info": {
                    "last_token_usage": {
                        "input_tokens": input_tokens,
                        "cached_input_tokens": cached_tokens,
                        "output_tokens": output_tokens,
                    }
                }
            },
        )


if __name__ == "__main__":
    unittest.main()
