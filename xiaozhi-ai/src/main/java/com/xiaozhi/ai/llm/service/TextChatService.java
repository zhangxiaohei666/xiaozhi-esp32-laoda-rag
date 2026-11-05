package com.xiaozhi.ai.llm.service;

import com.xiaozhi.ai.llm.factory.ChatModelFactory;
import com.xiaozhi.ai.llm.memory.ChatMemory;
import com.xiaozhi.ai.llm.memory.Conversation;
import com.xiaozhi.ai.llm.memory.ConversationContext;
import com.xiaozhi.ai.llm.memory.ConversationFactory;
import com.xiaozhi.ai.llm.memory.MessageTimeMetadata;
import com.xiaozhi.ai.tool.ToolRegistrationService;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.ToolsSessionHolder;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.ChatToken;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.utils.DateUtils;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import lombok.extern.slf4j.Slf4j;
/**
 * 纯文本流式对话服务。
 * 负责对话窗口的建立和一轮模型流式调用，不涉及 STT/TTS/Player 等音频组件，也不负责落库。
 */
@Slf4j
@Service
public class TextChatService {

    /** 推给前端的失败原因最长字符数 */
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    @Resource
    private ChatModelFactory chatModelFactory;
    @Resource
    private ConversationFactory conversationFactory;

    /**
     * RAG 检索服务。{@code required = false}：知识库未启用（未配 Qdrant / embedding）时
     * 这个 bean 不存在，网页聊天仍要能正常工作。
     */
    @Resource
    private com.xiaozhi.ai.rag.RetrievalService retrievalService;

    /**
     * 工具注册协调器。设备链路在 PersonaFactory 里用它把工具注册进会话的 ToolsSessionHolder；
     * 网页聊天没有设备会话，需要自己造一个"网页版 ToolSession"再走同一套注册流程，
     * 否则网页端永远调不到图书馆座位查询这类工具。
     */
    @Resource
    private ToolRegistrationService toolRegistrationService;

    /** 全局工具注册表，用来给网页会话建 ToolsSessionHolder */
    @Resource
    private ToolsGlobalRegistry toolsGlobalRegistry;

    /**
     * 建立按 sessionId 加载历史与摘要的对话（新会话为空，续接会拉到历史）。
     *
     * @param ownerId   聊天参与者标识
     * @param userId    用户 ID
     * @param role      角色
     * @param sessionId 会话 ID
     */
    public Conversation openConversation(String ownerId, Integer userId, RoleBO role, String sessionId) {
        return conversationFactory.initSessionConversation(ownerId, userId, role, sessionId);
    }

