package com.betterme.app

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.isVisible

/** Paragraphs and real checkbox rows, serialized through the existing plain-text content field. */
class DiaryContentEditor @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { private const val MAX_LENGTH = 20_000 }

    private data class Block(val text: String, val checklist: Boolean = false, val checked: Boolean = false)
    private class Row(val view: View, val edit: DiaryRowEditText, val check: AppCompatCheckBox) {
        var checklist = false
        fun block() = Block(edit.text?.toString().orEmpty(), checklist, check.isChecked)
    }

    var onContentChanged: (() -> Unit)? = null
    var onLimitReached: (() -> Unit)? = null
    private val rows = mutableListOf<Row>()
    private var mutating = false
    private var editorTextSize = 16f
    private var hintsEnabled = true
    private var structureVersion = 0

    init {
        orientation = VERTICAL
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isSaveEnabled = false
        setContent("")
        setOnClickListener { rows.lastOrNull()?.let { focus(it, it.edit.text?.length ?: 0, true) } }
    }

    fun getContent(): String = serialize(rows.map { it.block() })

    fun setHintsEnabled(enabled: Boolean) {
        hintsEnabled = enabled
        updateHints()
    }

    fun setContent(content: String) {
        change {
            removeAllViews()
            rows.clear()
            parse(content).forEach { appendRow(it, rows.size) }
            structureVersion++
        }
    }

    fun setEditorTextSize(size: Float) {
        editorTextSize = size
        rows.forEach { it.edit.textSize = size }
    }

    fun focusFirst() { rows.firstOrNull()?.let { focus(it, 0, false) } }

    fun insertText(text: String) {
        if (!isEnabled || text.isEmpty()) return
        val row = rows.firstOrNull { it.edit.hasFocus() } ?: rows.last()
        val value = row.edit.text ?: return
        val start = if (row.edit.hasFocus()) minOf(row.edit.selectionStart, row.edit.selectionEnd).coerceIn(0, value.length) else value.length
        val end = if (row.edit.hasFocus()) maxOf(row.edit.selectionStart, row.edit.selectionEnd).coerceIn(start, value.length) else start
        val oldLength = value.length
        val version = structureVersion
        value.replace(start, end, text)
        if (version == structureVersion) {
            val inserted = value.length - oldLength + end - start
            focus(row, start + inserted, true)
        }
    }

    fun insertChecklist() {
        if (!isEnabled) return
        val index = rows.indexOfFirst { it.edit.hasFocus() }
        if (index < 0) {
            val blocks = rows.map { it.block() }.toMutableList()
            // Reuse an empty trailing paragraph rather than creating a leading blank line.
            val last = blocks.last()
            if (!last.checklist && last.text.isEmpty()) {
                replaceRow(rows.lastIndex, listOf(Block("", true)), 0, 0)
            } else {
                blocks += Block("", true)
                if (!fits(blocks)) return
                change {
                    appendRow(blocks.last(), rows.size)
                    structureVersion++
                }
                focus(rows.last(), 0, true)
            }
            return
        }
        val row = rows[index]
        val block = row.block()
        if (block.checklist) {
            replaceRow(index, listOf(block, Block("", true)), 1, 0)
        } else if (block.text.isEmpty()) {
            replaceRow(index, listOf(Block("", true)), 0, 0)
        } else {
            val cursor = row.edit.selectionStart.coerceIn(0, block.text.length)
            val lineEnd = block.text.indexOf('\n', cursor)
            val pieces = mutableListOf(Block(block.text.substring(0, if (lineEnd < 0) block.text.length else lineEnd)), Block("", true))
            if (lineEnd >= 0) pieces += Block(block.text.substring(lineEnd + 1))
            replaceRow(index, pieces, 1, 0)
        }
    }

    private fun appendRow(block: Block, index: Int): Row {
        val view = LayoutInflater.from(context).inflate(R.layout.item_diary_editor_row, this, false)
        val row = Row(view, view.findViewById(R.id.rowText), view.findViewById(R.id.checkBox))
        row.edit.id = View.generateViewId()
        row.check.id = View.generateViewId()
        row.check.buttonTintList = null
        row.edit.textSize = editorTextSize
        setRow(row, block)
        rows.add(index, row)
        addView(view, index)
        row.check.setOnCheckedChangeListener { _, _ ->
            if (!mutating && row in rows) {
                updateCheckDescription(row)
                onContentChanged?.invoke()
            }
        }
        row.edit.onBackspaceAtStart = { backspaceAtStart(row) }
        row.edit.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            if (mutating || row !in rows) null else {
                val replacement = source.subSequence(start, end).toString()
                val candidate = dest.substring(0, dstart) + replacement + dest.substring(dend)
                val newBlocks = normalized(row.block().copy(text = candidate), candidate == "\n" && dest.isEmpty())
                val proposed = rows.map { it.block() }.toMutableList().apply {
                    val at = rows.indexOf(row)
                    removeAt(at)
                    addAll(at, newBlocks)
                }
                if (fits(proposed)) null else dest.subSequence(dstart, dend)
            }
        })
        var changeEnd = 0
        var wasEmpty = false
        row.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                wasEmpty = s.isNullOrEmpty()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                changeEnd = start + count
            }
            override fun afterTextChanged(s: Editable?) {
                if (mutating || row !in rows) return
                val value = s?.toString().orEmpty()
                if (row.checklist && '\n' in value) {
                    val pieces = normalized(row.block(), wasEmpty && value == "\n")
                    val offset = changeEnd.coerceIn(0, value.length)
                    val before = value.substring(0, offset)
                    val next = before.count { it == '\n' }.coerceAtMost(pieces.lastIndex)
                    val caret = if (pieces.size == 1) 0 else before.substringAfterLast('\n').length
                    // The input filter already checked the serialized size, including new prefixes.
                    replaceRow(rows.indexOf(row), pieces, next, caret, checkLimit = false)
                } else {
                    updateHints()
                    updateCheckDescription(row)
                    onContentChanged?.invoke()
                }
            }
        })
        return row
    }

    private fun setRow(row: Row, block: Block) {
        row.checklist = block.checklist
        row.check.isVisible = block.checklist
        row.check.isChecked = block.checked
        row.edit.setText(block.text)
        updateCheckDescription(row)
    }

    private fun replaceRow(index: Int, pieces: List<Block>, focusIndex: Int, caret: Int, checkLimit: Boolean = true) {
        if (index !in rows.indices) return
        val proposed = rows.map { it.block() }.toMutableList().apply {
            removeAt(index)
            addAll(index, pieces)
        }
        if (checkLimit && !fits(proposed)) return
        change {
            setRow(rows[index], pieces.first())
            pieces.drop(1).forEachIndexed { offset, block -> appendRow(block, index + offset + 1) }
            structureVersion++
        }
        focus(rows[index + focusIndex], caret, true)
    }

    private fun backspaceAtStart(row: Row): Boolean {
        if (mutating || !isEnabled) return false
        val index = rows.indexOf(row)
        if (index < 0) return false
        if (row.checklist) {
            replaceRow(index, listOf(Block(row.block().text)), 0, 0)
            return true
        }
        if (index == 0) return false
        // Backspace can also remove a paragraph boundary between separate input fields.
        val previous = rows[index - 1].block()
        val combined = previous.copy(text = previous.text + row.block().text)
        val pieces = normalized(combined)
        val proposed = rows.map { it.block() }.toMutableList().apply {
            removeAt(index)
            removeAt(index - 1)
            addAll(index - 1, pieces)
        }
        if (!fits(proposed)) return true
        change {
            removeView(row.view)
            rows.removeAt(index)
        }
        replaceRow(index - 1, pieces, 0, previous.text.length, checkLimit = false)
        return true
    }

    private fun normalized(block: Block, exitEmpty: Boolean = false): List<Block> = when {
        !block.checklist -> listOf(block)
        exitEmpty -> listOf(Block(""))
        else -> block.text.split('\n').mapIndexed { index, line -> Block(line, true, index == 0 && block.checked) }
    }

    private fun parse(content: String): List<Block> {
        val result = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += Block(paragraph.joinToString("\n"))
                paragraph.clear()
            }
        }
        content.split('\n').forEach { line ->
            if (line.startsWith("☐ ") || line.startsWith("☑ ")) {
                flushParagraph()
                result += Block(line.substring(2), true, line[0] == '☑')
            } else paragraph += line
        }
        flushParagraph()
        return result.ifEmpty { listOf(Block("")) }
    }

    private fun serialize(blocks: List<Block>): String = blocks.joinToString("\n") { block ->
        (if (block.checklist) if (block.checked) "☑ " else "☐ " else "") + block.text
    }

    private fun fits(blocks: List<Block>): Boolean {
        val length = serialize(blocks).length
        // Older over-limit entries remain readable and can still be shortened.
        if (length <= MAX_LENGTH || length <= getContent().length) return true
        onLimitReached?.invoke()
        return false
    }

    private fun change(action: () -> Unit) {
        val previous = mutating
        mutating = true
        try { action() } finally { mutating = previous }
        if (!mutating) {
            updateHints()
            onContentChanged?.invoke()
        }
    }

    private fun updateHints() {
        rows.forEach { row ->
            row.edit.hint = when {
                !hintsEnabled -> null
                row.checklist -> context.getString(R.string.checklist_item_hint)
                rows.size == 1 -> context.getString(R.string.diary_content_hint)
                else -> null
            }
        }
    }

    private fun updateCheckDescription(row: Row) {
        row.check.contentDescription = context.getString(R.string.checklist_item_description,
            row.edit.text?.toString().orEmpty().ifBlank { context.getString(R.string.checklist_item_hint) })
    }

    private fun focus(row: Row, offset: Int, showKeyboard: Boolean) {
        row.edit.requestFocus()
        row.edit.setSelection(offset.coerceIn(0, row.edit.text?.length ?: 0))
        row.edit.post {
            if (row !in rows || !row.edit.hasFocus() || !isAttachedToWindow) return@post
            val layout = row.edit.layout
            val line = layout?.getLineForOffset(row.edit.selectionStart.coerceAtLeast(0)) ?: 0
            val top = (layout?.getLineTop(line) ?: 0) + row.edit.totalPaddingTop
            row.edit.requestRectangleOnScreen(Rect(0, top, row.edit.width, top + dp(40)), false)
            if (showKeyboard) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(row.edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun saveSelection(state: Bundle) {
        val index = rows.indexOfFirst { it.edit.hasFocus() }
        if (index < 0) return
        // Save a text offset, because consecutive ordinary paragraphs may merge when reloaded.
        var offset = 0
        rows.take(index).forEach { offset += serialize(listOf(it.block())).length + 1 }
        if (rows[index].checklist) offset += 2
        state.putInt("diaryEditorSelectionStart", offset + rows[index].edit.selectionStart.coerceAtLeast(0))
        state.putInt("diaryEditorSelectionEnd", offset + rows[index].edit.selectionEnd.coerceAtLeast(0))
    }

    fun restoreSelection(state: Bundle) {
        if (!state.containsKey("diaryEditorSelectionStart")) return
        val start = state.getInt("diaryEditorSelectionStart")
        val end = state.getInt("diaryEditorSelectionEnd")
        var offset = 0
        rows.forEach { row ->
            val prefix = if (row.checklist) 2 else 0
            val length = row.edit.text?.length ?: 0
            if (start <= offset + prefix + length) {
                focus(row, start - offset - prefix, false)
                row.edit.setSelection((start - offset - prefix).coerceIn(0, length), (end - offset - prefix).coerceIn(0, length))
                return
            }
            offset += prefix + length + 1
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
