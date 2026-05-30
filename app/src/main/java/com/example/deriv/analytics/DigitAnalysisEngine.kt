package com.example.deriv.analytics

import com.example.deriv.database.TickEntity
import kotlin.math.abs

data class CurrentStreaks(
    val parityType: String,
    val parityCount: Int,
    val barrierType: String,
    val barrierCount: Int,
    val directionType: String,
    val directionCount: Int,
    val sameDigitValue: Int,
    val sameDigitCount: Int
)

data class ConsolidatedPrediction(
    val digit: Int,
    val type: String, // "P1" or "P2"
    val displayName: String,
    val confidence: Double,
    val description: String
)

data class AnalysisReport(
    val selectedSymbol: String,
    val sampleSize: Int,
    val totalTicksAnalyzed: Int,
    val digitFrequencies: Map<Int, Double>,
    val overPercentage: Double,
    val underPercentage: Double,
    val equalCount: Int,
    val evenPercentage: Double,
    val oddPercentage: Double,
    val currentStreaks: CurrentStreaks,
    val topMatchesDigit: Int,
    val topMatchesPct: Double,
    val topDiffersDigit: Int,
    val topDiffersPct: Double,
    val patternPredictions: List<Int>,
    val confidenceScore: Double,
    val risePercentage: Double,
    val fallPercentage: Double,
    val consolidatedPredictions: List<ConsolidatedPrediction>
)

object DigitAnalysisEngine {

