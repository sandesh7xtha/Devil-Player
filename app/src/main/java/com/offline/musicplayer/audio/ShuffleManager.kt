package com.offline.musicplayer.audio

import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.ExoPlayer
import kotlin.random.Random

class ShuffleManager {

    private var currentShuffledIndices: IntArray = intArrayOf()
    private var playedCountInCycle: Int = 0
    private var lastPlayedTrackIndex: Int = -1

    /**
     * Generates a custom ShuffleOrder using an unbiased Fisher-Yates shuffle algorithm.
     * Keeps the currently playing track index at position 0 of the shuffled order,
     * and shuffles all remaining track indices (0 until totalTracks excluding currentIndex).
     */
    fun createFisherYatesShuffleOrder(
        totalTracks: Int,
        currentIndex: Int,
        random: Random = Random.Default
    ): ShuffleOrder {
        if (totalTracks <= 1) {
            currentShuffledIndices = if (totalTracks == 1) intArrayOf(0) else intArrayOf()
            playedCountInCycle = 1
            lastPlayedTrackIndex = currentIndex
            return ShuffleOrder.DefaultShuffleOrder(currentShuffledIndices, random.nextLong())
        }

        val clampedCurrentIndex = if (currentIndex in 0 until totalTracks) currentIndex else 0
        val remainingIndices = ArrayList<Int>(totalTracks - 1)
        for (i in 0 until totalTracks) {
            if (i != clampedCurrentIndex) {
                remainingIndices.add(i)
            }
        }

        // Unbiased in-place Fisher-Yates shuffle on remaining indices
        for (i in remainingIndices.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = remainingIndices[i]
            remainingIndices[i] = remainingIndices[j]
            remainingIndices[j] = temp
        }

        // Construct exact array: position 0 is currentIndex, followed by shuffled remaining
        val shuffledIndices = IntArray(totalTracks)
        shuffledIndices[0] = clampedCurrentIndex
        for (i in remainingIndices.indices) {
            shuffledIndices[i + 1] = remainingIndices[i]
        }

        currentShuffledIndices = shuffledIndices
        playedCountInCycle = 1
        lastPlayedTrackIndex = clampedCurrentIndex

        val seed = random.nextLong()
        return ShuffleOrder.DefaultShuffleOrder(shuffledIndices, seed)
    }

    /**
     * Generates a fresh shuffle order for the next cycle when REPEAT_MODE_ALL is enabled.
     * Strictly guarantees that the first track of the new cycle is NOT identical to the last track played.
     */
    fun createNextCycleShuffleOrder(
        totalTracks: Int,
        lastPlayedIndex: Int,
        random: Random = Random.Default
    ): ShuffleOrder {
        if (totalTracks <= 1) {
            return createFisherYatesShuffleOrder(totalTracks, 0, random)
        }

        // Generate full list of indices
        val allIndices = (0 until totalTracks).toMutableList()

        // Full Fisher-Yates shuffle
        for (i in allIndices.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = allIndices[i]
            allIndices[i] = allIndices[j]
            allIndices[j] = temp
        }

        // Guarantee first track != lastPlayedIndex
        if (allIndices[0] == lastPlayedIndex) {
            // Swap with any other index (1 until totalTracks)
            val swapWith = 1 + random.nextInt(totalTracks - 1)
            val temp = allIndices[0]
            allIndices[0] = allIndices[swapWith]
            allIndices[swapWith] = temp
        }

        val shuffledArray = allIndices.toIntArray()
        currentShuffledIndices = shuffledArray
        playedCountInCycle = 0

        val seed = random.nextLong()
        return ShuffleOrder.DefaultShuffleOrder(shuffledArray, seed)
    }

    /**
     * Called whenever a media item transition occurs.
     * Tracks cycle progress and automatically generates a new non-repeating shuffle cycle
     * when all songs in the queue have played once and REPEAT_MODE_ALL is active.
     */
    fun onMediaItemTransition(exoPlayer: ExoPlayer, newIndex: Int) {
        val totalTracks = exoPlayer.mediaItemCount
        if (totalTracks <= 1 || !exoPlayer.shuffleModeEnabled) {
            lastPlayedTrackIndex = newIndex
            return
        }

        playedCountInCycle++

        // If a full cycle has played and REPEAT_MODE_ALL is active
        if (playedCountInCycle >= totalTracks && exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
            val newShuffleOrder = createNextCycleShuffleOrder(
                totalTracks = totalTracks,
                lastPlayedIndex = if (newIndex in 0 until totalTracks) newIndex else lastPlayedTrackIndex
            )
            exoPlayer.setShuffleOrder(newShuffleOrder)
        }

        lastPlayedTrackIndex = newIndex
    }

    /**
     * Toggles shuffle on ExoPlayer with the Fisher-Yates shuffle order.
     */
    fun enableShuffle(exoPlayer: ExoPlayer) {
        val totalTracks = exoPlayer.mediaItemCount
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (totalTracks > 0) {
            val order = createFisherYatesShuffleOrder(totalTracks, currentIndex)
            exoPlayer.setShuffleOrder(order)
        }
        exoPlayer.shuffleModeEnabled = true
    }

    fun disableShuffle(exoPlayer: ExoPlayer) {
        exoPlayer.shuffleModeEnabled = false
    }

    fun getCurrentShuffledIndices(): IntArray = currentShuffledIndices
}
