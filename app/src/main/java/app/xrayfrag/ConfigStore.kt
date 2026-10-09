package app.xrayfrag

import android.content.Context
import org.json.JSONObject
import java.io.File

object ConfigStore {
    private fun file(c: Context) = File(c.filesDir, "config.json")

    fun defaultConfig(c: Context): String =
        c.assets.open("config.json").bufferedReader().use { it.readText() }

    fun load(c: Context): String {
        val f = file(c)
        return if (f.exists()) f.readText() else defaultConfig(c)
    }

    /** Melempar exception kalau JSON tidak valid. */
    fun save(c: Context, text: String) {
        JSONObject(text)
        file(c).writeText(text)
    }

    fun reset(c: Context) {
        file(c).delete()
    }
}
