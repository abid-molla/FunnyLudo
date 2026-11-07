package com.funny.ludo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Sound effects.
 *  - tiny synthesized sounds (dice rattle, step pop, boing) -> SoundPool
 *  - the meme clips you supplied -> MediaPlayer
 *
 * Mapping:
 *   cut a piece        -> please-khoma-kore-dau / fart-funk / aayein-meme   (random)
 *   roll a 6           -> tudun-tadau / rag-korla                           (random)
 *   land on a safe/star-> safe-3 / duck-toy-sound                           (random)
 *   piece enters home  -> 7-crore-kbc / nahi-nahi-saluke...                 (random)
 *   win the game       -> hum-jeet-gaye
 *   can't move / 3 sixes -> fahhhh
 */
class Sfx(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("funnyludo", Context.MODE_PRIVATE)
    private val pool: SoundPool
    private val ids = HashMap<String, Int>()
    private val clips = ArrayList<MediaPlayer>()

    var enabled: Boolean = prefs.getBoolean("sound", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("sound", value).apply()
            if (!value) stopClips()
        }

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        ids["roll"] = pool.load(ctx, R.raw.snd_roll, 1)
        ids["step"] = pool.load(ctx, R.raw.snd_step, 1)
        ids["out"] = pool.load(ctx, R.raw.snd_out, 1)
    }

    fun play(name: String) {
        if (!enabled) return
        ids[name]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    private fun stopClips() {
        for (mp in clips.toList()) {
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            mp.release()
        }
        clips.clear()
    }

    /** meme clips never pile up: a new one replaces the previous one */
    private fun clip(res: Int) {
        if (!enabled) return
        stopClips()
        try {
            val mp = MediaPlayer.create(ctx, res) ?: return
            mp.setOnCompletionListener {
                clips.remove(it)
                it.release()
            }
            mp.setOnErrorListener { m, _, _ ->
                clips.remove(m)
                m.release()
                true
            }
            clips.add(mp)
            mp.start()
        } catch (_: Exception) {
        }
    }

    fun cut() = clip(listOf(R.raw.snd_cut1, R.raw.snd_cut2, R.raw.snd_cut3).random())
    fun six() = clip(listOf(R.raw.snd_six1, R.raw.snd_six2).random())
    fun safe() = clip(listOf(R.raw.snd_safe1, R.raw.snd_safe2).random())
    fun home() = clip(listOf(R.raw.snd_home1, R.raw.snd_home2).random())
    fun win() = clip(R.raw.snd_win)
    fun fahh() = clip(R.raw.snd_fahh)

    fun release() {
        stopClips()
        pool.release()
    }
}
