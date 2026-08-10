package com.terra.sim

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Nom de fichier d'une capture d'écran — lot 2.20-a.
 *
 * Pur et testable : l'horodatage est en UTC explicite, sinon le même
 * instant produirait des noms différents selon le fuseau de l'appareil —
 * et le test serait une loterie de fuseaux. Pas de java.time : il exige
 * l'API 26 ou le désucrage, or minSdk = 24 ; GregorianCalendar suffit et
 * existe partout.
 */
object CaptureName {

    fun build(worldName: String, version: String, altitudeM: Double?, epochMillis: Long): String {
        // Assainissement PROPRE au système de fichiers, indépendant de
        // WorldNamer : un nom de monde peut porter des espaces ou des
        // accents légitimes qui n'ont rien à faire dans un nom de fichier.
        val world = worldName
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "monde" }

        val alt = when {
            altitudeM == null -> "globe"
            altitudeM >= 10_000.0 -> "${(altitudeM / 1000.0).toInt()}km"
            else -> "${altitudeM.toInt()}m"
        }

        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        val stamp = String.format(
            Locale.ROOT, "%04d%02d%02d-%02d%02d%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND)
        )
        return "terra-$world-v$version-$alt-$stamp.png"
    }
}
