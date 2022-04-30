package net.vonforst.evmap.storage

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.room.*
import com.car2go.maps.model.LatLng
import com.car2go.maps.model.LatLngBounds
import com.car2go.maps.util.SphericalUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.vonforst.evmap.api.ChargepointApi
import net.vonforst.evmap.api.StringProvider
import net.vonforst.evmap.api.goingelectric.GoingElectricApiWrapper
import net.vonforst.evmap.api.openchargemap.OpenChargeMapApiWrapper
import net.vonforst.evmap.model.*
import net.vonforst.evmap.viewmodel.Resource
import net.vonforst.evmap.viewmodel.Status
import kotlin.math.sqrt

@Dao
interface ChargeLocationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg locations: ChargeLocation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(vararg locations: ChargeLocation)

    @Delete
    suspend fun delete(vararg locations: ChargeLocation)

    @Query("SELECT * FROM chargelocation WHERE dataSource == :dataSource AND id == :id AND isDetailed == 1")
    fun getChargeLocationById(id: Long, dataSource: String): LiveData<ChargeLocation>

    @SkipQueryVerification
    @Query("SELECT * FROM chargelocation WHERE dataSource == :dataSource AND Within(coordinates, BuildMbr(:lng1, :lat1, :lng2, :lat2))")
    fun getChargeLocationsInBounds(
        lat1: Double,
        lat2: Double,
        lng1: Double,
        lng2: Double,
        dataSource: String
    ): LiveData<List<ChargeLocation>>

    @SkipQueryVerification
    @Query("SELECT * FROM chargelocation WHERE dataSource == :dataSource AND Within(coordinates, BuildMbr(:lng1, :lat1, :lng2, :lat2))")
    suspend fun getChargeLocationsInBoundsAsync(
        lat1: Double,
        lat2: Double,
        lng1: Double,
        lng2: Double,
        dataSource: String
    ): List<ChargeLocation>
}

/**
 * The ChargeLocationsRepository wraps the ChargepointApi and the DB to provide caching
 * functionality.
 */
class ChargeLocationsRepository(
    api: ChargepointApi<ReferenceData>, private val scope: CoroutineScope,
    private val db: AppDatabase, private val prefs: PreferenceDataSource
) {
    val api = MutableLiveData<ChargepointApi<ReferenceData>>().apply { value = api }

    val referenceData = Transformations.switchMap(this.api) {
        when (it) {
            is GoingElectricApiWrapper -> {
                GEReferenceDataRepository(
                    it,
                    scope,
                    db.geReferenceDataDao(),
                    prefs
                ).getReferenceData()
            }
            is OpenChargeMapApiWrapper -> {
                OCMReferenceDataRepository(
                    it,
                    scope,
                    db.ocmReferenceDataDao(),
                    prefs
                ).getReferenceData()
            }
            else -> {
                throw RuntimeException("no reference data implemented")
            }
        }
    }
    private val chargeLocationsDao = db.chargeLocationsDao()

    fun getChargepoints(
        bounds: LatLngBounds,
        zoom: Float,
        filters: FilterValues?
    ): LiveData<Resource<List<ChargepointListItem>>> {
        val dbResult = chargeLocationsDao.getChargeLocationsInBounds(
            bounds.southwest.latitude,
            bounds.northeast.latitude,
            bounds.southwest.longitude,
            bounds.northeast.longitude,
            prefs.dataSource
        ) as LiveData<List<ChargepointListItem>>
        val apiResult = MediatorLiveData<Resource<List<ChargepointListItem>>>().apply {
            addSource(referenceData) {
                scope.launch {
                    val result = api.value!!.getChargepoints(it, bounds, zoom, filters)
                    value = result
                    if (result.status == Status.SUCCESS) {
                        chargeLocationsDao.insert(
                            *result.data!!.filterIsInstance(ChargeLocation::class.java)
                                .toTypedArray()
                        )
                    }
                }
            }
        }
        return CacheLiveData(dbResult, apiResult)
    }

    fun getChargepointsRadius(
        location: LatLng,
        radius: Int,
        zoom: Float,
        filters: FilterValues?
    ): LiveData<Resource<List<ChargepointListItem>>> {
        // database does not support radius queries, so let's build a square query instead
        val cornerDistance = radius * sqrt(2.0)
        val southwest = SphericalUtil.computeOffset(location, cornerDistance, 225.0)
        val northeast = SphericalUtil.computeOffset(location, cornerDistance, 45.0)
        val bounds = LatLngBounds(southwest, northeast)

        val dbResult = chargeLocationsDao.getChargeLocationsInBounds(
            bounds.southwest.latitude,
            bounds.northeast.latitude,
            bounds.southwest.longitude,
            bounds.northeast.longitude,
            prefs.dataSource
        ) as LiveData<List<ChargepointListItem>>
        val apiResult = MediatorLiveData<Resource<List<ChargepointListItem>>>().apply {
            addSource(referenceData) {
                scope.launch {
                    val result = api.value!!.getChargepoints(it, bounds, zoom, filters)
                    value = result
                    if (result.status == Status.SUCCESS) {
                        chargeLocationsDao.insert(
                            *result.data!!.filterIsInstance(ChargeLocation::class.java)
                                .toTypedArray()
                        )
                    }
                }
            }
        }
        return CacheLiveData(dbResult, apiResult)
    }

    fun getChargepointDetail(
        id: Long
    ): LiveData<Resource<ChargeLocation>> {
        val dbResult = chargeLocationsDao.getChargeLocationById(id, prefs.dataSource)
        val apiResult = MediatorLiveData<Resource<ChargeLocation>>().apply {
            addSource(referenceData) {
                scope.launch {
                    val result = api.value!!.getChargepointDetail(it, id)
                    value = result
                    if (result.status == Status.SUCCESS) {
                        chargeLocationsDao.insert(result.data!!)
                    }
                }
            }
        }
        return CacheLiveData(dbResult, apiResult)
    }

    fun getFilters(sp: StringProvider) = MediatorLiveData<List<Filter<FilterValue>>>().apply {
        addSource(referenceData) { data ->
            value = api.value!!.getFilters(data, sp)
        }
    }
}