package vn.vltelecom

/** Only additions are eligible for a collaborator operation; existing images stay authoritative. */
internal object ContributionDelta {
    data class Image(val group: String, val image: Map<String, Any?>)
    private fun groups(payload: Map<String, Any?>): Map<String, List<Map<String, Any?>>> {
        val report = payload["report"] as? Map<*, *> ?: error("Báo cáo chung chưa có nội dung ảnh")
        val result = linkedMapOf<String, List<Map<String, Any?>>>()
        for (raw in report["groups"] as? List<*> ?: emptyList<Any>()) {
            val group = raw as? Map<*, *> ?: error("Nhóm ảnh không hợp lệ")
            val name = group["name"] as? String ?: error("Nhóm ảnh chưa có tên")
            require(name.isNotBlank() && name !in result) { "Nhóm ảnh bị trùng tên" }
            @Suppress("UNCHECKED_CAST")
            val images = (group["images"] as? List<*> ?: emptyList<Any>()).map {
                it as? Map<String, Any?> ?: error("Ảnh không hợp lệ")
            }
            result[name] = images
        }
        return result
    }
    private fun key(image: Map<String, Any?>) = (image["file"] as? String)?.takeIf { it.isNotBlank() }
        ?: error("Ảnh chưa có dữ liệu")
    // IncidentReport.parse/json supplies these exact defaults when old server images omit them.
    // Keep every other field in the comparison, including attribution, GPS and overlay metadata.
    private fun normalized(image: Map<String, Any?>) = image.toMutableMap().apply {
        if (!containsKey("caption")) put("caption", "")
        if (!containsKey("originalFile")) put("originalFile", "")
        if (!containsKey("width")) put("width", 320)
        if (!containsKey("height")) put("height", 400)
    }
    fun added(base: Map<String, Any?>, local: Map<String, Any?>): List<Image> {
        val before = groups(base)
        val after = groups(local)
        val remaining = after.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((group, images) in before) {
            for (image in images) {
                key(image)
                val candidates=remaining[group]
                val index=candidates?.indexOfFirst{normalized(it)==normalized(image)} ?: -1
                require(index >= 0) {
                    "Người cùng làm chỉ được thêm ảnh. Thay đổi ảnh đã gửi được giữ trên máy, chưa ghi đè server."
                }
                candidates!!.removeAt(index)
            }
        }
        val added=remaining.flatMap{(group,images)->images.map{Image(group,it)}}
        require(added.map{key(it.image)}.distinct().size==added.size){"Ảnh mới bị trùng; chưa gửi để tránh lặp ảnh"}
        return added
    }
}
