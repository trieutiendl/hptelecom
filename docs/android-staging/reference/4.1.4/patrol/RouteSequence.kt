package vn.vltelecom

object RouteSequence {
    fun shifts(numbers:List<Int>,old:Int?,target:Int):Map<Int,Int> {
        require(target>0)
        return numbers.filter{it!=old}.mapNotNull { n ->
            val next=when {
                old==null && n>=target -> Math.addExact(n,1)
                old!=null && target<old && n>=target && n<old -> Math.addExact(n,1)
                old!=null && target>old && n>old && n<=target -> n-1
                else -> n
            }
            if(next==n)null else n to next
        }.toMap()
    }
}
