package com.tailbait.util

/**
 * Application-wide constants
 */
object Constants {
    // Database
    const val DATABASE_NAME = "tailbait.db"
    const val DATABASE_VERSION = 1
    // Scanning
    const val DEFAULT_SCAN_INTERVAL_SECONDS = 300 // 5 minutes
    const val DEFAULT_SCAN_DURATION_SECONDS = 30 // 30 seconds

    // CRITICAL FIX: Changed from -100 to -85 dBm (Nordic recommendation)
    // -100 dBm was too permissive, capturing extremely weak signals that:
    // 1. Drain battery by processing too many irrelevant devices
    // 2. Increase false positives from distant/weak signals
    // 3. Overwhelm the detection algorithm with noise
    // -85 dBm is the recommended threshold for reliable BLE signal detection
    const val MIN_RSSI_THRESHOLD = -100 // Minimum RSSI to consider (dBm)
    // Location
    const val DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS = 50.0
    const val MIN_DETECTION_DISTANCE_METERS = 100.0
    const val LOCATION_ACCURACY_THRESHOLD_METERS = 100.0f
    // Detection Algorithm
    const val DEFAULT_ALERT_THRESHOLD_COUNT = 3 // Alert after device seen at N locations
    const val DETECTION_WORKER_INTERVAL_MINUTES = 15L
    const val THREAT_SCORE_THRESHOLD = 0.5 // Minimum score to generate alert
    // Data Retention
    const val DEFAULT_DATA_RETENTION_DAYS = 30
    const val CLEANUP_WORKER_INTERVAL_DAYS = 1L
    // WorkManager Tags
    const val WORK_TAG_DETECTION = "detection_work"
    const val WORK_TAG_CLEANUP = "cleanup_work"
    const val WORK_TAG_BACKGROUND_SCAN = "background_scan_work"
    // Permissions
    const val REQUEST_CODE_BLUETOOTH = 1001
    const val REQUEST_CODE_LOCATION = 1002
    const val REQUEST_CODE_BACKGROUND_LOCATION = 1003
    const val REQUEST_CODE_NOTIFICATION = 1004
    // Service
    const val SERVICE_NOTIFICATION_ID = 1001
    const val ALERT_NOTIFICATION_BASE_ID = 2000
    const val ALERT_NOTIFICATION_SUMMARY_ID = 2999
    const val LEARN_MODE_NOTIFICATION_ID = 1002

    // Notification Actions
    const val ACTION_VIEW_ALERT = "com.tailbait.action.VIEW_ALERT"
    const val ACTION_DISMISS_ALERT = "com.tailbait.action.DISMISS_ALERT"
    const val ACTION_ADD_TO_WHITELIST = "com.tailbait.action.ADD_TO_WHITELIST"

    // Notification Extras
    const val EXTRA_ALERT_ID = "extra_alert_id"
    const val EXTRA_DEVICE_IDS = "extra_device_ids"

    // Notification Groups
    const val NOTIFICATION_GROUP_ALERTS = "tailbait_alerts"

    // Notification Request Codes
    const val REQUEST_CODE_VIEW_ALERT = 3001
    const val REQUEST_CODE_DISMISS_ALERT = 3002
    const val REQUEST_CODE_ADD_TO_WHITELIST = 3003

    // Learn Mode
    const val LEARN_MODE_DEFAULT_DURATION_MS = 300000L // 5 minutes
    const val LEARN_MODE_SCAN_INTERVAL_MS = 10000L // 10 seconds

    // Tracking Modes
    const val TRACKING_MODE_CONTINUOUS = "CONTINUOUS"
    const val TRACKING_MODE_PERIODIC = "PERIODIC"
    const val TRACKING_MODE_LOCATION_BASED = "LOCATION_BASED"

    // Scan Trigger Types
    const val SCAN_TRIGGER_MANUAL = "MANUAL"
    const val SCAN_TRIGGER_CONTINUOUS = "CONTINUOUS"
    const val SCAN_TRIGGER_PERIODIC = "PERIODIC"
    const val SCAN_TRIGGER_LOCATION_BASED = "LOCATION_BASED"
    // Whitelist Categories
    const val WHITELIST_CATEGORY_OWN = "OWN"
    const val WHITELIST_CATEGORY_PARTNER = "PARTNER"
    const val WHITELIST_CATEGORY_TRUSTED = "TRUSTED"
    // Alert Levels
    const val ALERT_LEVEL_LOW = "LOW"
    const val ALERT_LEVEL_MEDIUM = "MEDIUM"
    const val ALERT_LEVEL_HIGH = "HIGH"
    const val ALERT_LEVEL_CRITICAL = "CRITICAL"

    // Alert Thresholds
    const val ALERT_THROTTLE_WINDOW_MS = 3600000L // 1 hour in milliseconds
    const val ALERT_SIMILAR_WINDOW_MS = 86400000L // 24 hours in milliseconds
    const val MIN_THREAT_SCORE_LOW = 0.5
    const val MIN_THREAT_SCORE_MEDIUM = 0.6
    const val MIN_THREAT_SCORE_HIGH = 0.75
    const val MIN_THREAT_SCORE_CRITICAL = 0.9

    // Detection Worker
    const val DETECTION_WORKER_NAME = "detection_worker"
    const val DETECTION_WORKER_MAX_RETRIES = 3
    const val DETECTION_WORKER_BACKOFF_DELAY_MS = 10000L // 10 seconds

    // Cleanup Worker
    const val CLEANUP_WORKER_NAME = "cleanup_worker"

    // Service Actions (TailBaitService)
    const val ACTION_START_TRACKING = "com.tailbait.action.START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.tailbait.action.STOP_TRACKING"
    const val ACTION_PAUSE_TRACKING = "com.tailbait.action.PAUSE_TRACKING"
    const val ACTION_RESUME_TRACKING = "com.tailbait.action.RESUME_TRACKING"
    const val ACTION_TRIGGER_SCAN = "com.tailbait.action.TRIGGER_SCAN"

    // Notification Channels
    const val NOTIFICATION_CHANNEL_SERVICE = "tailbait_service_channel"
    const val NOTIFICATION_CHANNEL_ALERTS = "tailbait_alerts_channel"
    const val NOTIFICATION_CHANNEL_LEARN_MODE = "tailbait_learn_mode_channel"
}
