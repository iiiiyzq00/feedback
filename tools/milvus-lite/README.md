# Local Milvus Lite

Milvus Lite is local infrastructure. Python only starts the Lite process; all collection, vector, and search business operations stay in the Kotlin/JDK 17 backend through the Milvus Java SDK.

```bash
./tools/milvus-lite/setup.sh
./tools/milvus-lite/start.sh
./tools/milvus-lite/status.sh
./tools/milvus-lite/stop.sh
```

Defaults: version `2.5.1`, address `127.0.0.1:19530`, database `data/feedback.db`. Override with `MILVUS_LITE_ADDRESS`, `MILVUS_LITE_DB_FILE`, `MILVUS_LITE_VENV_DIR`, or `MILVUS_LITE_RUN_DIR`.
