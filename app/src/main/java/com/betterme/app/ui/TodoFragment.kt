package com.betterme.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.betterme.app.diary.DiaryCursor
import androidx.recyclerview.widget.LinearLayoutManager
import com.betterme.app.R
import com.betterme.app.MainActivity
import com.betterme.app.databinding.FragmentTodoBinding
import com.betterme.app.diary.DiaryStore
import com.betterme.app.diary.DiaryEntry
import com.betterme.app.diary.DiaryEntryKind
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Saved todos reuse record cards and media cleanup, filtered by their own kind. */
class TodoFragment : Fragment() {
    private var binding: FragmentTodoBinding? = null
    private var entries: DailyEntryAdapter? = null
    private var footer: DiaryPageAdapter? = null
    private var cursor: DiaryCursor? = null
    private var loadingPage = false
    private var hasRenderedContent = false
    private var pageError = false
    private var currentRecords: List<DiaryEntry> = emptyList()
    private var loadJob: Job? = null
    private var deleteJob: Job? = null
    private var deleteDialog: AlertDialog? = null
    private var loadVersion = 0
    private var scrollToNewest = false
    private val pagePositions = mutableMapOf<String, Int>()
    private var editorOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle("photo_pages")?.let { pages ->
            pages.keySet().forEach { pagePositions[it] = pages.getInt(it) }
        }
        scrollToNewest = scrollToNewest || savedInstanceState?.getBoolean("scroll_to_newest") == true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ui = FragmentTodoBinding.inflate(inflater, container, false)
        binding = ui
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ui = binding ?: return
        entries = DailyEntryAdapter(pagePositions, onEditEntry = { entryId ->
            if (binding === ui && canHandleClicks() && !editorOpen && deleteJob?.isActive != true &&
                deleteDialog == null && currentRecords.any { it.id == entryId }) {
                val host = activity as? MainActivity
                if (host != null) {
                    editorOpen = true
                    host.openDiary(entryId, DiaryEntryKind.TODO)
                }
            }
        }, onDeleteEntry = { if (binding === ui) confirmDelete(it) })
        ui.recycler.layoutManager = LinearLayoutManager(requireContext())
        footer = DiaryPageAdapter { loadEntries(append = true) }
        ui.recycler.adapter = ConcatAdapter(entries!!, footer!!)
        ui.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    recyclerView.post {
                        if (binding === ui) {
                            maybeLoadMore()
                        }
                    }
                }
            }
        })
        ui.recycler.itemAnimator = null
        ui.retry.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        editorOpen = false
        loadEntries()
    }

    override fun onPause() {
        deleteDialog?.dismiss()
        super.onPause()
    }

    private fun canHandleClicks(): Boolean = binding != null && isAdded && !isHidden &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !parentFragmentManager.isStateSaved

    /** May be called before this fragment's view is created by the tab transaction. */
    fun refresh(scrollToTop: Boolean = false) {
        scrollToNewest = scrollToNewest || scrollToTop
        if (binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) loadEntries()
    }

    private fun maybeLoadMore() {
        val ui = binding ?: return
        val layout = ui.recycler.layoutManager as LinearLayoutManager
        if (!pageError && cursor != null && !loadingPage &&
            layout.findLastVisibleItemPosition() >= (entries?.itemCount ?: 0) - 3) loadEntries(append = true)
    }

    private fun loadEntries(append: Boolean = false) {
        if (deleteJob?.isActive == true || (append && (loadingPage || cursor == null))) return
        val ui = binding ?: return
        val adapter = entries ?: return
        val store = DiaryStore(requireContext())
        loadJob?.cancel()
        val after = if (append) cursor else null
        // A refresh keeps the current footer; only pagination shows its loading indicator.
        footer?.update(append || (hasRenderedContent && cursor != null), append, false)
        if (!append) cursor = null
        loadingPage = true
        pageError = false
        val version = ++loadVersion
        ui.loading.isVisible = !hasRenderedContent
        if (!hasRenderedContent) {
            ui.statusGroup.isVisible = false
            ui.recycler.isVisible = true
        }
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = store.page(DiaryEntryKind.TODO, after = after)
                if (binding !== ui || version != loadVersion) return@launch
                cursor = page.next
                val records = if (append) {
                    (currentRecords + page.entries).distinctBy { it.id }
                } else {
                    page.entries
                }
                showRecords(ui, adapter, records, version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (binding === ui && version == loadVersion) {
                    pageError = true
                    if (append) {
                        footer?.update(true, false, true)
                        return@launch
                    }
                    ui.loading.isVisible = false
                    ui.recycler.isVisible = false
                    ui.statusGroup.isVisible = true
                    ui.statusText.setText(R.string.todo_load_error)
                    ui.retry.isVisible = true
                }
            } finally {
                if (binding === ui && version == loadVersion) {
                    loadingPage = false
                    footer?.update(cursor != null, false, append && pageError)
                }
            }
        }
    }

    private fun showRecords(
        ui: FragmentTodoBinding,
        adapter: DailyEntryAdapter,
        records: List<DiaryEntry>,
        version: Int
    ) {
        if (binding !== ui || version != loadVersion) return
        // Keep the source list current while ListAdapter computes its diff asynchronously.
        currentRecords = records
        val layout = ui.recycler.layoutManager as LinearLayoutManager
        val first = layout.findFirstVisibleItemPosition()
        val anchorId = adapter.currentList.getOrNull(first)?.id
        val anchorOffset = layout.findViewByPosition(first)?.let {
            layout.getDecoratedTop(it) - ui.recycler.paddingTop
        } ?: 0
        adapter.submitList(records) {
            if (binding !== ui || version != loadVersion) return@submitList
            hasRenderedContent = true
            ui.loading.isVisible = false
            ui.statusGroup.isVisible = records.isEmpty()
            ui.statusText.setText(R.string.todo_empty)
            ui.retry.isVisible = false
            ui.recycler.isVisible = records.isNotEmpty()
            if (scrollToNewest && records.isNotEmpty()) {
                ui.recycler.scrollToPosition(0)
                scrollToNewest = false
            } else if (anchorId != null) {
                // Updating an entry can move it in the sorted list; retain the visible anchor.
                val position = records.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 }
                    ?: first.coerceAtMost(records.lastIndex)
                if (position >= 0) layout.scrollToPositionWithOffset(position, anchorOffset)
            }
        }
    }

    private fun confirmDelete(entryId: String) {
        if (!canHandleClicks()) return
        if (editorOpen || deleteJob?.isActive == true || deleteDialog != null) return
        if (currentRecords.none { it.id == entryId }) return
        deleteDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DailyDeleteDialog)
            .setTitle(R.string.todo_delete_title)
            .setMessage(R.string.daily_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.daily_delete_confirm) { _, _ -> deleteEntry(entryId) }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (deleteDialog === dialog) deleteDialog = null }
                dialog.show()
            }
    }

    private fun deleteEntry(entryId: String) {
        if (deleteJob?.isActive == true) return
        val app = requireContext().applicationContext
        val store = DiaryStore(app)
        loadJob?.cancel()
        ++loadVersion
        // Fragment scope lets an accepted deletion finish across a view recreation.
        deleteJob = lifecycleScope.launch {
            try {
                val cleanupComplete = store.delete(entryId)
                currentRecords = currentRecords.filterNot { it.id == entryId }
                val ui = binding
                val adapter = entries
                if (ui != null && adapter != null) {
                    showRecords(ui, adapter, currentRecords, loadVersion)
                }
                if (!cleanupComplete) {
                    Toast.makeText(app, R.string.todo_delete_cleanup_warning, Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(app, R.string.daily_delete_error, Toast.LENGTH_SHORT).show()
            } finally {
                deleteJob = null
                // Refill the first page without reloading every previously visited page.
                if (binding != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    loadEntries()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val pages = Bundle()
        pagePositions.forEach { (id, page) -> pages.putInt(id, page) }
        outState.putBundle("photo_pages", pages)
        outState.putBoolean("scroll_to_newest", scrollToNewest)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        deleteDialog?.dismiss()
        deleteDialog = null
        loadVersion++
        loadJob?.cancel()
        loadJob = null
        binding?.recycler?.let { com.betterme.app.MediaThumbLoader().clearViews(it) }
        binding?.recycler?.adapter = null
        entries = null
        footer = null
        loadingPage = false
        hasRenderedContent = false
        binding = null
        super.onDestroyView()
    }
}
