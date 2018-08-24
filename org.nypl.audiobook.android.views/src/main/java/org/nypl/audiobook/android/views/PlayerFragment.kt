package org.nypl.audiobook.android.views

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatter
import org.joda.time.format.PeriodFormatterBuilder
import org.nypl.audiobook.android.api.PlayerAudioBookType
import org.nypl.audiobook.android.api.PlayerEvent
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventPlaybackRateChanged
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventChapterCompleted
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventChapterWaiting
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventPlaybackBuffering
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventPlaybackPaused
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventPlaybackProgressUpdate
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventPlaybackStarted
import org.nypl.audiobook.android.api.PlayerEvent.PlayerEventWithSpineElement.PlayerEventPlaybackStopped
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerCancelled
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerFinished
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerRunning
import org.nypl.audiobook.android.api.PlayerSleepTimerEvent.PlayerSleepTimerStopped
import org.nypl.audiobook.android.api.PlayerSleepTimerType
import org.nypl.audiobook.android.api.PlayerSpineElementType
import org.nypl.audiobook.android.api.PlayerType
import org.slf4j.LoggerFactory
import rx.Subscription
import java.util.concurrent.TimeUnit

/**
 * A player fragment.
 *
 * New instances MUST be created with {@link #newInstance()} rather than calling the constructor
 * directly. The public constructor only exists because the Android API requires it.
 *
 * Activities hosting this fragment MUST implement the {@link org.nypl.audiobook.android.views.PlayerFragmentListenerType}
 * interface. An exception will be raised if this is not the case.
 */

class PlayerFragment : android.support.v4.app.Fragment() {

  companion object {

    val parametersKey = "org.nypl.audiobook.android.views.PlayerFragment.parameters"

    @JvmStatic
    fun newInstance(parameters: PlayerFragmentParameters): PlayerFragment {
      val args = Bundle()
      args.putSerializable(parametersKey, parameters)
      val fragment = PlayerFragment()
      fragment.arguments = args
      return fragment
    }
  }

  private lateinit var listener: PlayerFragmentListenerType
  private lateinit var player: PlayerType
  private lateinit var book: PlayerAudioBookType
  private lateinit var sleepTimer: PlayerSleepTimerType
  private lateinit var coverView: ImageView
  private lateinit var playerTitleView: TextView
  private lateinit var playerAuthorView: TextView
  private lateinit var playPauseButton: ImageView
  private lateinit var playerSkipForwardButton: ImageView
  private lateinit var playerSkipBackwardButton: ImageView
  private var playerPositionDragging: Boolean = false
  private lateinit var playerPosition: SeekBar
  private lateinit var playerTimeCurrent: TextView
  private lateinit var playerTimeMaximum: TextView
  private lateinit var playerSpineElement: TextView
  private lateinit var playerWaiting: TextView
  private lateinit var menuPlaybackRate: MenuItem
  private lateinit var menuPlaybackRateText: TextView
  private lateinit var menuSleep: MenuItem
  private lateinit var menuSleepText: TextView
  private lateinit var menuSleepEndOfChapter: ImageView
  private lateinit var menuTOC: MenuItem
  private lateinit var parameters: PlayerFragmentParameters

  private var playerPositionCurrentSpine: PlayerSpineElementType? = null
  private var playerPositionCurrentOffset: Int = 0
  private var playerEventSubscription: Subscription? = null
  private var playerSleepTimerEventSubscription: Subscription? = null

  private val log = LoggerFactory.getLogger(PlayerFragment::class.java)

  private val hourMinuteSecondFormatter: PeriodFormatter =
    PeriodFormatterBuilder()
      .printZeroAlways()
      .minimumPrintedDigits(2)
      .appendHours()
      .appendLiteral(":")
      .appendMinutes()
      .appendLiteral(":")
      .appendSeconds()
      .toFormatter()

