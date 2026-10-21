ALTER TABLE t_channel_quality
  ADD COLUMN return_order_no VARCHAR(100) DEFAULT NULL COMMENT '来源商返单号；可读业务定位键' AFTER source_id,
  ADD COLUMN product_name VARCHAR(200) DEFAULT NULL COMMENT '来源产品名称' AFTER return_order_no,
  ADD COLUMN product_model VARCHAR(100) DEFAULT NULL COMMENT '来源产品型号' AFTER product_name,
  ADD COLUMN dispatch_date DATE DEFAULT NULL COMMENT '来源派单日期' AFTER product_model,
  ADD COLUMN activation_date DATE DEFAULT NULL COMMENT '来源激活日期' AFTER dispatch_date;

ALTER TABLE t_feedback_identity DROP CHECK chk_identity_type;
ALTER TABLE t_feedback_identity
  ADD CONSTRAINT chk_identity_type CHECK (identity_type IN ('PHONE','EMAIL','DEVICE_SN','USER_ID'));

INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order)
VALUES('action:view_feedback_diagnostics','受控查看反馈诊断身份','BUTTON','FEEDBACK','POST /api/v1/feedback/{id}/diagnostics/authorize',686)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
  ('ADMIN','action:view_feedback_diagnostics'),
  ('OPERATOR','action:view_feedback_diagnostics');
