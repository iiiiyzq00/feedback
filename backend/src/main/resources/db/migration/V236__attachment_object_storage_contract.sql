-- P1-A：为附件增加 S3 兼容对象存储位置与生命周期事实。
-- 本迁移只增加可空结构，不回填、不切换现有 storage_path 读写链路，也不删除本地文件。

ALTER TABLE t_feedback_attachment
  ADD COLUMN storage_backend VARCHAR(24) DEFAULT NULL
    COMMENT '权威对象存储后端；P1-A 仅允许 S3_COMPATIBLE' AFTER storage_path,
  ADD COLUMN object_bucket VARCHAR(63) DEFAULT NULL
    COMMENT '对象存储桶名称；不是公开 URL' AFTER storage_backend,
  ADD COLUMN object_key VARCHAR(1024) DEFAULT NULL
    COMMENT '对象键；不得保存应用服务器本地路径或永久公开 URL' AFTER object_bucket,
  ADD COLUMN object_version VARCHAR(1024) DEFAULT NULL
    COMMENT '对象版本标识；未启用版本控制时为空' AFTER object_key,
  ADD COLUMN object_etag VARCHAR(128) DEFAULT NULL
    COMMENT '对象存储返回的 ETag；仅用于完整性与幂等对账' AFTER object_version,
  ADD COLUMN object_storage_status VARCHAR(24) DEFAULT NULL
    COMMENT '对象生命周期状态：PENDING/QUARANTINED/AVAILABLE/DELETE_PENDING/DELETED/FAILED' AFTER object_etag,
  ADD COLUMN object_migrated_at DATETIME(3) DEFAULT NULL
    COMMENT '旧存储内容成功切换为对象存储权威事实的业务时区时间；P1-A 不回填' AFTER object_storage_status,
  ADD CONSTRAINT chk_attachment_storage_backend CHECK
    (storage_backend IS NULL OR storage_backend = 'S3_COMPATIBLE'),
  ADD CONSTRAINT chk_attachment_object_storage_status CHECK
    (object_storage_status IS NULL OR object_storage_status IN
      ('PENDING','QUARANTINED','AVAILABLE','DELETE_PENDING','DELETED','FAILED')),
  ADD CONSTRAINT chk_attachment_object_location CHECK
    ((storage_backend IS NULL
        AND object_bucket IS NULL
        AND object_key IS NULL
        AND object_version IS NULL
        AND object_etag IS NULL
        AND object_storage_status IS NULL
        AND object_migrated_at IS NULL)
      OR
      (storage_backend = 'S3_COMPATIBLE'
        AND object_bucket IS NOT NULL
        AND object_bucket <> ''
        AND object_key IS NOT NULL
        AND object_key <> ''
        AND object_storage_status IS NOT NULL)),
  ADD KEY idx_attachment_object_storage_work
    (storage_backend, object_storage_status, id);
