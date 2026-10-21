package com.example.llama

import org.junit.Assert.assertTrue
import org.junit.Test

/** gradle :app:testDebugUnitTest —— 跑同一套回归语料（不需要手机） */
class AssistantSelfTestJUnit {
    @Test
    fun corpus() {
        val r = AssistantSelfTest.runAll()
        assertTrue(r.render(), r.ok)
    }
}
