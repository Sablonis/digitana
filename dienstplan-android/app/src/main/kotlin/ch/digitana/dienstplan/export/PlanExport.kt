package ch.digitana.dienstplan.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.plan.CalendarExport
import ch.digitana.dienstplan.core.plan.DayCoverage
import ch.digitana.dienstplan.core.plan.DayInfo
import ch.digitana.dienstplan.core.plan.MemberRow
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.theme.LightShiftPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Ausgabeformat beim Teilen. */
enum class ExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    PNG("png", "image/png"),
}

/**
 * Plan als PDF oder Bild und eigene Dienste als Kalenderdatei. Die Dateien liegen nur kurz im
 * Cache-Ordner (nicht im Backup) und werden über einen FileProvider mit Leserecht geteilt;
 * beim nächsten Start werden sie gelöscht. Geteilte Dateien sind nicht verschlüsselt.
 */
object PlanExport {
    private const val DIR = "exports"
    private const val AUTHORITY_SUFFIX = ".exports"

    /** Alte Exporte entfernen (beim Start der App). */
    fun cleanUp(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    suspend fun shareWeek(context: Context, plan: PlanState, week: WeekId, today: LocalDate, teamName: String?, format: ExportFormat) {
        val model = WeekModel.build(plan, week, today)
        val title = listOfNotNull(teamName?.ifBlank { null }, "${WeekFormat.weekLabel(week)} · ${WeekFormat.rangeLabel(week)}").joinToString(" – ")
        val table = ExportTable(title, model.days, model.rows, model.coverage, legendTypes(model.rows), compact = false)
        val file = withContext(Dispatchers.Default) { render(context, table, format, "dienstplan-${week.bucketName}") }
        share(context, file, format.mimeType, context.getString(R.string.share_week_chooser))
    }

    suspend fun shareMonth(context: Context, plan: PlanState, month: YearMonth, today: LocalDate, teamName: String?, format: ExportFormat) {
        val model = MonthModel.build(plan, month, today)
        val title = listOfNotNull(teamName?.ifBlank { null }, Format.monthLabel(month)).joinToString(" – ")
        val table = ExportTable(title, model.days, model.rows, model.coverage, legendTypes(model.rows), compact = true)
        val file = withContext(Dispatchers.Default) { render(context, table, format, "dienstplan-$month") }
        share(context, file, format.mimeType, context.getString(R.string.share_month_chooser))
    }

    /** Eigene Dienste der nächsten [weeks] Wochen als .ics, bevorzugt direkt in eine Kalender-App. */
    suspend fun shareCalendar(context: Context, plan: PlanState, memberId: String, today: LocalDate, weeks: Int = 12) {
        val file = withContext(Dispatchers.IO) {
            val ics = CalendarExport.ics(plan, memberId, today, weeks * 7, ZoneId.systemDefault(), Instant.now())
            exportFile(context, "dienste.ics").apply { writeText(ics, Charsets.UTF_8) }
        }
        val uri = uriFor(context, file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/calendar")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Gibt es eine Kalender-App, die .ics öffnet (siehe <queries> im Manifest)? Sonst teilen.
        if (view.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(
                    Intent.createChooser(view, context.getString(R.string.share_calendar_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            } catch (e: ActivityNotFoundException) {
                // weiter mit Teilen
            }
        }
        share(context, file, "text/calendar", context.getString(R.string.share_calendar_chooser))
    }

    private fun legendTypes(rows: List<MemberRow>): List<ShiftType> =
        rows.flatMap { row -> row.cells.mapNotNull { it.type } }.distinct().sortedBy { it.kind.ordinal * 10_000 + (it.start?.toSecondOfDay()?.div(60) ?: 9_999) }

    private fun exportFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, name)
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    private fun share(context: Context, file: File, mimeType: String, title: String) {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun render(context: Context, table: ExportTable, format: ExportFormat, baseName: String): File {
        val file = exportFile(context, "$baseName.${format.extension}")
        when (format) {
            ExportFormat.PDF -> {
                // A4 quer in Punkten; die Tabelle wird bei Bedarf verkleinert.
                val pageWidth = 842
                val pageHeight = 595
                val document = PdfDocument()
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
                    val canvas = page.canvas
                    val margin = 24f
                    val size = TableRenderer.measure(table, pageWidth - 2 * margin)
                    val scale = minOf(1f, (pageHeight - 2 * margin) / size.second)
                    canvas.translate(margin, margin)
                    canvas.scale(scale, scale)
                    TableRenderer.draw(canvas, table, (pageWidth - 2 * margin) / scale)
                    document.finishPage(page)
                    file.outputStream().use { document.writeTo(it) }
                } finally {
                    document.close()
                }
            }
            ExportFormat.PNG -> {
                val width = if (table.compact) 2400f else 1400f
                val scale = 2f
                val size = TableRenderer.measure(table, width / scale)
                val bitmap = createBitmap(width.toInt(), (size.second * scale + 48).toInt())
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.translate(24f, 24f)
                    canvas.scale(scale, scale)
                    TableRenderer.draw(canvas, table, (width - 48f) / scale)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return file
    }
}

/** Was in der Tabelle steht – unabhängig von Woche oder Monat. */
internal data class ExportTable(
    val title: String,
    val days: List<DayInfo>,
    val rows: List<MemberRow>,
    val coverage: List<DayCoverage>,
    val legend: List<ShiftType>,
    /** Monat: schmale Spalten nur mit Kürzel. */
    val compact: Boolean,
)

/** Zeichnet die Tabelle auf ein Canvas (Einheiten: PDF-Punkte bzw. skalierte Pixel). */
internal object TableRenderer {
    private const val TITLE_SIZE = 16f
    private const val TEXT_SIZE = 9f
    private const val SMALL_SIZE = 7f
    private const val HEADER_HEIGHT = 28f
    private const val ROW_HEIGHT = 24f
    private const val NAME_WIDTH = 110f
    private const val HOURS_WIDTH = 44f
    private const val GAP = 10f

    private val palette = LightShiftPalette
    private val black = android.graphics.Color.rgb(0x1B, 0x1B, 0x20)
    private val gray = android.graphics.Color.rgb(0x76, 0x76, 0x82)
    private val line = android.graphics.Color.rgb(0xE4, 0xE1, 0xE7)
    private val weekend = android.graphics.Color.rgb(0xF0, 0xED, 0xF3)

    private fun paint(size: Float, bold: Boolean = false, color: Int = black, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
            textAlign = align
        }

    /** Breite und Höhe der Tabelle bei gegebener Breite. */
    fun measure(table: ExportTable, width: Float): Pair<Float, Float> {
        val legendRows = (table.legend.size + 2) / 3
        val height = TITLE_SIZE + GAP + HEADER_HEIGHT + table.rows.size * ROW_HEIGHT + ROW_HEIGHT + GAP + legendRows * 16f + 20f
        return width to height
    }

    fun draw(canvas: Canvas, table: ExportTable, width: Float) {
        var y = TITLE_SIZE
        canvas.drawText(table.title, 0f, y, paint(TITLE_SIZE, bold = true))
        y += GAP
        val dayWidth = (width - NAME_WIDTH - HOURS_WIDTH) / table.days.size
        val text = paint(TEXT_SIZE)
        val small = paint(SMALL_SIZE, color = gray, align = Paint.Align.CENTER)
        val center = paint(TEXT_SIZE, bold = true, align = Paint.Align.CENTER)
        val lines = Paint().apply { color = line; strokeWidth = 0.6f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Spaltenhintergrund fürs Wochenende.
        val tableTop = y
        val tableBottom = y + HEADER_HEIGHT + (table.rows.size + 1) * ROW_HEIGHT
        table.days.forEachIndexed { i, day ->
            if (day.isWeekend) {
                fill.color = weekend
                canvas.drawRect(NAME_WIDTH + i * dayWidth, tableTop, NAME_WIDTH + (i + 1) * dayWidth, tableBottom, fill)
            }
        }

        // Kopf
        canvas.drawText("Team", 4f, y + 17f, paint(TEXT_SIZE, bold = true))
        table.days.forEachIndexed { i, day ->
            val cx = NAME_WIDTH + (i + 0.5f) * dayWidth
            canvas.drawText(WeekFormat.weekday(day.date), cx, y + 11f, small)
            canvas.drawText(if (table.compact) day.date.dayOfMonth.toString() else WeekFormat.shortDate(day.date), cx, y + 23f, center)
        }
        canvas.drawText("Std.", width - HOURS_WIDTH / 2, y + 17f, paint(TEXT_SIZE, bold = true, align = Paint.Align.CENTER))
        y += HEADER_HEIGHT
        canvas.drawLine(0f, y, width, y, lines)

        // Zeilen
        for (row in table.rows) {
            canvas.drawText(ellipsize(row.member.name, text, NAME_WIDTH - 8f), 4f, y + 16f, text)
            row.cells.forEachIndexed { i, cell ->
                val type = cell.type
                val wish = cell.wish
                if (cell.typeId != null) {
                    val color = palette.of(type)
                    val rect = RectF(NAME_WIDTH + i * dayWidth + 1.5f, y + 2.5f, NAME_WIDTH + (i + 1) * dayWidth - 1.5f, y + ROW_HEIGHT - 2.5f)
                    fill.color = color.container.toArgb()
                    canvas.drawRoundRect(rect, 4f, 4f, fill)
                    val codePaint = paint(TEXT_SIZE, bold = true, color = color.content.toArgb(), align = Paint.Align.CENTER)
                    val time = type?.let { Format.shortTimeRange(it) }
                    if (!table.compact && type != null && time != null) {
                        canvas.drawText(type.code, rect.centerX(), y + 11.5f, codePaint)
                        canvas.drawText(time, rect.centerX(), y + 19.5f, paint(SMALL_SIZE - 1f, color = color.content.toArgb(), align = Paint.Align.CENTER))
                    } else {
                        canvas.drawText(type?.code ?: "?", rect.centerX(), y + 15.5f, codePaint)
                    }
                } else if (wish != null && !table.compact) {
                    canvas.drawText(wish.code, NAME_WIDTH + (i + 0.5f) * dayWidth, y + 15.5f, small)
                }
            }
            canvas.drawText(Format.hours(row.minutes), width - HOURS_WIDTH / 2, y + 16f, paint(TEXT_SIZE, align = Paint.Align.CENTER))
            y += ROW_HEIGHT
            canvas.drawLine(0f, y, width, y, lines)
        }

        // Besetzung (Arbeitsschichten)
        canvas.drawText("Besetzung", 4f, y + 16f, paint(TEXT_SIZE, bold = true))
        table.coverage.forEachIndexed { i, coverage ->
            canvas.drawText(coverage.total.toString(), NAME_WIDTH + (i + 0.5f) * dayWidth, y + 16f, paint(TEXT_SIZE, align = Paint.Align.CENTER))
        }
        canvas.drawText(Format.hours(table.rows.sumOf { it.minutes }), width - HOURS_WIDTH / 2, y + 16f, paint(TEXT_SIZE, bold = true, align = Paint.Align.CENTER))
        y += ROW_HEIGHT + GAP

        // Legende
        val columnWidth = width / 3
        table.legend.forEachIndexed { i, type ->
            val x = (i % 3) * columnWidth
            val ly = y + (i / 3) * 16f
            val color = palette.of(type)
            fill.color = color.container.toArgb()
            canvas.drawRoundRect(RectF(x, ly, x + 22f, ly + 12f), 3f, 3f, fill)
            canvas.drawText(type.code, x + 11f, ly + 9f, paint(SMALL_SIZE, bold = true, color = color.content.toArgb(), align = Paint.Align.CENTER))
            val label = listOfNotNull(type.name, Format.timeRange(type)).joinToString(" · ")
            canvas.drawText(ellipsize(label, text, columnWidth - 32f), x + 28f, ly + 9.5f, text)
        }
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }
}
