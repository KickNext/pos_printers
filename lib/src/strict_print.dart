import 'pos_printers.pigeon.dart';

/// Options of the opt-in strict printing mode.
///
/// The strict mode is off by default: with `PrintOptions()` every call behaves
/// exactly like the legacy methods (same native path, same exceptions). Turn it
/// on per call with `PrintOptions.strict()`, for example from a remote switch.
class PrintOptions {
  /// Legacy behaviour (default).
  const PrintOptions()
      : strict = false,
        connectAttempts = 3,
        connectBudget = const Duration(seconds: 10),
        jobTimeout = const Duration(seconds: 20),
        statusTimeout = const Duration(seconds: 3),
        checkStatusBefore = true,
        checkStatusAfter = true,
        refuseOnUnknownStatus = false,
        postStatusDelay = const Duration(milliseconds: 300);

  /// Honest status, bounded retries, outcome instead of guesses.
  const PrintOptions.strict({
    this.connectAttempts = 3,
    this.connectBudget = const Duration(seconds: 10),
    this.jobTimeout = const Duration(seconds: 20),
    this.statusTimeout = const Duration(seconds: 3),
    this.checkStatusBefore = true,
    this.checkStatusAfter = true,
    this.refuseOnUnknownStatus = false,
    this.postStatusDelay = const Duration(milliseconds: 300),
  }) : strict = true;

  final bool strict;

  /// Connection attempts. Only connecting is retried, never a write.
  final int connectAttempts;

  /// Total time for all connection attempts.
  final Duration connectBudget;

  /// Hard limit for the whole job (render, connect, status, write).
  final Duration jobTimeout;

  /// Limit for one status read.
  final Duration statusTimeout;

  /// Read the status before sending and refuse on a known fault.
  final bool checkStatusBefore;

  /// Read the status after sending and report it.
  final bool checkStatusAfter;

  /// Also refuse to send when the status is unknown or timed out.
  final bool refuseOnUnknownStatus;

  /// Pause between the last write and the status read.
  final Duration postStatusDelay;

  /// Converts to the Pigeon transfer object.
  StrictPrintOptionsDTO toDto() => StrictPrintOptionsDTO(
        connectAttempts: connectAttempts,
        connectBudgetMs: connectBudget.inMilliseconds,
        jobTimeoutMs: jobTimeout.inMilliseconds,
        statusTimeoutMs: statusTimeout.inMilliseconds,
        checkStatusBefore: checkStatusBefore,
        checkStatusAfter: checkStatusAfter,
        refuseOnUnknownStatus: refuseOnUnknownStatus,
        postStatusDelayMs: postStatusDelay.inMilliseconds,
      );
}

/// Outcome of a print call made with [PrintOptions].
///
/// In legacy mode (`PrintOptions()`) a successful call returns
/// `PrintOutcomeResult.legacySuccess()`: outcome [PrinterOutcome.unknown],
/// `sent` true, `strict` false. Legacy failures still throw, as before.
class PrintOutcomeResult {
  const PrintOutcomeResult({
    required this.outcome,
    required this.sent,
    required this.strict,
    this.stage = PrintStage.completed,
    this.statusBefore,
    this.statusAfter,
    this.bytesSent = 0,
    this.connectAttempts = 0,
    this.elapsed = Duration.zero,
    this.message,
  });

  const PrintOutcomeResult.legacySuccess()
      : outcome = PrinterOutcome.unknown,
        sent = true,
        strict = false,
        stage = PrintStage.completed,
        statusBefore = null,
        statusAfter = null,
        bytesSent = 0,
        connectAttempts = 0,
        elapsed = Duration.zero,
        message = null;

  factory PrintOutcomeResult.fromDto(PrintJobResultDTO dto) =>
      PrintOutcomeResult(
        outcome: dto.outcome,
        sent: dto.sent,
        strict: true,
        stage: dto.stage,
        statusBefore: dto.statusBefore,
        statusAfter: dto.statusAfter,
        bytesSent: dto.bytesSent,
        connectAttempts: dto.connectAttempts,
        elapsed: Duration(milliseconds: dto.elapsedMs),
        message: dto.message,
      );

  final PrinterOutcome outcome;

  /// At least one byte reached the printer: never resend automatically.
  final bool sent;
  final bool strict;
  final PrintStage stage;
  final int? statusBefore;
  final int? statusAfter;
  final int bytesSent;
  final int connectAttempts;
  final Duration elapsed;
  final String? message;

  /// The printer was ready after the job (bytes written, no fault reported).
  bool get isReady => outcome == PrinterOutcome.ready;
}
