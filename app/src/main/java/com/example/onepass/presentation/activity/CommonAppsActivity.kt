package com.example.onepass.presentation.activity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.onepass.R
import com.example.onepass.core.config.GlobalScaleManager

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    var selected: Boolean,
    var order: Int = 0,
    /** true = 内置桌面功能瓦片（微信点读/手电筒/远程协助），开关控制显示/隐藏而非选中 */
    val isTile: Boolean = false
)

class CommonAppsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CommonAppsActivity"
        private const val PREFS = "common_apps_prefs"
        private const val KEY_COMMON_APPS = "common_apps"
        private const val KEY_APP_ORDERS = "app_orders"
        private const val MAX_COMMON_APPS = 8
    }
    
    private lateinit var searchEdit: android.widget.EditText
    private lateinit var searchEmoji: android.widget.TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val apps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "CommonAppsActivity onCreate 开始")
        
        setContentView(R.layout.activity_common_apps)
        
        Log.d(TAG, "布局设置成功，开始初始化视图")
        
        val packageManager = packageManager
        searchEdit = findViewById(R.id.editSearch)
        searchEmoji = findViewById(R.id.searchEmoji)
        recyclerView = findViewById(R.id.recyclerViewApps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // 为搜索框添加点击事件，确保点击任何位置都能激活输入
        searchEdit.setOnClickListener {
            searchEdit.requestFocus()
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            inputMethodManager.showSoftInput(searchEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        
        // 为emoji标签添加点击事件
        searchEmoji.setOnClickListener {
            searchEdit.requestFocus()
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            inputMethodManager.showSoftInput(searchEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        
        loadApps(packageManager)
        adapter = AppAdapter(apps)
        recyclerView.adapter = adapter
        
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = (s ?: "").trim().toString().lowercase()
                adapter.filter(q)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        val btnDone = findViewById<Button>(R.id.btnDone)
        // 根据缩放比例调整按钮字体大小
        val originalButtonTextSize = 28f // 原始大小18sp，缩小50%
        val scaledButtonTextSize = GlobalScaleManager.getScaledValue(this, originalButtonTextSize)
        btnDone.textSize = scaledButtonTextSize
        
        btnDone.setOnClickListener {
            Log.d(TAG, "完成按钮被点击")
            
            // 保存选中的应用（内置功能瓦片不写入应用列表，但参与排序）
            val selectedApps = apps.filter { it.selected }
            val selectedPackageNames = selectedApps
                .filter { !it.isTile }
                .map { it.packageName }
                .toSet()
            
            // 为选中的应用和内置瓦片统一分配排序值（列表顺序即主界面顺序）
            val appOrders = mutableMapOf<String, Int>()
            selectedApps.forEachIndexed { index, app ->
                appOrders[app.packageName] = index
            }
            
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_COMMON_APPS, selectedPackageNames)
                .putString(KEY_APP_ORDERS, formatAppOrders(appOrders))
                .apply()
            
            Log.d(TAG, "保存了 ${selectedApps.size} 个常用应用（含 ${selectedApps.count { it.isTile }} 个内置功能）")
            Toast.makeText(this, "常用应用已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        Log.d(TAG, "CommonAppsActivity onCreate 完成")
    }

    private fun loadApps(packageManager: PackageManager) {
        Log.d(TAG, "loadApps 开始加载应用列表")
        
        apps.clear()
        
        // 从 SharedPreferences 加载已保存的应用列表
        val savedApps = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COMMON_APPS, HashSet<String>()) ?: HashSet()
        
        // 从 SharedPreferences 加载应用排序信息
        val savedOrders = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APP_ORDERS, null)
        val appOrders = if (savedOrders != null) {
            try {
                parseAppOrders(savedOrders)
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        
        try {
            // 创建一个意图，目标是所有"入口"Activity
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            
            // 使用 queryIntentActivities 获取应用列表
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            Log.d(TAG, "扫描到应用总数: ${resolveInfos.size}")
            
            if (resolveInfos.isEmpty()) {
                Log.e(TAG, "应用列表为空！")
                Toast.makeText(this, "无法获取应用列表", Toast.LENGTH_LONG).show()
                return
            }
            
            var currentOrder = 0
            val processedKeys = mutableSetOf<String>()

            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo.packageName
                val activityName = resolveInfo.activityInfo.name

                // 按「包名+Activity」去重，避免同一应用的多个入口被折叠
                val key = "$packageName/$activityName"
                if (processedKeys.contains(key)) {
                    continue
                }
                processedKeys.add(key)

                // 排除本应用的桌面主界面，但保留手电筒等快捷入口
                if (packageName == this.packageName && activityName.endsWith(".MainActivity")) {
                    Log.d(TAG, "跳过本应用桌面主界面: $activityName")
                    continue
                }
                
                Log.d(TAG, "处理应用: $packageName")
                
                try {
                    val appName = resolveInfo.loadLabel(packageManager).toString()
                    val icon = resolveInfo.loadIcon(packageManager)
                    
                    // 检查是否已在保存的列表中
                    val isSelected = savedApps.contains(packageName)
                    // 获取应用的排序值
                    val order = appOrders[packageName] ?: currentOrder++
                    val appInfo = AppInfo(appName, packageName, icon, isSelected, order)
                    apps.add(appInfo)
                    
                    Log.d(TAG, "  -> 添加到列表: $appName, 选中状态: $isSelected, 排序: $order")
                } catch (e: Exception) {
                    Log.e(TAG, "  -> 处理应用失败: ${e.message}", e)
                    continue
                }
            }
            
            Log.d(TAG, "应用扫描完成，添加: ${apps.size}")
            
            if (apps.isEmpty()) {
                Log.e(TAG, "没有添加任何应用！")
                Toast.makeText(this, "没有找到可用的应用", Toast.LENGTH_LONG).show()
            }

            // 内置桌面功能瓦片：作为可排序条目加入列表（始终在桌面列表内，开关控制显示/隐藏）
            val tileRes = mapOf(
                MainActivity.TILE_ID_WECHAT_READ to R.drawable.ic_wechat_read,
                MainActivity.TILE_ID_TORCH to R.drawable.ic_flashlight,
                MainActivity.TILE_ID_REMOTE_ASSIST to R.drawable.ic_remote_assist,
                MainActivity.TILE_ID_TV to R.drawable.ic_tv
            )
            var tileBaseOrder = 10000 // 默认排在所有应用之后；保存后可被调整
            tileRes.forEach { (id, resId) ->
                val icon = runCatching { ContextCompat.getDrawable(this, resId) }.getOrNull()
                if (icon != null) {
                    apps.add(
                        AppInfo(
                            label = tileLabel(id),
                            packageName = id,
                            icon = icon,
                            selected = true,
                            order = appOrders[id] ?: tileBaseOrder++,
                            isTile = true
                        )
                    )
                }
            }
            Log.d(TAG, "内置功能瓦片已加入列表")
            
            // 先按选中状态排序，再按排序值排序，最后按名称排序
            sortApps()
            Log.d(TAG, "应用列表已排序")
        } catch (e: SecurityException) {
            Log.e(TAG, "权限不足，无法获取应用列表", e)
            Toast.makeText(this, "权限不足，无法获取应用列表", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "加载应用列表时出错: ${e.message}", e)
            Toast.makeText(this, "加载应用列表时出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseAppOrders(ordersString: String): Map<String, Int> {
        val orders = mutableMapOf<String, Int>()
        val pairs = ordersString.split(",")
        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                try {
                    orders[parts[0]] = parts[1].toInt()
                } catch (e: Exception) {
                    // 忽略解析错误
                }
            }
        }
        return orders
    }

    /**
     * 列表排序：已选在前（按 order），未选在后（按名称）
     */
    private fun sortApps() {
        apps.sortWith(Comparator { app1, app2 ->
            if (app1.selected && !app2.selected) {
                -1
            } else if (!app1.selected && app2.selected) {
                1
            } else if (app1.order != app2.order) {
                app1.order.compareTo(app2.order)
            } else {
                app1.label.compareTo(app2.label, ignoreCase = true)
            }
        })
    }

    /**
     * 已选应用上移/下移：与相邻的已选应用交换排序值，从而调整主界面顺序。
     * @param direction -1 上移，1 下移
     */
    private fun moveApp(app: AppInfo, direction: Int) {
        val selected = apps.filter { it.selected }
        val index = selected.indexOf(app)
        if (index < 0) return
        val targetIndex = index + direction
        if (targetIndex < 0 || targetIndex >= selected.size) {
            Toast.makeText(
                this,
                if (direction < 0) "「${app.label}」已经是最前面了" else "「${app.label}」已经是最后面了",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val target = selected[targetIndex]
        val tmp = app.order
        app.order = target.order
        target.order = tmp
        sortApps()
        adapter.refresh()
        Log.d(TAG, "应用排序: ${app.label} ${if (direction < 0) "上移" else "下移"}，与 ${target.label} 交换")
    }

    private fun formatAppOrders(orders: Map<String, Int>): String {
        val pairs = mutableListOf<String>()
        for ((packageName, order) in orders) {
            pairs.add("$packageName:$order")
        }
        return pairs.joinToString(",")
    }

    /** 内置瓦片显示名称 */
    private fun tileLabel(id: String): String = when (id) {
        MainActivity.TILE_ID_WECHAT_READ -> "微信点读"
        MainActivity.TILE_ID_TORCH -> "手电筒"
        MainActivity.TILE_ID_REMOTE_ASSIST -> "远程协助"
        MainActivity.TILE_ID_TV -> "看电视"
        else -> id
    }

    /** 内置瓦片可见性 pref key（null = 非瓦片） */
    private fun tileVisibilityKey(id: String): String? = when (id) {
        MainActivity.TILE_ID_WECHAT_READ -> MainActivity.KEY_TILE_WECHAT_READ
        MainActivity.TILE_ID_TORCH -> MainActivity.KEY_TILE_TORCH
        MainActivity.TILE_ID_REMOTE_ASSIST -> MainActivity.KEY_TILE_REMOTE_ASSIST
        MainActivity.TILE_ID_TV -> MainActivity.KEY_TILE_TV
        else -> null
    }

    inner class AppAdapter(private val items: List<AppInfo>) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        private var displayItems = items.toList()
        private var currentQuery = ""

        fun filter(query: String) {
            currentQuery = query
            refresh()
        }

        /**
         * 按当前搜索词刷新显示列表：已选（含内置瓦片）始终显示，未选应用按名称过滤。
         * 勾选/移动后调用，保证排序按钮立即可见可用，无需退出重进。
         */
        fun refresh() {
            displayItems = if (currentQuery.isEmpty()) {
                items
            } else {
                items.filter { it.selected || it.label.lowercase().contains(currentQuery) }
            }
            notifyDataSetChanged()
            Log.d(TAG, "刷新显示列表, 查询='$currentQuery', 结果数=${displayItems.size}")
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }
        override fun getItemCount(): Int = displayItems.size
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = displayItems[position]
            holder.bind(app)
        }
        inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val icon: android.widget.ImageView = itemView.findViewById(R.id.appIcon)
            private val label: android.widget.TextView = itemView.findViewById(R.id.appLabel)
            private val check: android.widget.Switch = itemView.findViewById(R.id.appSelected)
            private val moveButtons: android.view.View = itemView.findViewById(R.id.moveButtons)
            private val btnMoveUp: android.widget.Button = itemView.findViewById(R.id.btnMoveUp)
            private val btnMoveDown: android.widget.Button = itemView.findViewById(R.id.btnMoveDown)
            fun bind(app: AppInfo) {
                // 关键步骤1: 必须先移除之前的监听器！
                check.setOnCheckedChangeListener(null)

                val tileKey = tileVisibilityKey(app.packageName)

                // 关键步骤2: 从数据模型中读取状态并设置给UI
                icon.setImageDrawable(app.icon)
                label.text = app.label
                if (app.isTile) {
                    // 内置瓦片：开关 = 主界面显示/隐藏
                    check.isChecked = getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                        .getBoolean(tileKey ?: return, true)
                } else {
                    check.isChecked = app.selected
                }

                // 根据缩放比例调整图标大小
                val originalIconSize = 150 // 原始大小64dp * 2
                val scaledIconSize = GlobalScaleManager.getScaledValue(itemView.context, originalIconSize)
                val iconParams = icon.layoutParams
                iconParams.width = scaledIconSize
                iconParams.height = scaledIconSize
                icon.layoutParams = iconParams

                // 根据缩放比例调整字体大小
                val originalTextSize = 28f // 原始大小18sp * 2
                val scaledTextSize = GlobalScaleManager.getScaledValue(itemView.context, originalTextSize)
                label.textSize = scaledTextSize

                // 排序按钮：仅选中项（含内置瓦片）显示；点击调整主界面顺序
                val buttonTextSize = GlobalScaleManager.getScaledValue(itemView.context, 22f)
                btnMoveUp.textSize = buttonTextSize
                btnMoveDown.textSize = buttonTextSize
                moveButtons.visibility = if (app.selected) android.view.View.VISIBLE else android.view.View.GONE
                btnMoveUp.setOnClickListener { moveApp(app, -1) }
                btnMoveDown.setOnClickListener { moveApp(app, 1) }

                // 设置已启用应用的背景色
                if (app.selected) {
                    itemView.setBackgroundColor(itemView.resources.getColor(R.color.app_selected_bg, null))
                } else {
                    itemView.setBackgroundColor(itemView.resources.getColor(android.R.color.transparent, null))
                }

                itemView.setOnClickListener {
                    if (app.isTile) {
                        // 点击整行 = 切换显示/隐藏
                        check.isChecked = !check.isChecked
                        return@setOnClickListener
                    }
                    if (app.selected) {
                        // 取消选择
                        app.selected = false
                        check.isChecked = false
                        itemView.setBackgroundColor(itemView.resources.getColor(android.R.color.transparent, null))
                        Log.d(TAG, "应用点击: ${app.label}, 选中状态: false")
                    } else {
                        // 检查是否超过最大选择数量（内置瓦片不计入上限）
                        val selectedCount = apps.count { it.selected && !it.isTile }
                        if (selectedCount >= MAX_COMMON_APPS) {
                            Toast.makeText(itemView.context, "最多只能选择${MAX_COMMON_APPS}个应用", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "应用点击: ${app.label}, 已达到最大选择数量")
                            return@setOnClickListener
                        }
                        // 选择应用
                        app.selected = true
                        check.isChecked = true
                        itemView.setBackgroundColor(itemView.resources.getColor(R.color.app_selected_bg, null))
                        Log.d(TAG, "应用点击: ${app.label}, 选中状态: true")
                    }
                    // 重新排序并刷新：勾选后立即进入已选区域、排序按钮立即可用
                    itemView.post {
                        sortApps()
                        refresh()
                    }
                }

                // 关键步骤3: 重新设置监听器，同步用户操作到数据模型
                check.setOnCheckedChangeListener { _, isChecked ->
                    if (app.isTile) {
                        // 内置瓦片：只写可见性，不改变选中状态
                        getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(tileKey ?: return@setOnCheckedChangeListener, isChecked)
                            .apply()
                        Log.d(TAG, "内置功能开关: ${app.label}, 显示: $isChecked")
                        return@setOnCheckedChangeListener
                    }
                    if (isChecked) {
                        // 检查是否超过最大选择数量（内置瓦片不计入上限）
                        val selectedCount = apps.count { it.selected && !it.isTile }
                        if (selectedCount >= MAX_COMMON_APPS) {
                            check.isChecked = false
                            Toast.makeText(itemView.context, "最多只能选择${MAX_COMMON_APPS}个应用", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "应用切换: ${app.label}, 已达到最大选择数量")
                            return@setOnCheckedChangeListener
                        }
                        itemView.setBackgroundColor(itemView.resources.getColor(R.color.app_selected_bg, null))
                    } else {
                        itemView.setBackgroundColor(itemView.resources.getColor(android.R.color.transparent, null))
                    }
                    app.selected = isChecked
                    Log.d(TAG, "应用切换: ${app.label}, 选中状态: $isChecked")
                    // 重新排序并刷新：勾选后立即进入已选区域、排序按钮立即可用
                    itemView.post {
                        sortApps()
                        refresh()
                    }
                }
            }
        }
    }
}
