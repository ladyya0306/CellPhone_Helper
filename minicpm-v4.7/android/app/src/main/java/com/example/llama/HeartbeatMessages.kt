package com.example.llama

/**
 * 心跳话术模板 —— 按人设 × 话题给多套说法，随机挑一条。
 *
 * 为什么用模板而不是让模型临时生成：
 *   心跳是后台触发的，模型可能未加载/正被前台占用。模板能保证**永远说得出话**，
 *   且语气可控、不会跑偏。模型生成版留作后续增强（设置里标注了「待接线」）。
 *
 * 池子大小很重要：v1 每话题只有 2 条，随机来随机去就那两句，
 * 这正是"小吵闹"的直接来源。现在每话题 4–6 条，配合服务端的
 * 「不与最近 4 条重复」+「同内容 4 小时冷却」两道闸门。
 */
object HeartbeatMessages {

    private fun pick(list: List<String>): String = list.random()

    /** 没有真实数据可说时的心跳话术：按人设 × 话题随机挑一句（话术池在各人设的 Pack*.kt 里；没有的话题用 else 池） */
    fun forTopic(persona: Persona, topic: String): String {
        val pools = PersonaPacks.packOf(persona.id).hbMessages
        return pick(pools[topic] ?: pools["else"] ?: listOf("在。"))
    }
}