  private val minuteSecondFormatter: PeriodFormatter =
    PeriodFormatterBuilder()
      .printZeroAlways()
      .minimumPrintedDigits(2)
      .appendMinutes()
      .appendLiteral(":")
      .appendSeconds()
      .toFormatter()

  override fun onCreate(state: Bundle?) {
    this.log.debug("onCreate")

    super.onCreate(state)

    this.parameters =
      this.arguments!!.getSerializable(parametersKey)
        as PlayerFragmentParameters

    /*
     * This fragment wants an options menu.
     */

    this.setHasOptionsMenu(true)
  }

  override fun onAttach(context: Context) {
    this.log.debug("onAttach")
    super.onAttach(context)

    if (context is PlayerFragmentListenerType) {
      this.listener = context
      this.player = this.listener.onPlayerWantsPlayer()
      this.book = this.listener.onPlayerTOCWantsBook()
      this.sleepTimer = this.listener.onPlayerWantsSleepTimer()
    } else {
      throw ClassCastException(
        StringBuilder(64)
          .append("The activity hosting this fragment must implement one or more listener interfaces.\n")
          .append("  Activity: ")
          .append(context::class.java.canonicalName)
          .append('\n')
          .append("  Required interface: ")
          .append(PlayerFragmentListenerType::class.java.canonicalName)
          .append('\n')
          .toString())
    }
  }

  override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    this.log.debug("onCreateOptionsMenu")

    super.onCreateOptionsMenu(menu, inflater)

    inflater.inflate(R.menu.player_menu, menu)

    this.menuPlaybackRate = menu.findItem(R.id.player_menu_playback_rate)
    this.menuPlaybackRate.actionView.setOnClickListener { this.onMenuPlaybackRateSelected() }
    this.menuPlaybackRateText = this.menuPlaybackRate.actionView.findViewById(R.id.player_menu_playback_rate_text)
    this.menuPlaybackRateText.text = PlayerPlaybackRateAdapter.textOfRate(this.player.playbackRate)

    this.menuSleep = menu.findItem(R.id.player_menu_sleep)
    this.menuSleep.actionView.setOnClickListener { this.onMenuSleepSelected() }
    this.menuSleepText = this.menuSleep.actionView.findViewById(R.id.player_menu_sleep_text)
    this.menuSleepText.text = ""
    this.menuSleepText.visibility = INVISIBLE
    this.menuSleepEndOfChapter = this.menuSleep.actionView.findViewById(R.id.player_menu_sleep_end_of_chapter)
    this.menuSleepEndOfChapter.visibility = INVISIBLE

    this.menuTOC = menu.findItem(R.id.player_menu_toc)
    this.menuTOC.setOnMenuItemClickListener { this.onMenuTOCSelected() }

    /*
     * Subscribe to player and timer events. We do the subscription here (as late as possible)
     * so that all of the views (including the options menu) have been created before the first
     * event is received.
     */

    this.playerEventSubscription =
      this.player.events.subscribe(
        { event -> this.onPlayerEvent(event) },
        { error -> this.onPlayerError(error) },
        { this.onPlayerEventsCompleted() })

