package com.example.deriv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ticks")
data class TickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val price: Double,
    val epoch: Long,
    val digit: Int,
    val timestamp: Long = System.currentTimeMillis()
)
