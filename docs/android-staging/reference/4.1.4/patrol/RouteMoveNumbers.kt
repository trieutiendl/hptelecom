package vn.vltelecom
object RouteMoveNumbers {
    fun allocate(occupied:List<Int>,count:Int):List<Int>{
        require(count>=0)
        val last=(occupied.maxOrNull()?:0).coerceAtLeast(0)
        require(last.toLong()+count<=Int.MAX_VALUE){"STT vượt giới hạn"}
        return (1..count).map{last+it}
    }
}
