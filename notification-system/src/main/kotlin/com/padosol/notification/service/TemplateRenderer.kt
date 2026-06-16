package com.padosol.notification.service

import com.padosol.notification.domain.Template
import com.padosol.notification.provider.RenderedMessage
import org.springframework.stereotype.Component

/** `{{key}}` 자리표시자를 params 로 치환한다(설계 §6 템플릿 렌더링). */
@Component
class TemplateRenderer {

    private val placeholder = Regex("""\{\{\s*(\w+)\s*}}""")

    fun render(template: Template, params: Map<String, String>): RenderedMessage {
        return RenderedMessage(
            subject = template.subject?.let { substitute(it, params) },
            body = substitute(template.body, params),
        )
    }

    private fun substitute(text: String, params: Map<String, String>): String =
        placeholder.replace(text) { match -> params[match.groupValues[1]] ?: match.value }
}
