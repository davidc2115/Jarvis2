package com.jarvis2.app.filegen

import android.content.Context
import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates a minimal, spec-valid KML placemark file — openable in Google Earth/Maps and real GIS tools. */
class KmlGenerator(private val context: Context) {

    fun generateFromCurrentLocation(label: String, location: Location?): File? {
        if (location == null) return null
        return generatePlacemark(label, location.latitude, location.longitude, location.altitude)
    }

    fun generatePlacemark(label: String, lat: Double, lon: Double, altitude: Double = 0.0): File {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>${escape(label)}</name>
                <Placemark>
                  <name>${escape(label)}</name>
                  <Point>
                    <coordinates>$lon,$lat,$altitude</coordinates>
                  </Point>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(outputDir(context), "jarvis_${stamp}.kml")
        file.writeText(kml)
        return file
    }

    /** Multiple points as one KML, e.g. exporting a set of visited places. */
    fun generatePlacemarks(points: List<Triple<String, Double, Double>>, fileLabel: String = "jarvis_points"): File {
        val placemarks = points.joinToString("\n") { (name, lat, lon) ->
            """
              <Placemark>
                <name>${escape(name)}</name>
                <Point><coordinates>$lon,$lat,0</coordinates></Point>
              </Placemark>
            """.trimIndent()
        }
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>${escape(fileLabel)}</name>
                $placemarks
              </Document>
            </kml>
        """.trimIndent()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(outputDir(context), "${fileLabel}_${stamp}.kml")
        file.writeText(kml)
        return file
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
