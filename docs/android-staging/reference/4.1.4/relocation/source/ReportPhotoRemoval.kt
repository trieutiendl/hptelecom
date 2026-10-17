package vn.vltelecom

/** Keep manual row notes with surviving photos; deleting the last photo must not discard notes. */
internal object ReportPhotoRemoval {
    fun remove(report:IncidentReport,group:IncidentGroup,indices:Set<Int>):List<IncidentImage> {
        val old=group.images.toList();val removed=old.filterIndexed{i,_->i in indices}
        if(removed.isEmpty())return emptyList()
        val surviving=old.withIndex().filter{it.index !in indices}
        val survivingPoints=surviving.mapNotNull{MxCoordinates.fromMetadata(it.value.metadata)}.map{it.description()}.toSet()
        val removedPoints=removed.mapNotNull{MxCoordinates.fromMetadata(it.metadata)}.filter{it.description() !in survivingPoints}
        val allPoints=old.mapNotNull{MxCoordinates.fromMetadata(it.metadata)}
        fun clean(note:String,points:List<MxCoordinates>)=note.lines().filterNot{line->line.isNotBlank()&&points.any{it.captionWithoutCoordinates(line).isBlank()}}.joinToString("\n")
        // Deduplicate the same row block shared by two photos, never intentional lines inside a note.
        fun combined(notes:List<String>)=notes.filter{it.isNotBlank()}.distinct().joinToString("\n")
        val rows=old.indices.map{it/2}.distinct().associateWith{group.pairNotes.getOrNull(it)?:group.note}
        val orphanNotes=rows.filterKeys{row->surviving.none{it.index/2==row}}.values.map{clean(it,removedPoints)}
        group.note=combined(listOf(clean(group.note,removedPoints))+orphanNotes)
        group.images.clear();group.images.addAll(surviving.map{it.value})
        group.pairNotes.clear();group.pairNotes.addAll(surviving.chunked(2).map{pair->
            val points=pair.mapNotNull{MxCoordinates.fromMetadata(it.value.metadata)}.map{it.description()}.toSet()
            val otherPoints=allPoints.filter{it.description() !in points}
            combined(pair.map{rows[it.index/2].orEmpty()}.distinct().map{clean(it,otherPoints)})
        })
        if(IncidentRules.isMx(group)&&!report.relocation) {
            val generated=old.take(2).mapNotNull{MxCoordinates.fromMetadata(it.metadata)}
            fun automatic(text:String)=text.isNotBlank()&&generated.any{it.captionWithoutCoordinates(text).isBlank()}
            if(automatic(report.closureLocation))report.closureLocation=""
            if(automatic(report.closureLocation2))report.closureLocation2=""
            report.backfillMxSummaryLocations()
        }
        return removed
    }
}