    /**
     * 流式对话一轮：调用时即取模型并把用户消息放进对话，返回包含思考过程和正式回复的 ChatToken 流。
     * 模型调用失败时以一条 error token 正常结束流；正常完成且正文非空才补上助手消息并回调，
     * 其余情况（报错、取消、空回复）把本轮的用户消息从对话里摘掉。
     *
     * @param conversation    本会话的对话窗口
     * @param role            本轮使用的角色
     * @param userText        用户输入文本
     * @param userCreatedAt   用户消息时间
     * @param onTurnCompleted 一轮完整结束时回调，入参是正文（不含思考过程）与助手消息时间。
     *                        助手消息挂的时间元数据与落库用的必须是同一个：摘要按批里最后一条的时间切分历史，
     *                        两边不一致会让重载时丢掉或重复一段消息
     */
    public Flux<ChatToken> streamTurn(Conversation conversation, RoleBO role, String userText,
                                      LocalDateTime userCreatedAt, BiConsumer<String, LocalDateTime> onTurnCompleted) {
        ChatModel chatModel = chatModelFactory.getChatModel(role);

        // 裸文本 UserMessage + 时间戳 metadata；Conversation 投影层会在送 LLM 前拼出 [时间戳] 文本 的前缀。
        // 无 speaker/emotion，故不挂 MessageMetadataBO。
        Instant userInstant = DateUtils.toInstant(userCreatedAt);
        UserMessage userMessage = new UserMessage(userText);
        MessageTimeMetadata.setTimeMillis(userMessage, userInstant);
        conversation.add(userMessage);

        // 文本聊天无位置
        // ⚠️ 必须包一层 new ArrayList<>：conversation.messages() 返回的是投影后的只读视图，
        //    直接 add 会抛 UnsupportedOperationException。
        List<Message> messages = new ArrayList<>(conversation.messages(ConversationContext.EMPTY));

        // ── RAG 注入 ──────────────────────────────────────────────────────────
        // 与设备链路（Persona.chatStream）保持一致的三个要点：
        //   ① 必须带 userId —— 检索只返回「本人的私有资料 + 所有人共享的资料」；
        //      不带 userId 会退化成"只查共享"甚至"不过滤"（多用户串数据）。
        //   ② 注入失败绝不能影响对话 —— 整段用 try-catch 兜住，失败就当没参考资料。
        //   ③ 插在 messages[1]（系统提示词之后、历史之前），与 Persona 位置一致。
        //
        // 历史背景：网页聊天此前完全没有 RAG（TextChatService 里 search 不到任何 rag 字样），
        // 同一个问题「图书馆几点开门」在 ESP32 上有答案、在网页里模型答"不知道"，
        // 两个入口能力不一致。
        try {
            if (retrievalService != null) {
                String injection = retrievalService.buildInjection(userText, conversation.getUserId());
                if (injection != null && !injection.isBlank()) {
                    messages.add(1, new SystemMessage(injection));
                }
            }
        } catch (Exception ragError) {
            log.warn("网页聊天 RAG 注入失败，本轮按无参考资料处理: {}", ragError.getMessage());
        }

        // ── 工具注册（Function Calling）────────────────────────────────────────
        // 设备链路在 PersonaFactory 里做这件事：toolRegistrationService.register(设备会话)，
        // 工具被注册进会话的 ToolsSessionHolder，Persona 再从那里取出来塞进 ChatOptions。
        // 网页聊天不经过 PersonaFactory，所以历史上网页端调不到任何工具
        // （问"图书馆还有多少空座位"会答"查不到实时的"）。
        //
        // 这里的做法：给网页会话造一个 ToolSession + ToolsSessionHolder，走同一套注册流程。
        // 关键取舍：
        //   · DeviceBO 传 null —— ToolsSessionHolder 只是把它存起来，没有任何方法读它
        //   · getDeviceId() 返回 null —— ChangeRoleFunction 会拿它去 sessionManager 查会话，
        //     查不到就自己 return null（该工具在网页端自动不注册），不会 NPE
        //   · 依赖会话状态的方法（addToolCallDetail / sendTextMessage / setToolCalling）做成空实现，
        //     图书馆座位这类无状态工具用不到它们
        List<ToolCallback> availableTools = new ArrayList<>();
        try {
            availableTools = collectWebChatTools(conversation, role);
        } catch (Exception toolError) {
            log.warn("网页聊天工具注册失败，本轮按无工具处理: {}", toolError.getMessage());
        }

        Prompt prompt;
        if (availableTools.isEmpty()) {
            prompt = new Prompt(messages);
        } else {
            ChatOptions chatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(availableTools)
                    .toolContext("conversationTimestamp", userInstant)
                    .build();
            prompt = new Prompt(messages, chatOptions);
            log.debug("网页聊天本轮可用工具 {} 个: {}", availableTools.size(),
                    availableTools.stream().map(t -> t.getToolDefinition().name()).toList());
        }

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean turnCompleted = new AtomicBoolean(false);
        AtomicReference<Usage> usage = new AtomicReference<>();

        return toChatTokens(chatModel.stream(prompt).doOnNext(response -> {
                    // 流式用量一般只在最后一块返回，只留带输入 token 的那块
                    Usage chunkUsage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
                    if (chunkUsage != null && chunkUsage.getPromptTokens() != null && chunkUsage.getPromptTokens() > 0) {
                        usage.set(chunkUsage);
                    }
                }))
                .doOnNext(token -> {
                    // 只累积正式回复内容，思考过程不持久化
                    if (token.isContent()) {
                        fullResponse.append(token.text());
                    }
                })
                // 必须在 onErrorResume 之前：之后的完成信号可能来自错误恢复，半截正文不能算一轮
                .doOnComplete(() -> {
                    if (fullResponse.isEmpty()) {
                        return;
                    }
                    String reply = fullResponse.toString();
                    LocalDateTime assistantCreatedAt = DateUtils.now();
                    // 挂上本轮用量，对话据此判断上下文是否超限
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .content(reply)
                            .properties(usage.get() != null ? Map.of(ChatMemory.USAGE_KEY, usage.get()) : Map.of())
                            .build();
                    MessageTimeMetadata.setTimeMillis(assistantMessage,
                            DateUtils.toInstant(assistantCreatedAt));
                    conversation.add(assistantMessage);
                    turnCompleted.set(true);
                    onTurnCompleted.accept(reply, assistantCreatedAt);
                })
                .doOnError(e -> log.error("文本聊天流式响应失败: sessionId={}", conversation.sessionId(), e))
                // 失败原因以 error token 推给前端，不混进正文，也不会被当成助手回复记进对话
                .onErrorResume(e -> Flux.just(ChatToken.error(describeFailure(e))))
                .doFinally(signalType -> {
                    // 正常完成且配上 AssistantMessage 才算一轮完整对话；客户端中途 abort、
                    // LLM 报错、或者拿到空回复，都要把孤立的 UserMessage 摘掉，
                    // 否则下一轮拼 Prompt 时会带着这条没有回复的历史消息，污染上下文。
                    if (!turnCompleted.get()) {
                        conversation.remove(userMessage);
                    }
                });
    }