    this.playerSleepTimerEventSubscription =
      this.sleepTimer.status.subscribe(
        { event -> this.onPlayerSleepTimerEvent(event) },
        { error -> this.onPlayerSleepTimerError(error) },
        { this.onPlayerSleepTimerEventsCompleted() })
  }

  private fun onPlayerSleepTimerEventsCompleted() {
    this.log.debug("onPlayerSleepTimerEventsCompleted")
  }

  private fun onPlayerSleepTimerError(error: Throwable) {
    this.log.error("onPlayerSleepTimerError: ", error)
  }

  private fun onPlayerSleepTimerEvent(event: PlayerSleepTimerEvent) {
    this.log.debug("onPlayerSleepTimerEvent: {}", event)

    return when (event) {
      PlayerSleepTimerStopped ->
        this.onPlayerSleepTimerEventStopped(event)
      is PlayerSleepTimerRunning ->
        this.onPlayerSleepTimerEventRunning(event)
      is PlayerSleepTimerCancelled ->
        this.onPlayerSleepTimerEventCancelled(event)
      PlayerSleepTimerFinished ->
        this.onPlayerSleepTimerEventFinished(event)
    }
  }

  private fun onPlayerSleepTimerEventFinished(event: PlayerSleepTimerEvent) {
    this.player.pause()

    UIThread.runOnUIThread(Runnable {
      this.menuSleepText.text = ""
      this.menuSleepText.visibility = INVISIBLE
      this.menuSleepEndOfChapter.visibility = INVISIBLE
    })
  }

  private fun onPlayerSleepTimerEventCancelled(event: PlayerSleepTimerCancelled) {
    UIThread.runOnUIThread(Runnable {
      this.menuSleepText.text = ""
      this.menuSleepText.visibility = INVISIBLE
      this.menuSleepEndOfChapter.visibility = INVISIBLE
    })
  }

  private fun onPlayerSleepTimerEventRunning(event: PlayerSleepTimerRunning) {
    UIThread.runOnUIThread(Runnable {
      val remaining = event.remaining
      if (remaining != null) {
        this.menuSleepText.text = this.minuteSecondTextFromDuration(remaining)
        this.menuSleepEndOfChapter.visibility = INVISIBLE
      } else {
        this.menuSleepText.text = ""
        this.menuSleepEndOfChapter.visibility = VISIBLE
      }

      this.menuSleepText.visibility = VISIBLE
    })
  }

  private fun onPlayerSleepTimerEventStopped(event: PlayerSleepTimerEvent) {
    UIThread.runOnUIThread(Runnable {
      this.menuSleepText.text = ""
      this.menuSleepText.visibility = INVISIBLE
      this.menuSleepEndOfChapter.visibility = INVISIBLE
    })
  }

  private fun onMenuTOCSelected(): Boolean {
    this.listener.onPlayerTOCShouldOpen()
    return true
  }

  private fun onMenuSleepSelected(): Boolean {
    this.listener.onPlayerSleepTimerShouldOpen()
    return true
  }

  private fun onMenuPlaybackRateSelected() {
    this.listener.onPlayerPlaybackRateShouldOpen()
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    state: Bundle?): View? {
    this.log.debug("onCreateView")
    return inflater.inflate(R.layout.player_view, container, false)
  }

  override fun onDestroyView() {
    this.log.debug("onDestroyView")
    super.onDestroyView()
    this.playerEventSubscription?.unsubscribe()
    this.playerSleepTimerEventSubscription?.unsubscribe()
  }

  override fun onViewCreated(view: View, state: Bundle?) {
    this.log.debug("onViewCreated")
    super.onViewCreated(view, state)

    this.coverView = view.findViewById(R.id.player_cover)!!

    this.playerTitleView = view.findViewById(R.id.player_title)
    this.playerAuthorView = view.findViewById(R.id.player_author)

    this.playPauseButton = view.findViewById(R.id.player_play_button)!!
    this.playPauseButton.setOnClickListener({ this.player.play() })

    this.playerSkipForwardButton = view.findViewById(R.id.player_jump_forwards)
    this.playerSkipForwardButton.setOnClickListener({ this.player.skipForward() })
    this.playerSkipBackwardButton = view.findViewById(R.id.player_jump_backwards)
    this.playerSkipBackwardButton.setOnClickListener({ this.player.skipBack() })

    this.playerWaiting = view.findViewById(R.id.player_waiting_buffering)
    this.playerWaiting.text = ""

    this.playerPosition = view.findViewById(R.id.player_progress)!!
    this.playerPosition.isEnabled = false
    this.playerPositionDragging = false
    this.playerPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        this@PlayerFragment.onProgressBarChanged(progress, fromUser)
      }

      override fun onStartTrackingTouch(seekBar: SeekBar) {
        this@PlayerFragment.onProgressBarDraggingStarted()
      }

      override fun onStopTrackingTouch(seekBar: SeekBar) {
        this@PlayerFragment.onProgressBarDraggingStopped()
      }
    })

    this.playerTimeCurrent = view.findViewById(R.id.player_time)!!
    this.playerTimeMaximum = view.findViewById(R.id.player_time_maximum)!!
    this.playerSpineElement = view.findViewById(R.id.player_spine_element)!!
    this.playerSpineElement.text = this.spineElementText(this.book.spine.first())

    this.listener.onPlayerWantsCoverImage(this.coverView)
    this.playerTitleView.text = this.listener.onPlayerWantsTitle()
    this.playerAuthorView.text = this.listener.onPlayerWantsAuthor()
  }

  private fun onProgressBarDraggingStopped() {
    this.log.debug("onProgressBarDraggingStopped")
    this.playerPositionDragging = false

    val spine = this.playerPositionCurrentSpine
    if (spine != null) {
      this.player.playAtLocation(
        spine.position.copy(
          offsetMilliseconds =
          TimeUnit.MILLISECONDS.convert(
            this.playerPosition.progress.toLong(),
            TimeUnit.SECONDS).toInt()))
    }
  }

  private fun onProgressBarDraggingStarted() {
    this.log.debug("onProgressBarDraggingStarted")
    this.playerPositionDragging = true
  }

  private fun onProgressBarChanged(progress: Int, fromUser: Boolean) {
    this.log.debug("onProgressBarChanged: {} {}", progress, fromUser)
  }

  private fun hourMinuteSecondTextFromMilliseconds(milliseconds: Int): String {
    return this.hourMinuteSecondFormatter.print(Duration.millis(milliseconds.toLong()).toPeriod())
  }

  private fun hourMinuteSecondTextFromDuration(duration: Duration): String {
    return this.hourMinuteSecondFormatter.print(duration.toPeriod())
  }

  private fun minuteSecondTextFromDuration(duration: Duration): String {
    return this.minuteSecondFormatter.print(duration.toPeriod())
  }

  private fun onPlayerEventsCompleted() {
    this.log.debug("onPlayerEventsCompleted")
  }

  private fun onPlayerError(error: Throwable) {
    this.log.debug("onPlayerError: ", error)
  }

  private fun onPlayerEvent(event: PlayerEvent) {
    this.log.debug("onPlayerEvent: {}", event)

    return when (event) {
      is PlayerEventPlaybackStarted ->
        this.onPlayerEventPlaybackStarted(event)
      is PlayerEventPlaybackBuffering ->
        this.onPlayerEventPlaybackBuffering(event)
      is PlayerEventChapterWaiting ->
        this.onPlayerEventChapterWaiting(event)
      is PlayerEventPlaybackProgressUpdate ->
        this.onPlayerEventPlaybackProgressUpdate(event)
      is PlayerEventChapterCompleted ->
        this.onPlayerEventChapterCompleted(event)
      is PlayerEventPlaybackPaused ->
        this.onPlayerEventPlaybackPaused(event)
      is PlayerEventPlaybackStopped ->
        this.onPlayerEventPlaybackStopped(event)
      is PlayerEventPlaybackRateChanged ->
        this.onPlayerEventPlaybackRateChanged(event)
    }
  }

  private fun onPlayerEventChapterWaiting(event: PlayerEventChapterWaiting) {
    UIThread.runOnUIThread(Runnable {
      val text =
        this.getString(R.string.audiobook_player_waiting, event.spineElement.index + 1)
      this.playerWaiting.setText(text)
      this.playerSpineElement.text = this.spineElementText(event.spineElement)
      this.onEventUpdateTimeRelatedUI(event.spineElement, 0)
    })
  }

  private fun onPlayerEventPlaybackBuffering(event: PlayerEventPlaybackBuffering) {
    UIThread.runOnUIThread(Runnable {
      this.playerWaiting.setText(R.string.audiobook_player_buffering)
      this.playerSpineElement.text = this.spineElementText(event.spineElement)
      this.onEventUpdateTimeRelatedUI(event.spineElement, event.offsetMilliseconds)
    })
  }

  private fun onPlayerEventChapterCompleted(event: PlayerEventChapterCompleted) {

    /*
     * If the chapter is completed, and the sleep timer is running indefinitely, then
     * tell the sleep timer to complete.
     */

    val running = this.sleepTimer.isRunning
    if (running != null) {
      if (running.duration == null) {
        this.sleepTimer.finish()
      }
    }
  }

  private fun onPlayerEventPlaybackRateChanged(event: PlayerEventPlaybackRateChanged) {
    UIThread.runOnUIThread(Runnable {
      this.menuPlaybackRateText.text = PlayerPlaybackRateAdapter.textOfRate(event.rate)
    })
  }

  private fun onPlayerEventPlaybackStopped(event: PlayerEventPlaybackStopped) {
    UIThread.runOnUIThread(Runnable {
      this.playPauseButton.setImageResource(R.drawable.play_icon)
      this.playPauseButton.setOnClickListener({ this.player.play() })
      this.playerSpineElement.text = this.spineElementText(event.spineElement)
      this.onEventUpdateTimeRelatedUI(event.spineElement, event.offsetMilliseconds)
    })
  }

  private fun spineElementText(spineElement: PlayerSpineElementType): String {
    return this.getString(
      R.string.audiobook_player_spine_element,
      spineElement.index + 1,
      spineElement.book.spine.size)
  }

  private fun onPlayerEventPlaybackPaused(event: PlayerEventPlaybackPaused) {
    UIThread.runOnUIThread(Runnable {
      this.playPauseButton.setImageResource(R.drawable.play_icon)
      this.playPauseButton.setOnClickListener({ this.player.play() })
      this.playerSpineElement.text = this.spineElementText(event.spineElement)
      this.onEventUpdateTimeRelatedUI(event.spineElement, event.offsetMilliseconds)
    })
  }

  private fun onPlayerEventPlaybackProgressUpdate(event: PlayerEventPlaybackProgressUpdate) {
    UIThread.runOnUIThread(Runnable {
      this.playPauseButton.setImageResource(R.drawable.pause_icon)
      this.playPauseButton.setOnClickListener({ this.player.pause() })
      this.playerWaiting.text = ""
      this.onEventUpdateTimeRelatedUI(event.spineElement, event.offsetMilliseconds)
    })
  }

  private fun onPlayerEventPlaybackStarted(event: PlayerEventPlaybackStarted) {
    UIThread.runOnUIThread(Runnable {
      this.playPauseButton.setImageResource(R.drawable.pause_icon)
      this.playPauseButton.setOnClickListener({ this.player.pause() })
      this.playerSpineElement.text = this.spineElementText(event.spineElement)
      this.playerPosition.isEnabled = true
      this.playerWaiting.text = ""
      this.onEventUpdateTimeRelatedUI(event.spineElement, event.offsetMilliseconds)
    })
  }

  private fun onEventUpdateTimeRelatedUI(
    spineElement: PlayerSpineElementType,
    offsetMilliseconds: Int) {

    this.playerPosition.max =
      spineElement.duration.standardSeconds.toInt()
    this.playerPosition.isEnabled = true

    this.playerPositionCurrentSpine = spineElement
    this.playerPositionCurrentOffset = offsetMilliseconds

    if (!this.playerPositionDragging) {
      this.playerPosition.progress =
        TimeUnit.MILLISECONDS.toSeconds(offsetMilliseconds.toLong()).toInt()
    }

    this.playerTimeMaximum.text =
      this.hourMinuteSecondTextFromDuration(spineElement.duration)
    this.playerTimeCurrent.text =
      this.hourMinuteSecondTextFromMilliseconds(offsetMilliseconds)
    this.playerSpineElement.text =
      this.spineElementText(spineElement)
  }
}