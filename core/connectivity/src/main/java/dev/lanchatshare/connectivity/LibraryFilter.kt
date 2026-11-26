package dev.lanchatshare.connectivity

import androidx.sqlite.db.SimpleSQLiteQuery

enum class FileKind { Images, Videos, Audio, Documents, Archives, Other }
enum class FileOrder { Date, Name, Size, Type }

data class LibraryFilter(val text: String = "", val kind: FileKind? = null, val outgoing: Boolean? = null,
    val peer: String? = null, val since: Long? = null, val order: FileOrder = FileOrder.Date) {
    internal fun query(): SimpleSQLiteQuery {
        val clauses = mutableListOf("(outgoing=1 OR state='done')","state!='removed'")
        val args = mutableListOf<Any>()
        if (text.isNotBlank()) { clauses += "instr(lower(name),lower(?)) > 0"; args += text.trim() }
        outgoing?.let { clauses += "outgoing=?"; args += if (it) 1 else 0 }
        peer?.let { clauses += "peer=?"; args += it }
        since?.let { clauses += "time>=?"; args += it }
        kind?.let {
            clauses += """CASE WHEN mime LIKE 'image/%' THEN 'Images' WHEN mime LIKE 'video/%' THEN 'Videos'
                WHEN mime LIKE 'audio/%' THEN 'Audio' WHEN lower(name) GLOB '*.zip' OR lower(name) GLOB '*.7z'
                OR lower(name) GLOB '*.rar' OR lower(name) GLOB '*.tar' OR lower(name) GLOB '*.gz' OR mime LIKE '%zip%' THEN 'Archives'
                WHEN mime LIKE 'text/%' OR mime='application/pdf' OR mime LIKE '%office%' OR mime LIKE '%msword%'
                OR mime LIKE '%ms-excel%' OR mime LIKE '%opendocument%' THEN 'Documents' ELSE 'Other' END=?"""
            args += it.name
        }
        val sort = when(order) {
            FileOrder.Date -> "time DESC"
            FileOrder.Name -> "name COLLATE NOCASE"
            FileOrder.Size -> "size DESC"
            FileOrder.Type -> "mime COLLATE NOCASE,name COLLATE NOCASE"
        }
        return SimpleSQLiteQuery("SELECT * FROM transfers WHERE " + clauses.joinToString(" AND ") + " ORDER BY $sort,rowid DESC", args.toTypedArray())
    }
}
