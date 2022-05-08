package net.vonforst.evmap.storage

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.car2go.maps.model.LatLng
import com.car2go.maps.model.LatLngBounds
import com.car2go.maps.util.SphericalUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.vonforst.evmap.api.ChargepointApi
import net.vonforst.evmap.api.StringProvider
import net.vonforst.evmap.api.goingelectric.GoingElectricApiWrapper
import net.vonforst.evmap.api.openchargemap.OpenChargeMapApiWrapper
import net.vonforst.evmap.model.ChargeLocation
import net.vonforst.evmap.model.ChargepointListItem
import net.vonforst.evmap.model.FilterValues
import net.vonforst.evmap.model.ReferenceData
import net.vonforst.evmap.viewmodel.Resource
import net.vonforst.evmap.viewmodel.Status
import kotlin.math.sqrt

@Dao
abstract class ChargeLocationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(vararg locations: ChargeLocation)

    // TODO: add max age here
    @Query("SELECT EXISTS(SELECT 1 FROM chargelocation WHERE dataSource == :dataSource AND id == :id AND isDetailed == 1)")
    abstract suspend fun checkExistsDetailed(id: Long, dataSource: String): Boolean

    suspend fun insertOrReplaceIfNoDetailedExists(vararg locations: ChargeLocation) {
        locations.forEach {
            if (it.isDetailed || !checkExistsDetailed(it.id, it.dataSource)) {
                insert(it)
            }
        }
    }

    @Delete
    abstract suspend fun delete(vararg locations: ChargeLocation)

    // TODO: add max age here
    @Query("SELECT * FROM chargelocation WHERE dataSource == :dataSource AND id == :id AND isDetailed == 1")
    abstract fun getChargeLocationById(id: Long, dataSource: String): LiveData<ChargeLocation>

    @SkipQueryVerification
    @Query("SELECT * FROM chargelocation WHERE dataSource == :dataSource AND Within(coordinates, BuildMbr(:lng1, :lat1, :lng2, :lat2))")
    abstract fun getChargeLocationsInBounds(
        lat1: Double,
        lat2: Double,
        lng1: Double,
        lng2: Double,
        dataSource: String
    ): LiveData<List<ChargeLocation>>

    @RawQuery(observedEntities = [ChargeLocation::class])
    abstract fun getChargeLocationsCustom(query: SupportSQLiteQuery): LiveData<List<ChargeLocation>>
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

    private fun queryWithFilters(
        api: ChargepointApi<ReferenceData>,
        filters: FilterValues,
        bounds: LatLngBounds
    ) = try {
        val query = api.convertFiltersToSQL(filters)
        val sql = StringBuilder().apply {
            append("SELECT")
            if (query.requiresChargeCardQuery or query.requiresChargepointQuery) {
                append(" DISTINCT chargelocation.*")
            } else {
                append(" *")
            }
            append(" FROM chargelocation")
            if (query.requiresChargepointQuery) {
                append(" JOIN json_each(chargelocation.chargepoints) AS cp")
            }
            if (query.requiresChargeCardQuery) {
                append(" JOIN json_each(chargelocation.chargecards) AS cc")
            }
            append(" WHERE dataSource == '${prefs.dataSource}'")
            append(" AND Within(coordinates, BuildMbr(${bounds.southwest.longitude}, ${bounds.southwest.latitude}, ${bounds.northeast.longitude}, ${bounds.northeast.latitude})) ")
            append(query.query)
        }.toString()

        chargeLocationsDao.getChargeLocationsCustom(
            SimpleSQLiteQuery(
                sql,
                null
            )
        ) as LiveData<List<ChargepointListItem>>
    } catch (e: NotImplementedError) {
        MutableLiveData()  // in this case we cannot get a DB result
    }

    fun getChargepoints(
        bounds: LatLngBounds,
        zoom: Float,
        filters: FilterValues?
    ): LiveData<Resource<List<ChargepointListItem>>> {
        val api = api.value!!

        val dbResult = if (filters == null) {
            chargeLocationsDao.getChargeLocationsInBounds(
                bounds.southwest.latitude,
                bounds.northeast.latitude,
                bounds.southwest.longitude,
                bounds.northeast.longitude,
                prefs.dataSource
            ) as LiveData<List<ChargepointListItem>>
        } else {
            queryWithFilters(api, filters, bounds)
        }
        val apiResult = MediatorLiveData<Resource<List<ChargepointListItem>>>().apply {
            addSource(referenceData) {
                scope.launch {
                    val result = api.getChargepoints(it, bounds, zoom, filters)
                    if (result.status == Status.SUCCESS) {
                        chargeLocationsDao.insertOrReplaceIfNoDetailedExists(
                            *result.data!!.filterIsInstance(ChargeLocation::class.java)
                                .toTypedArray()
                        )
                    }
                    value = result
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
        val api = api.value!!

        // database does not support radius queries, so let's build a square query instead
        val cornerDistance = radius * sqrt(2.0)
        val southwest = SphericalUtil.computeOffset(location, cornerDistance, 225.0)
        val northeast = SphericalUtil.computeOffset(location, cornerDistance, 45.0)
        val bounds = LatLngBounds(southwest, northeast)

        val dbResult = if (filters == null) {
            chargeLocationsDao.getChargeLocationsInBounds(
                bounds.southwest.latitude,
                bounds.northeast.latitude,
                bounds.southwest.longitude,
                bounds.northeast.longitude,
                prefs.dataSource,
            ) as LiveData<List<ChargepointListItem>>
        } else {
            queryWithFilters(api, filters, bounds)
        }
        val apiResult = MediatorLiveData<Resource<List<ChargepointListItem>>>().apply {
            addSource(referenceData) {
                scope.launch {
                    val result = api.getChargepointsRadius(it, location, radius, zoom, filters)
                    value = result
                    if (result.status == Status.SUCCESS) {
                        chargeLocationsDao.insertOrReplaceIfNoDetailedExists(
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

    fun getFilters(sp: StringProvider) = Transformations.map(referenceData) { data ->
        api.value!!.getFilters(data, sp)
    }
}