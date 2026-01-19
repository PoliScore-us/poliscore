package us.poliscore.ai;

public final class MinuteRateLimiter {

  private final long rpm;
  private final long tpm;

  private long windowStartMs;
  private long requestsUsed;
  private long tokensUsed;

  public MinuteRateLimiter(long rpm, long tpm) {
    this.rpm = Math.max(1, rpm);
    this.tpm = Math.max(1, tpm);
    this.windowStartMs = System.currentTimeMillis();
  }

  /**
   * Reserves capacity for 1 request and tokensToUse tokens.
   * Blocks until capacity is available.
   */
  public void acquire(int tokensToUse) throws InterruptedException {
    tokensToUse = Math.max(0, tokensToUse);

    synchronized (this) {
      while (true) {
        rollWindowIfNeeded();

        boolean okRequests = requestsUsed + 1 <= rpm;
        boolean okTokens = tokensUsed + tokensToUse <= tpm;

        if (okRequests && okTokens) {
          requestsUsed += 1;
          tokensUsed += tokensToUse;
          return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMs;
        long waitMs = Math.max(50L, 60_000L - elapsed); // wake promptly at window boundary
        this.wait(waitMs);
      }
    }
  }

  private void rollWindowIfNeeded() {
    long now = System.currentTimeMillis();
    long elapsed = now - windowStartMs;

    if (elapsed >= 60_000L) {
      // Advance by whole minutes to avoid drift if we were paused for a while.
      long minutes = elapsed / 60_000L;
      windowStartMs += minutes * 60_000L;

      requestsUsed = 0;
      tokensUsed = 0;

      // Wake waiting threads; they’ll re-check and reserve.
      this.notifyAll();
    }
  }
}
