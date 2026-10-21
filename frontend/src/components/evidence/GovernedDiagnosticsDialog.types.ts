import type {
  FeedbackDiagnosticItem,
  FeedbackDiagnosticsResponse,
} from '@/api/feedback';

/** GovernedDiagnosticsDialog 通过 defineExpose 提供给父组件的公开契约。 */
export interface GovernedDiagnosticsDialogExpose {
  clear: () => void;
  open: (
    sourceId: number,
    initial?: FeedbackDiagnosticsResponse,
  ) => Promise<void>;
  openLog: (
    sourceId: number,
    item: FeedbackDiagnosticItem,
    initial: FeedbackDiagnosticsResponse,
  ) => Promise<void>;
  openLogDirect: (
    sourceId: number,
    item: FeedbackDiagnosticItem,
  ) => Promise<void>;
}
