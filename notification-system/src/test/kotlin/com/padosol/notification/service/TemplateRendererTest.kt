package com.padosol.notification.service

import com.padosol.notification.domain.Template
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 템플릿 렌더링은 순수 함수 — subject/body 치환과 미존재 키 처리(F4 분기 보강). */
class TemplateRendererTest {

    private val renderer = TemplateRenderer()

    @Test
    fun `subject 와 body 를 모두 치환한다`() {
        val template = Template(templateId = "T", category = "c", body = "hi {{name}}", subject = "re: {{name}}")
        val message = renderer.render(template, mapOf("name" to "Kim"))
        assertEquals("re: Kim", message.subject)
        assertEquals("hi Kim", message.body)
    }

    @Test
    fun `없는 키는 자리표시자를 그대로 둔다`() {
        val message = renderer.render(Template(templateId = "T", category = "c", body = "{{missing}}"), emptyMap())
        assertEquals("{{missing}}", message.body)
    }
}
