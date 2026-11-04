package com.example.myapplication.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.example.myapplication.detection.Detection
import java.util.Locale

class AudioFeedback(context: Context) {

    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private val lastSeen = mutableMapOf<String, Long>()       // label -> last time in view
    private val lastSpoken = mutableMapOf<String, Long>()     // label -> last time said
    private val latest = mutableMapOf<String, Detection>()    // label -> its latest box
    private val waiting = LinkedHashSet<String>()             // new labels not yet spoken

    private val goneTime = 3_000L
    private val reminderTime = 15_000L
    private val minGapBetweenWords = 600L

    private val history = ArrayDeque<Set<String>>()
    private val historySize = 3
    private val minFrames = 2

    private var lastSpeakTime = 0L

    // Where the object is, from its box centre (0 = left edge, 1 = right edge)
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

        history.addLast(results.map { it.label }.toSet())
        if (history.size > historySize) history.removeFirst()

        val stable = results
            .filter { d -> history.count { d.label in it } >= minFrames }
            .sortedByDescending { it.score }

        for (d in stable) {
            val seen = lastSeen[d.label]
            val spoken = lastSpoken[d.label]
            val isNew = seen == null || now - seen > goneTime
            val needsReminder = spoken != null && now - spoken > reminderTime
            if (isNew || needsReminder) waiting.add(d.label)
            lastSeen[d.label] = now
            // Keep the most confident box per label, so the side is up to date
            val current = latest[d.label]
            if (current == null || current !in stable || d.score >= current.score) {
                latest[d.label] = d
            }
        }

        waiting.removeAll { label -> now - (lastSeen[label] ?: 0L) > goneTime }

        val next = waiting.firstOrNull() ?: return
        if (tts.isSpeaking && now - lastSpeakTime < minGapBetweenWords) return

        val det = latest[next] ?: return
        val side = sideOf(det)

        // Pan the voice to the object's side (-1 = left ear, 0 = both, 1 = right ear)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, side.pan)
        }

        tts.speak(sentenceFor(next, side), TextToSpeech.QUEUE_FLUSH, params, "sva_announce")
        waiting.remove(next)
        lastSpoken[next] = now
        lastSpeakTime = now
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}