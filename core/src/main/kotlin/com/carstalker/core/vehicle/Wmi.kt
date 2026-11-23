package com.carstalker.core.vehicle

/**
 * Small, offline World Manufacturer Identifier lookup for common passenger cars.
 *
 * WMI prefixes are public registration data, but they are not a complete vehicle decoder: a
 * prefix identifies a manufacturer (and sometimes a plant or model line), not the exact model.
 * The UI labels this as a heuristic and the user can override it in a future vehicle profile.
 */
object Wmi {

    private val makes: Map<String, String> = mapOf(
        // Honda / Acura
        "1HG" to "Honda", "2HG" to "Honda", "3HG" to "Honda", "JHM" to "Honda",
        "19X" to "Honda", "5FN" to "Honda", "JH4" to "Acura", "19U" to "Acura",
        // Subaru
        "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru", "4S4" to "Subaru",
        // Toyota / Lexus
        "JTD" to "Toyota", "JTE" to "Toyota", "JTF" to "Toyota", "JTG" to "Toyota",
        "JTH" to "Lexus", "4T1" to "Toyota", "4T3" to "Toyota", "5TD" to "Toyota",
        "2T1" to "Toyota", "2T3" to "Toyota", "5TF" to "Toyota",
        // Ford / Lincoln
        "1FA" to "Ford", "1FB" to "Ford", "1FC" to "Ford", "1FD" to "Ford",
        "1FT" to "Ford", "1FM" to "Ford", "2FT" to "Ford", "3FT" to "Ford",
        "5LM" to "Lincoln", "1LN" to "Lincoln",
        // General Motors
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "2G1" to "Chevrolet", "3G1" to "Chevrolet",
        "1GT" to "GMC", "1GK" to "GMC", "1G4" to "Buick", "1G6" to "Cadillac",
        "1GY" to "Cadillac", "1GB" to "Chevrolet",
        // Volkswagen Group
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen",
        "3VW" to "Volkswagen", "1VW" to "Volkswagen", "WAU" to "Audi", "WA1" to "Audi",
        "TRU" to "Audi", "TMB" to "Škoda", "TMP" to "Škoda", "VSS" to "SEAT",
        // BMW / Mercedes / Porsche
        "WBA" to "BMW", "WBS" to "BMW", "4US" to "BMW", "5UX" to "BMW",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "4JG" to "Mercedes-Benz",
        "WP0" to "Porsche", "WP1" to "Porsche",
        // Nissan / Infiniti
        "JN1" to "Nissan", "JN8" to "Nissan", "5N1" to "Nissan", "1N4" to "Nissan",
        "JNK" to "Infiniti", "JNR" to "Infiniti",
        // Hyundai / Kia
        "KMH" to "Hyundai", "5NP" to "Hyundai", "5NM" to "Hyundai",
        "KNA" to "Kia", "5XY" to "Kia", "KND" to "Kia",
        // Mazda
        "JM1" to "Mazda", "JM3" to "Mazda", "4F2" to "Mazda", "4F4" to "Mazda",
        // Tesla
        "5YJ" to "Tesla", "7SA" to "Tesla", "7G2" to "Tesla",
        // Volvo / Jaguar Land Rover
        "YV1" to "Volvo", "YV4" to "Volvo", "SAL" to "Land Rover", "SAJ" to "Jaguar",
        // Stellantis Europe
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "ZFA" to "Fiat",
        "ZAR" to "Alfa Romeo", "ZAM" to "Maserati", "UU1" to "Dacia",
        // Stellantis North America
        "1C3" to "Chrysler", "1C4" to "Jeep", "1J4" to "Jeep", "1B3" to "Dodge",
        "3C4" to "Chrysler", "3C6" to "Ram", "1D3" to "Dodge",
    )

    fun make(vin: String?): String? {
        if (vin == null || vin.length < 3) return null
        return makes[vin.take(3).uppercase()]
    }
}
