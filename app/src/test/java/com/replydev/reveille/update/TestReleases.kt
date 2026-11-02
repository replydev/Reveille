package com.replydev.reveille.update

import java.time.Instant

object TestReleases {
    fun release(tag: String, notes: String = "## Features\n\n- feat: something new\n"): Release {
        val version = ReleaseVersion.parseTag(tag)!!
        val base = "https://github.com/replydev/Reveille/releases/download/$tag/reveille-$tag.apk"
        return Release(
            tag = tag,
            versionCode = version.versionCode,
            publishedAt = Instant.parse("2026-11-02T22:41:36Z"),
            notes = notes,
            apkUrl = base,
            apkSize = 30_370_722,
            sha256Url = "$base.sha256",
            htmlUrl = "https://github.com/replydev/Reveille/releases/tag/$tag",
        )
    }

    /** A trimmed `GET /releases/latest` response, as GitHub returns it. */
    fun json(tag: String = "v0.3.0", body: String = "## Features\\n\\n- feat: something new\\n", extraAssets: String = "") = """
        {
          "html_url": "https://github.com/replydev/Reveille/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "Reveille $tag",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-11-02T22:41:36Z",
          "assets": [
            $extraAssets
            {
              "name": "reveille-$tag.apk",
              "size": 30370722,
              "browser_download_url": "https://github.com/replydev/Reveille/releases/download/$tag/reveille-$tag.apk"
            },
            {
              "name": "reveille-$tag.apk.sha256",
              "size": 86,
              "browser_download_url": "https://github.com/replydev/Reveille/releases/download/$tag/reveille-$tag.apk.sha256"
            }
          ],
          "body": "$body"
        }
    """.trimIndent()
}
