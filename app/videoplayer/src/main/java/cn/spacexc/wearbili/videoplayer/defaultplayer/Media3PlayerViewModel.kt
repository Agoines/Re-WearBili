package cn.spacexc.wearbili.videoplayer.defaultplayer

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem.fromUri
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import cn.spacexc.bilibilisdk.sdk.video.info.VideoInfo
import cn.spacexc.bilibilisdk.sdk.video.info.remote.subtitle.Subtitle
import cn.spacexc.wearbili.common.domain.log.TAG
import cn.spacexc.wearbili.common.domain.log.logd
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@UnstableApi
/**
 * Created by XC-Qan on 2023/6/28.
 * I'm very cute so please be nice to my code!
 * 给！爷！写！注！释！
 * 给！爷！写！注！释！
 * 给！爷！写！注！释！
 */

class Media3PlayerViewModel(application: Application) : AndroidViewModel(application) {
    var loaded by mutableStateOf(false)
    var loadingMessage by mutableStateOf("")

    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36"
    private var httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private var cacheDataSourceFactory: DataSource.Factory
    lateinit var player: Player

    var videoInfo: cn.spacexc.bilibilisdk.sdk.video.info.remote.info.VideoInfo? by mutableStateOf(
        null
    )

    var videoPlayerAspectRatio by mutableStateOf(16f / 9f)

    val currentPlayProgress = flow {
        while (true) {
            emit(player.currentPosition)
        }
    }
    var videoDuration by mutableStateOf(0L)

    var subtitleList = mutableStateMapOf<String, SubtitleConfig>()

    var currentSubtitleLanguage: String? = subtitleList.keys.lastOrNull()
    var currentSubtitleText = flow {
        //emit("字幕测试")
        var index = 0
        while (true) {
            //emit("index: $index")
            index++
            val nextSubtitle =
                if (currentSubtitleLanguage != null) {
                    //println("currentLanguage: $currentSubtitleLanguage")
                    //println("currentSubtitleContent: ${subtitleList[subtitleList.keys.lastOrNull()]?.currentSubtitle?.content}")
                    subtitleList[currentSubtitleLanguage]?.currentSubtitle?.content
                } else null
            emit(nextSubtitle)
            delay(5)
        }
    }

    init {
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
        httpDataSourceFactory.setUserAgent(userAgent)


        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(ExoPlayerUtils.getInstance(application).getCache())
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheWriteDataSinkFactory(null) // Disable writing.

        player = ExoPlayer.Builder(application)
            .setRenderersFactory(
                DefaultRenderersFactory(application).setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )
            )
            .build()

