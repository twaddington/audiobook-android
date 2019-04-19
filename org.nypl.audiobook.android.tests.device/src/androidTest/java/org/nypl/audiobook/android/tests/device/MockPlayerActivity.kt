package org.nypl.audiobook.android.tests.device

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.app.FragmentActivity
import android.support.v7.app.AppCompatActivity
import android.widget.ImageView
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import org.joda.time.Duration
import org.nypl.audiobook.android.api.PlayerAudioBookType
import org.nypl.audiobook.android.api.PlayerBookID
import org.nypl.audiobook.android.api.PlayerDownloadProviderType
import org.nypl.audiobook.android.api.PlayerSleepTimerType
import org.nypl.audiobook.android.api.PlayerType
import org.nypl.audiobook.android.mocking.MockingAudioBook
import org.nypl.audiobook.android.mocking.MockingDownloadProvider
import org.nypl.audiobook.android.mocking.MockingPlayer
import org.nypl.audiobook.android.mocking.MockingSleepTimer
import org.nypl.audiobook.android.views.PlayerAccessibilityEvent
import org.nypl.audiobook.android.views.PlayerFragment
import org.nypl.audiobook.android.views.PlayerFragmentListenerType
import org.nypl.audiobook.android.views.PlayerFragmentParameters
import org.nypl.audiobook.android.views.PlayerPlaybackRateFragment
import org.nypl.audiobook.android.views.PlayerSleepTimerFragment
import org.nypl.audiobook.android.views.PlayerTOCFragment
import org.nypl.audiobook.android.views.PlayerTOCFragmentParameters
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class MockPlayerActivity : AppCompatActivity(), PlayerFragmentListenerType {

  val timer: MockingSleepTimer = MockingSleepTimer()

  val downloadExecutor: ListeningExecutorService =
    MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4))
  val downloadStatusExecutor: ExecutorService =
    Executors.newFixedThreadPool(1)
  private val scheduledExecutor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()

  val downloadProvider: PlayerDownloadProviderType =
    MockingDownloadProvider(
      executorService = this.downloadExecutor,
      shouldFail = { request -> false })

  val book: MockingAudioBook =
    MockingAudioBook(
      id = PlayerBookID.transform("abc"),
      players = { book -> MockingPlayer(book) },
      downloadStatusExecutor = this.downloadStatusExecutor,
      downloadProvider = this.downloadProvider)

  val player: MockingPlayer = this.book.createPlayer()

  lateinit var playerFragment: PlayerFragment

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)

    this.setTheme(R.style.AudioBooksWithActionBar)

    for (i in 0..100) {
      val e = this.book.createSpineElement(
        "id$i",
        "P$i",
        Duration.standardSeconds(20))
    }

    this.setContentView(R.layout.mocking_player_activity)

    this.playerFragment = PlayerFragment.newInstance(PlayerFragmentParameters(
      primaryColor = Color.parseColor("#f02020")))

    this.supportFragmentManager
      .beginTransaction()
      .replace(R.id.mocking_player_fragment_holder, this.playerFragment, "PLAYER")
      .commit()
  }

  override fun onPlayerWantsPlayer(): PlayerType {
    return this.player
  }

  override fun onPlayerWantsCoverImage(view: ImageView) {

  }

  override fun onPlayerWantsTitle(): String {
    return "Any Title"
  }

  override fun onPlayerWantsAuthor(): String {
    return "Any Author"
  }

  override fun onPlayerWantsSleepTimer(): PlayerSleepTimerType {
    return this.timer
  }

  override fun onPlayerTOCShouldOpen() {
    val fragment =
      PlayerTOCFragment.newInstance(PlayerTOCFragmentParameters(
        primaryColor = Color.parseColor("#f02020")))

    this.supportFragmentManager
      .beginTransaction()
      .replace(R.id.mocking_player_fragment_holder, fragment, "PLAYER_TOC")
      .addToBackStack(null)
      .commit()
  }

  override fun onPlayerTOCWantsBook(): PlayerAudioBookType {
    return this.book
  }

  override fun onPlayerTOCWantsClose() {
    this.supportFragmentManager.popBackStack()
  }

  override fun onPlayerPlaybackRateShouldOpen() {
    Handler(Looper.getMainLooper()).post {
      val fragment =
        PlayerPlaybackRateFragment.newInstance(PlayerFragmentParameters(
          primaryColor = Color.parseColor("#f02020")))
      fragment.show(this.supportFragmentManager, "PLAYER_RATE")
    }
  }

  override fun onPlayerSleepTimerShouldOpen() {
    Handler(Looper.getMainLooper()).post {
      val fragment =
        PlayerSleepTimerFragment.newInstance(PlayerFragmentParameters(
          primaryColor = Color.parseColor("#f02020")))
      fragment.show(this.supportFragmentManager, "PLAYER_SLEEP_TIMER")
    }
  }

  override fun onPlayerWantsScheduledExecutor(): ScheduledExecutorService {
    return this.scheduledExecutor
  }

  override fun onPlayerAccessibilityEvent(event: PlayerAccessibilityEvent) {

  }
}
