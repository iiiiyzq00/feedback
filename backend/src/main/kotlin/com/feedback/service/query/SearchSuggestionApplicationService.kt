package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import org.springframework.stereotype.Service

/** 搜索建议公开应用用例；HTTP 层不依赖具体组装服务。 */
interface SearchSuggestionQueryUseCase {
    fun suggestions(queryInput: String, limit: Int): SearchSuggestionResponse
}

/**
 * 反馈搜索建议组装用例。
 *
 * 复用安全列表投影的首屏 100 条候选，不建立第二套权限或搜索数据口径。每个渠道最多贡献两条建议；
 * `channelCounts` 只描述候选窗口，`total` 保留列表读取返回的完整匹配数。
 */
@Service
class SearchSuggestionApplicationService(
    private val listReads: FeedbackListQueryUseCase
) : SearchSuggestionQueryUseCase {
    override fun suggestions(queryInput: String, limit: Int): SearchSuggestionResponse {
        val query = queryInput.trim()
        if (query.length < 2) throw ClientException(ErrorCode.BAD_REQUEST, "SEARCH_QUERY_TOO_SHORT")
        if (limit !in 1..20) throw ClientException(ErrorCode.BAD_REQUEST, "SEARCH_LIMIT_INVALID")
        val result = listReads.list(
            FeedbackListQuery(
                common = CommonFeedbackFilters(keyword = query),
                page = FeedbackPageRequest(1, CANDIDATE_LIMIT)
            )
        )
        val rows = result.records
        val counts = rows.groupingBy { it.sourceChannel }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .map { SearchSuggestionChannelCountResponse(it.key, it.value) }
        val items = rows.groupBy { it.sourceChannel }
            .values
            .flatMap { it.take(MAX_ITEMS_PER_CHANNEL) }
            .take(limit)
            .map { row ->
                SearchSuggestionItemResponse(
                    value = row.title,
                    title = row.title,
                    description = row.module,
                    typeLabel = row.sourceChannel.uppercase(),
                    path = "/channel/${row.id}",
                    id = row.id
                )
            }
        return SearchSuggestionResponse(items, counts, result.total, query)
    }

    private companion object {
        const val CANDIDATE_LIMIT = 100
        const val MAX_ITEMS_PER_CHANNEL = 2
    }
}
