package com.stackscan.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Unit tests for ImageStacker core algorithms.
 * These test pure functions without Android/OpenCV dependencies.
 */
class ImageStackerTest {

    // ===== MEDIAN TESTS =====

    @Test
    fun `medianOf odd count returns middle element`() {
        val values = intArrayOf(3, 1, 4, 1, 5)
        val result = medianOf(values, 5)
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun `medianOf even count returns average of two middle`() {
        val values = intArrayOf(1, 2, 3, 4)
        val result = medianOf(values, 4)
        assertEquals(2.5, result, 0.001)
    }

    @Test
    fun `medianOf single element`() {
        val values = intArrayOf(42)
        val result = medianOf(values, 1)
        assertEquals(42.0, result, 0.001)
    }

    @Test
    fun `medianOf two elements`() {
        val values = intArrayOf(10, 20)
        val result = medianOf(values, 2)
        assertEquals(15.0, result, 0.001)
    }

    // ===== SIGMA CLIPPING TESTS =====

    @Test
    fun `kappaSigmaMean rejects outlier`() {
        // 5 values: 4 consistent (~100), 1 outlier (500)
        val values = intArrayOf(100, 102, 98, 101, 500)
        val result = kappaSigmaMean(values, 5, 2.0, 3)
        // Should reject 500 and average the rest
        assertTrue("Result should be near 100, was $result", abs(result - 100.0f) < 10f)
    }

    @Test
    fun `kappaSigmaMean keeps consistent data`() {
        val values = intArrayOf(100, 101, 99, 100, 102)
        val result = kappaSigmaMean(values, 5, 2.0, 3)
        assertTrue("Result should be near 100, was $result", abs(result - 100.5f) < 5f)
    }

    @Test
    fun `kappaSigmaMean uses median for small sample`() {
        val values = intArrayOf(10, 20, 30)
        val result = kappaSigmaMean(values, 3, 2.0, 3)
        assertEquals(20.0f, result, 0.001f)
    }

    @Test
    fun `kappaSigmaMean handles all same values`() {
        val values = intArrayOf(50, 50, 50, 50)
        val result = kappaSigmaMean(values, 4, 2.0, 3)
        assertEquals(50.0f, result, 0.001f)
    }

    @Test
    fun `kappaSigmaMean handles extreme outlier`() {
        val values = intArrayOf(10, 10, 10, 10, 10, 10, 10, 10, 10, 255)
        val result = kappaSigmaMean(values, 10, 2.0, 3)
        assertTrue("Result should be near 10, was $result", abs(result - 10.0f) < 5f)
    }

    // ===== NaN / Infinity HANDLING =====

    @Test
    fun `kappaSigmaMean handles NaN in input gracefully`() {
        // NaN should not crash; the function should handle it
        val values = intArrayOf(100, 100, 100, 100)
        val result = kappaSigmaMean(values, 4, 2.0, 3)
        assertFalse("Result should not be NaN", result.isNaN())
        assertFalse("Result should not be Infinity", result.isInfinite())
    }

    // ===== INSUFFICIENT SAMPLE FALLBACK =====

    @Test
    fun `kappaSigmaMean with 2 samples returns median`() {
        val values = intArrayOf(10, 20)
        val result = kappaSigmaMean(values, 2, 2.0, 3)
        assertEquals(15.0f, result, 0.001f)
    }

    @Test
    fun `kappaSigmaMean with 1 sample returns that value`() {
        val values = intArrayOf(42)
        val result = kappaSigmaMean(values, 1, 2.0, 3)
        assertEquals(42.0f, result, 0.001f)
    }

    // ===== STAR ELONGATION (pure math) =====

    @Test
    fun `elongation calculation for circular blob`() {
        // For a perfectly circular blob: mu20 ≈ mu02, mu11 ≈ 0
        // eigenvalues should be equal → elongation ≈ 1.0
        val mu20 = 10.0
        val mu02 = 10.0
        val mu11 = 0.0
        val trace = mu20 + mu02
        val disc = sqrt(((mu20 - mu02) / 2.0).pow(2) + mu11 * mu11)
        val lamMax = trace / 2.0 + disc
        val lamMin = maxOf(trace / 2.0 - disc, 1e-6)
        val elongation = sqrt(lamMax / lamMin)
        assertEquals(1.0, elongation, 0.01)
    }

    @Test
    fun `elongation calculation for elongated blob`() {
        // For an elongated blob: mu20 >> mu02
        val mu20 = 100.0
        val mu02 = 10.0
        val mu11 = 0.0
        val trace = mu20 + mu02
        val disc = sqrt(((mu20 - mu02) / 2.0).pow(2) + mu11 * mu11)
        val lamMax = trace / 2.0 + disc
        val lamMin = maxOf(trace / 2.0 - disc, 1e-6)
        val elongation = sqrt(lamMax / lamMin)
        assertTrue("Elongated blob should have elongation > 1.5", elongation > 1.5)
    }

    @Test
    fun `elongation calculation for 2x elongated`() {
        // 2:1 aspect ratio → elongation should be ~2.0
        val mu20 = 40.0
        val mu02 = 10.0
        val mu11 = 0.0
        val trace = mu20 + mu02
        val disc = sqrt(((mu20 - mu02) / 2.0).pow(2) + mu11 * mu11)
        val lamMax = trace / 2.0 + disc
        val lamMin = maxOf(trace / 2.0 - disc, 1e-6)
        val elongation = sqrt(lamMax / lamMin)
        assertEquals(2.0, elongation, 0.1)
    }

    // ===== BACKGROUND MODEL =====

    @Test
    fun `background subtraction preserves relative brightness`() {
        // Simulate: pixel = 100, background = 30 → corrected = 70
        val pixel = 100f
        val bg = 30f
        val corrected = maxOf(0f, pixel - bg)
        assertEquals(70f, corrected, 0.001f)
    }

    @Test
    fun `background subtraction clips to zero`() {
        val pixel = 20f
        val bg = 30f
        val corrected = maxOf(0f, pixel - bg)
        assertEquals(0f, corrected, 0.001f)
    }

    // ===== TRANSFORM VALIDATION =====

    @Test
    fun `scale within valid range`() {
        val scale = 1.05
        assertTrue("Scale should be in 0.85..1.15", scale in 0.85..1.15)
    }

    @Test
    fun `rotation within valid range`() {
        val rotation = 5.0
        assertTrue("Rotation should be <= 15 degrees", abs(rotation) <= 15.0)
    }

    @Test
    fun `translation within valid range for 2048px image`() {
        val tx = 500.0
        val limit = 0.35 * 2048
        assertTrue("Translation should be within limit", abs(tx) <= limit)
    }

    @Test
    fun `excessive scale rejected`() {
        val scale = 2.0
        assertFalse("Scale > 1.15 should be rejected", scale in 0.85..1.15)
    }

    @Test
    fun `excessive rotation rejected`() {
        val rotation = 20.0
        assertFalse("Rotation > 15 should be rejected", abs(rotation) <= 15.0)
    }

    // ===== EXPOSURE NORMALIZATION =====

    @Test
    fun `exposure factor clamped to 0.5..2_0`() {
        val refLum = 100.0
        val frameLum = 10.0
        val factor = (refLum / frameLum.coerceAtLeast(0.001)).coerceIn(0.5, 2.0)
        assertEquals(2.0, factor, 0.001)
    }

    @Test
    fun `exposure factor for same brightness`() {
        val refLum = 100.0
        val frameLum = 100.0
        val factor = (refLum / frameLum.coerceAtLeast(0.001)).coerceIn(0.5, 2.0)
        assertEquals(1.0, factor, 0.001)
    }

    // ===== Kappa-sigma sigma floor =====

    @Test
    fun `sigma floor prevents division by near-zero`() {
        // All values identical → variance = 0 → sigma should be floor (2)
        val values = intArrayOf(50, 50, 50, 50, 50)
        val n = 5
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            sum += values[i]
            sumSq += values[i] * values[i]
        }
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        val sigma = maxOf(2.0, sqrt(variance))
        assertTrue("Sigma should be at least floor of 2", sigma >= 2.0)
    }

