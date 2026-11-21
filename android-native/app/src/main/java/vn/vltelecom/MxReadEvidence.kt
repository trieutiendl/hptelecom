package vn.vltelecom

/** Independent image passes must agree; ambiguous printed pairs are never guessed. */
internal class MxReadEvidence {
    private data class Vote(val point:MxLocalParser.Point,var count:Int)
    private val votes=mutableListOf<Vote>()
    private var ambiguous=false
    fun add(candidates:List<MxLocalParser.Point>) {
        val distinct=candidates.fold(mutableListOf<MxLocalParser.Point>()){list,p->
            if(list.none{MxLocalParser.same(it,p)})list.add(p)
            list
        }
        if(distinct.size>1){ambiguous=true;return}
        distinct.singleOrNull()?.let{p->
            val known=votes.firstOrNull{MxLocalParser.same(it.point,p)}
            if(known==null)votes.add(Vote(p,1))else known.count++
        }
    }
    fun result():MxLocalParser.Point? {
        if(ambiguous)return null
        val ordered=votes.sortedByDescending{it.count}
        val best=ordered.firstOrNull()?:return null
        // A single noisy pass must not abort the remaining original-image scans.
        return best.point.takeIf{
            if(ordered.size==1)best.count>=2
            else best.count>=3&&ordered.drop(1).all{it.count==1}
        }
    }
}
