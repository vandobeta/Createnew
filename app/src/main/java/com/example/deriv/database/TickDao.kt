package com.example.deriv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TickDao {
    @Query("SELECT * FROM ticks WHERE symbol = :symbol ORDER BY epoch DESC")
    fun getAllTicksFlow(symbol: String): Flow<List<TickEntity>>

    @Query("SELECT * FROM ticks WHERE symbol = :symbol ORDER BY epoch DESC LIMIT 1000")
    fun getLatest1000TicksFlow(symbol: String): Flow<List<TickEntity>>

    @Query("SELECT * FROM ticks WHERE symbol = :symbol ORDER BY epoch DESC LIMIT 1000")
    suspend fun getLatest1000Ticks(symbol: String): List<TickEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTick(tick: TickEntity)

    @Query("SELECT id FROM ticks WHERE symbol = :symbol ORDER BY epoch DESC LIMIT 1 OFFSET 1000")
    suspend fun getPruneThresholdId(symbol: String): Long?

    @Query("DELETE FROM ticks WHERE symbol = :symbol AND id < :thresholdId")
    suspend fun pruneBelowId(symbol: String, thresholdId: Long)
}
