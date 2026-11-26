package com.themoon.y1.managers

import android.content.Context
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * The translations. The built-in languages (assets/languages in the app) always load from the app, so that an
 * update of the app also updates them. The Languages folder on the storage is only for the language
 * files that a user adds: a file there with the name of a built-in language is not used.
 */
class LanguageManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val dictionary: HashMap<String, String> = HashMap()
    /** The file names of the built-in languages, from the app. */
    private var builtIn: List<String> = emptyList()
    /** The language files that a user added to the Languages folder (not the built-in names). */
    var availableLangFiles: MutableList<File> = ArrayList()
    var currentLangFileName: String = DEFAULT
    /** The name that [dictionary] holds now (null: none loaded yet). */
    private var loadedName: String? = null

    init {
        loadAvailableLanguages()
    }

    /** Reads the names of the built-in languages and the files in the Languages folder again. */
    fun loadAvailableLanguages() {
        builtIn = try {
            context.assets.list(ASSET_DIR)?.filter { isLanguageFile(it) }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val files = StoragePaths.getLanguagesDir().listFiles()?.filter { it.isFile } ?: emptyList()
        availableLangFiles = userFiles(builtIn, files.map { it.name }).map { name -> files.first { it.name == name } }.toMutableList()
    }

    /** The built-in language files, by file name (for example "spanish.json"). */
    fun builtInLanguages(): List<String> = builtIn

    /**
     * Loads the language [fileName] (a value of Settings → General → Language). English when it is null or not found.
     * The same language again is not read again, unless [reload] (a choice in Settings: the user can have edited the file).
     */
    fun applyLanguage(fileName: String?, reload: Boolean = false) {
        val name = fileName ?: DEFAULT
        currentLangFileName = name
        if (name == loadedName && !reload) return
        dictionary.clear()
        // English first: a key that the language file does not have shows the English text, also for the keys whose
        // English text is not the key (for example a count with its plural forms).
        readAsset(ENGLISH_FILE)?.let { dictionary.putAll(parse(it)) }
        val text = when (val source = sourceOf(name, builtIn, availableLangFiles.map { it.name })) {
            is Source.Asset -> readAsset(source.name)
            is Source.Storage -> readFile(File(StoragePaths.getLanguagesDir(), source.name))
            null -> null
        }
        text?.let { dictionary.putAll(parse(it)) }
        // A file of the Languages folder that could not be read (the storage is not ready yet): try again next time
        loadedName = if (text != null || isEnglish(name)) name else null
    }

    /**
     * Loads the chosen language again when its file in the Languages folder could not be read before (the storage
     * was not ready). True when it loaded now: the screens then need the new texts.
     */
    fun reloadIfNotLoaded(): Boolean {
        if (loadedName != null) return false
        loadAvailableLanguages()
        applyLanguage(currentLangFileName)
        return loadedName != null
    }

    /**
     * Translates input string using current dictionary.
     */
    fun t(originalText: String?): String {
        if (originalText == null) return ""
        if (dictionary.isEmpty()) return originalText

        val translated = dictionary[originalText]
        if (translated != null) {
            return translated
        }

        val trimmed = originalText.trim()
        val trimmedTranslated = dictionary[trimmed]
        if (trimmedTranslated != null) {
            return originalText.replace(trimmed, trimmedTranslated)
        }

        return originalText
    }

    private fun readAsset(name: String): String? = try {
        context.assets.open("$ASSET_DIR/$name").use { read(it) }
    } catch (_: Exception) {
        null
    }

    private fun readFile(f: File): String? = try {
        f.inputStream().use { read(it) }
    } catch (_: Exception) {
        null
    }

    // A file saved by Windows Notepad can start with a byte order mark, which JSONObject does not accept
    private fun read(input: InputStream): String = input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")

    /** Where a language loads from: the app ([Asset]) or the Languages folder ([Storage]). */
    sealed class Source {
        data class Asset(val name: String) : Source()
        data class Storage(val name: String) : Source()
    }

    companion object {
        /** The value of Settings → General → Language for English, the default. */
        const val DEFAULT = "English (Default)"
        const val ENGLISH_FILE = "english.json"
        private const val ASSET_DIR = "languages"

        @Volatile
        private var instance: LanguageManager? = null
        @Synchronized
        fun getInstance(context: Context?): LanguageManager {
            var inst = instance
            if (inst == null) {
                val ctx = context?.applicationContext ?: MainActivity.instance?.applicationContext
                checkNotNull(ctx) { "Context must not be null when initializing LanguageManager" }
                inst = LanguageManager(ctx)
                instance = inst
            }
            return inst!!
        }

        fun isLanguageFile(name: String): Boolean = name.lowercase().endsWith(".json")

        /** True for the English values: the default, and english.json (a value that older versions could save). */
        fun isEnglish(fileName: String?): Boolean =
            fileName == null || fileName.equals(DEFAULT, ignoreCase = true) || fileName.equals(ENGLISH_FILE, ignoreCase = true)

        /** The files of the Languages folder ([storageNames]) that a user added: not a built-in name (any case). */
        fun userFiles(builtIn: List<String>, storageNames: List<String>): List<String> =
            storageNames.filter { n -> isLanguageFile(n) && builtIn.none { it.equals(n, ignoreCase = true) } }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        /**
         * Where the language [name] loads from: a built-in name (any case) from the app, also when the Languages
         * folder has a file with that name (an old copy that an earlier version wrote there); else a file of the
         * Languages folder. Null for English (it is always loaded first), and when no file has that name.
         */
        fun sourceOf(name: String, builtIn: List<String>, storageNames: List<String>): Source? {
            if (isEnglish(name)) return null
            builtIn.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return Source.Asset(it) }
            return storageNames.firstOrNull { it == name }?.let { Source.Storage(it) }
        }

        /** The texts of a language file (a JSON object of key → text); empty when the file is not valid JSON. */
        fun parse(json: String): Map<String, String> = try {
            val o = JSONObject(json)
            val map = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.opt(k)
                if (v is String) map[k] = v
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
