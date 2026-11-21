package vn.vltelecom

/** EXIF text writers may use ASCII. JSON escapes retain every UTF-16 code unit. */
object PhotoTextEncoding {
    fun routeKey(text:String)="hp-route-text-v2:"+java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    fun asciiJson(json:String):String=buildString {
        json.forEach { c -> if(c.code>127)append("\\u"+c.code.toString(16).padStart(4,'0')) else append(c) }
    }
    // Only restore fixed application labels; never guess damaged user-entered text.
    fun restoreLabel(line:String):String=when {
        line.startsWith("Ng?y ch?p:")->"Ngày chụp:"+line.substringAfter(':')
        line.startsWith("ng?y ch?p:")->"Ngày chụp:"+line.substringAfter(':')
        line.startsWith("Ghi ch?:")->"Ghi chú:"+line.substringAfter(':')
        else->line
    }
}
