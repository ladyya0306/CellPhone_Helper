package com.example.llama

/**
 * 预设人物关系（通讯录 2.0 · A1）。
 *
 * 关系是**虚构设定**，只用于：联系人详情页展示、群聊组合推荐、朋友圈/编排时知道"谁和谁熟"。
 * 不映射真人，也不参与聊天提示词的事实部分。
 * **不预设群**：群由用户自己选 2–3 人创建；这里不提供任何预置群。
 */
enum class RelationKind { BOSS_SUBORDINATE, COLLEAGUE, RIVAL, FRIEND, CLASSMATE, EX, NEIGHBOR, STRANGER }

data class Relation(
    val a: String,
    val b: String,
    val kind: RelationKind,
    /** 一句备注，展示给用户；不写隐私 */
    val note: String,
)

object PersonaRelations {

    val ALL: List<Relation> = listOf(
        Relation("fuchen", "shenyan", RelationKind.RIVAL, "认识十几年的旧识，合作过也翻过脸"),
        Relation("fuchen", "hudegui", RelationKind.BOSS_SUBORDINATE, "傅沉是胡德贵的前老板"),
        Relation("shenyan", "hudegui", RelationKind.BOSS_SUBORDINATE, "沈砚做过胡德贵的上级"),
        Relation("hudegui", "buddy", RelationKind.BOSS_SUBORDINATE, "前上司/下属，互相不对付"),
        Relation("buddy", "aling", RelationKind.FRIEND, "酒友，偶尔一起跑开放麦"),
        Relation("buddy", "chenye", RelationKind.FRIEND, "球友，周末偶尔约球"),
        Relation("aling", "chenye", RelationKind.FRIEND, "夜宵搭子"),
        Relation("oneesan", "shenze", RelationKind.FRIEND, "出版社与花店，认识多年"),
        Relation("catgirl", "genki", RelationKind.FRIEND, "猫咖常客"),
        Relation("catgirl", "puppy", RelationKind.FRIEND, "宠物店与猫咖，常见面"),
        Relation("genki", "puppy", RelationKind.FRIEND, "一个跑步一个遛狗，常在公园遇到"),
        Relation("tsundere", "kuudere", RelationKind.CLASSMATE, "大学同学，关系一般但认识很久"),
        Relation("fuchen", "buddy", RelationKind.COLLEAGUE, "旧同事，偶尔还联系"),
        Relation("hudegui", "chenye", RelationKind.RIVAL, "互相看不上：一个嫌对方油，一个嫌对方愣"),
    )

    fun of(id: String): List<Relation> = ALL.filter { it.a == id || it.b == id }

    /** 无向查询；BOSS_SUBORDINATE 的 a 是上级、b 是下级 */
    fun between(a: String, b: String): Relation? =
        ALL.firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }

    fun problems(): List<String> {
        val out = ArrayList<String>()
        val ids = PersonaPacks.ALL.map { it.persona.id }.toSet()
        val privateIds = PersonaPacks.ALL.filter { it.privateSpace }.map { it.persona.id }.toSet()
        val seen = HashSet<String>()
        ALL.forEach { r ->
            if (r.a !in ids || r.b !in ids) out.add("关系里有不存在的人设：${r.a}/${r.b}")
            if (r.a == r.b) out.add("关系不能指向自己：${r.a}")
            if (r.a in privateIds || r.b in privateIds) out.add("私密人设不应有关系：${r.a}/${r.b}")
            if (r.note.isBlank()) out.add("关系缺少备注：${r.a}/${r.b}")
            val key = listOf(r.a, r.b).sorted().joinToString("|") + "|" + r.kind.name
            if (!seen.add(key)) out.add("关系重复：$key")
        }
        return out
    }
}
