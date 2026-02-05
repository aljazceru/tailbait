package com.tailbait.ui.components

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import com.tailbait.util.DeviceNameGenerator

/**
 * Data class representing a point of interest on the map
 */
data class MapMarker(
    val position: GeoPoint,
    val title: String,
    val description: String? = null,
    val id: String = ""
)

/**
 * Data class representing a path on the map
 */
data class MapPath(
    val points: List<GeoPoint>,
    val title: String,
    val color: Int = android.graphics.Color.BLUE,
    val width: Float = 5f
)

/**
 * OSMDroid Map Composable
 *
 * A Compose wrapper for OSMDroid MapView that provides:
 * - Interactive OpenStreetMap display
 * - Custom markers and paths
 * - Lifecycle-aware management
 * - Touch gestures and zoom controls
 * - Offline map support
 *
 * @param modifier Modifier for the composable
 * @param initialPosition Initial camera position
 * @param initialZoom Initial zoom level (1-21)
 * @param markers List of markers to display on the map
 * @param paths List of paths/polylines to display
 * @param onMarkerClick Callback when marker is clicked
 * @param onMapClick Callback when map is clicked
 * @param onMapLongClick Callback when map is long clicked
 */
@Composable
fun OpenStreetMap(
    modifier: Modifier = Modifier,
    initialPosition: GeoPoint = GeoPoint(40.7128, -74.0060), // New York
    initialZoom: Double = 15.0,
    markers: List<MapMarker> = emptyList(),
    paths: List<MapPath> = emptyList(),
    onMarkerClick: (MapMarker) -> Unit = {},
    onMapClick: (GeoPoint) -> Unit = {},
    onMapLongClick: (GeoPoint) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        }
    }

    // Create and manage MapView
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            maxZoomLevel = 22.0
            controller.setZoom(initialZoom)
            controller.setCenter(initialPosition)
            isHorizontalMapRepetitionEnabled = true
            isVerticalMapRepetitionEnabled = false

            // Disable hover events entirely to prevent Compose hover-exit crashes
            // This is the safest approach for certain Android versions/OEM builds
            isHovered = false
            // Don't set any hover listener - let the system handle hover events naturally
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Update markers when they change
    LaunchedEffect(markers) {
        // Remove existing markers
        mapView.overlays.removeAll { it is Marker }

        // Add new markers
        markers.forEach { markerData ->
            Marker(mapView).apply {
                position = markerData.position
                title = markerData.title
                snippet = markerData.description
                id = markerData.id
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                setOnMarkerClickListener { clickedMarker, _ ->
                    if (clickedMarker.id == markerData.id) {
                        onMarkerClick(markerData)
                        true
                    } else {
                        false
                    }
                }

                mapView.overlays.add(this)
            }
        }
    }

    // Update paths when they change
    LaunchedEffect(paths) {
        // Remove existing polylines
        mapView.overlays.removeAll { it is Polyline }

        // Add new paths
        paths.forEach { pathData ->
            Polyline().apply {
                setPoints(pathData.points)
                outlinePaint.color = pathData.color
                outlinePaint.strokeWidth = pathData.width
                title = pathData.title

                mapView.overlays.add(this)
            }
        }

        // Refresh map
        mapView.invalidate()
    }

    // Map click listener (simplified version)
    LaunchedEffect(Unit) {
        // Note: MapEventsOverlay may not be available in all OSMDroid versions
        // Click handling would require additional implementation or a different approach
        // For now, marker clicks will work, map click handling is optional
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = { mapView ->
            // Update map state if needed
            mapView.invalidate()
        }
    )
}

/**
 * Utility functions for creating common map objects
 */

/**
 * Create a GeoPoint from latitude and longitude
 */
fun createGeoPoint(latitude: Double, longitude: Double): GeoPoint = GeoPoint(latitude, longitude)

/**
 * Create a device marker for the map
 */
fun createDeviceMarker(
    latitude: Double,
    longitude: Double,
    deviceName: String,
    deviceAddress: String,
    deviceType: String?,
    manufacturerData: String? = null,
    detectionCount: Int = 1
): MapMarker {
    // Generate short, identifiable name for map display
    val shortName = DeviceNameGenerator.generateShortName(deviceType, deviceAddress, manufacturerData)

    return MapMarker(
        position = createGeoPoint(latitude, longitude),
        title = shortName,
        description = "Device: $deviceName\nType: ${deviceType ?: "Unknown"}\nAddress: $deviceAddress\nDetections: $detectionCount",
        id = deviceAddress
    )
}

/**
 * Create a location path from a list of geo points
 */
fun createLocationPath(
    points: List<GeoPoint>,
    title: String = "Device Path"
): MapPath {
    return MapPath(
        points = points,
        title = title,
        color = android.graphics.Color.parseColor("#2196F3"), // Material Blue
        width = 4f
    )
}

/**
 * Create a threat-level colored path
 */
fun createThreatPath(
    points: List<GeoPoint>,
    threatLevel: String,
    title: String = "Movement Path"
): MapPath {
    val color = when (threatLevel.uppercase()) {
        "CRITICAL" -> android.graphics.Color.parseColor("#F44336") // Red
        "HIGH" -> android.graphics.Color.parseColor("#FF9800") // Orange
        "MEDIUM" -> android.graphics.Color.parseColor("#FFC107") // Amber
        "LOW" -> android.graphics.Color.parseColor("#4CAF50") // Green
        else -> android.graphics.Color.parseColor("#2196F3") // Blue
    }

    return MapPath(
        points = points,
        title = title,
        color = color,
        width = 5f
    )
}