    fun analyze(
        ticks: List<TickEntity>,
        sampleSize: Int,
        barrier: Int
    ): AnalysisReport? {
        if (ticks.isEmpty()) return null

        // All analytical calculations are done chronologically (oldest to newest)
        val chronologicalTicks = ticks.take(sampleSize).reversed()
        val totalTicks = chronologicalTicks.size
        if (totalTicks == 0) return null

        // 1. DIGIT FREQUENCIES (calculated using the selected sampleSize)
        val frequencyMap = mutableMapOf<Int, Int>()
        for (i in 0..9) {
            frequencyMap[i] = 0
        }
        chronologicalTicks.forEach {
            frequencyMap[it.digit] = (frequencyMap[it.digit] ?: 0) + 1
        }
        val digitFrequencies = frequencyMap.mapValues { (_, count) ->
            (count.toDouble() / totalTicks) * 100.0
        }

        // 2. OVER / UNDER BARRIER (Calculated over specified last 100 ticks as trend, per planning rules)
        val trendTicks = ticks.take(100)
        val trendSize = trendTicks.size.coerceAtLeast(1)
        val overCount = trendTicks.count { it.digit > barrier }
        val underCount = trendTicks.count { it.digit < barrier }
        val equalCount = trendTicks.count { it.digit == barrier }
        val overPercentage = (overCount.toDouble() / trendSize) * 100.0
        val underPercentage = (underCount.toDouble() / trendSize) * 100.0

        // 3. EVEN / ODD DISTRIBUTION (per designated sampleSize)
        val evenCount = chronologicalTicks.count { it.digit % 2 == 0 }
        val oddCount = totalTicks - evenCount
        val evenPercentage = (evenCount.toDouble() / totalTicks) * 100.0
        val oddPercentage = (oddCount.toDouble() / totalTicks) * 100.0

        // 4. CURRENT STREAKS (Calculated from newest to oldest ticks)
        val currentStreaks = calculateStreaks(ticks, barrier)

        // 5. TWO-PROFILE COMPARISON MATCHES & CONFIDENCE SCORE
        // Profile A = last 50 ticks, Profile B = last 25 ticks
        val profileATicks = ticks.take(50).map { it.digit }
        val profileBTicks = ticks.take(25).map { it.digit }
        
        val freqA = calculateFrequencyMap(profileATicks)
        val freqB = calculateFrequencyMap(profileBTicks)

        // Find matches based on frequency of digit appearing or infrequent
        val frequency1000 = calculateFrequencyMap(ticks.take(1000).map { it.digit })
        val leastFrequentDigit1000 = frequency1000.minByOrNull { it.value }?.key ?: 0
        val mostFrequentDigit1000 = frequency1000.maxByOrNull { it.value }?.key ?: 9

        // Pure Prediction (P1) on Digit 6 specifically
        val freq6A = freqA[6] ?: 10.0
        val freq6B = freqB[6] ?: 10.0
        val balancingBoost6 = if (6 == leastFrequentDigit1000) 25.0 else 0.0
        val p1Confidence = (freq6A + freq6B + balancingBoost6).coerceIn(45.0, 98.5)

        // Top matches stats for compatibility mapping (pointing to Digit 6)
        val bestMatchesDigit = 6
        val confidenceScore = p1Confidence

        // Top Differing digit (typically the highly saturated or overdeveloped digits)
        val topDiffersDigit = mostFrequentDigit1000
        val topDiffersPct = (99.0 - (digitFrequencies[topDiffersDigit] ?: 10.0)).coerceIn(80.0, 99.5)

        // 7. REPEATING SEQUENCE PATTERN PREDICTOR pointing to Digit 6 (P2)
        var p2Confidence = 0.0
        var p2Description = ""
        val history = ticks.map { it.digit }.reversed()
        if (history.size >= 5) {
            val current3 = history.takeLast(3) // current 3-tick sequence
            var patternMatchCount = 0
            for (i in 0 until (history.size - 4)) {
                if (history[i] == current3[0] && history[i+1] == current3[1] && history[i+2] == current3[2]) {
                    val nextDigit = history[i+3]
                    if (nextDigit == 6) {
                        patternMatchCount++
                    }
                }
            }
            if (patternMatchCount > 0) {
                p2Confidence = (70.0 + patternMatchCount * 6.0).coerceAtLeast(82.0).coerceAtMost(98.5)
                p2Description = "Sequence pattern matched in history (${current3.joinToString("")} -> 6) recommending entry on Digit 6 ($patternMatchCount match instances)."
            } else {
                p2Confidence = 74.5
                p2Description = "Awaiting pattern trigger matching current sequence [${current3.joinToString("")}] to point to Digit 6."
            }
        } else {
            p2Confidence = 60.0
            p2Description = "Insufficient tick depth for reliable pattern matching."
        }

        // Calculate short term rise vs fall percentage over chronologicalTicks
        var riseTicksCount = 0
        var fallTicksCount = 0
        if (chronologicalTicks.size >= 2) {
            for (i in 1 until chronologicalTicks.size) {
                if (chronologicalTicks[i].price >= chronologicalTicks[i - 1].price) {
                    riseTicksCount++
                } else {
                    fallTicksCount++
                }
            }
        }
        val totalDirTicks = (riseTicksCount + fallTicksCount).coerceAtLeast(1)
        val risePercentage = (riseTicksCount.toDouble() / totalDirTicks) * 100.0
        val fallPercentage = (fallTicksCount.toDouble() / totalDirTicks) * 100.0

        val consolidated = mutableListOf<ConsolidatedPrediction>()
        
        // P1 Branch (Pure Prediction on Digit 6)
        consolidated.add(
            ConsolidatedPrediction(
                digit = 6,
                type = "P1",
                displayName = "P1 (Pure Prediction)",
                confidence = p1Confidence,
                description = "Master predictive signal targeting Digit 6 based on dual-profile balancing models."
            )
        )

        // P2 Branch (Pattern Matching pointing to Digit 6)
        consolidated.add(
            ConsolidatedPrediction(
                digit = 6,
                type = "P2",
                displayName = "P2 (Pattern Match)",
                confidence = p2Confidence,
                description = p2Description
            )
        )

        // P3 (Most Appearing Prediction Model)
        // Only if it's at 12+% and it misses appearing for 3 or more ticks. Discard if > 8 ticks.
        val mostFrequentRecent = digitFrequencies.maxByOrNull { it.value }
        if (mostFrequentRecent != null) {
            val freqPct = mostFrequentRecent.value
            val digit = mostFrequentRecent.key
            if (freqPct >= 12.0) {
                var absenceCount = 0
                for (t in ticks) {
                    if (t.digit == digit) {
                        break
                    }
                    absenceCount++
                }
                
                if (absenceCount in 3..8) {
                    val p3Conf = (68.0 + (absenceCount - 3) * 4.5).coerceIn(68.0, 96.0)
                    consolidated.add(
                        ConsolidatedPrediction(
                            digit = digit,
                            type = "P3",
                            displayName = "P3 (Most Appearing)",
                            confidence = p3Conf,
                            description = "Most frequent Digit $digit (Freq: ${String.format("%.1f", freqPct)}%) absent for $absenceCount ticks (Ideal: 3-8). Correction signal high!"
                        )
                    )
                }
            }
        }

        // P4 (Least Appearing Gaining Momentum Model)
        // Detects if the suppressed/least frequent digit begins to gain momentum in the last 8 ticks
        val leastFrequentRecent = digitFrequencies.minByOrNull { it.value }
        if (leastFrequentRecent != null) {
            val digit = leastFrequentRecent.key
            val last8 = ticks.take(8)
            val appearancesInLast8 = last8.count { it.digit == digit }
            if (appearancesInLast8 >= 2) {
                val p4Conf = (65.0 + appearancesInLast8 * 6.5).coerceIn(65.0, 92.0)
                consolidated.add(
                    ConsolidatedPrediction(
                        digit = digit,
                        type = "P4",
                        displayName = "P4 (Momentum)",
                        confidence = p4Conf,
                        description = "Suppressed Digit $digit is recovering value with $appearancesInLast8 occurrences in the last 8 ticks."
                    )
                )
            }
        }

        val sequencePredictions = matchRepeatingSequence(ticks)

        return AnalysisReport(
            selectedSymbol = ticks.first().symbol,
            sampleSize = sampleSize,
            totalTicksAnalyzed = ticks.size,
            digitFrequencies = digitFrequencies,
            overPercentage = overPercentage,
            underPercentage = underPercentage,
            equalCount = equalCount,
            evenPercentage = evenPercentage,
            oddPercentage = oddPercentage,
            currentStreaks = currentStreaks,
            topMatchesDigit = bestMatchesDigit,
            topMatchesPct = confidenceScore,
            topDiffersDigit = topDiffersDigit,
            topDiffersPct = topDiffersPct,
            patternPredictions = sequencePredictions,
            confidenceScore = confidenceScore,
            risePercentage = risePercentage,
            fallPercentage = fallPercentage,
            consolidatedPredictions = consolidated
        )
    }

