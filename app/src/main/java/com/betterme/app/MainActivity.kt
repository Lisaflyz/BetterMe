package com.betterme.app

import android.graphics.PorterDuff
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.betterme.app.databinding.ActivityMainBinding
import com.betterme.app.ui.AddEntryDialogFragment
import com.betterme.app.ui.DailyFragment
import com.betterme.app.ui.GrowthFragment
import com.betterme.app.ui.TodoFragment
import com.betterme.app.diary.DiaryEntryKind

/**
 * Better Me 主界面。
 *
 * 底部导航：每日 | 成长 | 待办。
 * 点击右下角悬浮 + 弹出添加记录弹层。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAB_DAILY = 0
        private const val TAB_GROWTH = 1
        private const val TAB_TODO = 2
        private const val TAG_ADD_ENTRY = "add_entry"
    }

    private lateinit var binding: ActivityMainBinding

    private var selectedTab = TAB_DAILY
    private var pendingTab: Int? = null
    private var tabTransactionPending = false
    private var pendingDiaryRefresh = false
    private var pendingSavedKind = DiaryEntryKind.DAILY
    private var pendingSavedTopic: String? = null
    private var diaryOpen = false
    private val diaryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        diaryOpen = false
        if (result.resultCode == RESULT_OK) {
            pendingDiaryRefresh = true
            pendingSavedKind = DiaryEntryKind.fromName(result.data?.getStringExtra(DiaryActivity.EXTRA_ENTRY_KIND))
            pendingSavedTopic = result.data?.getStringExtra(DiaryActivity.EXTRA_SAVED_TOPIC)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showSavedDiary()
        }
    }

    fun openDiary(entryId: String? = null, kind: DiaryEntryKind = DiaryEntryKind.DAILY) {
        if (diaryOpen) return
        diaryOpen = true
        diaryLauncher.launch(Intent(this, DiaryActivity::class.java).apply {
            putExtra(DiaryActivity.EXTRA_ENTRY_KIND, kind.name)
            entryId?.let { putExtra(DiaryActivity.EXTRA_ENTRY_ID, it) }
        })
    }

    override fun onPostResume() {
        super.onPostResume()
        applyPendingTab()
        showSavedDiary()
    }

    private fun showSavedDiary() {
        val manager = supportFragmentManager
        if (!pendingDiaryRefresh || tabTransactionPending || manager.isStateSaved ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val tab = when (pendingSavedKind) {
            DiaryEntryKind.DAILY -> TAB_DAILY
            DiaryEntryKind.GROWTH -> TAB_GROWTH
            DiaryEntryKind.TODO -> TAB_TODO
        }
        val target = manager.findFragmentByTag(tabTag(tab))
        if (target == null || !target.isAdded || target.isHidden || manager.primaryNavigationFragment !== target) {
            switchTab(tab)
            return
        }
        // Consume only after the destination's add/show transaction has finished.
        pendingDiaryRefresh = false
        when (target) {
            is GrowthFragment -> target.refresh(expandTopic = pendingSavedTopic)
            is DailyFragment -> target.refresh(scrollToTop = true)
            is TodoFragment -> target.refresh(scrollToTop = true)
        }
        pendingSavedTopic = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save the latest request even if its transaction is still queued.
        outState.putInt("selected_tab", selectedTab)
        outState.putBoolean("pending_diary_refresh", pendingDiaryRefresh)
        outState.putString("pending_saved_kind", pendingSavedKind.name)
        outState.putString("pending_saved_topic", pendingSavedTopic)
        outState.putBoolean("diary_open", diaryOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Upgrade compatibility: intercept removed class names before FragmentManager restores them.
        // The empty placeholder is removed when normalizing the restored tabs below.
        supportFragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment = when (className) {
                "com.betterme.app.ui.ProfileFragment", "com.betterme.app.ui.SkillsFragment" -> Fragment()
                else -> super.instantiate(classLoader, className)
            }
        }
        super.onCreate(savedInstanceState)
        pendingDiaryRefresh = savedInstanceState?.getBoolean("pending_diary_refresh") ?: false
        pendingSavedKind = DiaryEntryKind.fromName(savedInstanceState?.getString("pending_saved_kind"))
        pendingSavedTopic = savedInstanceState?.getString("pending_saved_topic")
        diaryOpen = savedInstanceState?.getBoolean("diary_open") ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabDaily.setOnClickListener { switchTab(TAB_DAILY) }
        binding.tabGrowth.setOnClickListener { switchTab(TAB_GROWTH) }
        binding.tabTodo.setOnClickListener { switchTab(TAB_TODO) }
        binding.plusButton.setOnClickListener {
            AddEntryDialogFragment().show(supportFragmentManager, TAG_ADD_ENTRY)
        }

        val restoredFragment = supportFragmentManager.fragments.firstOrNull {
            it.isAdded && !it.isHidden && it.id == R.id.fragmentContainer
        }
        val restoredTab = when (restoredFragment) {
            is GrowthFragment -> TAB_GROWTH
            is TodoFragment -> TAB_TODO
            else -> TAB_DAILY
        }
        val requestedTab = savedInstanceState?.getInt("selected_tab", restoredTab) ?: restoredTab
        switchTab(requestedTab)
    }

    private fun tabTag(tab: Int) = when (tab) {
        TAB_GROWTH -> "growth"
        TAB_TODO -> "todo"
        else -> "daily"
    }

    private fun switchTab(tab: Int) {
        selectedTab = when (tab) {
            TAB_GROWTH -> TAB_GROWTH
            TAB_TODO -> TAB_TODO
            else -> TAB_DAILY
        }
        pendingTab = selectedTab
        applyPendingTab()
    }

    private fun applyPendingTab() {
        val manager = supportFragmentManager
        if (tabTransactionPending || manager.isStateSaved || manager.isDestroyed || isFinishing) return
        val tab = pendingTab ?: return
        pendingTab = null
        val tag = tabTag(tab)
        // Resolve through FragmentManager each time; never re-add a removed, cached instance.
        val target = when (tab) {
            TAB_GROWTH -> manager.findFragmentByTag(tag) as? GrowthFragment ?: GrowthFragment()
            TAB_TODO -> manager.findFragmentByTag(tag) as? TodoFragment ?: TodoFragment()
            else -> manager.findFragmentByTag(tag) as? DailyFragment ?: DailyFragment()
        }
        // Skip an already displayed tab, but still consume a pending saved-entry refresh.
        if (target.isAdded && !target.isHidden && manager.primaryNavigationFragment === target &&
            target.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            manager.fragments.none { it !== target && it.isAdded && it.id == R.id.fragmentContainer && !it.isHidden }) {
            updateTabStyle(tab)
            showSavedDiary()
            return
        }
        val transaction = manager.beginTransaction()
            .setReorderingAllowed(true)
        manager.fragments.filter { it.isAdded && it.id == R.id.fragmentContainer }.forEach { fragment ->
            if (fragment !== target) {
                if (fragment is DailyFragment || fragment is GrowthFragment || fragment is TodoFragment) {
                    transaction.hide(fragment).setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                } else {
                    // Only obsolete restored placeholders are removed, never live tabs.
                    transaction.remove(fragment)
                }
            }
        }
        if (!target.isAdded) transaction.add(R.id.fragmentContainer, target, tag)
        transaction.show(target)
            .setMaxLifecycle(target, Lifecycle.State.RESUMED)
            .setPrimaryNavigationFragment(target)
        tabTransactionPending = true
        transaction.runOnCommit {
            tabTransactionPending = false
            updateTabStyle(tab)
            // Serialize transactions so rapid taps cannot enqueue two additions of the same tab.
            if (pendingTab != null) applyPendingTab() else showSavedDiary()
        }.commit()
    }

    private fun updateTabStyle(selectedTab: Int) {
        val selectedColor = ContextCompat.getColor(this, R.color.brand_gold)
        val normalColor = ContextCompat.getColor(this, R.color.nav_inactive)

        styleTab(
            binding.iconDaily, binding.labelDaily,
            selected = selectedTab == TAB_DAILY,
            selectedColor = selectedColor,
            normalColor = normalColor
        )
        styleTab(
            binding.iconGrowth, binding.labelGrowth,
            selected = selectedTab == TAB_GROWTH,
            selectedColor = selectedColor,
            normalColor = normalColor
        )
        styleTab(
            binding.iconTodo, binding.labelTodo,
            selected = selectedTab == TAB_TODO,
            selectedColor = selectedColor,
            normalColor = normalColor
        )
    }

    private fun styleTab(
        icon: ImageView,
        label: TextView,
        selected: Boolean,
        selectedColor: Int,
        normalColor: Int
    ) {
        val color = if (selected) selectedColor else normalColor
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        label.setTextColor(color)
        label.typeface = if (selected) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }
    }
}
