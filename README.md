# Better Me (Android)

「Better Me」是一款本地日常记录与成长复盘 Android 应用。

- 图标：使用 `/Users/zhanglifei/Desktop/betterMe/better.jpg` 自动生成（普通图标 + Android 8+ 自适应图标；更新版图标源文件：better.jpg（2026-09-08 更新））。
- 首页底部 Tab：**每日 | 加号(+) | 成长**（三个等宽区域，白色底栏 + 圆形加号）。
- 点击底部 **+**：弹出底部弹层（样式参考 `弹窗.jpg`，遮罩 + 白色圆角底部弹层 + 选项 + 取消）。
  - 选项：**记录日常**、**成长复盘**（均进入 `DiaryActivity`，按记录类型展示标签）、**取消**。
- **相册多选页**（`AlbumActivity`）：从日记编辑页点击「打开相册」进入；支持全部/照片/视频筛选、
  三列网格多选（最多 9 项，带序号角标）、底部“下一步(N)”。点击素材进入 `AlbumPreviewActivity` 预览。
  - 点击下一步统一通过 `RESULT_OK` 和 `selected_uris` 返回有序素材列表，回到日记页导入，不再跳转旧发布页。
  - Android 13+ 请求 `READ_MEDIA_IMAGES/VIDEO`，Android 14 支持部分素材授权，旧版本用 `READ_EXTERNAL_STORAGE`。
- **日记编辑页**（`DiaryActivity`）：日常与成长共用，支持标题、正文、清单、日期、标签、照片与视频，以及字体和纸张样式。
  - 保存写入本地记录；后台暂存草稿，主动退出有改动时提示是否放弃。
  - 多项素材中包含视频时按选择顺序合成视频；纯图片保留图片列表，单视频直接播放。

- **成长复盘**：复用日常编辑功能，标题提示为「记录成长」，主题支持 Android、AI、英语及自定义；保存到本机后回到成长页并展开对应主题。主题卡片显示文章篇数，点击展开或收起，主题内文章复用每日卡片（图片轮播、视频、文字摘要、删除）。
- **记录存储**：完整记录保存在私有 JSON 文件中，Room 保存可重建的分页与主题索引，SharedPreferences 保存草稿，导入媒体保存在私有目录。日常与成长按类型分别展示，新建草稿分别保存；旧日记默认归为日常。主题内文章与主题列表按最近更新时间排序，同名主题合并。
- **详情页**（`DiaryDetailActivity`）：每日或成长文章卡片点击进入，展示图片轮播／视频、完整正文与清单；右上角可继续编辑。成长详情展示主题，修改主题或删除文章后更新归属和篇数。

## 环境

| 项 | 版本 |
| --- | --- |
| Android Studio | Ladybug 或更新（能打开 Gradle 8.7 / AGP 8.5 即可） |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| minSdk | 23 |
| Gradle | 8.7（项目自带 wrapper） |
| Android Gradle Plugin | 8.5.1 |
| Kotlin | 1.9.0 |

命令行构建参考（本次旧编辑分支清理未运行测试或构建）：

```bash
cd BetterMe
JAVA_HOME=/path/to/jdk17 ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 目录结构（重点）

```
app/src/main/java/com/betterme/app/
├── MainActivity.kt               # 主界面：底部 Tab 切换 + 打开弹层
├── DiaryActivity.kt              # 日常 / 成长编辑与保存
├── DiaryDetailActivity.kt        # 已保存记录详情
├── AlbumActivity.kt              # 相册多选与结果回传
├── AlbumPreviewActivity.kt       # 相册选择过程中的预览
├── PhotoPreviewActivity.kt       # 只读图片放大
├── VideoPlayerActivity.kt        # 独立视频播放
├── AlbumAdapter.kt               # 相册网格 + 多选角标
├── MediaThumbLoader.kt           # 共享缩略图加载器
├── diary/                       # 记录、草稿、Room 索引与媒体处理
└── ui/
    ├── DailyFragment.kt          # 本地日常卡片列表
    ├── GrowthFragment.kt         # 成长主题分组与文章列表
    └── AddEntryDialogFragment.kt # 点击 + 弹出的底部弹层

app/src/main/res/
├── drawable/                     # 图标/形状（ic_*、bg_*）
├── layout/
│   ├── activity_main.xml         # 底部 Tab 栏布局（每日 | + | 成长）
│   ├── activity_diary.xml        # 正式日记编辑页
│   ├── dialog_add_entry.xml      # 弹层布局（日常 / 成长 + 取消）
│   ├── fragment_daily.xml        # 每日卡片列表
│   └── fragment_growth.xml       # 成长主题列表
├── mipmap-*/                     # 自动生成的图标资源
└── values/{colors,strings,themes}.xml
```

## 常用修改

- **弹层选项（文案/图标/点击行为）**
  改 `res/layout/dialog_add_entry.xml`（选项行）与 `res/values/strings.xml`（文案），
  再在 `AddEntryDialogFragment.onViewCreated()` 里为每个选项绑定点击事件。
  图标换 `res/drawable/ic_*.xml`。

- **主题色**
  在 `res/values/colors.xml` 中维护深绿文字 `text_primary`（`#20382F`）、香槟金 `brand_gold`（`#D9C295`）和暖白底色 `surface`（`#FFFDF7`）。

- **底部 Tab 切换逻辑**
  `MainActivity.switchTab()` / `updateTabStyle()`。

- **图标重新生成**
  Android Studio 可直接打开工程后，用 Image Asset 重新导入 `better.jpg`；
  或运行 `scripts` 外的 `python` 脚本（见文末“重新生成图标”备注）。

## 待办（TODO）

- [x] 「每日 / 成长」接入本机保存的记录，支持详情、编辑和删除
- [x] 日常 / 成长添加入口统一进入正式日记编辑页，相册统一返回选择结果
- [ ] 深色模式（当前固定浅色主题）

> 说明：参考图 `首页.jpg`、`弹窗.jpg` 是小红书（Xiaohongshu）界面截图，本项目只借用了它的
> 底栏 + 加号弹层交互与视觉风格；品牌图标来自 `better.jpg`。

## 项目总结文档

- [Word 总结](output/project-summary/BetterMe项目总结.docx)
- [Markdown 总结](output/project-summary/BetterMe项目总结.md)
- [文档与截图资料包](output/project-summary/BetterMe项目总结及截图.zip)

当前包含 7 个 Activity、17 个布局 XML。总结提供页面与视图映射、交互契约、存储流程及架构图。
13 张截图沿用 2026-10-31 采集的已安装版本，用于展示保留页面；不代表本次清理已运行验证。
