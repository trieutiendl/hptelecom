package vn.vltelecom

/** Three-way merge of JSON values. Callers must use the same asset IDs in all three trees. */
internal object SyncMerge {
    data class Result(val value: Any?, val conflicts: List<String>) {
        val successful: Boolean get() = conflicts.isEmpty()
    }

    private object Missing

    fun merge(base: Any?, local: Any?, remote: Any?): Result {
        val conflicts = linkedSetOf<String>()
        val value = mergeValue(base, local, remote, "$", "", conflicts)
        if (conflicts.isEmpty()) validatePhotos(value, "$", conflicts)
        return Result(if (conflicts.isEmpty()) value else null, conflicts.toList())
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is Number && b is Number -> {
            val left = a.toString().toBigDecimalOrNull()
            val right = b.toString().toBigDecimalOrNull()
            left != null && right != null && left.compareTo(right) == 0
        }
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }

    private fun mergeValue(
        base: Any?, local: Any?, remote: Any?, path: String, field: String,
        conflicts: MutableSet<String>
    ): Any? {
        if (same(local, remote)) return local
        if (same(local, base)) return remote
        if (same(remote, base)) return local
        // Missing differs from an explicit JSON null. Deletion versus an edit requires review.
        if (local === Missing || remote === Missing) return conflict(path, conflicts)
        if ((base is Map<*, *> || base === Missing) && local is Map<*, *> && remote is Map<*, *>) {
            val before = if (base is Map<*, *>) base else emptyMap<String, Any?>()
            val keys = (before.keys + local.keys + remote.keys).toSet()
            if (keys.any { it !is String }) return conflict(path, conflicts)
            val merged = linkedMapOf<String, Any?>()
            for (key in keys) {
                key as String
                val child = mergeValue(present(before, key), present(local, key), present(remote, key),
                    "$path.${key}", key, conflicts)
                if (child !== Missing) merged[key] = child
            }
            validateRowAlignment(before, local, remote, merged, path, conflicts)
            return merged
        }
        if ((base is List<*> || base === Missing) && local is List<*> && remote is List<*>) {
            val before = if (base is List<*>) base else emptyList<Any?>()
            return mergeList(before, local, remote, path, field, conflicts)
        }
        return conflict(path, conflicts)
    }

    private fun present(map: Map<*, *>, key: String): Any? = if (map.containsKey(key)) map[key] else Missing

    private fun validateRowAlignment(
        base: Map<*, *>, local: Map<*, *>, remote: Map<*, *>, merged: Map<*, *>,
        path: String, conflicts: MutableSet<String>
    ) {
        val notes = merged["pairNotes"] as? List<*> ?: return
        if (notes.none { it is String && it.isNotBlank() || it != null && it !is String }) return
        if (listOf(base, local, remote).none { it["images"] is List<*> }) return
        fun layout(value: Map<*, *>): List<String>? =
            (value["images"] as? List<*>)?.let { keyed(it, "images")?.keys?.toList() }
        val order = layout(merged)
        if (order != null && order == layout(base)) return
        // Row descriptions belong to adjacent image pairs, not to array slots
        // that can be merged independently. Keep a side's notes only when its
        // complete image order survives. Combining different layouts requires
        // review; guessing could move prose/GPS or hide an orphaned last row.
        if (order != null && listOf(local, remote).any {
                order == layout(it) && same(notes, present(it, "pairNotes"))
            }) return
        conflicts.add("$path.pairNotes")
    }

    private fun conflict(path: String, conflicts: MutableSet<String>): Any {
        conflicts.add(path)
        return Missing
    }

    private fun keyed(values: List<*>, field: String): LinkedHashMap<String, Any?>? {
        if (field !in setOf("photos", "images", "groups")) return null
        val result = linkedMapOf<String, Any?>()
        for (value in values) {
            val row = value as? Map<*, *> ?: return null
            fun text(key: String) = (row[key] as? String)?.takeIf { it.isNotBlank() }
            val id = when (field) {
                "groups" -> text("key")?.let { "key:$it" } ?: text("name")?.let { "name:$it" }
                else -> (text("originalFile") ?: text("file"))?.let { "asset:$it" }
            } ?: return null
            if (result.containsKey(id)) return null
            result[id] = value
        }
        return result
    }

    private fun mergeList(
        base: List<*>, local: List<*>, remote: List<*>, path: String, field: String,
        conflicts: MutableSet<String>
    ): Any? {
        // Positional arrays and ambiguous identities are atomic, never guessed from their index.
        val before = keyed(base, field) ?: return conflict(path, conflicts)
        val ours = keyed(local, field) ?: return conflict(path, conflicts)
        val theirs = keyed(remote, field) ?: return conflict(path, conflicts)
        val values = linkedMapOf<String, Any?>()
        for (id in (before.keys + ours.keys + theirs.keys).toSet()) {
            val value = mergeValue(present(before, id), present(ours, id), present(theirs, id),
                "$path[$id]", "", conflicts)
            if (value !== Missing) values[id] = value
        }
        val common = before.keys.filter { it in ours && it in theirs && it in values }.toSet()
        val baseOrder = before.keys.filter { it in common }
        val localOrder = ours.keys.filter { it in common }
        val remoteOrder = theirs.keys.filter { it in common }
        val localReordered = localOrder != baseOrder
        val remoteReordered = remoteOrder != baseOrder
        if (localReordered && remoteReordered && localOrder != remoteOrder) return conflict("$path.order", conflicts)
        // Keep the side that reordered established items; append additions unique to the other side.
        val primary = if (localReordered) ours.keys else theirs.keys
        val secondary = if (localReordered) theirs.keys else ours.keys
        return (primary + secondary).toSet().filter { it in values }.map { values[it] }
    }

    private fun validatePhotos(value: Any?, path: String, conflicts: MutableSet<String>) {
        when (value) {
            is Map<*, *> -> for ((key, child) in value) {
                val next = "$path.$key"
                if (key == "photos" && child is List<*>) {
                    val seqs = mutableSetOf<java.math.BigDecimal>()
                    for ((index, photo) in child.withIndex()) {
                        val number = ((photo as? Map<*, *>)?.get("seq") as? Number)?.toString()?.toBigDecimalOrNull()
                        if (number == null || number.signum() <= 0 || number.stripTrailingZeros().scale() > 0) {
                            conflicts.add("$next[$index].seq")
                        } else if (!seqs.add(number.stripTrailingZeros())) {
                            conflicts.add("$next.seq=$number")
                        }
                    }
                }
                validatePhotos(child, next, conflicts)
            }
            is List<*> -> value.forEachIndexed { index, child -> validatePhotos(child, "$path[$index]", conflicts) }
        }
    }
}
