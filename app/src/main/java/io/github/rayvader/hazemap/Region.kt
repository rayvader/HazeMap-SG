package io.github.rayvader.hazemap

/** The five NEA PSI regions with their label coordinates (from the NEA API metadata). */
enum class Region(val key: String, val label: String, val lat: Double, val lon: Double) {
    NORTH("north", "North", 1.41803, 103.82),
    SOUTH("south", "South", 1.29587, 103.82),
    EAST("east", "East", 1.35735, 103.94),
    WEST("west", "West", 1.35735, 103.70),
    CENTRAL("central", "Central", 1.35735, 103.82);

    companion object {
        fun fromKey(key: String): Region? = entries.firstOrNull { it.key == key }
    }
}

/** Which number the map colors by. */
enum class Metric(val apiPath: String, val display: String) {
    PSI("psi", "PSI"),
    PM25("pm25", "PM2.5");
}
