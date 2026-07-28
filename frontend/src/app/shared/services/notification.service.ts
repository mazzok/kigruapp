import { Injectable } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * App-wide user feedback via Material snackbar toasts.
 * success() = short green toast, error() = longer red toast.
 * extractError() pulls a human-readable message out of an HttpErrorResponse so
 * backend validation reasons (e.g. "invalid cron expression: ...") are surfaced
 * instead of being swallowed silently.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  constructor(private snackBar: MatSnackBar) {}

  success(message: string): void {
    this.snackBar.open(message, 'OK', {
      duration: 3000,
      panelClass: ['snack-success'],
    });
  }

  error(message: string): void {
    this.snackBar.open(message, 'OK', {
      duration: 6000,
      panelClass: ['snack-error'],
    });
  }

  /** Best-effort extraction of a readable message from an HTTP/error object. */
  extractError(err: unknown): string {
    const fallback = 'Speichern fehlgeschlagen';
    if (err instanceof HttpErrorResponse) {
      const body = err.error;
      if (typeof body === 'string' && body.trim().length > 0) {
        return body;
      }
      if (body && typeof body === 'object') {
        const message = (body as { message?: unknown }).message;
        if (typeof message === 'string' && message.trim().length > 0) {
          return message;
        }
      }
      if (typeof err.message === 'string' && err.message.trim().length > 0) {
        return err.message;
      }
    }
    if (err instanceof Error && err.message.trim().length > 0) {
      return err.message;
    }
    return fallback;
  }
}
