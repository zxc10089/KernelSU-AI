package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONObject

/**
 * Family ⑧ 人机协同 — the model asking the user a question.
 *
 * `request_user_input` is a real tool with a real envelope, but it is honest about its limits: the
 * data layer has no UI channel, so it returns the question with `answered=false` and the console
 * renders it as an input card. The model must not treat that as an answer.
 *
 * The other four names in this family (`propose_action`, `request_batch_approval`, `update_plan_step`,
 * `rollback_action`) are deliberately *not* tools: they are how the client already works, and exposing
 * them to the model would let it talk about the queue instead of putting things in it.
 */
object AiInteractTools {

    suspend fun requestUserInput(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val question = args.str("question").trim()
        if (question.isEmpty()) return toolFailed(TOOL_INPUT, "empty_question")
        val options = args.strList("options")
        val allowFreeText = args.bool("allow_free_text", true)
        val data = JSONObject()
            .put("question", question)
            .put("options", jsonArray(options))
            .put("allowFreeText", allowFreeText)
            .put("answered", false)
        return toolOutcome(
            TOOL_INPUT,
            true,
            data = data,
            message = "问题已提交给用户，本轮不会得到答案；请先总结当前进展并等待用户回答。"
        )
    }

    private const val TOOL_INPUT = "request_user_input"
}
