package com.tailbait.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Permissions : Screen("permissions")
    object Home : Screen("home")
    object DeviceList : Screen("device_list")
    object Map : Screen("map") {
        const val DEVICE_ID_ARG = "deviceId"
        val routeWithArgs = "$route?$DEVICE_ID_ARG={$DEVICE_ID_ARG}"
        fun createRoute(deviceId: Long? = null): String {
            return if (deviceId != null) "$route?$DEVICE_ID_ARG=$deviceId" else route
        }
    }
    object Alerts : Screen("alerts")
    object AlertDetail : Screen("alert_detail")
    object Whitelist : Screen("whitelist")
    object Settings : Screen("settings")
    object Help : Screen("help")
    object LearnMode : Screen("learn_mode")
}
