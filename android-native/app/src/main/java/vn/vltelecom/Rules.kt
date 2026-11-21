package vn.vltelecom

object Rules {
    fun nextSequence(existing:List<Int>):Int {
        var next=1L
        for(seq in existing.filter{it>0}.distinct().sorted()) {
            if(seq.toLong()>next)break
            if(seq.toLong()==next)next++
        }
        check(next<=Int.MAX_VALUE){"STT vượt giới hạn"}
        return next.toInt()
    }
    fun validate(seq:String,name:String,note:String,lat:Double?,lon:Double?):String? {
        if((seq.toIntOrNull()?:0)<=0)return "STT phải là số nguyên lớn hơn 0."
        if(name.isBlank())return "Hãy nhập tên vị trí."
        if(note.isBlank())return "Hãy nhập ghi chú."
        if(lat==null||lon==null)return "Chưa có GPS. Bạn có thể giữ bản nháp và thử lại ngoài trời."
        if(!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0)return "Tọa độ GPS không hợp lệ."
        return null
    }
}
