package vn.vltelecom

/** Bounded original-pixel stamp regions, independent of the overview decode size. */
internal object MxImageRegions {
    data class Region(val left:Int,val top:Int,val right:Int,val bottom:Int)
    fun stamps(width:Int,height:Int):List<Region> {
        require(width>0&&height>0&&width.toLong()*height<=120_000_000L)
        val w=width.coerceAtMost(1800)
        val h=height.coerceAtMost(1000)
        return listOf(width-w,0,(width-w)/2).flatMap{x->
            listOf(0,height-h).map{y->Region(x,y,x+w,y+h)}
        }.distinct()
    }
}
