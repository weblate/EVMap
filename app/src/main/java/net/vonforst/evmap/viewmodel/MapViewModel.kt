package net.vonforst.evmap.viewmodel

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.*
import com.car2go.maps.AnyMap
import com.car2go.maps.model.LatLng
import com.car2go.maps.model.LatLngBounds
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import net.vonforst.evmap.api.availability.ChargeLocationStatus
import net.vonforst.evmap.api.availability.getAvailability
import net.vonforst.evmap.api.createApi
import net.vonforst.evmap.api.goingelectric.GEChargepoint
import net.vonforst.evmap.api.goingelectric.GEReferenceData
import net.vonforst.evmap.api.openchargemap.OCMConnection
import net.vonforst.evmap.api.openchargemap.OCMReferenceData
import net.vonforst.evmap.api.stringProvider
import net.vonforst.evmap.autocomplete.PlaceWithBounds
import net.vonforst.evmap.model.*
import net.vonforst.evmap.storage.AppDatabase
import net.vonforst.evmap.storage.ChargeLocationsRepository
import net.vonforst.evmap.storage.FilterProfile
import net.vonforst.evmap.storage.PreferenceDataSource
import net.vonforst.evmap.ui.cluster
import net.vonforst.evmap.utils.distanceBetween

@Parcelize
data class MapPosition(val bounds: LatLngBounds, val zoom: Float) : Parcelable

internal fun getClusterDistance(zoom: Float): Int? {
    return when (zoom) {
        in 0.0..7.0 -> 100
        in 7.0..11.5 -> 75
        in 11.5..12.5 -> 60
        in 12.5..13.0 -> 45
        else -> null
    }
}

class MapViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    val apiId: String
        get() = repo.api.value!!.id
    val apiName: String
        get() = repo.api.value!!.name

    private val db = AppDatabase.getInstance(application)
    private val prefs = PreferenceDataSource(application)
    private val repo = ChargeLocationsRepository(
        createApi(prefs.dataSource, application),
        viewModelScope,
        db,
        prefs
    )

    val bottomSheetState: MutableLiveData<Int> by lazy {
        state.getLiveData("bottomSheetState")
    }

    val mapPosition: MutableLiveData<MapPosition> by lazy {
        state.getLiveData("mapPosition")
    }
    val filterStatus: MutableLiveData<Long> by lazy {
        MutableLiveData<Long>().apply {
            value = prefs.filterStatus
            observeForever {
                prefs.filterStatus = it
                if (it != FILTERS_DISABLED) prefs.lastFilterProfile = it
            }
        }
    }
    private val filterValues: LiveData<List<FilterValue>> =
        db.filterValueDao().getFilterValues(filterStatus, prefs.dataSource)
    private val referenceData = repo.referenceData
    private val filters = repo.getFilters(application.stringProvider())

    private val filtersWithValue: LiveData<FilterValues> by lazy {
        filtersWithValue(filters, filterValues)
    }

    val filterProfiles: LiveData<List<FilterProfile>> by lazy {
        db.filterProfileDao().getProfiles(prefs.dataSource)
    }

    val chargeCardMap: LiveData<Map<Long, ChargeCard>> by lazy {
        MediatorLiveData<Map<Long, ChargeCard>>().apply {
            value = null
            addSource(referenceData) { data ->
                value = if (data is GEReferenceData) {
                    data.chargecards.map {
                        it.id to it.convert()
                    }.toMap()
                } else {
                    null
                }
            }
        }
    }

    val filtersCount: LiveData<Int> by lazy {
        MediatorLiveData<Int>().apply {
            value = 0
            addSource(filtersWithValue) { filtersWithValue ->
                value = filtersWithValue.count {
                    !it.value.hasSameValueAs(it.filter.defaultValue())
                }
            }
        }
    }
    val chargepoints: MediatorLiveData<Resource<List<ChargepointListItem>>> by lazy {
        MediatorLiveData<Resource<List<ChargepointListItem>>>()
            .apply {
                value = Resource.loading(emptyList())
                listOf(mapPosition, filtersWithValue, referenceData).forEach {
                    addSource(it) {
                        reloadChargepoints()
                    }
                }
            }
    }
    val filteredConnectors: MutableLiveData<Set<String>> by lazy {
        MutableLiveData<Set<String>>()
    }
    val filteredMinPower: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>()
    }
    val filteredChargeCards: MutableLiveData<Set<Long>> by lazy {
        MutableLiveData<Set<Long>>()
    }

    val chargerSparse: MutableLiveData<ChargeLocation> by lazy {
        state.getLiveData("chargerSparse")
    }
    val chargerDetails: MediatorLiveData<Resource<ChargeLocation>> by lazy {
        MediatorLiveData<Resource<ChargeLocation>>().apply {
            addSource(chargerSparse) { _ ->
                val charger = chargerSparse.value
                if (charger != null) {
                    loadChargerDetails(charger.id)
                } else {
                    value = null
                }
            }
        }
    }
    val charger: MediatorLiveData<Resource<ChargeLocation>> by lazy {
        MediatorLiveData<Resource<ChargeLocation>>().apply {
            addSource(chargerDetails) {
                value = when (it?.status) {
                    null -> null
                    Status.SUCCESS -> Resource.success(it.data)
                    Status.LOADING -> Resource.loading(chargerSparse.value)
                    Status.ERROR -> Resource.error(it.message, chargerSparse.value)
                }
            }
        }
    }
    val chargerDistance: MediatorLiveData<Double> by lazy {
        MediatorLiveData<Double>().apply {
            val callback = { _: Any? ->
                val loc = location.value
                val charger = chargerSparse.value
                value = if (loc != null && charger != null) {
                    distanceBetween(
                        loc.latitude,
                        loc.longitude,
                        charger.coordinates.lat,
                        charger.coordinates.lng
                    )
                } else null
            }
            addSource(chargerSparse, callback)
            addSource(location, callback)
        }
    }
    val location: MutableLiveData<LatLng> by lazy {
        MutableLiveData<LatLng>()
    }
    val availability: MediatorLiveData<Resource<ChargeLocationStatus>> by lazy {
        MediatorLiveData<Resource<ChargeLocationStatus>>().apply {
            addSource(chargerSparse) { charger ->
                if (charger != null) {
                    viewModelScope.launch {
                        loadAvailability(charger)
                    }
                } else {
                    value = null
                }
            }
        }
    }
    val filteredAvailability: MediatorLiveData<Resource<ChargeLocationStatus>> by lazy {
        MediatorLiveData<Resource<ChargeLocationStatus>>().apply {
            val callback = { _: Any? ->
                val av = availability.value
                val filters = filtersWithValue.value
                if (av?.status == Status.SUCCESS && filters != null) {
                    value = Resource.success(
                        av.data!!.applyFilters(
                            filteredConnectors.value,
                            filteredMinPower.value
                        )
                    )
                } else {
                    value = av
                }
            }
            addSource(availability, callback)
            addSource(filteredConnectors, callback)
            addSource(filteredMinPower, callback)
        }
    }
    val myLocationEnabled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>()
    }
    val layersMenuOpen: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply {
            value = false
        }
    }

    val favorites: LiveData<List<FavoriteWithDetail>> by lazy {
        db.favoritesDao().getAllFavorites()
    }

    val searchResult: MutableLiveData<PlaceWithBounds> by lazy {
        state.getLiveData("searchResult")
    }

    val mapType: MutableLiveData<AnyMap.Type> by lazy {
        MutableLiveData<AnyMap.Type>().apply {
            value = prefs.mapType
            observeForever {
                prefs.mapType = it
            }
        }
    }

    val mapTrafficEnabled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply {
            value = prefs.mapTrafficEnabled
            observeForever {
                prefs.mapTrafficEnabled = it
            }
        }
    }

    fun reloadPrefs() {
        filterStatus.value = prefs.filterStatus
        if (prefs.dataSource != apiId) {
            repo.api.value = createApi(prefs.dataSource, getApplication())
        }
    }

    fun toggleFilters() {
        if (filterStatus.value == FILTERS_DISABLED) {
            filterStatus.value = prefs.lastFilterProfile
        } else {
            filterStatus.value = FILTERS_DISABLED
        }
    }

    suspend fun copyFiltersToCustom() {
        if (filterStatus.value == FILTERS_CUSTOM) return

        db.filterValueDao().deleteFilterValuesForProfile(FILTERS_CUSTOM, prefs.dataSource)
        filterValues.value?.map {
            it.profile = FILTERS_CUSTOM
            it
        }?.let {
            db.filterValueDao().insert(*it.toTypedArray())
        }
    }

    fun setMapType(type: AnyMap.Type) {
        mapType.value = type
    }

    fun insertFavorite(charger: ChargeLocation) {
        viewModelScope.launch {
            db.chargeLocationsDao().insert(charger)
            db.favoritesDao()
                .insert(Favorite(chargerId = charger.id, chargerDataSource = charger.dataSource))
        }
    }

    fun deleteFavorite(favorite: Favorite) {
        viewModelScope.launch {
            db.favoritesDao().delete(favorite)
        }
    }

    fun reloadChargepoints() {
        val pos = mapPosition.value ?: return
        val filters = filtersWithValue.value ?: return
        val referenceData = referenceData.value ?: return
        chargepointLoader(Triple(pos, filters, referenceData))
    }

    private var chargepointsInternal: LiveData<Resource<List<ChargepointListItem>>>? = null
    private var chargepointLoader =
        throttleLatest(
            500L,
            viewModelScope
        ) { data: Triple<MapPosition, FilterValues, ReferenceData> ->
            chargepoints.value = Resource.loading(chargepoints.value?.data)

            val mapPosition = data.first
            val filters = data.second
            val refData = data.third

            if (filterStatus.value == FILTERS_FAVORITES) {
                // load favorites from local DB
                val b = mapPosition.bounds
                var chargers = db.favoritesDao().getFavoritesInBoundsAsync(
                    b.southwest.latitude,
                    b.northeast.latitude,
                    b.southwest.longitude,
                    b.northeast.longitude
                ).map { it.charger } as List<ChargepointListItem>

                val clusterDistance = getClusterDistance(mapPosition.zoom)
                clusterDistance?.let {
                    chargers = cluster(chargers, mapPosition.zoom, clusterDistance)
                }
                filteredConnectors.value = null
                filteredMinPower.value = null
                filteredChargeCards.value = null
                chargepoints.value = Resource.success(chargers)
                return@throttleLatest
            }

            if (apiId == "going_electric") {
                val chargeCardsVal = filters.getMultipleChoiceValue("chargecards")!!
                filteredChargeCards.value =
                    if (chargeCardsVal.all) null else chargeCardsVal.values.map { it.toLong() }
                        .toSet()

                val connectorsVal = filters.getMultipleChoiceValue("connectors")!!
                filteredConnectors.value =
                    if (connectorsVal.all) null else connectorsVal.values.map {
                        GEChargepoint.convertTypeFromGE(it)
                    }.toSet()
                filteredMinPower.value = filters.getSliderValue("minPower")
            } else if (apiId == "open_charge_map") {
                val connectorsVal = filters.getMultipleChoiceValue("connectors")!!
                filteredConnectors.value =
                    if (connectorsVal.all) null else connectorsVal.values.map {
                        OCMConnection.convertConnectionTypeFromOCM(
                            it.toLong(),
                            refData as OCMReferenceData
                        )
                    }.toSet()
                filteredMinPower.value = filters.getSliderValue("minPower")
            } else {
                filteredConnectors.value = null
                filteredMinPower.value = null
                filteredChargeCards.value = null
            }

            val result = repo.getChargepoints(mapPosition.bounds, mapPosition.zoom, filters)
            chargepointsInternal?.let { chargepoints.removeSource(it) }
            chargepointsInternal = result
            chargepoints.addSource(result) {
                chargepoints.value = it
            }
        }

    private suspend fun loadAvailability(charger: ChargeLocation) {
        availability.value = Resource.loading(null)
        availability.value = getAvailability(charger)
    }

    var chargerDetailsInternal: LiveData<Resource<ChargeLocation>>? = null
    private fun loadChargerDetails(chargerId: Long) {
        chargerDetails.value = Resource.loading(null)
        val result = repo.getChargepointDetail(chargerId)
        chargerDetailsInternal?.let { chargerDetails.removeSource(it) }
        chargerDetailsInternal = result
        chargerDetails.addSource(result) {
            chargerDetails.value = it
        }
    }

    fun loadChargerById(chargerId: Long) {
        chargerDetails.value = Resource.loading(null)
        chargerSparse.value = null

        loadChargerDetails(chargerId)
        chargerDetails.observeForever(object : Observer<Resource<ChargeLocation>> {
            override fun onChanged(t: Resource<ChargeLocation>) {
                chargerDetails.removeObserver(this)
                chargerSparse.value = t.data
            }
        })
    }
}