package com.betterme.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import com.betterme.app.databinding.ActivityAlbumBinding
import com.betterme.app.diary.DiaryMedia
import com.betterme.app.diary.DiaryMediaKind
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Returns selected media URIs to the caller; private copies are thumbnail fallbacks only. */
class AlbumActivity : AppCompatActivity() {
    companion object {
        const val MAX_SELECT = 9
        private const val LIMIT_TOAST_INTERVAL_MS = 3_000L
        private val FILTERS = listOf("ALL", "VIDEO", "PHOTO")
        const val EXTRA_RESULT = "selected_uris"
        const val EXTRA_PRESELECT = "preselect_uris"
        const val EXTRA_LOCAL_MEDIA = "local_media"
    }
    private lateinit var binding: ActivityAlbumBinding
    private lateinit var adapter: AlbumAdapter
    private lateinit var selectedAdapter: AlbumSelectedAdapter
    private val selected = LinkedHashMap<Uri, MediaItem>()
    private val fallback = mutableListOf<MediaItem>()
    private var allItems = emptyList<MediaItem>()
    private var filter = "ALL"
    private var bucket: String? = null
    private var preselect = emptyList<Uri>()
    private var initialized = false
    private var requesting = false
    private var queryJob: Job? = null
    private var lastLimitToastAt = -LIMIT_TOAST_INTERVAL_MS
    private var limitToast: Toast? = null
    private var previewOpening = false
    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        previewOpening = false
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val restored = data?.getStringExtra(AlbumPreviewContract.SELECTED)?.let {
                runCatching { AlbumPreviewContract.decodeList(it) }.getOrNull()
            }
            if (data != null && restored != null) {
                selected.clear()
                restored.forEach { selected[it.uri] = it }
                initialized = true
                lastLimitToastAt = data.getLongExtra(AlbumPreviewContract.LAST_LIMIT_TOAST, lastLimitToastAt)
                refreshGrid()
                updateSelection()
                if (data.getBooleanExtra(AlbumPreviewContract.CONFIRM, false)) confirmSelection()
            }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        loadMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preselect = savedInstanceState?.getStringArrayList("selected")?.map(Uri::parse)
            ?: intent.getParcelableArrayListExtra<Uri>(EXTRA_PRESELECT).orEmpty()
        filter = savedInstanceState?.getString("filter")?.takeIf { it in FILTERS } ?: "ALL"
        lastLimitToastAt = savedInstanceState?.getLong("lastLimitToastAt", -LIMIT_TOAST_INTERVAL_MS) ?: -LIMIT_TOAST_INTERVAL_MS
        bucket = savedInstanceState?.getString("bucket")
        val local = intent.getStringExtra(EXTRA_LOCAL_MEDIA)
        if (local != null) runCatching { DiaryMedia.fromArray(JSONArray(local)) }.getOrNull()?.forEach {
            fallback += MediaItem(Uri.parse(it.sourceUri), 0,
                if (it.kind == DiaryMediaKind.VIDEO) MediaItem.Kind.VIDEO else MediaItem.Kind.PHOTO,
                0, it.durationMs, localUri = Uri.parse(it.uri))
        }
        adapter = AlbumAdapter((resources.displayMetrics.widthPixels - dp(8)) / 3, lifecycleScope, ::toggle, ::openPreview)
        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter
        selectedAdapter = AlbumSelectedAdapter(::removeSelected, ::openPreview)
        binding.selectedStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.selectedStrip.adapter = selectedAdapter
        binding.recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return
                val spacing = dp(2)
                val column = position % 3
                val start = column * spacing / 3
                val end = spacing - (column + 1) * spacing / 3
                if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) outRect.set(end, 0, start, spacing)
                else outRect.set(start, 0, end, spacing)
            }
        })
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnAlbum.setOnClickListener { chooseAlbum() }
        binding.chipAll.setOnClickListener { selectFilter("ALL") }
        binding.chipVideo.setOnClickListener { selectFilter("VIDEO") }
        binding.chipPhoto.setOnClickListener { selectFilter("PHOTO") }
        binding.mediaSwipeArea.onSwipe = { direction ->
            FILTERS.getOrNull(FILTERS.indexOf(filter) + direction)?.let(::selectFilter)
        }
        binding.btnPermissions.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("照片与视频权限")
                .setItems(arrayOf("重新选择可访问的照片和视频", "打开系统权限设置")) { _, which ->
                    if (which == 0) requestAccess()
                    else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.show()
        }
        binding.btnNext.setOnClickListener { confirmSelection() }
        updateSelection()
        if (!hasAnyAccess()) requestAccess()
    }

    override fun onResume() {
        super.onResume()
        if (!requesting) loadMedia()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(if (initialized) selected.keys.map(Uri::toString) else preselect.map(Uri::toString)))
        outState.putString("filter", filter)
        outState.putString("bucket", bucket)
        outState.putLong("lastLimitToastAt", lastLimitToastAt)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        limitToast?.cancel()
        super.onDestroy()
    }

    private fun selectFilter(value: String) {
        if (filter == value) return
        filter = value
        refreshGrid(scrollToTop = true)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasAnyAccess() = if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun hasFullAccess() = if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun requestAccess() {
        requesting = true
        permissions.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    private fun loadMedia() {
        queryJob?.cancel()
        binding.loading.isVisible = true
        binding.btnPermissions.isVisible = !hasFullAccess()
        queryJob = lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) {
                (query(MediaItem.Kind.PHOTO) + query(MediaItem.Kind.VIDEO)).sortedWith(compareByDescending<MediaItem> { it.dateAdded }.thenByDescending { it.id })
            }
            if (!initialized) {
                preselect.take(MAX_SELECT).forEach { uri ->
                    (allItems.firstOrNull { it.uri == uri } ?: fallback.firstOrNull { it.uri == uri })?.let { selected[uri] = it }
                }
                initialized = true
            }
            binding.loading.isVisible = false
            binding.btnPermissions.isVisible = !hasFullAccess()
            binding.btnPermissions.text = if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
                !(granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO))) "仅可访问部分素材 · 重新选择 ›"
                else "管理照片与视频访问权限 ›"
            if (bucket != null && allItems.none { it.bucketId == bucket }) bucket = null
            updateSelection()
            refreshGrid()
        }
    }
    private fun query(kind: MediaItem.Kind): List<MediaItem> {
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = if (kind == MediaItem.Kind.PHOTO) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO
            if (!granted(permission) && !(Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) return emptyList()
        } else if (!hasAnyAccess()) return emptyList()
        val collection = if (kind == MediaItem.Kind.PHOTO) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = mutableListOf("_id", "date_added", "bucket_id", "bucket_display_name")
        if (kind == MediaItem.Kind.VIDEO) columns += "duration"
        val items = mutableListOf<MediaItem>()
        try {
            contentResolver.query(collection, columns.toTypedArray(), null, null, "date_added DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    items += MediaItem(ContentUris.withAppendedId(collection, id), id, kind, c.getLong(1),
                        if (kind == MediaItem.Kind.VIDEO) c.getLong(4) else 0,
                        c.getString(2) ?: "", c.getString(3) ?: "其他相册")
                }
            }
        } catch (_: SecurityException) { /* A permission change can race the query. */ }
        catch (error: Exception) { android.util.Log.w("Album", "Media query failed", error) }
        return items
    }
    private fun chooseAlbum() {
        val buckets = allItems.distinctBy { it.bucketId }
        val labels = arrayOf("全部相册") + buckets.map { "${it.bucketName} (${allItems.count { media -> media.bucketId == it.bucketId }})" }
        MaterialAlertDialogBuilder(this).setTitle("选择相册").setItems(labels) { _, index ->
            bucket = if (index == 0) null else buckets[index - 1].bucketId
            refreshGrid(scrollToTop = true)
        }.show()
    }
    private fun refreshGrid(scrollToTop: Boolean = false) {
        val visible = allItems.filter { (bucket == null || it.bucketId == bucket) && (filter == "ALL" || it.kind.name == filter) }
        // Capture immutable orders, including selections hidden by the current filter.
        // Never read mutable `selected` from a background DiffUtil comparison.
        val orders = selected.keys.withIndex().associate { it.value to it.index }
        adapter.submitList(visible.map { AlbumCell(it, orders[it.uri] ?: -1) }) {
            if (scrollToTop) binding.recycler.scrollToPosition(0)
        }
        binding.tvTitle.text = allItems.firstOrNull { it.bucketId == bucket }?.bucketName ?: getString(R.string.album_all_folders)
        binding.tvEmpty.isVisible = visible.isEmpty()
        binding.tvEmpty.text = when {
            hasFullAccess() -> "这里还没有素材\n可切换其他相册"
            hasAnyAccess() -> "这里还没有可访问的素材\n可切换相册或管理访问权限"
            else -> "请授权访问照片或视频\n点击上方管理访问权限"
        }
        binding.chipAll.isSelected = filter == "ALL"
        binding.chipVideo.isSelected = filter == "VIDEO"
        binding.chipPhoto.isSelected = filter == "PHOTO"
        listOf(Triple(binding.tvChipAll, binding.lineAll, "ALL"), Triple(binding.tvChipVideo, binding.lineVideo, "VIDEO"), Triple(binding.tvChipPhoto, binding.linePhoto, "PHOTO")).forEach { (label, line, name) ->
            val color = ContextCompat.getColor(this, if (filter == name) R.color.brand_gold else R.color.album_muted)
            label.setTextColor(color)
            line.setBackgroundColor(if (filter == name) color else Color.TRANSPARENT)
        }
    }
    private fun toggle(item: MediaItem) {
        if (selected.remove(item.uri) == null) {
            if (selected.size >= MAX_SELECT) {
                showSelectionLimit()
                return
            }
            selected[item.uri] = item
        }
        refreshGrid()
        updateSelection()
    }

    private fun openPreview(item: MediaItem) {
        if (previewOpening) return
        previewOpening = true
        previewLauncher.launch(AlbumPreviewContract.intent(this, item, selected.values, lastLimitToastAt))
    }

    private fun showSelectionLimit() {
        // Leading-edge debounce (throttle-first): show immediately, at most once per 3 seconds.
        // Use monotonic time so manual clock changes do not affect the cooldown.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLimitToastAt < LIMIT_TOAST_INTERVAL_MS) return
        lastLimitToastAt = now
        limitToast?.cancel()
        limitToast = Toast.makeText(this, R.string.album_too_many, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun removeSelected(item: MediaItem) {
        // A second tap can arrive before the asynchronous diff removes its holder.
        // Removing an already-removed item must never select it again.
        if (selected.remove(item.uri) == null) return
        refreshGrid()
        updateSelection()
    }

    private fun updateSelection() {
        binding.tvTip.text = getString(R.string.album_order_tip, selected.size)
        binding.btnNext.text = "下一步(${selected.size})"
        binding.btnNext.isEnabled = selected.isNotEmpty()
        binding.btnNext.setBackgroundResource(R.drawable.album_button)
        binding.btnNext.alpha = if (selected.isEmpty()) .4f else 1f
        binding.selectedScroll.isVisible = selected.isNotEmpty()
        selectedAdapter.submitList(selected.values.mapIndexed { index, item -> AlbumCell(item, index) })
    }

    private fun confirmSelection() {
        if (selected.isEmpty()) {
            return
        }
        val uris = ArrayList(selected.keys)
        setResult(RESULT_OK, Intent().putParcelableArrayListExtra(EXTRA_RESULT, uris))
        finish()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
