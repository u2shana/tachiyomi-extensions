package eu.kanade.tachiyomi.extension.zh.picacomic

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.auth0.android.jwt.JWT
import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import kotlin.collections.ArrayList

@Nsfw
class Picacomic : HttpSource(), ConfigurableSource {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "哔咔漫画"
    override val baseUrl = "https://picaapi.picacomic.com"

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val basicHeaders = mapOf(
        "api-key" to "C69BAF41DA5ABD1FFEDC6D2FEA56B",
        "app-channel" to preferences.getString("APP_CHANNEL", "2")!!,
        "app-version" to "2.2.1.3.3.4",
        "app-uuid" to "defaultUuid",
        "app-platform" to "android",
        "app-build-version" to "44",
        "User-Agent" to "okhttp/3.8.1",
        "accept" to "application/vnd.picacomic.com.v1+json",
        "image-quality" to preferences.getString("IMAGE_QUALITY", "high")!!,
        "Content-Type" to "application/json; charset=UTF-8", // must be exactly matched!
    )

    private fun encrpt(url: String, time: Long, method: String, nonce: String): String {
        val hmacSha256Key = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
        val apiKey = basicHeaders["api-key"]
        val path = url.substringAfter("$baseUrl/")
        val raw = "$path$time$nonce${method}$apiKey".toLowerCase(Locale.ROOT)
        return hmacSHA256(hmacSha256Key, raw).convertToString()
    }

    private val token: String by lazy {
        var t: String = preferences.getString("TOKEN", "")!!
        if (t.isEmpty() || JWT(t).isExpired(10)) {
            val username = preferences.getString("USERNAME", "")!!
            val password = preferences.getString("PASSWORD", "")!!
            if (username.isEmpty() || password.isEmpty()) {
                throw Exception("请在扩展设置界面输入用户名和密码")
            }

            t = getToken(username, password)
            preferences.edit().putString("TOKEN", t).apply()
        }
        t
    }

    private fun picaHeaders(url: String, method: String = "GET"): Headers {
        val time = Instant.now().epochSecond
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val nonce = (1..32).map { allowedChars.random() }
            .joinToString("")
        val signature = encrpt(url, time, method, nonce)
        return basicHeaders.toMutableMap().apply {
            put("time", time.toString())
            put("nonce", nonce)
            put("signature", signature)
            if (!url.endsWith("/auth/sign-in")) // avoid recursive call
                put("authorization", token)
        }.toHeaders()
    }

    private fun getToken(username: String, password: String): String {
        val url = "$baseUrl/auth/sign-in"
        val body = JSONObject(
            mapOf(
                "email" to username,
                "password" to password,
            )
        ).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val response = client.newCall(
            POST(url, picaHeaders(url, "POST"), body)
        ).execute()
        if (response.isSuccessful) {
            return JSONObject(response.body!!.string())
                .getJSONObject("data")
                .getString("token")
        } else {
            throw Exception("登录失败")
        }
    }

