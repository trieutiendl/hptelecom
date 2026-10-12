package vn.vltelecom

/** Photo-only collaborator intent. Report fields and row notes are never sent as a full PUT. */
internal object PhotoMutationDelta {
    data class Mutation(val action:String,val group:String,val file:String,val expected:Map<String,Any?>,val image:Map<String,Any?>?=null)
    data class Plan(val images:List<ContributionDelta.Image>,val mutations:List<Mutation>) {
        val empty get()=images.isEmpty()&&mutations.isEmpty()
        fun wire():Map<String,Any?> = mapOf("images" to images.map{mapOf("group" to it.group,"image" to it.image)},
            "mutations" to mutations.map{m->linkedMapOf<String,Any?>("action" to m.action,"group" to m.group,"file" to m.file,"expected" to m.expected).apply{m.image?.let{put("image",it)}}})
    }
    private fun groups(payload:Map<String,Any?>):Map<String,List<Map<String,Any?>>> {
        val report=payload["report"] as? Map<*,*> ?: error("Báo cáo chưa có nội dung ảnh")
        val out=linkedMapOf<String,List<Map<String,Any?>>>()
        for(raw in report["groups"] as? List<*> ?: emptyList<Any>()) {
            val group=raw as? Map<*,*> ?: error("Nhóm ảnh không hợp lệ")
            val name=group["name"] as? String ?: error("Nhóm ảnh chưa có tên")
            require(name.isNotBlank()&&name !in out){"Nhóm ảnh bị trùng tên; chưa gửi thay đổi"}
            @Suppress("UNCHECKED_CAST")
            val photos=(group["images"] as? List<*> ?: emptyList<Any>()).map{it as? Map<String,Any?> ?: error("Ảnh không hợp lệ")}
            photos.forEach(::file);out[name]=photos
        }
        return out
    }
    private fun file(image:Map<String,Any?>)= (image["file"] as? String)?.takeIf{it.isNotBlank()} ?: error("Ảnh chưa có dữ liệu")
    private fun caption(image:Map<String,Any?>)=image["caption"] as? String ?: ""
    private fun normalized(image:Map<String,Any?>)=image.toMutableMap().apply {
        if(!containsKey("caption"))put("caption","")
        if(!containsKey("originalFile"))put("originalFile","")
        if(!containsKey("width"))put("width",320)
        if(!containsKey("height"))put("height",400)
    }
    private fun same(a:Any?,b:Any?):Boolean=when {
        a is Number&&b is Number -> a.toString().toBigDecimalOrNull()?.let{left->b.toString().toBigDecimalOrNull()?.let{left.compareTo(it)==0}}==true
        a is Map<*,*>&&b is Map<*,*> -> a.keys==b.keys&&a.keys.all{same(a[it],b[it])}
        a is List<*>&&b is List<*> -> a.size==b.size&&a.indices.all{same(a[it],b[it])}
        else -> a==b
    }
    private fun target(groups:Map<String,List<Map<String,Any?>>>,group:String,id:String):Map<String,Any?>? {
        val matches=groups[group].orEmpty().filter{file(it)==id}
        require(matches.size<=1){"Ảnh bị tham chiếu trùng trong cùng nhóm; chưa sửa hoặc xóa để tránh nhầm ảnh"}
        return matches.singleOrNull()
    }
    fun plan(localBase:Map<String,Any?>,local:Map<String,Any?>,serverBase:Map<String,Any?>):Plan {
        val before=groups(localBase);val after=groups(local);val wire=groups(serverBase)
        val known=before.values.flatten().map(::file).toSet()
        val additions=mutableListOf<ContributionDelta.Image>();val mutations=mutableListOf<Mutation>()
        for((name,photos) in before) {
            val oldById=photos.groupBy(::file);val newById=after[name].orEmpty().groupBy(::file)
            for((id,old) in oldById) {
                val current=newById[id].orEmpty()
                if(old.size>1||current.size>1) {
                    require(old.size==current.size&&old.indices.all{same(normalized(old[it]),normalized(current[it]))}){"Ảnh trùng chỉ được giữ nguyên; chưa gửi thay đổi"}
                    continue
                }
                val previous=old.single();val updated=current.singleOrNull()
                if(updated!=null&&same(normalized(previous),normalized(updated)))continue
                val expected=target(wire,name,id) ?: error("Thiếu mốc ảnh trên server; giữ thay đổi trên máy")
                if(updated==null) {
                    require(after.filterKeys{it!=name}.values.flatten().none{file(it)==id}){"Chưa hỗ trợ chuyển ảnh sang nhóm khác"}
                    mutations.add(Mutation("delete",name,id,expected))
                } else {
                    require(same(normalized(previous)-"caption",normalized(updated)-"caption")){"Chỉ được sửa mô tả ảnh; muốn thay ảnh hãy xóa rồi thêm ảnh mới"}
                    mutations.add(Mutation("edit",name,id,expected,expected+mapOf("caption" to caption(updated))))
                }
            }
        }
        for((name,photos) in after)for(photo in photos) {
            if(file(photo) !in known)additions.add(ContributionDelta.Image(name,photo))
            else require(before[name].orEmpty().any{file(it)==file(photo)}){"Chưa hỗ trợ chép ảnh đã gửi sang nhóm khác"}
        }
        require(additions.map{file(it.image)}.distinct().size==additions.size){"Ảnh mới bị trùng; chưa gửi để tránh lặp ảnh"}
        return Plan(additions,mutations)
    }
    /** Rebase only photo intent, never index-based row notes or a whole stale report. */
    fun rebase(plan:Plan,remote:Map<String,Any?>):Plan {
        val now=groups(remote)
        val changes=plan.mutations.mapNotNull{m->
            val current=target(now,m.group,m.file)
            if(current==null) {
                require(m.action=="delete"){"Ảnh đã bị xóa trên server; giữ mô tả sửa trên máy để đối chiếu"}
                null // A repeated deletion never recreates an absent photo.
            } else if(m.action=="delete") {
                require(same(normalized(current),normalized(m.expected))){"Ảnh đã được sửa trên server; chưa xóa để tránh mất thay đổi của người khác"}
                m.copy(expected=current)
            } else {
                val desired=caption(requireNotNull(m.image));val existing=caption(current)
                require(existing==caption(m.expected)||existing==desired){"Mô tả ảnh đã được người khác sửa; giữ thay đổi trên máy để đối chiếu"}
                if(existing==desired)null else m.copy(expected=current,image=current+mapOf("caption" to desired))
            }
        }
        val existing=now.values.flatten().map(::file).toSet()
        require(plan.images.none{file(it.image) in existing}){"Ảnh mới đã xuất hiện trên server; cần đối chiếu trước khi gửi lại"}
        return Plan(plan.images,changes)
    }
    fun fromWire(value:Map<String,Any?>):Plan {
        @Suppress("UNCHECKED_CAST")
        fun image(value:Any?)=value as? Map<String,Any?> ?: error("Nhật ký ảnh không hợp lệ")
        val additions=(value["images"] as? List<*>).orEmpty().map{val row=image(it);ContributionDelta.Image(row["group"] as String,image(row["image"]))}
        val mutations=(value["mutations"] as? List<*>).orEmpty().map{val row=image(it);Mutation(row["action"] as String,row["group"] as String,row["file"] as String,image(row["expected"]),row["image"]?.let(::image))}
        return Plan(additions,mutations)
    }
}
