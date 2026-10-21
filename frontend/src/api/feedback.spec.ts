import { describe, expect, it } from 'vitest';
import type { FeedbackAttachmentSummary, FeedbackDetail } from './feedback';
import apiSource from './feedback.ts?raw';

const compact = (source: string) => source.replace(/\s+/g, '');

const governedDetailFixtures = [
  {
    id: 1,
    sourceChannel: 'jira',
    status: 'NEW',
    reportedAt: null,
    channelMeta: {
      jira_key: null,
      jira_status_raw: null,
      resolved_at: null,
    },
    navigation: { previousId: null },
    clusterSummary: null,
    aiResult: { summary: null, confidence: null },
    fieldHistory: [{ id: 9, fieldName: null }],
    attachments: [{ id: 8, mimeType: null }],
  },
  {
    id: 2,
    sourceChannel: 'station_feedback',
    status: 'PROCESSING',
    reportedAt: '2026-08-29T18:00:00',
    channelMeta: {
      product_id: null,
      ingest_status: null,
      processing_stage: null,
    },
  },
  {
    id: 3,
    sourceChannel: 'sdk',
    status: 'RESOLVED',
    reportedAt: null,
    channelMeta: null,
  },
] satisfies FeedbackDetail[];

const governedAttachmentFixtures = [
  { id: 8, mimeType: null, fileSizeBytes: null },
  { id: 9 },
] satisfies FeedbackAttachmentSummary[];

describe('feedback governed attachment transport', () => {
  it('types explicit nulls and field-policy omissions in governed detail responses', () => {
    expect(governedDetailFixtures).toHaveLength(3);
  });

  it('types explicit nulls and field-policy omissions in standalone attachment responses', () => {
    expect(governedAttachmentFixtures).toHaveLength(2);
  });

  it('allows a caller-bounded timeout, cancellation and download progress', () => {
    expect(apiSource).toContain('timeoutMs?: number');
    expect(apiSource).toContain('signal?: AbortSignal');
    expect(apiSource).toContain(
      'onProgress?: (loaded: number, total?: number) => void',
    );
    expect(apiSource).toContain('timeout: options?.timeoutMs ?? 0');
    expect(apiSource).toContain('signal: options?.signal');
    expect(apiSource).toContain('silent: true');
    expect(compact(apiSource)).toContain(
      compact(
        'onDownloadProgress: (event: AxiosProgressEvent) => options?.onProgress?.(event.loaded, event.total)',
      ),
    );
    expect(apiSource).toContain('Promise<AttachmentAccessGrant>');
    expect(apiSource).toContain('Promise<FeedbackDiagnosticsResponse>');
  });
});
