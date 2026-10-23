package tv.minimal.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** TV-friendly source and playlist selection, with optional local image import. */
class WallpaperPickerActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var sourceButton: Button
    private lateinit var importButton: Button
    private lateinit var downloadButton: Button
    private var importing = false
    private var importSource: WallpaperPreferences.Source? = null
    private var importSelection: Set<String>? = null
    private val sources = WallpaperPreferences.Source.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(56), dpi(24), dpi(56), dpi(24))
        }
        column.addView(TextView(this).apply {
            setText(R.string.wallpaper_title)
            textSize = 26f
        })
        summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, dpi(8), 0, dpi(12))
        }
        column.addView(summary)
        fun button(label: Int, action: () -> Unit): Button = Button(this).apply {
            setText(label)
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        sourceButton = button(R.string.wallpaper_source) { chooseSource() }
        button(R.string.wallpaper_choose_images) { chooseImages() }
        button(R.string.wallpaper_next) {
            WallpaperEngine.nextImage(applicationContext)
            Toast.makeText(this, R.string.wallpaper_next_done, Toast.LENGTH_SHORT).show()
        }
        importButton = button(R.string.wallpaper_import) { openImagePicker() }
        downloadButton = button(R.string.wallpaper_import_downloads) { requestDownloadImport() }
        button(R.string.wallpaper_back) { finish() }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    private fun sourceName(source: WallpaperPreferences.Source): String = getString(when (source) {
        WallpaperPreferences.Source.AUTO -> R.string.wallpaper_auto
        WallpaperPreferences.Source.BING -> R.string.wallpaper_bing
        WallpaperPreferences.Source.LOCAL -> R.string.wallpaper_local
        WallpaperPreferences.Source.OFFLINE -> R.string.wallpaper_offline
    })

    private fun refreshSummary() {
        val source = WallpaperPreferences.getSource(this)
        sourceButton.text = getString(R.string.wallpaper_source_value, sourceName(source))
        summary.setText(R.string.wallpaper_description)
    }

    private fun chooseSource() {
        var selected = sources.indexOf(WallpaperPreferences.getSource(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_source)
            .setSingleChoiceItems(sources.map { sourceName(it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.wallpaper_apply) { _, _ ->
                WallpaperPreferences.setSource(this, sources[selected])
                WallpaperEngine.reload(applicationContext)
                refreshSummary()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseImages() {
        val source = WallpaperPreferences.getSource(this)
        val choices = WallpaperEngine.choices(this, source)
        if (choices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.wallpaper_choose_images)
                .setMessage(if (source == WallpaperPreferences.Source.LOCAL)
                    R.string.wallpaper_empty_local else R.string.wallpaper_empty_online)
                .setPositiveButton(android.R.string.ok, null).show()
            WallpaperEngine.reload(applicationContext)
            return
        }
        val saved = WallpaperPreferences.getSelectedIds(this, source)
        val available = choices.map { it.id }.toSet()
        val selected = (saved?.intersect(available)?.takeIf { it.isNotEmpty() } ?: available).toMutableSet()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_choose_images)
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(),
                BooleanArray(choices.size) { choices[it].id in selected }) { _, index, checked ->
                if (checked) selected.add(choices[index].id) else selected.remove(choices[index].id)
            }
            .setPositiveButton(R.string.wallpaper_save, null)
            .setNeutralButton(R.string.wallpaper_use_all) { _, _ ->
                WallpaperPreferences.setSelectedIds(this, source, null)
                WallpaperEngine.reload(applicationContext)
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.wallpaper_choose_one, Toast.LENGTH_SHORT).show()
                } else {
                    WallpaperPreferences.setSelectedIds(this, source, selected)
                    WallpaperEngine.reload(applicationContext)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun openImagePicker() {
        if (importing) return
        for (action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
            try {
                startActivityForResult(Intent(action).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, PICK_IMAGES)
                return
            } catch (_: ActivityNotFoundException) {
                // Some Fire TV versions only expose a picker through an installed file manager.
            }
        }
        AlertDialog.Builder(this).setTitle(R.string.wallpaper_import)
            .setMessage(R.string.wallpaper_no_picker)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun requestDownloadImport() {
        if (importing) return
        if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(this, R.string.wallpaper_download_picker_required, Toast.LENGTH_LONG).show()
            openImagePicker()
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_DOWNLOADS)
        } else importDownloads()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_DOWNLOADS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) importDownloads()
            else Toast.makeText(this, R.string.wallpaper_storage_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun importDownloads() {
        if (importing) return
        beginImport()
        val app = applicationContext
        IMPORTER.execute {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FireTV-Bilder")
            val results = runCatching {
                val candidates = directory.listFiles()
                    ?: throw IllegalStateException("Download folder missing or not readable")
                val images = candidates.filter { it.isFile && it.extension.lowercase() in WallpaperImagePolicy.extensions }
                    .sortedBy { it.name }.take(100)
                images.map { WallpaperImporter.importImage(app, Uri.fromFile(it)) }
            }.getOrNull()
            runOnUiThread {
                if (results.isNullOrEmpty()) {
                    importing = false
                    if (!isFinishing && !isDestroyed) {
                        importButton.isEnabled = true
                        downloadButton.isEnabled = true
                        AlertDialog.Builder(this).setTitle(R.string.wallpaper_import_downloads)
                            .setMessage(R.string.wallpaper_downloads_empty)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else finishImport(results)
            }
        }
    }

    private fun beginImport() {
        importSource = WallpaperPreferences.getSource(this)
        importSelection = WallpaperPreferences.getSelectedIds(this, importSource!!)
        importing = true
        importButton.isEnabled = false
        downloadButton.isEnabled = false
    }

    private fun finishImport(results: List<Boolean>) {
        importing = false
        val app = applicationContext
        val currentSource = WallpaperPreferences.getSource(app)
        val unchanged = currentSource == importSource &&
            WallpaperPreferences.getSelectedIds(app, currentSource) == importSelection
        // A newer choice (including from a newly opened settings screen) wins over this import.
        if (results.any { it } && unchanged) {
            WallpaperPreferences.setSource(app, WallpaperPreferences.Source.LOCAL)
            WallpaperPreferences.setSelectedIds(app, WallpaperPreferences.Source.LOCAL, null)
            WallpaperEngine.reload(app)
        }
        if (!isFinishing && !isDestroyed) {
            importButton.isEnabled = true
            downloadButton.isEnabled = true
            refreshSummary()
            Toast.makeText(this,
                getString(R.string.wallpaper_import_result, results.count { it }, results.count { !it }),
                Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform Activity result API to avoid additional runtime libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
            data.data?.let { add(it) }
        }.distinct().take(30)
        if (uris.isEmpty()) return
        beginImport()
        val app = applicationContext
        IMPORTER.execute {
            val results = uris.map { WallpaperImporter.importImage(app, it) }
            runOnUiThread { finishImport(results) }
        }
    }

    companion object {
        private const val PICK_IMAGES = 42
        private const val READ_DOWNLOADS = 43
        private val IMPORTER = Executors.newSingleThreadExecutor()
    }
}
