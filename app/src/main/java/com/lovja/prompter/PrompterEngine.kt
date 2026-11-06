package com.lovja.prompter

import android.view.Choreographer

/**
 * Metnin neresinde olduğumuzu tutar. Konum "kelime sırası" olarak saklanır
 * (ör. 12.4 = 12. kelimenin biraz ilerisi). Şimdilik sabit hızla ilerler;
 * konuşma tanıma eklendiğinde [jumpTo] / [setTarget] ile tanınan kelimeye göre sürülecek.
 */
class PrompterEngine {

    var text: String = ""
        private set

    /** Her kelimenin metindeki başlangıç karakteri. */
    var wordStarts: IntArray = IntArray(0)
        private set

    /** Şu anki konum, kelime sırası cinsinden. */
    var position: Float = 0f
        private set

    var wordsPerSecond: Float = 2.2f
        set(value) {
            field = value.coerceIn(0.5f, 6f)
            notifyChanged()
        }

    var textSizeSp: Float = 34f
        set(value) {
            field = value.coerceIn(16f, 80f)
            notifyChanged()
        }

    var playing: Boolean = false
        private set

    private val listeners = linkedSetOf<() -> Unit>()
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            if (lastFrameNanos != 0L) {
                val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                position = (position + wordsPerSecond * dt).coerceAtMost(maxPosition())
                if (position >= maxPosition()) playing = false
                notifyChanged()
            }
            lastFrameNanos = frameTimeNanos
            if (playing) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setText(newText: String) {
        text = newText
        val starts = ArrayList<Int>()
        var inWord = false
        newText.forEachIndexed { i, c ->
            val isSpace = c.isWhitespace()
            if (!isSpace && !inWord) starts.add(i)
            inWord = !isSpace
        }
        wordStarts = starts.toIntArray()
        position = 0f
        notifyChanged()
    }

    fun wordCount() = wordStarts.size

    private fun maxPosition() = (wordStarts.size - 1).coerceAtLeast(0).toFloat()

    fun play() {
        if (playing || wordStarts.isEmpty()) return
        playing = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        notifyChanged()
    }

    fun pause() {
        playing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        notifyChanged()
    }

    fun rewind() {
        position = 0f
        notifyChanged()
    }

    fun jumpTo(wordIndex: Float) {
        position = wordIndex.coerceIn(0f, maxPosition())
        notifyChanged()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }
}
