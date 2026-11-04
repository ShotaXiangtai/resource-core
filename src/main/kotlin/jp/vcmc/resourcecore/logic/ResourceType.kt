package jp.vcmc.resourcecore.logic

/** 管理対象の資源ワールドの種類。 */
enum class ResourceType(val id: String, val defaultKey: String) {
    OVERWORLD("overworld", "resource:overworld"),
    NETHER("nether", "resource:nether"),
    END("end", "resource:the_end");

    companion object {
        fun fromId(id: String): ResourceType? = entries.firstOrNull { it.id == id.lowercase() }
    }
}
