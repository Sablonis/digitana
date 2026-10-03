package ch.digitana.dienstplan.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Kurze Bewegung, z. B. Ausblenden (ms). */
const val MOTION_SHORT = 120

/** Übliche Bewegung: Seiten, Reiter, Karten (ms). */
const val MOTION_MEDIUM = 260

/** Längere Bewegung, z. B. bis ein Zurückwischen ganz abgeschlossen ist (ms). */
const val MOTION_LONG = 420

/** Federnd, aber ohne Nachschwingen: für Grössen- und Farbwechsel im Raster. */
fun <T> gentleSpring(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Leicht nachschwingend: für Hervorhebungen (z. B. das gewählte Feld). */
fun <T> bouncySpring(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