    private val blocklist = preferences.getString("BLOCK_GENRES", "")!!
        .split(',').map { it.trim() }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/comics?page=$page&s=dd"
        return GET(url, picaHeaders(url))
    }

    // for /comics/random, /comics/leaderboard
    private fun singlePageParse(response: Response): MangasPage {
        val body = response.body!!.string()
        val comics = JSONObject(body)
            .getJSONObject("data")
            .getJSONArray("comics")
            .filterJSONObject { !hitBlocklist(it) }

        val mangas = ArrayList<SManga>()
        for (i in 0 until comics.length()) {
            val comic = comics.getJSONObject(i)
            val manga = SManga.create().apply {
                title = comic.getString("title")
                thumbnail_url = comic.getJSONObject("thumb").let {
                    it.getString("fileServer") + "/static/" +
                        it.getString("path")
                }
                url = "$baseUrl/comics/${comic.getString("_id")}"
            }
            mangas.add(manga)
        }
        return MangasPage(mangas, response.request.url.toString().contains("/comics/random"))
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/comics/random"
        return GET(url, picaHeaders(url))
    }

    override fun latestUpdatesParse(response: Response): MangasPage = singlePageParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort: String? = null
        var category: String? = null

        // parse filters
        for (filter in filters) {
            when (filter) {
                is SortFilter -> sort = filter.toUriPart()
                is CategoryFilter -> category = filter.toUriPart()
            }
        }

        // return comics from some category or just sort
        if (query.isEmpty()) {
            var url = "$baseUrl/comics?page=$page&s=$sort"
            if (!category.isNullOrEmpty())
                url += "&c=${URLEncoder.encode(category, "utf-8")}"

            return GET(url, picaHeaders(url))
        }

        // return comics from some search
        // filters may be empty
        val opts = mapOf(
            "keyword" to query,
            "categories" to JSONArray(), // TODO
            "sort" to (sort ?: "dd"),
        )

        val url = "$baseUrl/comics/advanced-search?page=$page"

        val jsonType = "application/json; charset=UTF-8".toMediaTypeOrNull()
        val body = JSONObject(opts as Map<*, *>).toString().toRequestBody(jsonType)

        return POST(url, picaHeaders(url, "POST"), body)
    }

    private fun hitBlocklist(comic: JSONObject): Boolean {
        val genres = ArrayList<String>()
        if (comic.has("categories"))
            comic.getJSONArray("categories")
                .let {
                    (0 until it.length()).map { i -> it.optString(i).trim() }
                }
                .let { genres.addAll(it) }
        if (comic.has("tags"))
            comic.getJSONArray("tags")
                .let {
                    (0 until it.length()).map { i -> it.optString(i).trim() }
                }
                .let { genres.addAll(it) }

        return genres.any { it in blocklist }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body!!.string()
        val hasNextPage: Boolean
        val comics = JSONObject(body)
            .getJSONObject("data")
            .getJSONObject("comics")
            .also {
                hasNextPage = it.getInt("page") < it.getInt("pages")
            }
            .getJSONArray("docs")
            .filterJSONObject { !hitBlocklist(it) }

        val mangas = ArrayList<SManga>()
        for (i in 0 until comics.length()) {
            val comic = comics.getJSONObject(i)
            SManga.create().apply {
                title = comic.getString("title")
                thumbnail_url = comic.getJSONObject("thumb").let {
                    it.getString("fileServer") + "/static/" +
                        it.getString("path")
                }
                url = "$baseUrl/comics/${comic.getString("_id")}"
            }.let(mangas::add)
        }
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, picaHeaders(manga.url))

    override fun mangaDetailsParse(response: Response): SManga {
        val responseBody = response.body!!.string()
        val obj = JSONObject(responseBody)
        val comic = obj.getJSONObject("data")
            .getJSONObject("comic")

        val categories = comic.getJSONArray("categories").let {
            (0 until it.length()).map { i -> it.optString(i).trim() }
        }
        val tags = comic.getJSONArray("tags").let {
            (0 until it.length()).map { i -> it.optString(i).trim() }
        }

        return SManga.create().apply {
            title = comic.getString("title")

            if (comic.has("author"))
                author = comic.getString("author")
            if (comic.has("description"))
                description = comic.getString("description")
            if (comic.has("chineseTeam"))
                artist = comic.getString("chineseTeam")

            genre = (tags + categories).distinct().joinToString(", ")
            status = if (comic.getBoolean("finished"))
                SManga.COMPLETED
            else SManga.ONGOING
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "${manga.url}/eps?page=1"
        return GET(url, picaHeaders(url))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val comicId = response.request.url.pathSegments[1]
        var hasNextPage: Boolean
        var currentPage: Int
        val chapters = JSONObject(response.body!!.string())
            .getJSONObject("data")
            .getJSONObject("eps")
            .also {
                currentPage = it.getInt("page")
                hasNextPage = it.getInt("page") < it.getInt("pages")
            }
            .getJSONArray("docs")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())

        val ret = ArrayList<SChapter>()
        for (i in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(i)
            val chapterOrder = chapter.getString("order")
            SChapter.create().apply {
                name = chapter.getString("title")
                url = "$baseUrl/comics/$comicId/order/$chapterOrder"
                date_upload = sdf.parse(chapter.getString("updated_at"))!!.time
            }.let(ret::add)
        }

        if (hasNextPage) {
            val nextUrl = response.request.url.newBuilder()
                .setQueryParameter(
                    "page", (currentPage + 1).toString()
                ).build().toString()

            val nextResponse = client.newCall(GET(nextUrl, picaHeaders(nextUrl))).execute()
            ret.addAll(chapterListParse(nextResponse))
        }

        return ret
    }

    override fun pageListRequest(chapter: SChapter) = GET(
        chapter.url + "/pages?page=1",
        picaHeaders(chapter.url + "/pages?page=1")
    )

    override fun pageListParse(response: Response): List<Page> {
        val ret = ArrayList<Page>()
        val responseBody = response.body!!.string()
        val obj = JSONObject(responseBody)
        val pages = obj.getJSONObject("data")
            .getJSONObject("pages")

        val pageList = pages.getJSONArray("docs")

        for (i in 0 until pageList.length()) {
            val item = pageList.getJSONObject(i)
            val url = item.getJSONObject("media").let {
                it.getString("fileServer") + "/static/" +
                    it.getString("path")
            }
            ret.add(Page(i, "", url))
        }

        if (pages.getInt("page") < pages.getInt("pages")) {
            val nextUrl = response.request.url.newBuilder()
                .setQueryParameter(
                    "page", (pages.getInt("page") + 1).toString()
                ).build().toString()

            val nextResponse = client.newCall(GET(nextUrl, picaHeaders(nextUrl))).execute()
            ret.addAll(pageListParse(nextResponse))
        }
        return ret
    }

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        CategoryFilter(),
    )

    private class SortFilter : UriPartFilter(
        "排序",
        arrayOf(
            Pair("新到旧", "dd"),
            Pair("旧到新", "da"),
            Pair("最多爱心", "ld"),
            Pair("最多绅士指名", "vd"),
        )
    )

    private class CategoryFilter : UriPartFilter(
        "类型",
        arrayOf(Pair("全部", "")) +
            arrayOf(
                "大家都在看", "牛牛不哭", "那年今天", "官方都在看",
                "嗶咔漢化", "全彩", "長篇", "同人", "短篇", "圓神領域",
                "碧藍幻想", "CG雜圖", "純愛", "百合花園", "後宮閃光", "單行本", "姐姐系",
                "妹妹系", "SM", "人妻", "NTR", "強暴",
                "艦隊收藏", "Love Live", "SAO 刀劍神域", "Fate",
                "東方", "禁書目錄", "Cosplay",
                "英語 ENG", "生肉", "性轉換", "足の恋", "非人類",
                "耽美花園", "偽娘哲學", "扶他樂園", "重口地帶", "歐美", "WEBTOON",
            ).map { Pair(it, it) }.toTypedArray()
    )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        open fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "USERNAME"
            title = "用户名"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("USERNAME", newValue as String).commit()
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "PASSWORD"
            title = "密码"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("PASSWORD", newValue as String).commit()
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "BLOCK_GENRES"
            title = "屏蔽词列表"
            dialogTitle = "屏蔽词列表"
            dialogMessage = "根据关键词过滤漫画，关键词之间用','分离。" +
                "关键词分为分类和标签两种，在热门和最新中只能按分类过滤（即在filter的类型中出现的词），" +
                "而在搜索中两者都可以"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("BLOCK_GENRES", newValue as String).commit()
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "IMAGE_QUALITY"
            title = "图片质量"
            entries = arrayOf("原图", "低", "中", "高")
            entryValues = arrayOf("original", "low", "medium", "high")
            setDefaultValue("高")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "APP_CHANNEL"
            title = "分流"
            entries = arrayOf("1", "2", "3")
            entryValues = entries
            setDefaultValue("1")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.let(screen::addPreference)
    }
}

private fun JSONArray.filterJSONObject(predict: (JSONObject) -> Boolean): JSONArray {
    val ret = JSONArray()
    for (i in 0 until this.length()) {
        if (predict(this.optJSONObject(i))) {
            ret.put(this.optJSONObject(i))
        }
    }
    return ret
}
