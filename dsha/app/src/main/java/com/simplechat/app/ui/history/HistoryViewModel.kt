package com.simplechat.app.ui.history

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationSearchHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话列表（侧拉抽屉）的状态与管理操作。
 *
 * 列表数据直接来自 Room 的 `Flow`，因此重命名 / 置顶 / 删除会自动反映到 UI，
 * 不需要手动刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: ChatRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")

    /** 搜索关键词。空串即全量列表。 */
    var query by mutableStateOf("")
        private set

    /**
     * 抽屉列表：**全量**，不受搜索框影响。
     *
     * 搜索框只是个入口按钮（点了进搜索页），抽屉本身没有过滤态。
     */
    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 搜索页结果：**标题或消息正文**命中，附命中的那条消息原文。
     *
     * 关键词为空时直接给空列表、不查库 —— 搜索页在空关键词下本来就只显示一句提示。
     */
    val searchHits: StateFlow<List<ConversationSearchHit>> = queryFlow
        .flatMapLatest { keyword ->
            if (keyword.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.searchConversations(keyword)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 多选模式。 */
    var selectionMode by mutableStateOf(false)
        private set

    val selectedIds = mutableStateListOf<String>()

    fun updateQuery(value: String) {
        query = value
        queryFlow.value = value
    }

    // ── 多选 ──────────────────────────────────────────────

    fun enterSelection(initialId: String) {
        selectionMode = true
        if (initialId !in selectedIds) selectedIds.add(initialId)
    }

    fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
    }

    // ── 单条操作 ──────────────────────────────────────────

    fun rename(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun togglePin(conversation: ConversationEntity) {
        viewModelScope.launch { repository.setPinned(conversation.id, !conversation.pinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteConversations(listOf(id)) }
    }

    // ── 批量操作 ──────────────────────────────────────────

    fun pinSelected(pinned: Boolean) {
        val ids = selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { repository.setPinned(it, pinned) }
            exitSelection()
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.toList()
        viewModelScope.launch {
            repository.deleteConversations(ids)
            exitSelection()
        }
    }

    /** 清理历代内置预设（不再播种：预设全部由用户自建）。 */
    fun seedPresets() {
        viewModelScope.launch { repository.reconcileBuiltInPresets() }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}

/**
 * 历史栏的大标题：**按时间分组**。
 *
 * 曾按工作区分组（普通 / 内置 / 本机 / 云端）。那是把"我在哪干活"当成了
 * 索引，但抽屉的第一动作是"找回刚才那段对话" —— 用户记得的是"昨天聊的"，
 * 不是"那天绑的是云端"。工作区身份已经由输入框上方那颗 chip 承担，
 * 抽屉里再分一次组，只让列表变成四段长短不一的碎片。
 */
enum class HistoryGroup(@StringRes private val labelRes: Int) {
    /** 置顶。恒在第一位 —— 用户手动钉过的，比任何自动分类都优先。 */
    PINNED(R.string.group_pinned),

    /** 没绑任何工作区的普通对话。排在最上（置顶之后）。 */
    PLAIN(R.string.group_plain),

    /** 本机目录：内置工作区，或用户选的内部存储文件夹。 */
    LOCAL(R.string.group_local),

    /** 云端工作区：HTTP 服务或 SSH 服务器。 */
    CLOUD(R.string.group_cloud),
    ;

    val label: String get() = Res.get(labelRes)
}

/**
 * 会话归到哪一组。
 *
 * 用「当天 0 点」而不是「now - 24h」：凌晨一点看两小时前的对话，人的直觉是
 * "昨天"，滚动窗口会把它留在"今天"，分组标题就开始说谎。
 */
fun groupOf(conversation: ConversationEntity, now: Long = System.currentTimeMillis()): HistoryGroup {
    if (conversation.pinned) return HistoryGroup.PINNED
    /*
     * 分类只看**这段对话当时**用的是哪种工作区，不看现在全局绑的是哪个。
     *
     * `workspaceKind` 在每次发消息时落库（见 ChatViewModel.stampWorkspace），
     * 所以三个月前在项目 A 里做的对话，今天打开也还在"本地工作区"里，
     * 而不是因为你现在切到了别的目录就跳组。
     */
    return when (conversation.workspaceKind) {
        "CLOUD" -> HistoryGroup.CLOUD
        "", "NONE" -> HistoryGroup.PLAIN
        else -> HistoryGroup.LOCAL
    }
}

/**
 * 分组标题下面那行小字：这一组里出现过哪些具体工作区。
 *
 * 本地工作区里可能既有"内置工作区"，也有 `/Download/proj`；只说一句
 * "本地工作区"用户还是不知道哪条对话在哪个目录。列出标签就够了，
 * 不给每条对话加第二行（48dp 的行高是列表可读性的一部分）。
 */
fun groupDetail(items: List<ConversationEntity>, group: HistoryGroup): String? {
    if (group != HistoryGroup.LOCAL && group != HistoryGroup.CLOUD) return null
    val labels = items.map { it.workspaceLabel.trim() }.filter { it.isNotEmpty() }.distinct()
    if (labels.isEmpty()) return null
    return if (labels.size <= 2) {
        labels.joinToString(" · ")
    } else {
        labels.take(2).joinToString(" · ") + " 等 " + labels.size + " 个"
    }
}
