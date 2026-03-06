package com.teams.webApp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Receives the DownloadManager broadcast when a file download finishes
 * and shows a toast with an option to open the file.
 */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)

        if (cursor.moveToFirst()) {
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusCol >= 0) cursor.getInt(statusCol) else -1

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val uriString = if (uriCol >= 0) cursor.getString(uriCol) else null
                if (uriString != null) {
                    Toast.makeText(context, context.getString(R.string.download_complete), Toast.LENGTH_SHORT).show()
                }
            }
        }
        cursor.close()
    }
}
