@file:Suppress("DEPRECATION")

package com.example.messenger_app.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.example.messenger_app.BuildConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object AppUpdateManager {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/Melvud/antimax/main/version.json"

    fun checkForUpdateAndPrompt(activity: Activity, showIfUpToDate: Boolean = false) {
        thread {
            runCatching {
                val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentVersion = pInfo.versionName ?: "0.0.0"

                val body = (URL(VERSION_URL).openConnection() as HttpURLConnection).run {
                    connectTimeout = 15000
                    readTimeout = 20000
                    requestMethod = "GET"
                    inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
                }

                val json = JSONObject(body)
                val latestVersion = json.optString("version").trim()
                val apkUrl = json.optString("apk_url").trim()
                val changelog = json.optString("changelog").trim()

                if (latestVersion.isBlank() || apkUrl.isBlank()) return@runCatching

                if (isNewer(latestVersion, currentVersion)) {
                    activity.runOnUiThread {
                        val msg = buildString {
                            append("Доступна новая версия ").append(latestVersion).append("!\n\n")
                            if (changelog.isNotEmpty()) {
                                append("Что нового:\n").append(changelog)
                            }
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Обновление")
                            .setMessage(msg)
                            .setPositiveButton("Обновить") { dlg, _ ->
                                dlg.dismiss()
                                downloadAndInstall(activity, apkUrl, latestVersion)
                            }
                            .setNegativeButton("Позже") { dlg, _ -> dlg.dismiss() }
                            .show()
                    }
                } else if (showIfUpToDate) {
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("Обновление")
                            .setMessage("У вас уже последняя версия ($currentVersion).")
                            .setPositiveButton("Ок", null)
                            .show()
                    }
                }
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.').mapNotNull { it.toIntOrNull() }
        val c = current.split('.').mapNotNull { it.toIntOrNull() }
        val max = maxOf(l.size, c.size)
        for (i in 0 until max) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String, versionName: String) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.filesDir
        val outFile = File(dir, "update_${versionName}.apk")
        if (outFile.exists()) outFile.delete()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        val title = TextView(activity).apply {
            text = "Скачивание обновления"
            textSize = 18f
            setPadding(0, 0, 0, 12)
        }
        val progressBar = ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val percent = TextView(activity).apply {
            text = "0 %"
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
        }
        val tip = TextView(activity).apply {
            text = "Пожалуйста, не закрывайте приложение"
            setPadding(0, 12, 0, 0)
            movementMethod = ScrollingMovementMethod()
        }
        content.addView(title)
        content.addView(progressBar)
        content.addView(percent)
        content.addView(tip)

        val dlg = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .create()

        dlg.show()

        val canceled = AtomicBoolean(false)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            canceled.set(true)
            dlg.dismiss()
        }

        thread {
            runCatching {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    requestMethod = "GET"
                }
                val total = conn.contentLength
                if (total > 0) {
                    activity.runOnUiThread { progressBar.isIndeterminate = false }
                }

                val input = BufferedInputStream(conn.inputStream)
                val output = FileOutputStream(outFile)

                val buf = ByteArray(8 * 1024)
                var downloaded = 0L
                var read: Int

                while (input.read(buf).also { read = it } != -1) {
                    if (canceled.get()) {
                        output.flush()
                        output.close()
                        input.close()
                        outFile.delete()
                        conn.disconnect()
                        return@thread
                    }
                    output.write(buf, 0, read)
                    downloaded += read

                    if (total > 0) {
                        val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        activity.runOnUiThread {
                            progressBar.progress = progress
                            percent.text = "$progress %"
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()
                conn.disconnect()

                if (!canceled.get()) {
                    activity.runOnUiThread {
                        dlg.dismiss()
                        tryStartInstall(activity, outFile)
                    }
                }
            }.onFailure { e ->
                e.printStackTrace()
                if (outFile.exists()) outFile.delete()
                activity.runOnUiThread {
                    if (dlg.isShowing) dlg.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Ошибка")
                        .setMessage("Не удалось загрузить обновление:\n${e.message}")
                        .setPositiveButton("Ок", null)
                        .show()
                }
            }
        }
    }

    private fun tryStartInstall(activity: Activity, file: File) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"

        // Получаем content:// URI через FileProvider (так требует Android N+)
        val uri: Uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(activity, authority, file)
            } else {
                Uri.fromFile(file)
            }
        } catch (e: IllegalArgumentException) {
            // Провайдер не настроен — аккуратно сообщаем, без краша
            AlertDialog.Builder(activity)
                .setTitle("Установка")
                .setMessage(
                    "Не найден FileProvider с authority:\n$authority\n\n" +
                        "Проверьте, что он указан в AndroidManifest.xml и файл res/xml/file_paths.xml существует."
                )
                .setPositiveButton("Ок", null)
                .show()
            return
        }

        // Пытаемся открыть системный установщик
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }.recoverCatching {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            activity.startActivity(intent)
        }.onFailure {
            it.printStackTrace()
            AlertDialog.Builder(activity)
                .setTitle("Установка")
                .setMessage(
                    "Не удалось запустить установщик.\n" +
                        "Откройте файл вручную: ${file.absolutePath}"
                )
                .setPositiveButton("Ок", null)
                .show()
        }
    }
}
