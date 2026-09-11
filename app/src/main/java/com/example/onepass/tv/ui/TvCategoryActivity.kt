package com.example.onepass.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.onepass.R
import com.example.onepass.tv.data.TvRepository
import com.example.onepass.tv.model.TvCategory
import com.example.onepass.tv.model.TvChannel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 看电视 · 分类页。
 *
 * 交互链路（用户要求「点进看电视瓦片先选节目」→ 后续又要求「去掉选择频道的环节，
 * 点击节目直接播放」，所以现在是**两步到播放**）：
 *     桌面「看电视」瓦片
 *          → 本页（央视频道 / 现代节目 / 古装节目 / 戏曲节目）
 *          → **直接起播**（本类「上次看的台」，没有就用本类第一个台）
 *
 * 也就是说：点分类不再弹频道列表了。要精细选台，进播放页后点底部的「换台」
 * （[TvChannelsActivity]）——那是个**可选**的进阶动作，不是必经步骤。
 *
 * 为什么分类不是频道分组：公开源的 group-title 是运营方随便填的（「地方频道」248 个、
 * 「电影频道」78 个），照搬对老人毫无意义。这里的四类是按「今天想看什么」重新归的，
 * 判定规则见 [com.example.onepass.tv.data.TvClassifier]。
 */
class TvCategoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvCategory"
    }

    private lateinit var cards: Map<TvCategory, View>
    private lateinit var counts: Map<TvCategory, TextView>

    private val uiScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_category)
        bindViews()

        // 先拿缓存算数量，保证秒开；远程清单在后台刷新后再校正一次
        applyCounts(TvRepository.cached(this))

        uiScope.launch {
            val fresh = runCatching { TvRepository.load(this@TvCategoryActivity) }
                .onFailure { Log.w(TAG, "刷新频道失败: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return@launch
            applyCounts(fresh)
        }
    }

    /**
     * 回到本页就重新读一次缓存。
     *
     * 为什么必须在这刷新（踩过坑）：老人从播放页点「换台」进去、按了「重新获取」，
     * 那边把新清单一拉、缓存也写了 —— 但**本页的台数只在 onCreate 算过一次**，
     * 返回后数字还是旧的，看起来就像「刷新没生效」。onResume 重读即可对齐。
     */
    override fun onResume() {
        super.onResume()
        runCatching { applyCounts(TvRepository.cached(this)) }
            .onFailure { Log.w(TAG, "重读缓存失败: ${it.javaClass.simpleName}") }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        cards = mapOf(
            TvCategory.CCTV to findViewById(R.id.tvCatCctv),
            TvCategory.MODERN to findViewById(R.id.tvCatModern),
            TvCategory.COSTUME to findViewById(R.id.tvCatCostume),
            TvCategory.OPERA to findViewById(R.id.tvCatOpera)
        )
        counts = mapOf(
            TvCategory.CCTV to findViewById(R.id.tvCatCctvCount),
            TvCategory.MODERN to findViewById(R.id.tvCatModernCount),
            TvCategory.COSTUME to findViewById(R.id.tvCatCostumeCount),
            TvCategory.OPERA to findViewById(R.id.tvCatOperaCount)
        )

        TvCategory.ordered.forEach { category ->
            cards[category]?.setOnClickListener {
                // 点分类 = 直接开播。不传 EXTRA_CHANNEL_ID，让播放页自己挑：
                // 优先「这一类里上次看的台」，没有就本类第一个 —— 逻辑在
                // TvPlayerActivity.resolveStartIndex()，这里不重复实现。
                startActivity(
                    Intent(this, TvPlayerActivity::class.java)
                        .putExtra(TvPlayerActivity.EXTRA_CATEGORY, category.id)
                )
            }
        }
    }

    /**
     * 把每个分类的台数写到卡片上；**数量为 0 的分类整张隐藏**，
     * 宁可少一排也别让老人点进空页面。
     */
    private fun applyCounts(channels: List<TvChannel>) {
        val countMap = TvRepository.counts(channels)
        TvCategory.ordered.forEach { category ->
            val n = countMap[category] ?: 0
            counts[category]?.text = getString(R.string.tv_category_count, n)
            cards[category]?.visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }
}
