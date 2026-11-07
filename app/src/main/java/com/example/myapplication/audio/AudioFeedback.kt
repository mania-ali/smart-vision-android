package com.example.myapplication.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.myapplication.detection.Detection
import java.util.Locale

class AudioFeedback(context: Context) {

    private var ready = false

    // true from the moment we start a sentence until Android says it finished
    @Volatile private var speaking = false

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    init {
        // Android tells us when each sentence starts and ends
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking = false }
        })
    }

    private val lastSeen = mutableMapOf<String, Long>()     // label -> last time in view
    private val lastSpoken = mutableMapOf<String, Long>()   // label -> last time said
    private val latest = mutableMapOf<String, Detection>()  // label -> its newest box
    private val waiting = LinkedHashSet<String>()           // queue of labels to say

    private val goneTime = 3_000L       // out of view this long = "new" again
    private val reminderTime = 15_000L  // reminder if it stays in view
    private val maxWaiting = 2          // keep the queue short so we never fall behind

    // Stability check: a label must appear in 2 of the last 3 frames
    private val history = ArrayDeque<Set<String>>()
    private val historySize = 3
    private val minFrames = 2

    private enum class Side(val pan: Float) { LEFT(-1f), CENTRE(0f), RIGHT(1f) }

    private fun sideOf(d: Detection): Side {
        val cx = (d.box.left + d.box.right) / 2
        return when {
            cx < 0.33f -> Side.LEFT
            cx > 0.66f -> Side.RIGHT
            else -> Side.CENTRE
        }
    }

    private fun sentenceFor(label: String, side: Side) = when (side) {
        Side.LEFT -> "$label on your left"
        Side.RIGHT -> "$label on your right"
        Side.CENTRE -> "$label ahead"
    }

    fun update(results: List<Detection>) {
        if (!ready) return
        val now = System.currentTimeMillis()

        // Remember this frame's labels (last 3 frames only)
        history.addLast(results.map { it.label }.toSet())
        if (history.size > historySize) history.removeFirst()

        // Keep only stable detections, most confident first
        val stable = results
            .filter { d -> history.count { d.label in it } >= minFrames }
            .sortedByDescending { it.score }

        // Queue labels that are new or need a reminder; keep the newest box per label
        val updatedThisFrame = mutableSetOf<String>()
        for (d in stable) {
            val seen = lastSeen[d.label]
            val spoken = lastSpoken[d.label]
            val isNew = seen == null || now - seen > goneTime
            val needsReminder = spoken != null && now - spoken > reminderTime
            if ((isNew || needsReminder) && d.label !in waiting) waiting.add(d.label)

            lastSeen[d.label] = now
            if (updatedThisFrame.add(d.label)) latest[d.label] = d  // first = most confident
        }

        // Drop queued labels that left the view
        waiting.removeAll { label -> now - (lastSeen[label] ?: 0L) > goneTime }

        // Keep the queue short: drop the oldest if too many are waiting
        while (waiting.size > maxWaiting) waiting.remove(waiting.first())

        // Never cut a sentence off: wait until the current one finishes
        if (speaking) return

        val next = waiting.firstOrNull() ?: return
        val det = latest[next] ?: return
        val side = sideOf(det)

        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, side.pan) }

        speaking = true   // set at once, so the next frame doesn't start another sentence
        tts.speak(sentenceFor(next, side), TextToSpeech.QUEUE_ADD, params, "sva_$now")
        waiting.remove(next)
        lastSpoken[next] = now
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}