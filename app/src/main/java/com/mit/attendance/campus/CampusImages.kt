package com.mit.attendance.campus

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.TextUtils
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mit.attendance.R

/** Images never download or expand inside a feed or detail card. */
internal object CampusImages {
    private val markdown = Regex("!\\[[^]]*]\\(\\s*(?:<([^>]+)>|([^\\s)]+))(?:\\s+\"[^\"]*\")?\\s*\\)")
    private val imagePath = Regex("(?:akatsuki/)?api/images/([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")

    fun text(content: String) = markdown.replace(content, "").trim()

    private fun ids(content: String): List<String> = markdown.findAll(content).mapNotNull { match ->
        val uri = Uri.parse(match.groupValues[1].ifBlank { match.groupValues[2] })
        val allowed = if (uri.isRelative) uri.authority == null
            else uri.scheme == "https" && uri.host == "mitasia.in"
        if (!allowed) null else imagePath.matchEntire(uri.path.orEmpty().trimStart('/'))?.groupValues?.get(1)?.lowercase()
    }.distinct().toList()

    fun attach(activity: AppCompatActivity, parent: LinearLayout, session: CampusSession, content: String) {
        val images = ids(content)
        if (images.isEmpty()) return
        CampusUi.button(activity, parent, if (images.size == 1) "View image" else "View images · ${images.size}") {
            activity.startActivity(Intent(activity, CampusImageActivity::class.java)
                .putStringArrayListExtra("images", ArrayList(images))
                .putExtra("account", session.profile.id)
                .putExtra("generation", session.generation)
                .putExtra("class", session.selectedClass))
        }.apply {
            icon = ContextCompat.getDrawable(activity, R.drawable.ic_campus_image)
            iconTint = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.medkit_ink))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.medkit_cream_2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(-1, CampusUi.dp(activity, 64)).apply {
                topMargin = CampusUi.dp(activity, 8); bottomMargin = CampusUi.dp(activity, 8)
            }
            contentDescription = if (images.size == 1) "Open image full screen" else "Open ${images.size} images full screen"
        }
    }
}
