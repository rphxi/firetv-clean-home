package tv.minimal.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast

/** Remote-friendly customization; also works when launched from the accessibility overlay. */
class CustomizeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(56), dpi(24), dpi(56), dpi(24))
        }
        column.addView(TextView(this).apply {
            text = "Launcher anpassen"
            textSize = 26f
        })
        column.addView(Button(this).apply {
            text = "Schnellzugriffe auswählen"
            setOnClickListener { chooseFavorites() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(Button(this).apply {
            setText(R.string.reorder_favorites)
            setOnClickListener { reorderFavorites() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(Button(this).apply {
            setText(R.string.wallpaper_title)
            setOnClickListener { startActivity(Intent(this@CustomizeActivity, WallpaperPickerActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(Button(this).apply {
            text = "Home-Taste einrichten"
            setOnClickListener {
                AlertDialog.Builder(this@CustomizeActivity)
                    .setTitle("Home-Taste")
                    .setMessage("Aktiviere in den Bedienungshilfen den Dienst »Home button redirect«. Wird der Dienst nicht angezeigt, kann er über ADB eingerichtet werden. Amazons Launcher bleibt installiert. Ob Fire OS 8 die Umleitung zulässt, muss auf deinem Gerät geprüft werden.")
                    .setPositiveButton("Bedienungshilfen öffnen") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(this@CustomizeActivity,
                                "Bitte Einstellungen → Barrierefreiheit am Fire TV öffnen.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Abbrechen", null).show()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(this).apply { textSize = 16f }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "Zurück zum Startbildschirm"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (HomeRedirectService.instance != null) "Home-Umleitung: Dienst verbunden"
            else "Home-Umleitung: Dienst noch nicht verbunden"
    }

    private fun reorderFavorites(focusIndex: Int = 0) {
        val favorites = Apps.catalog(this).favorites
        if (favorites.size < 2) {
            Toast.makeText(this, R.string.reorder_needs_apps, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reorder_favorites)
            .setItems(favorites.map { it.label }.toTypedArray()) { _, index ->
                val offsets = buildList {
                    if (index > 0) add(-1)
                    if (index < favorites.lastIndex) add(1)
                }
                AlertDialog.Builder(this)
                    .setTitle(favorites[index].label)
                    .setItems(offsets.map {
                        getString(if (it < 0) R.string.move_left else R.string.move_right)
                    }.toTypedArray()) { _, action ->
                        val offset = offsets[action]
                        Apps.setFavorites(this, FavoriteOrder.move(
                            favorites.map { it.pkg }, index, offset))
                        reorderFavorites(index + offset)
                    }
                    .setNegativeButton(R.string.back_to_order) { _, _ -> reorderFavorites(index) }
                    .setOnCancelListener { reorderFavorites(index) }
                    .show()
            }
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.show()
        // Stay on the moved tile so repeated moves only need the remote's Select button.
        dialog.listView.setSelection(focusIndex.coerceIn(favorites.indices))
        dialog.listView.requestFocus()
    }

    private fun chooseFavorites() {
        val catalog = Apps.catalog(this)
        val entries = catalog.favorites + catalog.others
        // Retain the existing dock order; newly selected apps are appended in selection order.
        val selected = catalog.favorites.map { it.pkg }.toMutableList()
        AlertDialog.Builder(this)
            .setTitle("Schnellzugriffe auswählen")
            .setMultiChoiceItems(entries.map { it.label }.toTypedArray(),
                BooleanArray(entries.size) { entries[it].pkg in selected }) { _, index, checked ->
                val pkg = entries[index].pkg
                if (checked) { if (pkg !in selected) selected.add(pkg) } else selected.remove(pkg)
            }
            .setPositiveButton("Speichern") { _, _ -> Apps.setFavorites(this, selected) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