    /**
     * 将 ChatResponse 流转换为 ChatToken 流，包含思考内容和正式回复。
     * 同一块里思考内容在前、正文在后；没有 result 或 output 的块直接跳过。
     * <p>
     * Spring AI 1.1.0+ 中，启用 reasoningEffort 后，推理内容通过
     * {@code AssistantMessage.getProperties().get("reasoningContent")} 返回。
     */
    public static Flux<ChatToken> toChatTokens(Flux<ChatResponse> responses) {
        return responses.mapNotNull(ChatResponse::getResult)
                .mapNotNull(Generation::getOutput)
                .flatMap(message -> {
                    List<ChatToken> tokens = new ArrayList<>();
                    Object reasoning = message.getMetadata().get("reasoningContent");
                    if (reasoning instanceof String r && !r.isEmpty()) {
                        tokens.add(ChatToken.thinking(r));
                    }
                    String text = message.getText();
                    if (text != null && !text.isEmpty()) {
                        tokens.add(ChatToken.content(text));
                    }
                    return Flux.fromIterable(tokens);
                });
    }

    /**
     * 取模型调用失败的原因给前端展示：服务商返回了错误响应时带上状态码与响应体，
     * 否则取异常链最底层的描述；过长时截断，避免整页 HTML 错误页灌进聊天气泡。
     */
    static String describeFailure(Throwable error) {
        String deepestMessage = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException webError) {
                return truncateFailureReason(
                        "HTTP " + webError.getStatusCode().value() + " " + webError.getResponseBodyAsString());
            }
            if (t instanceof RestClientResponseException restError) {
                return truncateFailureReason(
                        "HTTP " + restError.getStatusCode().value() + " " + restError.getResponseBodyAsString());
            }
            if (StringUtils.hasText(t.getMessage())) {
                deepestMessage = t.getMessage();
            }
        }
        return truncateFailureReason(deepestMessage != null ? deepestMessage : error.getClass().getSimpleName());
    }

    private static String truncateFailureReason(String reason) {
        String trimmed = reason.strip();
        return trimmed.length() <= MAX_FAILURE_REASON_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_FAILURE_REASON_LENGTH) + "…";
    }

    // ========================================================================
    // 工具支持：给网页聊天造一个 ToolSession，走与设备链路相同的注册流程
    // ========================================================================

    /**
     * 收集本轮网页聊天可用的工具。
     *
     * <p>流程与设备链路一致（{@code PersonaFactory} 里的做法）：
     * <ol>
     *   <li>建一个 {@link ToolsSessionHolder}（工具最终落在这里）</li>
     *   <li>建一个 {@link ToolSession} 包住它</li>
     *   <li>调 {@link ToolRegistrationService#register} 让各个 registrar 去填</li>
     *   <li>从 holder 取出来</li>
     * </ol>
     *
     * <p>为什么不直接调 {@code toolsGlobalRegistry.getAllFunctions(...)}：
     * 那个方法内部是 {@code forEach} 且没有 try-catch，任何一个工具在
     * {@code getFunctionCallTool} 里抛异常都会让整批工具注册失败。
     * 走 {@code ToolRegistrationService} 的话，每个 registrar 的失败都被独立 catch，
     * 能拿到"能注册的那部分"。
     */
    private List<ToolCallback> collectWebChatTools(Conversation conversation, RoleBO role) {
        if (toolRegistrationService == null || toolsGlobalRegistry == null) {
            return new ArrayList<>();
        }
        String sessionId = conversation.getSessionId();
        Integer roleId = role != null ? role.getRoleId() : conversation.getRoleId();

        // DeviceBO 传 null：ToolsSessionHolder 只是把它存起来，没有任何方法读
        ToolsSessionHolder holder = new ToolsSessionHolder(sessionId, null, toolsGlobalRegistry);
        ToolSession webChatSession = new WebChatToolSession(sessionId, roleId, holder);

        toolRegistrationService.register(webChatSession);
        return holder.getAllFunction();
    }

    /**
     * 网页聊天场景的 {@link ToolSession} 实现。
     *
     * <p>设备链路里的实现（{@code ChatSessionToolAdapter}）背后有一整套 WebSocket 会话、
     * 播放器状态、设备 MCP 通道；网页聊天这些都没有，所以：
     * <ul>
     *   <li>{@code getDeviceId()} 返回 {@code null} —— 依赖设备身份的工具
     *       （如换角色）在查不到会话时会自己 return null，等于自动不注册，不会抛异常</li>
     *   <li>与会话副作用相关的方法全部空实现 —— 网页聊天的工具调用结果直接随流返回，
     *       不需要写回会话历史或播放器</li>
     * </ul>
     */
    private static final class WebChatToolSession implements ToolSession {

        private final String sessionId;
        private final Integer roleId;
        private final ToolsSessionHolder holder;

        private WebChatToolSession(String sessionId, Integer roleId, ToolsSessionHolder holder) {
            this.sessionId = sessionId;
            this.roleId = roleId;
            this.holder = holder;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public Integer getRoleId() {
            return roleId;
        }

        @Override
        public String getDeviceId() {
            // 网页聊天没有设备。依赖设备身份的工具会因此自动跳过
            return null;
        }

        @Override
        public ToolsSessionHolder getToolsSessionHolder() {
            return holder;
        }

        @Override
        public boolean isDeviceMcpInitialized() {
            // 网页聊天不连设备 MCP
            return false;
        }

        @Override
        public void addToolCallDetail(Long turnId, String name, String args, String result) {
            // 网页聊天的工具调用记录随流式响应返回，不写回会话
        }

        @Override
        public void addToolCallMessages(Long turnId,
                                        AssistantMessage toolCallAssistantMessage,
                                        ToolResponseMessage toolResponseMessage) {
            // 同上：不注入会话历史
        }

        @Override
        public void sendTextMessage(String message) {
            // 网页没有独立文本通道
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void setToolCalling(boolean calling) {
            // 网页没有播放器，不需要防止提前 sendStop
        }
    }
}
