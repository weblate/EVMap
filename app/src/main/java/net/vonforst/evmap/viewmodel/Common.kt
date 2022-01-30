package net.vonforst.evmap.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import net.vonforst.evmap.model.Filter
import net.vonforst.evmap.model.FilterValue
import net.vonforst.evmap.model.FilterValues
import net.vonforst.evmap.model.FilterWithValue
import net.vonforst.evmap.storage.FilterValueDao
import kotlin.reflect.full.cast

fun filtersWithValue(
    filters: LiveData<List<Filter<FilterValue>>>,
    filterValues: LiveData<List<FilterValue>>
): MediatorLiveData<FilterValues> =
    MediatorLiveData<FilterValues>().apply {
        listOf(filters, filterValues).forEach {
            addSource(it) {
                val f = filters.value ?: return@addSource
                val values = filterValues.value ?: return@addSource
                value = f.map { filter ->
                    val value =
                        values.find { it.key == filter.key } ?: filter.defaultValue()
                    FilterWithValue(filter, filter.valueClass.cast(value))
                }
            }
        }
    }

fun FilterValueDao.getFilterValues(filterStatus: LiveData<Long>, dataSource: String) =
    MediatorLiveData<List<FilterValue>>().apply {
        var source: LiveData<List<FilterValue>>? = null
        addSource(filterStatus) { status ->
            source?.let { removeSource(it) }
            source = getFilterValues(status, dataSource)
            addSource(source!!) { result ->
                value = result
            }
        }
    }