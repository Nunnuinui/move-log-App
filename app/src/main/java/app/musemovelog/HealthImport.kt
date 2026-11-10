package app.musemovelog

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest as AggReq
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime

/**
 * Imports past steps/distance from Health Connect into the local day_summary
 * table. Samsung Health data counts too, as long as it is shared into
 * Health Connect on the device.
 */
object HealthImport {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )

    private val scope = CoroutineScope(Dispatchers.IO)

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    @JvmStatic
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** For framework Activities: build the permission intent manually. */
    @JvmStatic
    fun createPermissionIntent(context: Context): android.content.Intent =
        requestContract().createIntent(context, PERMISSIONS)

    /**
     * On Android 14+ Health Connect is a system module and its permissions
     * are ordinary runtime permissions; the app-targeted permission intent
     * used on older versions has no handler there (startActivity fails).
     */
    @JvmStatic
    fun useRuntimePermissions(): Boolean = android.os.Build.VERSION.SDK_INT >= 34

    @JvmStatic
    fun runtimePermissions(): Array<String> = arrayOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class)
    )

    /**
     * The platform Health Connect permission UI is reached through the
     * ACTION_REQUEST_PERMISSIONS activity intent (this is what the SDK's
     * module contract wraps). Activity.requestPermissions() does NOT work
     * for health permissions: the platform denies them instantly without
     * showing anything, because they are owned by the Health Connect
     * module rather than the regular permission manager.
     */
    @JvmStatic
    fun createRuntimePermissionIntent(): android.content.Intent =
        // Intent.ACTION_REQUEST_PERMISSIONS / EXTRA_PERMISSIONS are not in
        // the compile SDK's public stubs; these are their stable values.
        android.content.Intent("android.content.pm.action.REQUEST_PERMISSIONS")
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS", runtimePermissions())

    @JvmStatic
    fun parsePermissionResult(resultCode: Int, data: android.content.Intent?): Set<String> =
        requestContract().parseResult(resultCode, data) ?: emptySet()

    @JvmStatic
    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    /** Intent to Health Connect's own management UI, where the user can
     *  grant this app access manually if the permission prompt misbehaves. */
    @JvmStatic
    fun manageDataIntent(context: Context): android.content.Intent =
        HealthConnectClient.getHealthConnectManageDataIntent(context)

    @JvmStatic
    fun grantedNow(context: Context, callback: (Set<String>) -> Unit) {
        scope.launch {
            val granted = try {
                val client = HealthConnectClient.getOrCreate(context)
                client.permissionController.getGrantedPermissions()
            } catch (e: Exception) {
                Dbg.log(context, "getGrantedPermissions failed: " + e)
                emptySet()
            }
            withContext(Dispatchers.Main) { callback(granted) }
        }
    }

    @JvmStatic
    fun hasPermissions(context: Context, callback: (Boolean) -> Unit) {
        scope.launch {
            val granted = try {
                val client = HealthConnectClient.getOrCreate(context)
                client.permissionController.getGrantedPermissions()
            } catch (e: Exception) {
                emptySet()
            }
            val ok = granted.containsAll(
                setOf(
                    HealthPermission.getReadPermission(StepsRecord::class),
                    HealthPermission.getReadPermission(DistanceRecord::class)
                )
            )
            withContext(Dispatchers.Main) { callback(ok) }
        }
    }

    /**
     * Aggregates steps and distance per day for the last [days] days and
     * stores them. Callback: (importedDayCount, errorMessageOrNull) on main.
     */
    @JvmStatic
    fun importHistory(context: Context, store: Store, days: Int, callback: (Int, String?) -> Unit) {
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                val withCalories = granted.contains(
                    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
                val metrics = mutableSetOf(
                    StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL)
                if (withCalories) metrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                val end = LocalDateTime.now()
                val startDate = end.toLocalDate().minusDays((days - 1).toLong())
                val start = startDate.atStartOfDay()
                val response = client.aggregateGroupByDuration(
                    AggReq(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = Duration.ofDays(1)
                    )
                )
                var count = 0
                for (bucket in response) {
                    val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L
                    val dist = bucket.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                    val kcal = if (withCalories)
                        bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                    else 0.0
                    if (steps > 0 || dist > 0 || kcal > 0) {
                        val day = bucket.startTime
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                        store.upsertDaySummaryMax(day, steps, dist, kcal)
                        count++
                    }
                }
                withContext(Dispatchers.Main) { callback(count, null) }
            } catch (e: Exception) {
                Dbg.log(context, "health import failed: " + e)
                withContext(Dispatchers.Main) { callback(-1, e.message) }
            }
        }
    }

    /**
     * Aggregates one day per hour and merges the buckets into the local
     * hourly table (used by the day chart). Callback on main: true if the
     * fetch ran (even when the day simply has no Health Connect data).
     */
    @JvmStatic
    fun importDayHourly(context: Context, store: Store, dayStartMs: Long, callback: (Boolean) -> Unit) {
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                    withContext(Dispatchers.Main) { callback(false) }
                    return@launch
                }
                val withCalories = granted.contains(
                    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
                val metrics = mutableSetOf(
                    StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL)
                if (withCalories) metrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                val zone = java.time.ZoneId.systemDefault()
                val start = java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDateTime()
                val end = start.plusDays(1)
                val response = client.aggregateGroupByDuration(
                    AggReq(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = Duration.ofHours(1)
                    )
                )
                val day = start.toLocalDate().toString()
                for (bucket in response) {
                    val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L
                    val dist = bucket.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                    val kcal = if (withCalories)
                        bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                    else 0.0
                    if (steps > 0 || dist > 0 || kcal > 0) {
                        store.mergeHourly(day, bucket.startTime.atZone(zone).hour, steps, dist, kcal)
                    }
                }
                withContext(Dispatchers.Main) { callback(true) }
            } catch (e: Exception) {
                Dbg.log(context, "hourly import failed: " + e)
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }
}
