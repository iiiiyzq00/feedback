-- Complete AI-derived fields without adding tables; the governed table count remains 42.
ALTER TABLE t_feedback_ai
  ADD COLUMN ai_application VARCHAR(100) DEFAULT NULL AFTER ai_summary,
  ADD COLUMN ai_function_module VARCHAR(100) DEFAULT NULL AFTER ai_application,
  ADD COLUMN ai_sentiment VARCHAR(20) DEFAULT NULL AFTER ai_category,
  ADD COLUMN ai_risk_type VARCHAR(30) DEFAULT NULL AFTER ai_sentiment,
  ADD COLUMN data_sources JSON DEFAULT NULL AFTER evidence_codes;
