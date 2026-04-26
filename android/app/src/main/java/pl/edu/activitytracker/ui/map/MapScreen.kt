package pl.edu.activitytracker.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.RouteSegmenter
import pl.edu.activitytracker.domain.label
import pl.edu.activitytracker.permissions.AppPermissions
import kotlin.math.abs

@Composable
fun MapScreen(
    paddingValues: PaddingValues,
    state: TrackerState,
    onLocationPermissionGranted: () -> Unit,
    onMapVisible: () -> Unit,
    onMapHidden: () -> Unit,
) {
    val context = LocalContext.current
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    val hasAutoCentered = remember { mutableStateOf(false) }
    val hasRequestedLocationPermission = remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = AppPermissions.locationPermissions.any { permissions[it] == true }
        if (granted) {
            onLocationPermissionGranted()
        }
    }

    DisposableEffect(Unit) {
        onMapVisible()
        onDispose { onMapHidden() }
    }

    LaunchedEffect(Unit) {
        if (
            !AppPermissions.hasLocationPermission(context) &&
            !hasRequestedLocationPermission.value
        ) {
            hasRequestedLocationPermission.value = true
            permissionLauncher.launch(AppPermissions.locationPermissions)
        }
    }

    LaunchedEffect(state.currentLocation, state.route.size) {
        val mapView = mapViewState.value ?: return@LaunchedEffect
        if (!hasAutoCentered.value && hasCurrentPosition(state.currentLocation, state.route)) {
            centerOnCurrentLocation(
                mapView = mapView,
                currentLocation = state.currentLocation,
                route = state.route,
            )
            hasAutoCentered.value = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    mapViewState.value = this
                }
            },
            update = { mapView ->
                mapViewState.value = mapView
                renderRoute(
                    mapView = mapView,
                    route = state.route,
                    currentLocation = state.currentLocation,
                )
            },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Route", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.route.size} GPS points | ${state.locationStatus.label()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActivityLegend()
            }
        }

        FloatingActionButton(
            onClick = {
                mapViewState.value?.let { mapView ->
                    centerOnCurrentLocation(
                        mapView = mapView,
                        currentLocation = state.currentLocation,
                        route = state.route,
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Center on current location")
        }
    }
}

private fun renderRoute(
    mapView: MapView,
    route: List<RoutePoint>,
    currentLocation: LocationSample?,
) {
    mapView.overlays.clear()

    RouteSegmenter.movingSegments(route).forEach { segment ->
        val points = listOf(
            GeoPoint(segment.start.latitude, segment.start.longitude),
            GeoPoint(segment.end.latitude, segment.end.longitude),
        )
        mapView.overlays.add(polyline(points, AndroidColor.WHITE, 13f))
        mapView.overlays.add(polyline(points, colorFor(segment.activity), 8f))
    }

    stationaryClusters(route).forEach { cluster ->
        val marker = Marker(mapView).apply {
            position = GeoPoint(cluster.latitude, cluster.longitude)
            title = cluster.activity.displayName
            snippet = "${cluster.count} GPS points"
            icon = stopIcon(mapView, cluster.activity)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
    }

    val currentPosition = currentLocation?.let {
        GeoPoint(it.latitude, it.longitude)
    } ?: route.lastOrNull()?.let {
        GeoPoint(it.latitude, it.longitude)
    }
    val accuracyMeters = currentLocation?.accuracyMeters ?: route.lastOrNull()?.accuracyMeters

    currentPosition?.let { position ->
        val marker = Marker(mapView).apply {
            this.position = position
            title = "You"
            snippet = accuracyMeters?.let { "Accuracy: ${it.toInt()} m" }
            icon = currentPositionIcon(mapView)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(marker)
    }

    mapView.invalidate()
}

private fun centerOnCurrentLocation(
    mapView: MapView,
    currentLocation: LocationSample?,
    route: List<RoutePoint>,
) {
    val position = currentLocation?.let {
        GeoPoint(it.latitude, it.longitude)
    } ?: route.lastOrNull()?.let {
        GeoPoint(it.latitude, it.longitude)
    } ?: return

    val currentZoom = mapView.zoomLevelDouble
    val targetZoom = if (abs(currentZoom - USER_FOCUS_ZOOM) > USER_FOCUS_ZOOM_THRESHOLD) {
        USER_FOCUS_ZOOM
    } else {
        null
    }

    mapView.controller.animateTo(position, targetZoom, USER_FOCUS_ANIMATION_MS)
}

private fun hasCurrentPosition(
    currentLocation: LocationSample?,
    route: List<RoutePoint>,
): Boolean {
    return currentLocation != null || route.isNotEmpty()
}

private const val USER_FOCUS_ZOOM = 17.5
private const val USER_FOCUS_ZOOM_THRESHOLD = 0.75
private const val USER_FOCUS_ANIMATION_MS = 650L

@Composable
private fun ActivityLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("Walk", colorForCompose(ActivityType.Walking))
        LegendItem("Run", colorForCompose(ActivityType.Running))
        LegendItem("Bike", colorForCompose(ActivityType.Cycling))
        LegendItem("Sit", colorForCompose(ActivityType.Sitting))
        LegendItem("Lie", colorForCompose(ActivityType.Lying))
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 6.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun polyline(points: List<GeoPoint>, color: Int, strokeWidth: Float): Polyline {
    return Polyline().apply {
        setPoints(points)
        outlinePaint.color = color
        outlinePaint.strokeWidth = strokeWidth
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
    }
}

private data class StationaryCluster(
    val activity: ActivityType,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
)

private fun stationaryClusters(route: List<RoutePoint>): List<StationaryCluster> {
    val clusters = mutableListOf<StationaryCluster>()
    val current = mutableListOf<RoutePoint>()

    fun flushCurrent() {
        if (current.isEmpty()) {
            return
        }

        clusters += StationaryCluster(
            activity = current.first().activity,
            latitude = current.map { it.latitude }.average(),
            longitude = current.map { it.longitude }.average(),
            count = current.size,
        )
        current.clear()
    }

    route.forEach { point ->
        if (!point.activity.isStationary) {
            flushCurrent()
        } else if (current.isEmpty() || current.last().activity == point.activity) {
            current += point
        } else {
            flushCurrent()
            current += point
        }
    }
    flushCurrent()

    return clusters
}

private fun stopIcon(mapView: MapView, activityType: ActivityType): BitmapDrawable {
    val label = when (activityType) {
        ActivityType.Sitting -> "S"
        ActivityType.Lying -> "L"
        else -> "?"
    }
    return circleIcon(
        mapView = mapView,
        fillColor = colorFor(activityType),
        strokeColor = AndroidColor.WHITE,
        text = label,
    )
}

private fun currentPositionIcon(mapView: MapView): BitmapDrawable {
    return circleIcon(
        mapView = mapView,
        fillColor = AndroidColor.rgb(31, 102, 220),
        strokeColor = AndroidColor.WHITE,
        text = null,
    )
}

private fun circleIcon(
    mapView: MapView,
    fillColor: Int,
    strokeColor: Int,
    text: String?,
): BitmapDrawable {
    val density = mapView.resources.displayMetrics.density
    val size = (34 * density).toInt()
    val center = size / 2f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    canvas.drawCircle(center, center, size * 0.38f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3.5f * density
    paint.color = strokeColor
    canvas.drawCircle(center, center, size * 0.38f, paint)

    if (text != null) {
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * 0.42f
        val baseline = center - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, center, baseline, paint)
    }

    return BitmapDrawable(mapView.resources, bitmap)
}

private fun colorFor(activityType: ActivityType): Int {
    return colorForCompose(activityType).toArgb()
}

private fun colorForCompose(activityType: ActivityType): Color {
    return when (activityType) {
        ActivityType.Walking -> Color(0xFF22965F)
        ActivityType.Running -> Color(0xFFD94C3D)
        ActivityType.Cycling -> Color(0xFF2A73C9)
        ActivityType.Sitting -> Color(0xFFE09B2D)
        ActivityType.Lying -> Color(0xFF7B4CC2)
        ActivityType.Unknown -> Color(0xFF444444)
    }
}
