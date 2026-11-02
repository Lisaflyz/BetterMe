package com.betterme.app.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.betterme.app.MainActivity
import com.betterme.app.diary.DiaryEntryKind
import com.betterme.app.R
import com.betterme.app.databinding.DialogAddEntryBinding

/**
 * 点击悬浮“+”弹出的添加弹层。
 *
 * 样式参考 弹窗.jpg（小红书发布弹窗）：白色圆角底部弹层 + 遮罩 + 选项列表 + 取消。
 * 记录日常、成长复盘、添加待办分别打开 DiaryActivity 的对应模式。
 */
class AddEntryDialogFragment : DialogFragment() {

    private var _binding: DialogAddEntryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.optionGallery.setOnClickListener {
            // 先打开日记编辑页，照片由用户在编辑页按需添加。
            (requireActivity() as? MainActivity)?.openDiary()
            dismiss()
        }
        binding.optionText.setOnClickListener {
            (requireActivity() as? MainActivity)?.openDiary(kind = DiaryEntryKind.GROWTH)
            dismiss()
        }
        binding.optionTodo.setOnClickListener {
            (requireActivity() as? MainActivity)?.openDiary(kind = DiaryEntryKind.TODO)
            dismiss()
        }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        // 让弹层贴底、透明背景（圆角由布局 bg_sheet_top 提供）、带滑入动画
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.5f)
            setWindowAnimations(R.style.DialogBottomAnim)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
