package com.pollen.rag.chat;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PromptService {

    private static final RowMapper<PromptTemplate> MAPPER = (rs, rowNum) -> new PromptTemplate(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getLong("kb_id"),
            rs.getString("name"),
            rs.getString("system_prompt"),
            rs.getString("user_prompt"),
            rs.getInt("version"),
            rs.getBoolean("enabled")
    );

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是企业内部知识库助手。请严格基于【参考资料】回答问题。

            规则：
            1. 只能使用参考资料中的信息，不得使用常识补充、不得推测。
            2. 每个结论后必须标注来源编号，格式 [1]、[2]；多条来源写作 [1][2]。
            3. 若参考资料不足以回答，直接回复"根据现有资料无法确定"，并说明缺少什么信息，禁止编造。
            4. 回答使用简体中文，先给结论，再给依据；涉及流程/步骤时用有序列表。
            5. 不要复述参考资料原文超过 3 句，需用自己的话归纳。

            【知识库】{{kb_name}}

            【参考资料】
            {{context}}

            【历史对话】
            {{history}}""";

    private final JdbcTemplate jdbc;

    public PromptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PromptTemplate get(String code, long kbId) {
        List<PromptTemplate> rows = jdbc.query("""
                SELECT * FROM prompt_template
                 WHERE code = ? AND enabled = TRUE AND (kb_id = ? OR kb_id = 0)
                 ORDER BY kb_id DESC, version DESC
                 LIMIT 1
                """, MAPPER, code, kbId);
        return rows.stream().findFirst().orElseGet(() -> defaultTemplate(code, kbId));
    }

    public PromptTemplate findById(long id) {
        return jdbc.query("SELECT * FROM prompt_template WHERE id = ?", MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Prompt 模板不存在: " + id));
    }

    public List<PromptTemplate> list(long kbId) {
        return jdbc.query("""
                SELECT * FROM prompt_template
                 WHERE kb_id = ? OR kb_id = 0
                 ORDER BY code, kb_id DESC, version DESC
                """, MAPPER, kbId);
    }

    public PromptTemplate update(long id, String name, String systemPrompt, String userPrompt) {
        PromptTemplate current = findById(id);
        int nextVersion = nextVersion(current.code(), current.kbId());
        jdbc.update("""
                UPDATE prompt_template SET enabled = FALSE
                 WHERE code = ? AND kb_id = ? AND enabled = TRUE
                """, current.code(), current.kbId());
        Long newId = jdbc.queryForObject("""
                INSERT INTO prompt_template (code, kb_id, name, system_prompt, user_prompt, version, enabled)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                RETURNING id
                """, Long.class, current.code(), current.kbId(),
                name == null || name.isBlank() ? current.name() : name,
                systemPrompt, userPrompt, nextVersion);
        return findById(newId == null ? id : newId);
    }

    public Map<String, String> preview(long id, String context, String question, String history, String kbName) {
        PromptTemplate tpl = findById(id);
        String safeContext = context == null || context.isBlank() ? "[1] 来源：示例文档\\n这是示例参考资料。" : context;
        String safeHistory = history == null || history.isBlank() ? "（无）" : history;
        String safeQuestion = question == null || question.isBlank() ? "示例问题" : question;
        String safeKbName = kbName == null || kbName.isBlank() ? "示例知识库" : kbName;
        return Map.of(
                "system", renderSystem(tpl, safeContext, safeHistory, safeKbName, safeQuestion),
                "user", renderUser(tpl, safeQuestion)
        );
    }

    public String renderSystem(PromptTemplate template, String context, String history,
                               String kbName, String question) {
        return template.systemPrompt()
                .replace("{{context}}", context)
                .replace("{{history}}", history)
                .replace("{{kb_name}}", kbName)
                .replace("{{question}}", question)
                .replace("{{citation_rule}}", "每个结论后必须标注来源编号，如 [1][2]。");
    }

    public String renderUser(PromptTemplate template, String question) {
        return template.userPrompt().replace("{{question}}", question);
    }

    private int nextVersion(String code, long kbId) {
        Integer version = jdbc.queryForObject("""
                SELECT coalesce(max(version), 0) + 1 FROM prompt_template WHERE code = ? AND kb_id = ?
                """, Integer.class, code, kbId);
        return version == null ? 1 : version;
    }

    private PromptTemplate defaultTemplate(String code, long kbId) {
        return new PromptTemplate(0, code, 0, "内置默认模板",
                DEFAULT_SYSTEM_PROMPT, "{{question}}", 1, true);
    }
}
