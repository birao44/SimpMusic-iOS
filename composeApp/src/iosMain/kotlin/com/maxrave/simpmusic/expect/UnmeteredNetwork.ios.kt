package com.maxrave.simpmusic.expect

import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/*
 * Android's NET_CAPABILITY_NOT_METERED, read from NWPathMonitor: a usable path that is neither
 * expensive (cellular, or Wi-Fi tethered to a phone's hotspot) nor constrained (Low Data Mode).
 * Until the monitor's first update arrives the answer is "metered", so nothing large starts on a
 * connection that has not been checked yet.
 */

@Volatile
private var unmetered = false

private val pathMonitor: nw_path_monitor_t by lazy {
    nw_path_monitor_create().also { monitor ->
        nw_path_monitor_set_update_handler(monitor) { path ->
            unmetered =
                nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path) &&
                !nw_path_is_constrained(path)
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.maxrave.simpmusic.networkpath", null))
        nw_path_monitor_start(monitor)
    }
}

/** Starts watching the network early so the first [isOnUnmeteredNetwork] already has an answer. */
internal fun startNetworkPathMonitor() {
    pathMonitor
}

actual fun isOnUnmeteredNetwork(): Boolean {
    pathMonitor
    return unmetered
}
