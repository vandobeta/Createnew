package com.example.deriv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Long = 1L,
    val alertPreference: String = "BOTH",
    val vibrationSetting: String = "STANDARD",
    val vibrationStrengthThreshold: Float = 60f,
    val selectedColorTheme: String = "SLATE",
    val selectedSymbol: String = "R_100",
    val selectedTradeType: String = "MATCHES_DIFFERS",
    val barrier: Int = 5,
    val sampleSize: Int = 50,
    
    // Algorithmic rules - start disabled!
    val p1Enabled: Boolean = false,
    val p1TargetDigit: Int = 6,
    val p1MinConfidence: Double = 45.0,
    
    val p2Enabled: Boolean = false,
    val p2TargetDigit: Int = 6,
    val p2MinConfidence: Double = 70.0,
    
    val p3Enabled: Boolean = false,
    val p3MinFrequency: Double = 12.0,
    val p3MinAbsence: Int = 3,
    
    val p4Enabled: Boolean = false,
    val p4MinAppearances: Int = 2,
    
    // Session count & constraints
    val sessionSignalLimit: Int = 10,
    val sessionSignalCount: Int = 0,
    
    // Custom daily session timers
    val timerSession1: String = "08:00",
    val timerSession2: String = "14:00",
    val timerSession3: String = "20:00"
)