        player.addListener(object : Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_READY -> {
                        videoPlayerAspectRatio = player.videoSize.width.toFloat()
                            .logd("width")!! / player.videoSize.height.toFloat().logd("height")!!
                        videoDuration = player.duration
                        startContinuouslyUpdatingSubtitle()
                        Log.d(TAG, "onPlaybackStateChanged: startUpdatingSubtitles")
                        loaded = true
                    }

                    Player.STATE_BUFFERING -> {
                        appendLoadMessage("缓冲中...")
                    }

                    Player.STATE_ENDED -> {

                    }

                    Player.STATE_IDLE -> {

                    }
                }
            }
        })
    }

    fun playVideoFromId(
        videoIdType: String,
        videoId: String,
        videoCid: Long,
        webiSignatureKey: String?,
        isLowResolution: Boolean = true
    ) {
        appendLoadMessage("初始化播放器...")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://www.bilibili.com/video/$videoId"
        )
        httpDataSourceFactory.setDefaultRequestProperties(headers)

        viewModelScope.launch {
            getVideoInfo(videoIdType, videoId)
            loadSubtitle()
            appendLoadMessage("加载视频url...")
            if (isLowResolution) {
                val urlResponse =
                    VideoInfo.getLowResolutionVideoPlaybackUrl(videoIdType, videoId, videoCid)
                val urlData = urlResponse.data?.data
                if (urlData == null) {
                    appendLoadMessage(
                        "出错！${urlResponse.code}: ${urlResponse.message}",
                        needLineWrapping = false
                    )
                    return@launch
                }
                val videoUrl = urlData.durl.first { it.url.isNotEmpty() }.url
                appendLoadMessage("成功!", needLineWrapping = false)
                player.setMediaItem(fromUri(videoUrl))
                player.playWhenReady = true
                player.prepare()
            } else {
                val urlResponse =
                    VideoInfo.getVideoPlaybackUrls(videoIdType, videoId, videoCid, webiSignatureKey)
                val urlData = urlResponse.data?.data
                if (urlData == null) {
                    //TODO 处理错误
                    appendLoadMessage(
                        "出错！${urlResponse.code}: ${urlResponse.message}",
                        needLineWrapping = false
                    )
                    return@launch
                }
                val videoUrl =
                    urlData.dash.video.last { it.baseUrl.isNotEmpty() }.baseUrl.logd("videoUrl")!!
                val audioUrl =
                    urlData.dash.audio.last { it.baseUrl.isNotEmpty() }.baseUrl.logd("audioUrl")!!

                val videoSource: MediaSource = ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(fromUri(videoUrl))
                val audioSource: MediaSource = ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(fromUri(audioUrl))

                val mergeSource: MediaSource = MergingMediaSource(videoSource, audioSource)
                appendLoadMessage("成功!", needLineWrapping = false)
                player.setMediaItem(mergeSource.mediaItem)
                player.playWhenReady = true
                player.prepare()
            }
        }
    }

    private suspend fun loadSubtitle() {
        val urls = videoInfo?.data?.subtitle?.list
        urls?.let {
            initSubtitle(urls)
            subtitleList.logd("subtitles")
        }
    }

    private suspend fun initSubtitle(urls: List<cn.spacexc.bilibilisdk.sdk.video.info.remote.info.Subtitle>) {
        appendLoadMessage("加载字幕...")
        val tasks = urls.map { subtitle ->
            viewModelScope.async {
                appendLoadMessage("加载\"${subtitle.lan}\"字幕...")
                Log.d(TAG, "initSubtitle1: $subtitle")
                val response = VideoInfo.getSubtitle(subtitle.subtitle_url)
                response.data?.body?.let { subtitles ->
                    Log.d(TAG, "initSubtitle2: result for $subtitles")
                    val temp = subtitleList
                    temp[subtitle.lan] = SubtitleConfig(
                        subtitleList = subtitles,
                        subtitleLanguageCode = subtitle.lan,
                        subtitleLanguage = subtitle.lan_doc
                    )
                    subtitleList = temp
                    appendLoadMessage("加载\"${subtitle.lan}\"字幕成功!")
                }
            }
        }
        tasks.awaitAll()
        appendLoadMessage("字幕加载完成")
    }

    private suspend fun getVideoInfo(
        videoIdType: String,
        videoId: String
    ) {
        appendLoadMessage("获取视频信息...")
        val response = VideoInfo.getVideoInfoById(videoIdType, videoId).logd("subtitleResponse")!!
        if (response.code != 0 || response.data == null || response.data?.data == null) return
        videoInfo = response.data
        appendLoadMessage("获取成功", needLineWrapping = false)
    }

    private suspend fun updateSubtitle() {
        val tasks = subtitleList.entries.map { entry ->
            viewModelScope.async {
                val config = entry.value
                if (config.currentSubtitle != null) {
                    if (player.currentPosition !in (config.currentSubtitle.from * 1000).toLong()..(config.currentSubtitle.to * 1000).toLong()) {
                        if (config.currentSubtitleIndex + 1 < config.subtitleList.size) {
                            val nextSubtitle = config.subtitleList[config.currentSubtitleIndex + 1]
                            if (player.currentPosition in (nextSubtitle.from * 1000).toLong()..(nextSubtitle.to * 1000).toLong()) {
                                subtitleList[entry.key] = subtitleList[entry.key]!!.copy(
                                    currentSubtitle = nextSubtitle,
                                    currentSubtitleIndex = config.currentSubtitleIndex + 1
                                )
                            } else {
                                val currentSubtitle =
                                    config.subtitleList.indexOfFirst { player.currentPosition in (it.from * 1000).toLong()..(it.to * 1000).toLong() }
                                if (currentSubtitle != -1) {
                                    subtitleList[entry.key] = subtitleList[entry.key]!!.copy(
                                        currentSubtitle = config.subtitleList[currentSubtitle],
                                        currentSubtitleIndex = currentSubtitle
                                    )
                                } else {
                                    subtitleList[entry.key] =
                                        subtitleList[entry.key]!!.copy(currentSubtitle = null)
                                }
                            }
                        } else {
                            subtitleList[entry.key] =
                                subtitleList[entry.key]!!.copy(currentSubtitle = null)
                        }
                    }
                } else {
                    val currentSubtitle =
                        config.subtitleList.indexOfFirst { player.currentPosition in (it.from * 1000).toLong()..(it.to * 1000).toLong() }
                    if (currentSubtitle != -1) {
                        subtitleList[entry.key] = subtitleList[entry.key]!!.copy(
                            currentSubtitle = config.subtitleList[currentSubtitle],
                            currentSubtitleIndex = currentSubtitle
                        )
                    } else {
                        subtitleList[entry.key] =
                            subtitleList[entry.key]!!.copy(currentSubtitle = null)
                    }
                }
            }
        }
        tasks.awaitAll()
        //println("subtitleUpdated: ${subtitleList.map { "${it.key}: ${it.value.currentSubtitle}" }}")
    }

    private fun startContinuouslyUpdatingSubtitle() {
        Log.d(TAG, "startContinuouslyUpdatingSubtitle")
        currentSubtitleLanguage = subtitleList.keys.firstOrNull()
        viewModelScope.launch {
            while (player.currentPosition >= 0) {
                updateSubtitle()
                delay(5)
            }
        }

    }

    private fun appendLoadMessage(message: String, needLineWrapping: Boolean = true) {
        loadingMessage += if (needLineWrapping) "\n$message" else message
    }

}

data class SubtitleConfig(
    val currentSubtitleIndex: Int = 0,
    val currentSubtitle: Subtitle? = null,
    val subtitleList: List<Subtitle>,
    val subtitleLanguageCode: String,
    val subtitleLanguage: String
)