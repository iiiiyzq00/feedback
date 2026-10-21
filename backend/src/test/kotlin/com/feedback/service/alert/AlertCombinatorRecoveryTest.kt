package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertCombinatorRecoveryTest {
 @Test fun `any rule remains triggered when backlog condition matches but oldest condition is below recovery`() {
  val conditions=listOf(true,false)
  val matched=conditions.any{it}
  val primaryValue=0.0
  val recoveryThreshold=3.0
  assertTrue(matched)
  assertFalse(shouldResolveAlert(matched,primaryValue,recoveryThreshold))
 }

 @Test fun `inactive rule resolves only after trigger misses and primary reaches recovery threshold`() {
  val matched=false
  assertTrue(shouldResolveAlert(matched,2.0,3.0))
 assertFalse(shouldResolveAlert(matched,5.0,3.0))
 }

 @Test fun `any production alert selects the condition that actually triggered`() {
  val evaluations=listOf(
   mapOf<String,Any>("metric" to "MASKING_BACKLOG_COUNT","matched" to true),
   mapOf<String,Any>("metric" to "MASKING_OLDEST_PENDING_MINUTES","matched" to false)
  )
  assertEquals(0,alertPrimaryConditionIndex("ANY",evaluations,true))
 }

 @Test fun `all production alert keeps the business outcome condition as primary`() {
  val evaluations=listOf(
   mapOf<String,Any>("metric" to "MASKING_ATTEMPT_COUNT","matched" to true),
   mapOf<String,Any>("metric" to "MASKING_FAILURE_RATE","matched" to true)
  )
  assertEquals(1,alertPrimaryConditionIndex("ALL",evaluations,true))
 }
}
