package org.nypl.audiobook.android.api

import org.joda.time.Duration
import rx.Observable
import javax.annotation.concurrent.ThreadSafe

/**
 * The interface exposed by sleep timer implementations.
 *
 * Implementations of this interface are required to be thread-safe. That is, methods and properties
 * may be safely called/accessed from any thread.
 */

@ThreadSafe
interface PlayerSleepTimerType : AutoCloseable {

  /**
   * Start the timer. The timer will count down over the given duration and will periodically
   * publish events giving the remaining time.
   *
   * @param time The total duration for which the timer will run
   *
   * @throws java.lang.IllegalStateException If and only if the player is closed
   */

  @Throws(java.lang.IllegalStateException::class)
  fun start(time: Duration)

  /**
   * Cancel the timer. The timer will stop and will publish an event indicating the current
   * state.
   *
   * @throws java.lang.IllegalStateException If and only if the player is closed
   */

  @Throws(java.lang.IllegalStateException::class)
  fun cancel()

  /**
   * An observable indicating the current state of the timer. The observable is buffered such
   * that each new subscription will receive the most recently published status event, and will
   * then receive new status events as they are published.
   */

  val status: Observable<PlayerSleepTimerEvent>

  /**
   * Close the timer. After this method is called, it is an error to call any of the other methods
   * in the interface.
   */

  override fun close()

  /**
   * @return `true` if the timer has been closed.
   */

  val isClosed: Boolean
}
