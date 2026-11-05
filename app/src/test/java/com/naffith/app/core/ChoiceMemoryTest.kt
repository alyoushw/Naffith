package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoiceMemoryTest {

    private class MapStore : ChoiceMemory.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
        override fun all(): Map<String, String> = map.toMap()
    }

    private val ahmed1 = Contact("1", "أحمد علي", "0501111111")
    private val ahmed2 = Contact("2", "أحمد سعيد", "0552222222")
    private val contacts = listOf(ahmed1, ahmed2)

    @Test fun remembersChoiceIgnoringSpellingDifferences() {
        val memory = ChoiceMemory(MapStore())
        memory.remember("أحمد", ahmed2)
        assertEquals(ahmed2, memory.recall(listOf("احمد"), contacts))
    }

    @Test fun nothingRememberedReturnsNull() {
        assertNull(ChoiceMemory(MapStore()).recall(listOf("أحمد"), contacts))
    }

    @Test fun deletedContactIsForgotten() {
        val store = MapStore()
        val memory = ChoiceMemory(store)
        memory.remember("أحمد", ahmed2)
        assertNull(memory.recall(listOf("أحمد"), listOf(ahmed1)))
        assertEquals(0, store.map.size)
    }

    @Test fun forgetAll() {
        val memory = ChoiceMemory(MapStore())
        memory.remember("أحمد", ahmed2)
        memory.forgetAll()
        assertNull(memory.recall(listOf("أحمد"), contacts))
    }

    @Test fun listsAliasesWithOriginalSpelling() {
        val memory = ChoiceMemory(MapStore())
        memory.remember("روحي", ahmed2)
        memory.remember("أبو علي", ahmed1)
        val all = memory.all()
        assertEquals(listOf("أبو علي", "روحي"), all.map { it.spoken })
        assertEquals("أحمد سعيد", all[1].contactName)
        assertEquals("0552222222", all[1].number)
    }

    @Test fun forgetOneAlias() {
        val memory = ChoiceMemory(MapStore())
        memory.remember("روحي", ahmed2)
        memory.remember("أبو علي", ahmed1)
        memory.forget(memory.all().first { it.spoken == "روحي" })
        assertNull(memory.recall(listOf("روحي"), contacts))
        assertEquals(ahmed1, memory.recall(listOf("أبو علي"), contacts))
    }

    @Test fun readsOldFormat() {
        val store = MapStore()
        store.map["choice:روحي"] = "احمد سعيد|552222222"
        val memory = ChoiceMemory(store)
        assertEquals(ahmed2, memory.recall(listOf("روحي"), contacts))
        assertEquals("احمد سعيد", memory.all().single().contactName)
    }
}
