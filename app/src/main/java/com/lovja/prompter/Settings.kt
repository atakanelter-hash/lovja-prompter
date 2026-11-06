package com.lovja.prompter

import android.content.Context

/** Kullanıcı ayarları (telefonda saklanır). */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("prompter", Context.MODE_PRIVATE)

    /** true = dikey 9:16 (Reels), false = yatay 16:9 */
    var vertical: Boolean
        get() = p.getBoolean("vertical", true)
        set(v) = p.edit().putBoolean("vertical", v).apply()

    /** true = 4K, false = Full HD */
    var uhd: Boolean
        get() = p.getBoolean("uhd", false)
        set(v) = p.edit().putBoolean("uhd", v).apply()

    /** Seçili mikrofonun kimliği; -1 = otomatik (takılı mikrofon varsa o). */
    var micId: Int
        get() = p.getInt("micId", -1)
        set(v) = p.edit().putInt("micId", v).apply()

    /** Kendini ayna gibi görme (sadece ekranda; video normal kaydedilir). */
    var mirror: Boolean
        get() = p.getBoolean("mirror", true)
        set(v) = p.edit().putBoolean("mirror", v).apply()

    /** Harici mikrofonda ses-görüntü ince ayarı (ms). + = ses daha geç. */
    var audioOffsetMs: Int
        get() = p.getInt("audioOffsetMs", 0)
        set(v) = p.edit().putInt("audioOffsetMs", v).apply()

    /** Küçük ekrandaki arayüzün elle döndürülmesi (derece). */
    var outerAngle: Int
        get() = p.getInt("outerAngle", 0)
        set(v) = p.edit().putInt("outerAngle", v).apply()

    /** Yan yana düzende görüntü sağda mı. */
    var previewRight: Boolean
        get() = p.getBoolean("previewRight", true)
        set(v) = p.edit().putBoolean("previewRight", v).apply()

    var script: String?
        get() = p.getString("script", null)
        set(v) = p.edit().putString("script", v).apply()

    var wps: Float
        get() = p.getFloat("wps", 2.2f)
        set(v) = p.edit().putFloat("wps", v).apply()

    var textSize: Float
        get() = p.getFloat("size", 34f)
        set(v) = p.edit().putFloat("size", v).apply()
}
