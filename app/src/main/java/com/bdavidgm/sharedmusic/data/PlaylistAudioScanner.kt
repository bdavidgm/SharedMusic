package com.bdavidgm.sharedmusic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Recorre carpetas SAF y detecta archivos de audio (sin androidx.documentfile). */
object PlaylistAudioScanner {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "3gp", "webm", "aiff", "wma"
    )

    fun isAudioFile(mimeType: String?, fileName: String): Boolean {
        mimeType?.let {
            if (it.startsWith("audio/", ignoreCase = true)) return true
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun collectAudioUrisFromTree(context: Context, treeUri: Uri): List<Uri> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<Uri>()
        walkChildren(context, treeUri, rootId, out)
        return out
    }

    private fun walkChildren(context: Context, treeUri: Uri, parentDocId: String, out: MutableList<Uri>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIdx < 0 || mimeIdx < 0 || nameIdx < 0) return@use
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val mime = cursor.getString(mimeIdx)
                val name = cursor.getString(nameIdx) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkChildren(context, treeUri, docId, out)
                } else if (isAudioFile(mime, name)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                }
            }
        }
    }
}
