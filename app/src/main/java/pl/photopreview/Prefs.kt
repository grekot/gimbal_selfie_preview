package pl.photopreview

import android.content.Context

/** Small persistent settings: last connected host and the stream config (as JSON). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("photopreview", Context.MODE_PRIVATE)

    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(value) { sp.edit().putString("lastHost", value).apply() }

    var configJson: String?
        get() = sp.getString("config", null)
        set(value) { sp.edit().putString("config", value).apply() }
}
