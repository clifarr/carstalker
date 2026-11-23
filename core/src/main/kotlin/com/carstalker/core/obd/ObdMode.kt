package com.carstalker.core.obd

/** OBD-II service (mode) identifiers used by CarStalker. */
object ObdMode {
    const val CURRENT_DATA = 0x01
    const val FREEZE_FRAME = 0x02
    const val STORED_DTCS = 0x03
    const val CLEAR_DTCS = 0x04
    const val O2_SENSOR_TEST = 0x05
    const val ONBOARD_MONITOR = 0x06
    const val PENDING_DTCS = 0x07
    const val CONTROL_OPERATION = 0x08
    const val VEHICLE_INFO = 0x09
    const val PERMANENT_DTCS = 0x0A
}
