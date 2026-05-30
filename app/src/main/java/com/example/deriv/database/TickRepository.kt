package com.example.deriv.database

import android.util.Log
import kotlinx.coroutines.flow.Flow

class TickRepository(private val tickDao: TickDao) {
    fun getLatest1000TicksFlow(symbol: String): Flow<List<TickEntity>> {
        return tickDao.getLatest1000TicksFlow(symbol)
    }

    suspend fun getLatest1000Ticks(symbol: String): List<TickEntity> {
        return tickDao.getLatest1000Ticks(symbol)
    }

    suspend fun saveTick(symbol: String, price: Double, epoch: Long, digit: Int) {
        try {
            val entity = TickEntity(
                symbol = symbol,
                price = price,
                epoch = epoch,
                digit = digit
            )
            tickDao.insertTick(entity)
            val thresholdId = tickDao.getPruneThresholdId(symbol)
            if (thresholdId != null) {
                tickDao.pruneBelowId(symbol, thresholdId)
            }
        } catch (e: Throwable) {
            Log.e("TickRepository", "Error storing ticks into SQLite: ${e.message}", e)
        }
    }
}
