package com.miniwall.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoTriggersTest {
    private val body = Json.parseToJsonElement(
        """{"placements":[
          {"placement":"welcome","trigger":{"type":"app_open","pages":[],"priority":1}},
          {"placement":"rate","trigger":{"type":"app_open","every":3,"pages":[],"priority":9}},
          {"placement":"level","trigger":{"type":"event","event":"level_completed","pages":[],"priority":0}},
          {"placement":"later","trigger":{"type":"delay","seconds":4,"pages":[],"priority":0}},
          {"placement":"web","trigger":{"type":"page_load","pages":[],"priority":0}},
          {"placement":"broken","trigger":{"type":"event","pages":[],"priority":0}}
        ]}"""
    ).jsonObject

    @Test
    fun appOpensFireEveryNthByPriorityAndPersist() {
        val store = MemoryStore()
        val triggers = AutoTriggers(store).apply { load(body) }
        assertEquals(listOf("rate", "level", "welcome", "later").sorted(), triggers.triggers.map { it.placement }.sorted())
        assertEquals(listOf("welcome"), triggers.appOpened().now)
        assertEquals(listOf("welcome"), triggers.appOpened().now)
        // The third open reaches "rate" (every 3rd), which has the higher priority.
        val third = triggers.appOpened()
        assertEquals(listOf("rate", "welcome"), third.now)
        assertEquals(listOf(AppOpened.Delayed("later", 4)), third.delayed)
        // The count survives a restart.
        assertEquals(listOf("welcome"), AutoTriggers(store).apply { load(body) }.appOpened().now)
    }

    @Test
    fun eventsMatchByName() {
        val triggers = AutoTriggers(MemoryStore()).apply { load(body) }
        assertEquals(listOf("level"), triggers.event("level_completed"))
        assertEquals(emptyList(), triggers.event("other"))
    }
}