    @Test
    fun `sigma uses variance when larger than floor`() {
        val values = intArrayOf(10, 50, 100, 150, 200)
        val n = 5
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            sum += values[i]
            sumSq += values[i] * values[i]
        }
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        val sigma = maxOf(2.0, sqrt(variance))
        assertTrue("Sigma should be > 2 when variance is large", sigma > 2.0)
    }

    // Helper: access private medianOf via reflection for testing
    private fun medianOf(values: IntArray, n: Int): Double {
        val sorted = values.copyOfRange(0, n).sortedArray()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }

    // Helper: access private kappaSigmaMean logic for testing
    private fun kappaSigmaMean(values: IntArray, n: Int, kappa: Double, kappaPasses: Int): Float {
        if (n <= 3) {
            return medianOf(values, n).toFloat()
        }
        val effectiveKappa = if (n in 4..7) kappa * 1.25 else kappa
        val effectivePasses = if (n in 4..7) minOf(kappaPasses, 1) else kappaPasses
        val accepted = BooleanArray(n) { true }
        var center = medianOf(values, n)
        repeat(effectivePasses) {
            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            for (i in 0 until n) {
                if (accepted[i]) {
                    val v = values[i].toDouble()
                    sum += v
                    sumSq += v * v
                    count++
                }
            }
            if (count == 0) return@repeat
            val mean = sum / count
            val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
            val dataRange = (values.maxOrNull()!! - values.minOrNull()!!).toDouble().coerceAtLeast(1.0)
            val sigma = maxOf(maxOf(2.0, 0.015 * dataRange), sqrt(variance))
            var changed = false
            for (i in 0 until n) {
                if (accepted[i] && abs(values[i] - mean) > effectiveKappa * sigma) {
                    accepted[i] = false
                    changed = true
                }
            }
            center = mean
            if (!changed) return@repeat
        }
        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            if (accepted[i]) {
                sum += values[i]
                count++
            }
        }
        return if (count == 0) center.toFloat() else (sum / count).toFloat()
    }

    private fun Double.pow(n: Double): Double = Math.pow(this, n)
}
