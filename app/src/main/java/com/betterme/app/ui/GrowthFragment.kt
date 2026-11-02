package com.betterme.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.DiaryActivity
import com.betterme.app.DiaryDetailActivity
import com.betterme.app.R
import com.betterme.app.databinding.FragmentGrowthBinding
import com.betterme.app.diary.DiaryCursor
import com.betterme.app.diary.DiaryTopic
import com.betterme.app.diary.DiaryEntry
import com.betterme.app.diary.DiaryEntryKind
import com.betterme.app.diary.DiaryStore
import com.betterme.app.diary.GrowthTopics
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Topic headers and the daily feed's article cards share one vertically scrolling RecyclerView. */
class GrowthFragment : Fragment() {
    private var binding: FragmentGrowthBinding? = null
    private var feed: ConcatAdapter? = null
    private var articles: DailyEntryAdapter? = null
    private val headers = linkedMapOf<String, GrowthTopicAdapter>()
    private var topics: List<DiaryTopic> = emptyList()
    private var footer: DiaryPageAdapter? = null
    private var cursor: DiaryCursor? = null
    private var loadingPage = false
    private var hasRenderedContent = false
    private var pageError = false
    private var records: List<DiaryEntry> = emptyList()
    private val pagePositions = mutableMapOf<String, Int>()
    private var expandedKey: String? = null
    private var pendingTopic: String? = null
    private var anchorKey: String? = null
    private var anchorOffset = 0
    private var loadJob: Job? = null
    private var deleteJob: Job? = null
    private var deleteDialog: AlertDialog? = null
    private var version = 0
    private var renderVersion = 0
    private var detailsOpen = false

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        detailsOpen = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingTopic = result.data?.getStringExtra(DiaryActivity.EXTRA_SAVED_TOPIC)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expandedKey = savedInstanceState?.getString("expanded_topic") ?: expandedKey
        pendingTopic = pendingTopic ?: savedInstanceState?.getString("pending_topic")
        anchorKey = savedInstanceState?.getString("anchor_key") ?: anchorKey
        anchorOffset = savedInstanceState?.getInt("anchor_offset") ?: anchorOffset
        detailsOpen = savedInstanceState?.getBoolean("details_open") ?: detailsOpen
        savedInstanceState?.getBundle("photo_pages")?.let { pages ->
            pages.keySet().forEach { pagePositions[it] = pages.getInt(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentGrowthBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = binding ?: return
        articles = DailyEntryAdapter(pagePositions,
            onEditEntry = { if (binding === ui) openEntry(it) },
            onDeleteEntry = { if (binding === ui) confirmDelete(it) },
            prunePagePositions = false)
        footer = DiaryPageAdapter { loadRecords(append = true) }
        feed = ConcatAdapter()
        ui.recycler.layoutManager = LinearLayoutManager(requireContext())
        ui.recycler.adapter = feed
        ui.recycler.itemAnimator = null
        ui.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingPage || pageError || cursor == null) return
                val adapter = articles ?: return
                val start = positionOf("topic:${expandedKey}") + 1
                val last = (recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                if (start > 0 && last >= start + adapter.itemCount - 3) recyclerView.post {
                    if (binding === ui && !pageError) loadRecords(append = true)
                }
            }
        })
        ui.retry.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    override fun onPause() {
        deleteDialog?.dismiss()
        super.onPause()
    }

    private fun canHandleClicks(): Boolean = binding != null && isAdded && !isHidden &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !parentFragmentManager.isStateSaved

    /** MainActivity may request a topic before this fragment has a view. */
    fun refresh(expandTopic: String? = null) {
        if (expandTopic != null) pendingTopic = expandTopic
        if (binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) loadRecords()
    }

