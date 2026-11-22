package com.example.mobilemathstudio.persistence

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.example.mobilemathstudio.math.expr.MathCaret
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.expr.MathNode
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.performance.PerformanceMode
import com.example.mobilemathstudio.settings.AnswerLayout
import com.example.mobilemathstudio.settings.AppLanguage
import com.example.mobilemathstudio.settings.AppSettings
import com.example.mobilemathstudio.settings.DecimalDisplay
import com.example.mobilemathstudio.settings.HistoryMode
import com.example.mobilemathstudio.settings.HistoryRetention
import com.example.mobilemathstudio.settings.ThemeMode
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.GraphState
import com.example.mobilemathstudio.ui.canvas.InputCell
import com.example.mobilemathstudio.math.graph.GraphViewport
import com.example.mobilemathstudio.ui.screens.CanvasItem
import com.example.mobilemathstudio.ui.theme.WindowsAccentBlue
import org.json.JSONArray
import org.json.JSONObject

data class PersistedCanvas(
    val item: CanvasItem,
    val cells: List<InputCell>,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
)

data class WorkspaceSnapshot(
    val settings: AppSettings,
    val activeCanvasId: String,
    val canvases: List<PersistedCanvas>,
    val onboardingDone: Boolean,
)

/**
 * Lightweight JSON persistence (SharedPreferences + filesDir JSON).
 * Survives Activity recreation and process death.
 */
class AppStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): WorkspaceSnapshot {
        val fallback = WorkspaceSnapshot(
            settings = defaultSettingsFromSystem(),
            activeCanvasId = "default",
            canvases = listOf(
                PersistedCanvas(
                    item = CanvasItem("default", "Untitled Canvas", "Just now"),
                    cells = emptyList(),
                ),
            ),
            onboardingDone = false,
        )
        return CrashGuard.run(fallback) {
            val settings = loadSettings()
            val onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false)
            val activeId = prefs.getString(KEY_ACTIVE_CANVAS, "default") ?: "default"
            val canvases = loadCanvases()
            WorkspaceSnapshot(
                settings = settings,
                activeCanvasId = activeId,
                canvases = canvases.ifEmpty { fallback.canvases },
                onboardingDone = onboardingDone,
            )
        }
    }

    /**
     * Serialises and writes on a background thread. Arguments are immutable
     * snapshots, so the caller (main thread) never waits for JSON or disk I/O.
     * Writes are queued in order and replace the file atomically.
     */
    fun save(
        settings: AppSettings,
        activeCanvasId: String,
        canvases: List<PersistedCanvas>,
        onboardingDone: Boolean,
    ) {
        writer.execute {
            CrashGuard.run {
                prefs.edit()
                    .putBoolean(KEY_ONBOARDING, onboardingDone)
                    .putString(KEY_ACTIVE_CANVAS, activeCanvasId)
                    .putString(KEY_SETTINGS, settingsToJson(settings).toString())
                    .apply()
                val bytes = canvasesToJson(canvases).toString().toByteArray(Charsets.UTF_8)
                val target = app.getFileStreamPath(CANVAS_FILE)
                val temp = java.io.File(target.parentFile, "$CANVAS_FILE.tmp")
                temp.outputStream().use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }
        }
    }

    fun markOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).apply()
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return defaultSettingsFromSystem()
        return runCatching { settingsFromJson(JSONObject(raw)) }.getOrElse { defaultSettingsFromSystem() }
    }

    private fun defaultSettingsFromSystem(): AppSettings {
        val night = (app.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val lang = when (app.resources.configuration.locales[0]?.language) {
            "ru" -> AppLanguage.Russian
            "en" -> AppLanguage.English
            else -> AppLanguage.System
        }
        return AppSettings(
            themeMode = if (night) ThemeMode.Dark else ThemeMode.System,
            language = lang,
        )
    }

    private fun loadCanvases(): List<PersistedCanvas> {
        val file = app.getFileStreamPath(CANVAS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            canvasesFromJson(JSONArray(text))
        }.getOrDefault(emptyList())
    }

    companion object {
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "app-store").apply { isDaemon = true }
        }

        private const val PREFS = "mathcad_store"
        private const val KEY_SETTINGS = "settings_json"
        private const val KEY_ACTIVE_CANVAS = "active_canvas"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val CANVAS_FILE = "canvases.json"

        fun settingsToJson(s: AppSettings): JSONObject = JSONObject()
            .put("themeMode", s.themeMode.name)
            .put("accent", s.accentColor.value.toString(16))
            .put("language", s.language.name)
            .put("performanceMode", s.performanceMode.name)
            .put("historyRetention", s.historyRetention.name)
            .put("historyMode", s.historyMode.name)
            .put("answerLayout", s.answerLayout.name)
            .put("decimalPlaces", s.decimalPlaces)
            .put("liquidGlassEnabled", s.liquidGlassEnabled)

        fun settingsFromJson(o: JSONObject): AppSettings {
            val accentHex = o.optString("accent", WindowsAccentBlue.value.toString(16))
            val accent = runCatching {
                Color(accentHex.toULong(16))
            }.getOrDefault(WindowsAccentBlue)
            return AppSettings(
                themeMode = runCatching { ThemeMode.valueOf(o.optString("themeMode", ThemeMode.System.name)) }
                    .getOrDefault(ThemeMode.System),
                accentColor = accent,
                language = runCatching { AppLanguage.valueOf(o.optString("language", "System")) }
                    .getOrDefault(AppLanguage.System),
                performanceMode = runCatching {
                    PerformanceMode.valueOf(o.optString("performanceMode", "Auto"))
                }.getOrDefault(PerformanceMode.Auto),
                historyRetention = runCatching {
                    HistoryRetention.valueOf(o.optString("historyRetention", "TwoDays"))
                }.getOrDefault(HistoryRetention.TwoDays),
                historyMode = runCatching {
                    HistoryMode.valueOf(o.optString("historyMode", "Auto"))
                }.getOrDefault(HistoryMode.Auto),
                answerLayout = runCatching {
                    AnswerLayout.valueOf(o.optString("answerLayout", AnswerLayout.Column.name))
                }.getOrDefault(AnswerLayout.Column),
                decimalPlaces = DecimalDisplay.clamp(
                    o.optInt("decimalPlaces", DecimalDisplay.DEFAULT_PLACES),
                ),
                liquidGlassEnabled = o.optBoolean("liquidGlassEnabled", true),
            )
        }

        fun canvasesToJson(canvases: List<PersistedCanvas>): JSONArray {
            val arr = JSONArray()
            canvases.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.item.id)
                        .put("name", c.item.name)
                        .put("updatedLabel", c.item.updatedLabel)
                        .put("pinned", c.item.pinned)
                        .put("panX", c.panX.toDouble())
                        .put("panY", c.panY.toDouble())
                        .put("zoom", c.zoom.toDouble())
                        .put("cells", JSONArray().also { ja ->
                            c.cells.forEach { cell -> ja.put(cellToJson(cell)) }
                        }),
                )
            }
            return arr
        }

        fun canvasesFromJson(arr: JSONArray): List<PersistedCanvas> {
            val out = mutableListOf<PersistedCanvas>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cellsArr = o.optJSONArray("cells") ?: JSONArray()
                val cells = mutableListOf<InputCell>()
                for (j in 0 until cellsArr.length()) {
                    cells += cellFromJson(cellsArr.getJSONObject(j))
                }
                out += PersistedCanvas(
                    item = CanvasItem(
                        id = o.getString("id"),
                        name = o.optString("name", "Canvas"),
                        updatedLabel = o.optString("updatedLabel", ""),
                        pinned = o.optBoolean("pinned", false),
                    ),
                    cells = cells,
                    panX = o.optDouble("panX", 0.0).toFloat(),
                    panY = o.optDouble("panY", 0.0).toFloat(),
                    zoom = o.optDouble("zoom", 1.0).toFloat(),
                )
            }
            return out
        }

        fun cellToJson(cell: InputCell): JSONObject = JSONObject()
            .put("id", cell.id)
            .put("x", cell.position.x.toDouble())
            .put("y", cell.position.y.toDouble())
            .put("kind", cell.kind.name)
            .put("document", docToJson(cell.document))
            .put("equations", JSONArray().also { a ->
                cell.equations.forEach { a.put(docToJson(it)) }
            })
            .put("activeEquationIndex", cell.activeEquationIndex)
            .put("scale", cell.scale.toDouble())
            .put("resultExact", cell.resultExact)
            .put("resultDecimal", cell.resultDecimal)
            .put("showDecimal", cell.showDecimal)
            .put("selected", cell.selected)
            .put("linkedCellIds", JSONArray(cell.graph?.linkedCellIds ?: emptyList<String>()))
            .put("gx0", cell.graph?.viewport?.xMin ?: -10.0)
            .put("gx1", cell.graph?.viewport?.xMax ?: 10.0)
            .put("gy0", cell.graph?.viewport?.yMin ?: -10.0)
            .put("gy1", cell.graph?.viewport?.yMax ?: 10.0)

        fun cellFromJson(o: JSONObject): InputCell {
            val eqs = mutableListOf<MathDocument>()
            val eqArr = o.optJSONArray("equations") ?: JSONArray()
            for (i in 0 until eqArr.length()) {
                eqs += docFromJson(eqArr.getJSONObject(i))
            }
            return InputCell(
                id = o.getString("id"),
                position = Offset(o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat()),
                kind = runCatching { CellKind.valueOf(o.optString("kind", "Formula")) }
                    .getOrDefault(CellKind.Formula),
                document = docFromJson(o.optJSONObject("document") ?: JSONObject()),
                equations = eqs,
                activeEquationIndex = o.optInt("activeEquationIndex", 0),
                graph = GraphState(
                    linkedCellIds = run {
                        val arr = o.optJSONArray("linkedCellIds") ?: JSONArray()
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    viewport = GraphViewport(
                        xMin = o.optDouble("gx0", -10.0),
                        xMax = o.optDouble("gx1", 10.0),
                        yMin = o.optDouble("gy0", -10.0),
                        yMax = o.optDouble("gy1", 10.0),
                    ).sanitized(),
                ).takeIf { o.optString("kind") == CellKind.Graph.name },
                scale = o.optDouble("scale", 1.0).toFloat(),
                resultExact = if (!o.has("resultExact") || o.isNull("resultExact")) null
                else o.optString("resultExact").ifBlank { null },
            resultDecimal = if (!o.has("resultDecimal") || o.isNull("resultDecimal")) null
                else o.optString("resultDecimal").ifBlank { null },
                showDecimal = o.optBoolean("showDecimal", false),
                selected = o.optBoolean("selected", false),
            )
        }

        fun docToJson(doc: MathDocument): JSONObject = JSONObject()
            .put("root", nodeToJson(doc.root))
            .put("path", JSONArray(doc.caret.path))
            .put("offset", doc.caret.offset)

        fun docFromJson(o: JSONObject): MathDocument {
            val rootNode = nodeFromJson(o.optJSONObject("root") ?: JSONObject().put("t", "row").put("c", JSONArray()))
            val root = rootNode as? MathNode.Row ?: MathNode.Row(listOf(MathNode.Hole()))
            val pathArr = o.optJSONArray("path") ?: JSONArray()
            val path = (0 until pathArr.length()).map { pathArr.getInt(it) }
            return MathDocument(
                root = root,
                caret = MathCaret(path.ifEmpty { listOf(0) }, o.optInt("offset", 0)),
            )
        }

        fun nodeToJson(node: MathNode): JSONObject = when (node) {
            is MathNode.Hole -> JSONObject().put("t", "hole").put("id", node.id)
            is MathNode.Text -> JSONObject().put("t", "text").put("v", node.value)
            is MathNode.Row -> JSONObject().put("t", "row").put(
                "c",
                JSONArray().also { a -> node.children.forEach { a.put(nodeToJson(it)) } },
            )
            is MathNode.Fraction -> JSONObject().put("t", "frac")
                .put("n", nodeToJson(node.num)).put("d", nodeToJson(node.den))
            is MathNode.Power -> JSONObject().put("t", "pow")
                .put("b", nodeToJson(node.base)).put("e", nodeToJson(node.exp))
            is MathNode.Root -> JSONObject().put("t", "root")
                .put("rad", nodeToJson(node.radicand))
                .put("idx", node.index?.let { nodeToJson(it) })
            is MathNode.Parens -> JSONObject().put("t", "par").put("i", nodeToJson(node.inner))
        }

        fun nodeFromJson(o: JSONObject): MathNode = when (o.optString("t")) {
            "hole" -> MathNode.Hole(o.optString("id", UUID_FALLBACK()))
            "text" -> MathNode.Text(o.optString("v", ""))
            "row" -> {
                val c = o.optJSONArray("c") ?: JSONArray()
                val kids = (0 until c.length()).map { nodeFromJson(c.getJSONObject(it)) }
                MathNode.Row(kids.ifEmpty { listOf(MathNode.Hole()) })
            }
            "frac" -> MathNode.Fraction(
                nodeFromJson(o.getJSONObject("n")),
                nodeFromJson(o.getJSONObject("d")),
            )
            "pow" -> MathNode.Power(
                nodeFromJson(o.getJSONObject("b")),
                nodeFromJson(o.getJSONObject("e")),
            )
            "root" -> MathNode.Root(
                index = o.optJSONObject("idx")?.let { nodeFromJson(it) },
                radicand = nodeFromJson(o.getJSONObject("rad")),
            )
            "par" -> MathNode.Parens(nodeFromJson(o.getJSONObject("i")))
            else -> MathNode.Hole()
        }

        private fun UUID_FALLBACK(): String = java.util.UUID.randomUUID().toString()
    }
}
