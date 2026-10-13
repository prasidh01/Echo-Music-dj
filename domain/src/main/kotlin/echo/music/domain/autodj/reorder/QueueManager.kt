package echo.music.domain.autodj.reorder

import echo.music.domain.autodj.model.TrackProfile

object QueueManager {
  const val DEFAULT_LOOKAHEAD_WINDOW = 10

  /**
   * Reorders upcoming queue tracks relative to the current playing track.
   * If [autoRemixOrderingEnabled] is false, preserves natural playlist queue order untouched.
   * When upcoming queue is empty or has a single item, performs a safe no-op.
   * Limits reordering to [lookaheadWindow] (default 10 items) to prevent UI thread delays on massive playlists.
   */
  fun <T> reorderUpcoming(
    currentTrack: T?,
    upcomingQueue: List<T>,
    profileProvider: (T) -> TrackProfile?,
    autoRemixOrderingEnabled: Boolean = true,
    lookaheadWindow: Int = DEFAULT_LOOKAHEAD_WINDOW,
  ): List<T> {
    if (!autoRemixOrderingEnabled) return upcomingQueue
    if (upcomingQueue.size <= 1) return upcomingQueue

    val anchorProfile = currentTrack?.let(profileProvider)
    return AutoRemixOrderingEngine.chainTracks(
      anchor = anchorProfile,
      candidates = upcomingQueue,
      profileProvider = profileProvider,
      lookaheadLimit = lookaheadWindow,
    )
  }

  /**
   * Overload operating on the full queue and active playback index.
   */
  fun <T> reorderUpcoming(
    queue: List<T>,
    currentIndex: Int,
    profileProvider: (T) -> TrackProfile?,
    autoRemixOrderingEnabled: Boolean = true,
    lookaheadWindow: Int = DEFAULT_LOOKAHEAD_WINDOW,
  ): List<T> {
    if (!autoRemixOrderingEnabled) return queue
    if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size) return queue

    val currentTrack = queue[currentIndex]
    val before = queue.subList(0, currentIndex + 1)
    val upcoming = queue.subList(currentIndex + 1, queue.size)

    val reordered = reorderUpcoming(
      currentTrack = currentTrack,
      upcomingQueue = upcoming,
      profileProvider = profileProvider,
      autoRemixOrderingEnabled = autoRemixOrderingEnabled,
      lookaheadWindow = lookaheadWindow,
    )

    return before + reordered
  }
}