    private fun loadRecords(append: Boolean = false, scrollToTopic: String? = null) {
        if (deleteJob?.isActive == true || (append && (loadingPage || cursor == null))) return
        val ui = binding ?: return
        val store = DiaryStore(requireContext())
        rememberAnchor()
        loadJob?.cancel()
        val request = ++version
        ++renderVersion
        val after = if (append) cursor else null
        // A refresh keeps the current footer; only pagination shows its loading indicator.
        footer?.update(append || (hasRenderedContent && cursor != null), append, false)
        if (!append) cursor = null
        loadingPage = true
        pageError = false
        ui.loading.isVisible = !hasRenderedContent
        if (!hasRenderedContent) {
            ui.statusGroup.isVisible = false
            ui.recycler.isVisible = true
        }
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loadedTopics = if (append) topics else store.topics()
                if (binding !== ui || request != version) return@launch
                val requested = pendingTopic?.let(GrowthTopics::key)
                val selected = (requested ?: expandedKey)?.takeIf { key -> loadedTopics.any { it.key == key } }
                val page = selected?.let {
                    store.page(DiaryEntryKind.GROWTH, topic = it, after = after)
                }
                if (binding !== ui || request != version) return@launch
                topics = loadedTopics
                expandedKey = selected
                cursor = page?.next
                records = if (append) {
                    (records + page?.entries.orEmpty()).distinctBy { it.id }
                } else {
                    page?.entries.orEmpty()
                }
                if (!append) pendingTopic = null
                renderTopics(scrollToTopic = if (append) null else requested ?: scrollToTopic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (binding === ui && request == version) {
                    pageError = true
                    if (append) return@launch
                    ui.loading.isVisible = false
                    ui.recycler.isVisible = false
                    ui.statusGroup.isVisible = true
                    ui.statusText.setText(R.string.growth_load_error)
                    ui.retry.isVisible = true
                }
            } finally {
                if (binding === ui && request == version) {
                    loadingPage = false
                    footer?.update(cursor != null, false, append && pageError)
                }
            }
        }
    }

    private fun toggleTopic(key: String) {
        if (!canHandleClicks()) return
        if (detailsOpen || deleteJob?.isActive == true || deleteDialog != null) return
        pendingTopic = null
        rememberAnchor()
        expandedKey = if (expandedKey == key) null else key
        records = emptyList()
        // Remove the previous topic's cards immediately while the new first page is read.
        renderTopics(scrollToTopic = key)
        loadRecords(scrollToTopic = key)
    }

    private fun renderTopics(scrollToTopic: String? = null) {
        val ui = binding ?: return
        val concat = feed ?: return
        val adapter = articles ?: return
        rememberAnchor()
        val target = scrollToTopic
        val selectedKey = expandedKey
        val generation = ++renderVersion
        // Wait for the shared card diff before moving its adapter beneath a different topic.
        adapter.submitList(records) {
            if (binding !== ui || generation != renderVersion) return@submitList
            val desired = mutableListOf<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
            topics.forEach { topic ->
                val key = topic.key
                val header = headers.getOrPut(key) { GrowthTopicAdapter(key, ::toggleTopic) }
                val label = topic.label.ifBlank { getString(R.string.growth_uncategorized) }
                header.update(label, topic.count, key == selectedKey)
                desired.add(header)
                if (key == selectedKey) {
                    desired.add(adapter)
                    footer?.let { desired.add(it) }
                }
            }
            concat.adapters.filter { it !in desired }.forEach { concat.removeAdapter(it) }
            desired.forEachIndexed { index, section ->
                if (concat.adapters.getOrNull(index) !== section) {
                    if (section in concat.adapters) concat.removeAdapter(section)
                    concat.addAdapter(index, section)
                }
            }
            headers.keys.retainAll(topics.map { it.key }.toSet())
            hasRenderedContent = true
            ui.loading.isVisible = false
            ui.statusGroup.isVisible = topics.isEmpty()
            ui.statusText.setText(R.string.growth_empty)
            ui.retry.isVisible = false
            ui.recycler.isVisible = topics.isNotEmpty()
            val layout = ui.recycler.layoutManager as LinearLayoutManager
            val targetPosition = target?.let { positionOf("topic:$it") } ?: -1
            val anchorPosition = anchorKey?.let(::positionOf) ?: -1
            when {
                targetPosition >= 0 -> layout.scrollToPositionWithOffset(targetPosition, 0)
                anchorPosition >= 0 -> layout.scrollToPositionWithOffset(anchorPosition, anchorOffset)
                selectedKey != null -> positionOf("topic:$selectedKey").takeIf { it >= 0 }?.let {
                    layout.scrollToPositionWithOffset(it, 0)
                }
            }
        }
    }

    private fun rememberAnchor() {
        val ui = binding ?: return
        val layout = ui.recycler.layoutManager as? LinearLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition()
        val child = layout.findViewByPosition(first) ?: return
        anchorKey = keyAt(first) ?: return
        anchorOffset = layout.getDecoratedTop(child) - ui.recycler.paddingTop
    }

    private fun keyAt(position: Int): String? {
        var index = position
        for (section in feed?.adapters.orEmpty()) {
            if (index >= 0 && index < section.itemCount) {
                return when (section) {
                    is GrowthTopicAdapter -> "topic:${section.key}"
                    is DailyEntryAdapter -> section.currentList.getOrNull(index)?.let { "entry:${it.id}" }
                    else -> null
                }
            }
            index -= section.itemCount
        }
        return null
    }

    private fun positionOf(key: String): Int {
        var offset = 0
        for (section in feed?.adapters.orEmpty()) {
            when (section) {
                is GrowthTopicAdapter -> if (key == "topic:${section.key}") return offset
                is DailyEntryAdapter -> {
                    val index = section.currentList.indexOfFirst { key == "entry:${it.id}" }
                    if (index >= 0) return offset + index
                }
            }
            offset += section.itemCount
        }
        return -1
    }

    private fun openEntry(id: String) {
        if (!canHandleClicks()) return
        if (detailsOpen || deleteJob?.isActive == true || deleteDialog != null || records.none { it.id == id }) return
        detailsOpen = true
        detailLauncher.launch(Intent(requireContext(), DiaryDetailActivity::class.java)
            .putExtra(DiaryDetailActivity.EXTRA_ENTRY_ID, id)
            .putExtra(DiaryDetailActivity.EXTRA_INITIAL_PAGE, pagePositions[id] ?: 0))
    }

    private fun confirmDelete(id: String) {
        if (!canHandleClicks()) return
        if (detailsOpen || deleteJob?.isActive == true || deleteDialog != null || records.none { it.id == id }) return
        deleteDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DailyDeleteDialog)
            .setTitle(R.string.growth_delete_title)
            .setMessage(R.string.daily_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.daily_delete_confirm) { _, _ -> deleteEntry(id) }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (deleteDialog === dialog) deleteDialog = null }
                dialog.show()
            }
    }

    private fun deleteEntry(id: String) {
        if (deleteJob?.isActive == true) return
        val app = requireContext().applicationContext
        val store = DiaryStore(app)
        loadJob?.cancel()
        ++version
        ++renderVersion
        deleteJob = lifecycleScope.launch {
            try {
                val cleanupComplete = store.delete(id)
                records = records.filterNot { it.id == id }
                pagePositions.remove(id)
                if (!cleanupComplete) Toast.makeText(app, R.string.growth_delete_cleanup_warning, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(app, R.string.daily_delete_error, Toast.LENGTH_SHORT).show()
            } finally {
                deleteJob = null
                if (binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    loadRecords()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberAnchor()
        outState.putString("expanded_topic", expandedKey)
        outState.putString("pending_topic", pendingTopic)
        outState.putString("anchor_key", anchorKey)
        outState.putInt("anchor_offset", anchorOffset)
        outState.putBoolean("details_open", detailsOpen)
        outState.putBundle("photo_pages", Bundle().apply { pagePositions.forEach { (id, page) -> putInt(id, page) } })
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        rememberAnchor()
        ++version
        ++renderVersion
        loadJob?.cancel()
        loadJob = null
        deleteDialog?.dismiss()
        deleteDialog = null
        binding?.recycler?.let { com.betterme.app.MediaThumbLoader().clearViews(it) }
        binding?.recycler?.adapter = null
        headers.clear()
        articles = null
        footer = null
        loadingPage = false
        hasRenderedContent = false
        feed = null
        binding = null
        super.onDestroyView()
    }
}
