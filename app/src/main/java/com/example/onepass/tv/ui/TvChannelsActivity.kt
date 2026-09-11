package com.example.onepass.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.onepass.R
import com.example.onepass.tv.data.TvRepository
import com.example.onepass.tv.model.TvCategory
import com.example.onepass.tv.model.TvChannel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 频道选择页（某个分类下的台）。
 *
 * **现在只有一个入口**：播放页点「换台」进来（[TvPlayerActivity]），选完回到正在播的页面。
 * 分类页走的是「点分类直接起播」，不再经过这里 —— 这是用户明确要求「去掉选择频道的环节」
 * 之后的调整。所以这一页从「必经步骤」降级成了「可选的精细选台」。
 *
 * 刻意做得「信息很稀」：两列大卡片，只有台名和分组，没有搜索、没有台标网络图、
 * 没有收藏按钮 —— 这些对零基础老人都是噪音。
 */
class TvChannelsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvChannels"

        /** 上一个页面正在播的台（在列表里用主题色标出） */
        const val EXTRA_CURRENT_ID = "extra_current_id"
        /** 要看哪个分类 */
        const val EXTRA_CATEGORY = "extra_category"

        const val RESULT_CHANNEL_ID = "result_channel_id"
        const val RESULT_CATEGORY = "result_category"
    }

    private lateinit var listView: RecyclerView
    private lateinit var titleText: TextView
    private lateinit var countText: TextView

    private var channels: List<TvChannel> = emptyList()
    private var currentId: String? = null
    private var category: TvCategory = TvCategory.CCTV

    private val uiScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_channels)

        currentId = intent.getStringExtra(EXTRA_CURRENT_ID)
        category = TvCategory.fromId(intent.getStringExtra(EXTRA_CATEGORY))

        titleText = findViewById(R.id.tvChannelsTitle)
        countText = findViewById(R.id.tvChannelCount)
        listView = findViewById(R.id.tvChannelList)
        listView.layoutManager = GridLayoutManager(this, 2)

        titleText.text = category.displayName

        // 缓存是完整清单（几百个台），这里只取当前分类；分类判定见 TvClassifier
        channels = TvRepository.byCategory(this, TvRepository.cached(this), category)
        render(
            getString(
                if (TvRepository.isUsingBuiltinFallback(this)) R.string.tv_channels_source_builtin
                else R.string.tv_channels_source_remote
            )
        )

        findViewById<TextView>(R.id.tvRefresh).setOnClickListener { refresh() }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun render(sourceLabel: String) {
        countText.text = getString(
            R.string.tv_channels_count_range,
            category.displayName,
            channels.size,
            sourceLabel
        )
        listView.adapter = TvChannelAdapter(channels, currentId) { channel -> pick(channel) }
    }

    /** 选中一个台：把 id 和分类一起回传，由上一个页面决定是起播还是接着播 */
    private fun pick(channel: TvChannel) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_CHANNEL_ID, channel.id)
                .putExtra(RESULT_CATEGORY, category.id)
        )
        finish()
    }

    /** 手动刷新：忽略 6 小时 TTL，强制重新拉远程清单 */
    private fun refresh() {
        countText.text = getString(R.string.tv_channels_refreshing)
        uiScope.launch {
            val fresh = runCatching { TvRepository.load(this@TvChannelsActivity, forceRefresh = true) }
                .onFailure { Log.w(TAG, "刷新失败: ${it.javaClass.simpleName}") }
                .getOrNull()

            if (fresh.isNullOrEmpty()) {
                countText.text = getString(R.string.tv_channels_refresh_failed)
                return@launch
            }
            channels = TvRepository.byCategory(this@TvChannelsActivity, fresh, category)
            render(getString(R.string.tv_channels_refresh_done, channels.size))
        }
    }
}
