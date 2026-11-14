package com.safecross.ai.risk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSteadinessTest {

    @Test
    fun noReadings_isSteady() {
        assertTrue(PhoneSteadiness().isSteady(1_000L))
    }

    @Test
    fun smallHandTremor_isSteady() {
        val s = PhoneSteadiness()
        s.onRotation(0.1f, 1_000L)
        s.onLinearAcceleration(0.4f, 1_000L)
        assertTrue(s.isSteady(1_010L))
    }

    @Test
    fun turningThePhone_isNotSteady_untilItSettles() {
        val s = PhoneSteadiness(settleMs = 700L)
        s.onRotation(1.0f, 1_000L)
        assertFalse(s.isSteady(1_000L))
        assertFalse(s.isSteady(1_600L))
        assertTrue(s.isSteady(1_700L))
    }

    @Test
    fun walking_isNotSteady() {
        val s = PhoneSteadiness()
        s.onLinearAcceleration(2.5f, 5_000L)
        assertFalse(s.isSteady(5_100L))
    }

    @Test
    fun laterShake_extendsTheWait() {
        val s = PhoneSteadiness(settleMs = 700L)
        s.onRotation(1.0f, 1_000L)
        s.onRotation(1.0f, 1_500L)
        assertFalse(s.isSteady(1_900L))
        assertTrue(s.isSteady(2_200L))
    }

    @Test
    fun reset_clearsShake() {
        val s = PhoneSteadiness()
        s.onRotation(1.0f, 1_000L)
        s.reset()
        assertTrue(s.isSteady(1_001L))
    }
}
