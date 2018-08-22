package org.nypl.audiobook.android.api

import org.joda.time.Duration
import org.nypl.audiobook.android.api.PlayerSleepTimer.PlayerTimerRequest.PlayerTimerRequestClose
import org.nypl.audiobook.android.api.PlayerSleepTimer.PlayerTimerRequest.PlayerTimerRequestFinish
import org.nypl.audiobook.android.api.PlayerSleepTimer.PlayerTimerRequest.PlayerTimerRequestStart
import org.nypl.audiobook.android.api.PlayerSleepTimer.PlayerTimerRequest.PlayerTimerRequestStop
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerCancelled
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerFinished
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerRunning
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerStopped
import org.nypl.audiobook.android.api.PlayerSleepTimerType.Running
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.BehaviorSubject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.concurrent.ThreadSafe

/**
 * The primary implementation of the {@link PlayerSleepTimerType} interface.
 *
 * The implementation is thread-safe, and a given instance may be used from any thread.
 */

@ThreadSafe
class PlayerSleepTimer private constructor(
  private val statusEvents: BehaviorSubject<PlayerSleepTimerEvent>,
  private val executor: ExecutorService)
  : PlayerSleepTimerType {

  /**
   * The type of requests that can be made to the timer.
   */

  private sealed class PlayerTimerRequest {

    /**
     * Request that the timer be closed.
     */

    object PlayerTimerRequestClose : PlayerTimerRequest()

    /**
     * Request that the timer finish (as if the duration had elapsed).
     */

    object PlayerTimerRequestFinish : PlayerTimerRequest()

    /**
     * Request that the timer start now and count down over the given duration. If the timer
     * is already running, the timer is restarted.
     */

    class PlayerTimerRequestStart(
      val duration: Duration?)
      : PlayerTimerRequest()

    /**
     * Request that the timer stop.
     */

    object PlayerTimerRequestStop : PlayerTimerRequest()
  }

  private val log: Logger = LoggerFactory.getLogger(PlayerSleepTimer::class.java)
  private val closed: AtomicBoolean = AtomicBoolean(false)
  private val taskFuture: Future<*>
  private val task: PlayerSleepTimerTask
  private val requests: ArrayBlockingQueue<PlayerTimerRequest> = ArrayBlockingQueue(16)

  init {
    this.log.debug("starting initial task")
    this.task = PlayerSleepTimerTask(this)
    this.taskFuture = this.executor.submit(this.task)
    this.log.debug("waiting for task to start")
    this.task.latch.await()
  }

  /**
   * A sleep timer task that runs for as long as the sleep timer exists. The task is
   * terminated when the sleep timer is closed.
   */

  private class PlayerSleepTimerTask(
    private val timer: PlayerSleepTimer) : Runnable {

    internal val latch: CountDownLatch = CountDownLatch(1)

    private val log: Logger = LoggerFactory.getLogger(PlayerSleepTimerTask::class.java)

    @Volatile
    internal var running: Running? = null
    private val oneSecond = Duration.standardSeconds(1L)

    init {
      this.log.debug("created timer task")
    }

    override fun run() {
      this.log.debug("starting main task")
      this.latch.countDown()

      try {
        this.timer.statusEvents.onNext(PlayerSleepTimerStopped)

        initialRequestWaiting@ while (true) {
          try {
            this.running = null
            this.log.debug("waiting for timer requests")

            /*
             * Wait indefinitely (or at least until the thread is interrupted) for an initial
             * request.
             */

            var initialRequest: PlayerTimerRequest?
            try {
              initialRequest = this.timer.requests.take()
            } catch (e: InterruptedException) {
              initialRequest = null
            }

            if (this.timer.isClosed) {
              return
            }

            when (initialRequest) {
              null, PlayerTimerRequestClose -> {
                return
              }

              is PlayerTimerRequestStart -> {
                this.log.debug("received start request: {}", initialRequest.duration)
                this.running = Running(initialRequest.duration)
                this.timer.statusEvents.onNext(PlayerSleepTimerRunning(initialRequest.duration))
              }

              PlayerTimerRequestStop -> {
                this.log.debug("received (redundant) stop request")
                this.timer.statusEvents.onNext(PlayerSleepTimerStopped)
                continue@initialRequestWaiting
              }

              PlayerTimerRequestFinish -> {
                this.log.debug("received finish request")
                this.timer.statusEvents.onNext(PlayerSleepTimerFinished)
                continue@initialRequestWaiting
              }
            }

            /*
             * The timer is now running. Wait in a loop for requests. Time out waiting after a second
             * each time in order to decrement the remaining time.
             */

            processingTimerRequests@ while (true) {

              var request: PlayerTimerRequest?
              try {
                request = this.timer.requests.poll(1L, TimeUnit.SECONDS)
              } catch (e: InterruptedException) {
                request = null
              }

              when (request) {
                null -> {
                  val currentRemaining = this.running?.duration
                  if (currentRemaining != null) {
                    val newRemaining = currentRemaining.minus(this.oneSecond)
                    this.running = Running(newRemaining)
                    this.timer.statusEvents.onNext(PlayerSleepTimerRunning(newRemaining))

                    if (newRemaining.isShorterThan(this.oneSecond)) {
                      this.log.debug("timer finished")
                      this.timer.statusEvents.onNext(PlayerSleepTimerFinished)
                      continue@initialRequestWaiting
                    }
                  }
                }

                PlayerTimerRequestClose -> {
                  return
                }

                is PlayerTimerRequestStart -> {
                  this.log.debug("restarting timer")
                  this.running = Running(request.duration)
                  this.timer.statusEvents.onNext(PlayerSleepTimerRunning(request.duration))
                  continue@processingTimerRequests
                }

                PlayerTimerRequestStop -> {
                  this.log.debug("stopping timer")
                  this.timer.statusEvents.onNext(PlayerSleepTimerCancelled(this.running?.duration))
                  this.timer.statusEvents.onNext(PlayerSleepTimerStopped)
                  continue@initialRequestWaiting
                }

                PlayerTimerRequestFinish -> {
                  this.log.debug("received finish request")
                  this.timer.statusEvents.onNext(PlayerSleepTimerFinished)
                  continue@initialRequestWaiting
                }
              }
            }
          } catch (e: Exception) {
            this.log.error("error processing request: ", e)
          }
        }
      } finally {
        this.log.debug("stopping main task")
        this.log.debug("completing status events")
        this.timer.statusEvents.onNext(PlayerSleepTimerStopped)
        this.timer.statusEvents.onCompleted()
      }
    }
  }

  companion object {

    private val log: Logger = LoggerFactory.getLogger(PlayerSleepTimer::class.java)

    /**
     * Create a new sleep timer.
     */

    fun create(): PlayerSleepTimerType {
      return PlayerSleepTimer(
        executor = Executors.newFixedThreadPool(1) { run -> this.createTimerThread(run) },
        statusEvents = BehaviorSubject.create())
    }

    /**
     * Create a thread suitable for use with the ExoPlayer audio engine.
     */

    private fun createTimerThread(r: Runnable?): Thread {
      val thread = PlayerSleepTimerThread(r ?: Runnable { })
      this.log.debug("created timer thread: {}", thread.name)
      thread.setUncaughtExceptionHandler { t, e ->
        this.log.error("uncaught exception on engine thread {}: ", t, e)
      }
      return thread
    }
  }

  private fun checkIsNotClosed() {
    if (this.isClosed) {
      throw IllegalStateException("Timer has been closed")
    }
  }

  override fun start(time: Duration?) {
    this.checkIsNotClosed()
    this.requests.offer(PlayerTimerRequestStart(time), 10L, TimeUnit.MILLISECONDS)
  }

  override fun cancel() {
    this.checkIsNotClosed()
    this.requests.offer(PlayerTimerRequestStop, 10L, TimeUnit.MILLISECONDS)
  }

  override fun finish() {
    this.checkIsNotClosed()
    this.requests.offer(PlayerTimerRequestFinish, 10L, TimeUnit.MILLISECONDS)
  }

  override fun close() {
    if (this.closed.compareAndSet(false, true)) {
      this.taskFuture.cancel(true)
      this.executor.shutdown()
      this.requests.offer(PlayerTimerRequestClose, 10L, TimeUnit.MILLISECONDS)
    }
  }

  override val isClosed: Boolean
    get() = this.closed.get()

  override val status: Observable<PlayerSleepTimerEvent> =
    this.statusEvents.distinctUntilChanged()

  override val isRunning: Running?
    get() = this.task.running

}
