package vn.vltelecom

/** Android may return an array whose runtime component is a concrete filter subclass. */
internal inline fun <reified T> appendTypedElement(existing:Array<out T>,extra:T):Array<T> =
    Array<T>(existing.size+1){index->if(index<existing.size)existing[index] else extra}