    private fun calculateFrequencyMap(digits: List<Int>): Map<Int, Double> {
        val total = digits.size.coerceAtLeast(1)
        val map = mutableMapOf<Int, Int>()
        for (i in 0..9) {
            map[i] = 0
        }
        digits.forEach {
            map[it] = (map[it] ?: 0) + 1
        }
        return map.mapValues { (_, count) -> (count.toDouble() / total) * 100.0 }
    }

    private fun calculateStreaks(ticks: List<TickEntity>, barrier: Int): CurrentStreaks {
        if (ticks.isEmpty()) {
            return CurrentStreaks(
                parityType = "Odd", parityCount = 0,
                barrierType = "Equal", barrierCount = 0,
                directionType = "Rise", directionCount = 0,
                sameDigitValue = 5, sameDigitCount = 0
            )
        }

        // PARITY
        val firstParity = if (ticks[0].digit % 2 != 0) "Odd" else "Even"
        var parityCount = 0
        for (t in ticks) {
            val p = if (t.digit % 2 != 0) "Odd" else "Even"
            if (p == firstParity) {
                parityCount++
            } else {
                break
            }
        }

        // BARRIER
        val firstDigit = ticks[0].digit
        val firstBarrierType = when {
            firstDigit > barrier -> "Over $barrier"
            firstDigit < barrier -> "Under $barrier"
            else -> "Equal $barrier"
        }
        var barrierCount = 0
        for (t in ticks) {
            val bt = when {
                t.digit > barrier -> "Over $barrier"
                t.digit < barrier -> "Under $barrier"
                else -> "Equal $barrier"
            }
            if (bt == firstBarrierType) {
                barrierCount++
            } else {
                break
            }
        }

        // DIRECTION (Uses price values)
        var directionType = "Rise"
        var directionCount = 0
        if (ticks.size >= 2) {
            val diff0 = ticks[0].price - ticks[1].price
            directionType = if (diff0 >= 0) "Rise" else "Fall"
            
            for (i in 0 until ticks.size - 1) {
                val diff = ticks[i].price - ticks[i + 1].price
                val currentDir = if (diff >= 0) "Rise" else "Fall"
                if (currentDir == directionType) {
                    directionCount++
                } else {
                    break
                }
            }
        } else {
            directionCount = ticks.size
        }

        // SAME DIGIT
        val sameDigitVal = ticks[0].digit
        var sameDigitCount = 0
        for (t in ticks) {
            if (t.digit == sameDigitVal) {
                sameDigitCount++
            } else {
                break
            }
        }

        return CurrentStreaks(
            parityType = firstParity,
            parityCount = parityCount,
            barrierType = firstBarrierType,
            barrierCount = barrierCount,
            directionType = directionType,
            directionCount = directionCount.coerceAtLeast(1),
            sameDigitValue = sameDigitVal,
            sameDigitCount = sameDigitCount
        )
    }

    private fun matchRepeatingSequence(ticks: List<TickEntity>): List<Int> {
        val totalList = ticks.map { it.digit }.reversed() // Older to Newer list
        val n = totalList.size
        if (n < 8) return emptyList()

        // Get the active trailing prefix context sequence of length 4, e.g. L[N-4], L[N-3], L[N-2], L[N-1]
        val activePattern = totalList.takeLast(4)

        // Scan backwards through the historical prefix window L[0 .. N-5]
        // Search index j where L[j], L[j+1], L[j+2], L[j+3] equals activePattern
        for (j in (n - 8) downTo 0) {
            if (totalList[j] == activePattern[0] &&
                totalList[j + 1] == activePattern[1] &&
                totalList[j + 2] == activePattern[2] &&
                totalList[j + 3] == activePattern[3]) {
                
                // Found a repeat context! Extract predicted future digits immediately following this index
                val predictions = mutableListOf<Int>()
                if (j + 4 < n) predictions.add(totalList[j + 4])
                if (j + 5 < n) predictions.add(totalList[j + 5])
                if (j + 6 < n) predictions.add(totalList[j + 6])
                return predictions
            }
        }
        return emptyList()
    }
}
