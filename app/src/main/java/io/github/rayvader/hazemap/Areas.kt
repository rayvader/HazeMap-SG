package io.github.rayvader.hazemap

/** One of the 47 forecast areas NEA names in its 2-hour forecast, at its label position. */
data class Area(val name: String, val lat: Double, val lon: Double) {
    /** The NEA region (north, south, east, west, central) whose label point is closest. */
    val region: Region
        get() = Region.entries.minBy { sq(it.lat - lat) + sq(it.lon - lon) }
}

object Areas {
    const val DEFAULT_NAME = "Ang Mo Kio"

    val all: List<Area> = listOf(
        Area("Ang Mo Kio", 1.375, 103.839),
        Area("Bedok", 1.321, 103.924),
        Area("Bishan", 1.350772, 103.839),
        Area("Boon Lay", 1.304, 103.701),
        Area("Bukit Batok", 1.353, 103.754),
        Area("Bukit Merah", 1.277, 103.819),
        Area("Bukit Panjang", 1.362, 103.77195),
        Area("Bukit Timah", 1.325, 103.791),
        Area("Central Water Catchment", 1.38, 103.805),
        Area("Changi", 1.357, 103.987),
        Area("Choa Chu Kang", 1.377, 103.745),
        Area("City", 1.292, 103.844),
        Area("Clementi", 1.315, 103.76),
        Area("Geylang", 1.318, 103.884),
        Area("Hougang", 1.361218, 103.886),
        Area("Jalan Bahar", 1.347, 103.67),
        Area("Jurong East", 1.326, 103.737),
        Area("Jurong Island", 1.266, 103.699),
        Area("Jurong West", 1.34039, 103.705),
        Area("Kallang", 1.312, 103.862),
        Area("Lim Chu Kang", 1.423, 103.717332),
        Area("Mandai", 1.419, 103.812),
        Area("Marine Parade", 1.297, 103.891),
        Area("Novena", 1.327, 103.826),
        Area("Pasir Ris", 1.37, 103.948),
        Area("Paya Lebar", 1.358, 103.914),
        Area("Pioneer", 1.315, 103.675),
        Area("Pulau Tekong", 1.403, 104.053),
        Area("Pulau Ubin", 1.404, 103.96),
        Area("Punggol", 1.401, 103.904),
        Area("Queenstown", 1.291, 103.78576),
        Area("Seletar", 1.404, 103.869),
        Area("Sembawang", 1.445, 103.818495),
        Area("Sengkang", 1.384, 103.891443),
        Area("Sentosa", 1.243, 103.832),
        Area("Serangoon", 1.357, 103.865),
        Area("Southern Islands", 1.208, 103.842),
        Area("Sungei Kadut", 1.413, 103.756),
        Area("Tampines", 1.345, 103.944),
        Area("Tanglin", 1.308, 103.813),
        Area("Tengah", 1.374, 103.715),
        Area("Toa Payoh", 1.334304, 103.856327),
        Area("Tuas", 1.294947, 103.635),
        Area("Western Islands", 1.205926, 103.746),
        Area("Western Water Catchment", 1.405, 103.689),
        Area("Woodlands", 1.432, 103.786528),
        Area("Yishun", 1.418, 103.839),
    )

    val default: Area
        get() = byName(DEFAULT_NAME) ?: all.first()

    fun byName(name: String?): Area? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
