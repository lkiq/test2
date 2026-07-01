package com.xuelian.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuelian.career.dto.response.InterviewReportResponse;
import com.xuelian.career.dto.response.InterviewSession;
import com.xuelian.career.entity.InterviewRecord;
import com.xuelian.career.entity.JobPosition;
import com.xuelian.career.mapper.InterviewRecordMapper;
import com.xuelian.career.mapper.JobPositionMapper;
import com.xuelian.career.service.DeepSeekService;
import com.xuelian.career.service.InterviewService;
import com.xuelian.career.util.PromptTemplateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 模拟面试服务实现 - DeepSeek AI 生成题目与评估 + 本地题库兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    private final InterviewRecordMapper recordMapper;
    private final JobPositionMapper jobPositionMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DeepSeekService deepSeekService;
    private final PromptTemplateUtil promptUtil;
    private final ObjectMapper objectMapper;

    private static final String SESSION_PREFIX = "interview:session:";
    /** 题型分组常量 */
    private static final String TYPE_TECHNICAL = "技术基础";
    private static final String TYPE_PROJECT = "项目经验";
    private static final String TYPE_BEHAVIORAL = "行为面试";
    private static final String TYPE_SCENARIO = "情景模拟";
    private static final String TYPE_SYSTEM_DESIGN = "系统设计";

    /**
     * 分组题库 — AI 不可用时按题型智能选取，避免重复和维度集中
     * 每组至少2道题，支持最多8道面试的多样性需求
     */
    private static final Map<String, List<Map<String, String>>> QUESTION_POOL = Map.of(
            TYPE_TECHNICAL, List.of(
                    Map.of("q", "请解释Java中HashMap的底层实现原理，包括JDK 1.7和1.8的区别", "type", TYPE_TECHNICAL),
                    Map.of("q", "请描述Spring Boot自动装配的核心原理，谈谈你对starter机制的理解", "type", TYPE_TECHNICAL),
                    Map.of("q", "请对比说明Redis几种常用数据结构的使用场景及底层数据结构的优劣势", "type", TYPE_TECHNICAL)
            ),
            TYPE_PROJECT, List.of(
                    Map.of("q", "请描述一个你参与过的最具挑战性的项目，重点说明你在其中承担的角色和技术难点", "type", TYPE_PROJECT),
                    Map.of("q", "请分享一次你在项目中解决复杂技术问题的完整过程，包括问题定位、方案选型和最终效果", "type", TYPE_PROJECT)
            ),
            TYPE_BEHAVIORAL, List.of(
                    Map.of("q", "请描述一次你与团队成员在技术方案上产生分歧的经历，你是如何达成共识的？", "type", TYPE_BEHAVIORAL),
                    Map.of("q", "面对紧迫的项目工期和技术债务并存的情况，你会如何平衡和决策？请举例说明", "type", TYPE_BEHAVIORAL)
            ),
            TYPE_SCENARIO, List.of(
                    Map.of("q", "假设线上服务突然出现大量超时报警，请描述你的完整排查思路和应急处理流程", "type", TYPE_SCENARIO),
                    Map.of("q", "如果让你设计一个日活千万的用户画像系统，你会如何进行技术选型和架构设计？", "type", TYPE_SCENARIO)
            ),
            TYPE_SYSTEM_DESIGN, List.of(
                    Map.of("q", "请设计一个支持高并发秒杀活动的系统架构，涵盖流量削峰、库存扣减和数据一致性保障", "type", TYPE_SYSTEM_DESIGN),
                    Map.of("q", "假设需要将单体应用拆分为微服务架构，请说明拆分策略、服务通信方式和数据迁移方案", "type", TYPE_SYSTEM_DESIGN)
            )
    );

    /**
     * 方向专用技术题库 — 按岗位方向区分，AI 不可用时根据 jobDirection 选取对应方向的技术基础题
     * 支持的 direction：后端开发、前端开发、数据分析、产品经理、测试
     * 未匹配的方向默认使用"后端开发"题库
     */
    private static final Map<String, List<Map<String, String>>> DIRECTION_TECHNICAL_POOL = Map.of(
            "后端开发", List.of(
                    Map.of("q", "请解释Java中HashMap的底层实现原理，包括JDK 1.7和1.8的区别", "type", TYPE_TECHNICAL),
                    Map.of("q", "请描述Spring Boot自动装配的核心原理，谈谈你对starter机制的理解", "type", TYPE_TECHNICAL),
                    Map.of("q", "请对比说明Redis几种常用数据结构的使用场景及底层数据结构的优劣势", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明数据库事务的ACID特性，以及MySQL的InnoDB引擎是如何实现这些特性的", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释线程池的核心参数及其工作原理，什么情况下会触发拒绝策略", "type", TYPE_TECHNICAL)
            ),
            "前端开发", List.of(
                    Map.of("q", "请解释Vue 3的响应式原理，以及虚拟DOM的diff算法是如何工作的", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明浏览器渲染页面的完整流程，包括重排和重绘的触发条件及优化方法", "type", TYPE_TECHNICAL),
                    Map.of("q", "请对比说明React和Vue在设计理念、响应式机制和生态圈方面的主要差异", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明前端性能优化的常用手段，并举例说明如何减少首屏加载时间", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释跨域问题的产生原因及常见的解决方案，CORS的配置细节是什么", "type", TYPE_TECHNICAL)
            ),
            "数据分析", List.of(
                    Map.of("q", "请说明SQL中JOIN的几种类型及其使用场景，如何优化慢查询", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释数据清洗的常见步骤，以及如何处理缺失值和异常值", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明A/B测试的设计原理，如何判断实验结果的统计显著性", "type", TYPE_TECHNICAL),
                    Map.of("q", "请对比说明Python中Pandas的DataFrame与SQL表的概念异同，各适合什么场景", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明常见机器学习算法（如决策树、逻辑回归）的基本原理及适用场景", "type", TYPE_TECHNICAL)
            ),
            "产品经理", List.of(
                    Map.of("q", "请说明产品需求优先级的常用排序方法（如RICE、KANO模型）及其适用场景", "type", TYPE_TECHNICAL),
                    Map.of("q", "请描述一个完整的功能迭代流程，从需求采集到上线后的数据验证", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明如何撰写一份高质量的产品需求文档（PRD），应包含哪些核心要素", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释用户体验（UX）与用户界面（UI）的区别，并说明在产品设计中如何平衡两者", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明如何定义和评估产品的核心指标（North Star Metric），举例说明", "type", TYPE_TECHNICAL)
            ),
            "测试", List.of(
                    Map.of("q", "请说明软件测试的常见类型（单元测试、集成测试、系统测试）及其侧重点", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释黑盒测试与白盒测试的区别，并各举一个实际案例", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明如何设计一个高效的测试用例，需要覆盖哪些维度", "type", TYPE_TECHNICAL),
                    Map.of("q", "请解释自动化测试的适用场景，以及常用的自动化测试框架有哪些", "type", TYPE_TECHNICAL),
                    Map.of("q", "请说明BUG的生命周期管理流程，以及如何进行BUG的优先级和严重程度划分", "type", TYPE_TECHNICAL)
            )
    );

    /**
     * 方向专用系统设计题库 — 仅后端开发方向有专用题，其他方向使用通用题
     */
    private static final Map<String, List<Map<String, String>>> DIRECTION_SYSTEM_DESIGN_POOL = Map.of(
            "后端开发", List.of(
                    Map.of("q", "请设计一个支持高并发秒杀活动的系统架构，涵盖流量削峰、库存扣减和数据一致性保障", "type", TYPE_SYSTEM_DESIGN),
                    Map.of("q", "假设需要将单体应用拆分为微服务架构，请说明拆分策略、服务通信方式和数据迁移方案", "type", TYPE_SYSTEM_DESIGN),
                    Map.of("q", "请设计一个分布式文件存储系统，需要考虑存储扩容、数据冗余和访问性能", "type", TYPE_SYSTEM_DESIGN)
            ),
            "前端开发", List.of(
                    Map.of("q", "请设计一个大型前端应用的状态管理方案，如何处理组件间通信和全局状态", "type", TYPE_SYSTEM_DESIGN),
                    Map.of("q", "请设计一个支持多语言、多主题的前端工程化方案，需要考虑哪些关键点", "type", TYPE_SYSTEM_DESIGN)
            ),
            "通用", List.of(
                    Map.of("q", "请描述你如何设计一个团队协作的工作流程，需要考虑任务分配、进度跟踪和质量保障", "type", TYPE_SYSTEM_DESIGN),
                    Map.of("q", "请说明在资源有限的情况下，如何设计一个MVP（最小可行产品）并规划后续迭代", "type", TYPE_SYSTEM_DESIGN)
            )
    );

    /** 方向题库的默认兜底方向 */
    private static final String DEFAULT_DIRECTION = "后端开发";

    /** 题型轮换顺序（优先级从高到低） */
    private static final List<String> QUESTION_TYPE_ORDER = List.of(
            TYPE_PROJECT, TYPE_TECHNICAL, TYPE_SCENARIO, TYPE_BEHAVIORAL, TYPE_SYSTEM_DESIGN
    );

    /** 本地题库每道题的关键词列表（用于兜底评分的关键词匹配，按题型分组索引） */
    private static final Map<String, List<String>> TYPE_KEYWORDS = Map.of(
            TYPE_TECHNICAL, List.of("原理", "底层", "实现", "机制", "源码", "JVM", "并发", "线程",
                    "集合", "框架", "Spring", "Redis", "数据库", "SQL", "缓存", "分布式"),
            TYPE_PROJECT, List.of("项目", "挑战", "方案", "解决", "技术", "团队", "成果", "难点",
                    "优化", "架构", "设计", "交付", "迭代", "需求", "上线"),
            TYPE_BEHAVIORAL, List.of("团队", "沟通", "协作", "分歧", "共识", "冲突", "领导",
                    "规划", "目标", "决策", "平衡", "优先级", "反馈"),
            TYPE_SCENARIO, List.of("排查", "日志", "监控", "性能", "负载", "故障", "报警",
                    "应急", "线上", "优化", "CPU", "内存", "慢查询", "瓶颈", "扩容"),
            TYPE_SYSTEM_DESIGN, List.of("架构", "设计", "高并发", "微服务", "分布式", "一致性",
                    "容灾", "削峰", "限流", "消息队列", "分库分表", "服务治理")
    );

    /** 每种题型到评估维度的权重映射 */
    private static final Map<String, Map<String, Double>> TYPE_DIMENSION_WEIGHTS = Map.of(
            TYPE_TECHNICAL, Map.of("logic", 0.35, "professionalism", 0.40, "communication", 0.05, "adaptability", 0.10, "jobFit", 0.10),
            TYPE_PROJECT, Map.of("logic", 0.20, "professionalism", 0.35, "communication", 0.15, "adaptability", 0.15, "jobFit", 0.15),
            TYPE_BEHAVIORAL, Map.of("logic", 0.10, "professionalism", 0.10, "communication", 0.40, "adaptability", 0.25, "jobFit", 0.15),
            TYPE_SCENARIO, Map.of("logic", 0.30, "professionalism", 0.25, "communication", 0.10, "adaptability", 0.30, "jobFit", 0.05),
            TYPE_SYSTEM_DESIGN, Map.of("logic", 0.35, "professionalism", 0.35, "communication", 0.05, "adaptability", 0.15, "jobFit", 0.10)
    );

    @Override
    public InterviewSession startInterview(Long userId, Long targetJobId, String interviewType) {
        String sessionId = UUID.randomUUID().toString();
        String key = SESSION_PREFIX + sessionId;
        int totalQuestions = calculateQuestionCount(targetJobId);

        // 查出岗位方向，存入 session，供本地题库选题时使用
        String jobDirection = "通用";
        if (targetJobId != null) {
            JobPosition job = jobPositionMapper.selectById(targetJobId);
            if (job != null && job.getDirection() != null && !job.getDirection().isEmpty()) {
                jobDirection = job.getDirection();
            }
        }

        Map<String, Object> session = new HashMap<>();
        session.put("userId", userId);
        session.put("targetJobId", targetJobId);
        session.put("jobDirection", jobDirection);
        session.put("interviewType", interviewType);
        session.put("questionIndex", 0);
        session.put("totalQuestions", totalQuestions);
        session.put("followUpCount", 0);
        session.put("maxFollowUp", 1);
        session.put("mainQuestionIndex", 0);
        session.put("usedQuestionTypes", new ArrayList<String>());
        session.put("answers", new ArrayList<Map<String, String>>());
        session.put("answeredCount", 0);
        session.put("allGeneratedQuestions", new ArrayList<String>());
        session.put("currentQuestionChain", new ArrayList<Map<String, String>>());
        session.put("stage", "FIRST_QUESTION");
        session.put("questionStartTime", System.currentTimeMillis());
        redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);

        // 尝试用 DeepSeek 生成第一道题
        try {
            if (deepSeekService.isAvailable()) {
                QuestionResult qr = callAIForQuestion(session, null, null);
                if (qr != null) {
                    session.put("aiCurrentQuestion", qr.question);
                    session.put("questionStartTime", System.currentTimeMillis());
                    redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
                    return InterviewSession.builder()
                            .sessionId(sessionId)
                            .question(qr.question)
                            .questionType(qr.questionType)
                            .questionIndex(1)
                            .totalQuestions(totalQuestions)
                            .mainQuestionIndex(1)
                            .isFollowUp(false)
                            .answeredCount(0)
                            .finished(false)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("AI 出题失败，使用本地题库: {}", e.getMessage());
        }

        // AI 不可用时使用智能兜底选题
        Map<String, String> fallbackQ = selectSmartFallback(session, null);
        session.put("aiCurrentQuestion", fallbackQ.get("q"));
        List<String> allGenerated = (List<String>) session.computeIfAbsent("allGeneratedQuestions", k -> new ArrayList<>());
        allGenerated.add(fallbackQ.get("q"));
        session.put("allGeneratedQuestions", allGenerated);
        redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
        return InterviewSession.builder()
                .sessionId(sessionId)
                .question(fallbackQ.get("q"))
                .questionType(fallbackQ.get("type"))
                .questionIndex(1)
                .totalQuestions(totalQuestions)
                .mainQuestionIndex(1)
                .isFollowUp(false)
                .answeredCount(0)
                .finished(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public InterviewSession submitAnswer(Long userId, String sessionId, String answer) {
        String key = SESSION_PREFIX + sessionId;
        Map<String, Object> session = (Map<String, Object>) redisTemplate.opsForValue().get(key);
        if (session == null) return null;

        // 获取当前题目信息（惰性求值，避免 getLocalQuestion 的副作用）
        boolean hasAiQuestion = session.containsKey("aiCurrentQuestion");
        String currentQuestion = hasAiQuestion
                ? (String) session.get("aiCurrentQuestion")
                : getLocalQuestion((Integer) session.get("questionIndex"), session);

        // 判断当前是否为追问
        int followUpCount = (int) session.getOrDefault("followUpCount", 0);
        boolean isCurrentFollowUp = followUpCount > 0;

        // 获取答题开始时间，计算耗时
        Long questionStartTime = (Long) session.get("questionStartTime");
        long elapsedMs = (questionStartTime != null) ? System.currentTimeMillis() - questionStartTime : 0;

        List<Map<String, String>> answers = (List<Map<String, String>>) session.get("answers");
        int currentIndex = ((Number) session.get("questionIndex")).intValue();
        int totalQuestions = (int) session.getOrDefault("totalQuestions", 5);

        // 记录答案和耗时（含 isFollowUp 标记）
        Map<String, String> answerRecord = new LinkedHashMap<>();
        answerRecord.put("question", currentQuestion);
        answerRecord.put("answer", answer);
        answerRecord.put("elapsedMs", String.valueOf(elapsedMs));
        if (isCurrentFollowUp) {
            answerRecord.put("isFollowUp", "true");
        }
        answers.add(answerRecord);
        session.put("answers", answers);

        // ======== 递增已答题数（含追问） ========
        int answeredCount = (int) session.getOrDefault("answeredCount", 0);
        answeredCount++;
        session.put("answeredCount", answeredCount);

        // ======== 维护 currentQuestionChain 追问链 ========
        List<Map<String, String>> chain = (List<Map<String, String>>) session.get("currentQuestionChain");
        if (chain == null) {
            chain = new ArrayList<>();
            session.put("currentQuestionChain", chain);
        }
        // 追加当前回答到追问链
        Map<String, String> answerEntry = new LinkedHashMap<>();
        answerEntry.put("role", isCurrentFollowUp ? "follow_up_answer" : "answer");
        answerEntry.put("content", answer);
        chain.add(answerEntry);

        int mainQIndex = ((Number) session.getOrDefault("mainQuestionIndex", 0)).intValue();

        // ======== FOLLOW_UP 追问逻辑 ========
        int maxFollowUp = (int) session.getOrDefault("maxFollowUp", 2);

        // 最后一题守卫：当前已是最后一道主问题时跳过追问，直接结束面试
        boolean isLastQuestion = (mainQIndex + 1) >= totalQuestions;
        log.info("【DEBUG最后一题守卫】mainQIndex={}, totalQuestions={}, isLastQuestion={}, followUpCount={}, maxFollowUp={}",
                 mainQIndex, totalQuestions, isLastQuestion, followUpCount, maxFollowUp);
        if (isLastQuestion) {
            log.info("最后一题(mainQIndex={}/{})，跳过追问，准备结束面试", mainQIndex + 1, totalQuestions);
        }

        if (followUpCount < maxFollowUp && !isLastQuestion) {
            // ⚠️ 本地回答质量检测前置：不达标则跳过追问，零 AI 调用
            if (!isAnswerSubstantial(answer)) {
                log.info("isAnswerSubstantial=false，跳过追问，下一题继续走AI出题");
            } else {
                try {
                    if (deepSeekService.isAvailable()) {
                        FollowUpResult fur = callAIForFollowUp(session, answer, currentQuestion, followUpCount);
                        if (fur != null && "FOLLOW_UP".equals(fur.action)) {
                            followUpCount++;
                            session.put("followUpCount", followUpCount);
                            session.put("aiCurrentQuestion", fur.question);
                            session.put("questionStartTime", System.currentTimeMillis());

                            // 追加追问问题到链
                            Map<String, String> fuEntry = new LinkedHashMap<>();
                            fuEntry.put("role", "follow_up_question");
                            fuEntry.put("content", fur.question);
                            chain.add(fuEntry);
                            session.put("currentQuestionChain", chain);

                            redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
                            return InterviewSession.builder()
                                    .sessionId(sessionId)
                                    .question(fur.question)
                                    .questionType("追问")
                                    .questionIndex(mainQIndex)
                                    .totalQuestions(totalQuestions)
                                    .mainQuestionIndex(mainQIndex)
                                    .isFollowUp(true)
                                    .feedback(fur.feedback)
                                    .answeredCount(answeredCount)
                                    .finished(false)
                                    .build();
                        }
                    }
                } catch (Exception e) {
                    log.warn("AI 追问失败: {}", e.getMessage());
                }
            }
        }

        // ======== 重置追问计数 + 追问链，进入下一题 ========
        session.put("followUpCount", 0);
        chain.clear();
        session.put("currentQuestionChain", chain);
        int nextIndex = currentIndex + 1;
        session.put("questionIndex", nextIndex);
        int nextMainQIndex = mainQIndex + 1;
        session.put("mainQuestionIndex", nextMainQIndex);

        // 如果还有题，用 AI 出下一道
        if (nextMainQIndex < totalQuestions) {
                try {
                    if (deepSeekService.isAvailable()) {
                        QuestionResult qr = callAIForQuestion(session, answer, currentQuestion);
                        if (qr != null) {
                            session.put("aiCurrentQuestion", qr.question);
                            session.put("questionStartTime", System.currentTimeMillis());

                            // 新主问题：初始化追问链
                            List<Map<String, String>> newChain = new ArrayList<>();
                            Map<String, String> qEntry = new LinkedHashMap<>();
                            qEntry.put("role", "question");
                            qEntry.put("content", qr.question);
                            newChain.add(qEntry);
                            session.put("currentQuestionChain", newChain);

                            redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
                            return InterviewSession.builder()
                                    .sessionId(sessionId)
                                    .question(qr.question)
                                    .questionType(qr.questionType)
                                    .questionIndex(nextMainQIndex)
                                    .totalQuestions(totalQuestions)
                                    .mainQuestionIndex(nextMainQIndex)
                                    .isFollowUp(false)
                                    .answeredCount(answeredCount)
                                    .finished(false)
                                    .build();
                        }
                    }
                } catch (Exception e) {
                    log.warn("AI 出题失败，使用本地题库: {}", e.getMessage());
                }

            session.put("questionStartTime", System.currentTimeMillis());

            // 智能兜底选题：优先选择未使用过的题型
            Map<String, String> localQ = selectSmartFallback(session, null);
            session.put("aiCurrentQuestion", localQ.get("q"));

            // 记录到全局已出题列表，用于跨轮次去重
            List<String> allGenerated = (List<String>) session.computeIfAbsent("allGeneratedQuestions", k -> new ArrayList<>());
            allGenerated.add(localQ.get("q"));
            session.put("allGeneratedQuestions", allGenerated);

            // 本地题库：初始化追问链
            List<Map<String, String>> newChain = new ArrayList<>();
            Map<String, String> qEntry = new LinkedHashMap<>();
            qEntry.put("role", "question");
            qEntry.put("content", localQ.get("q"));
            newChain.add(qEntry);
            session.put("currentQuestionChain", newChain);

            redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
            return InterviewSession.builder()
                    .sessionId(sessionId)
                    .question(localQ.get("q"))
                    .questionType(localQ.get("type"))
                    .questionIndex(nextMainQIndex)
                    .totalQuestions(totalQuestions)
                    .mainQuestionIndex(nextMainQIndex)
                    .isFollowUp(false)
                    .answeredCount(answeredCount)
                    .finished(false)
                    .build();
        }

        // 全部答完
        session.put("stage", "EVALUATION");
        redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
        return InterviewSession.builder()
                .sessionId(sessionId).question(null)
                .questionIndex(totalQuestions).totalQuestions(totalQuestions)
                .mainQuestionIndex(totalQuestions)
                .isFollowUp(false)
                .answeredCount(answeredCount)
                .finished(true).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public InterviewReportResponse endInterview(Long userId, String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        Map<String, Object> session = (Map<String, Object>) redisTemplate.opsForValue().get(key);
        if (session == null) return null;

        // 尝试 DeepSeek 综合评估
        InterviewReportResponse report = null;
        try {
            if (deepSeekService.isAvailable()) {
                report = callAIForEvaluation(session);
            }
        } catch (Exception e) {
            log.warn("AI 评估失败，使用本地评分: {}", e.getMessage());
        }

        // AI 不可用时的本地评分兜底
        if (report == null) {
            report = localEvaluation(session);
        }

        // 持久化
        InterviewRecord record = new InterviewRecord();
        record.setUserId(userId);
        record.setTargetJobId((Long) session.get("targetJobId"));
        record.setInterviewType((String) session.get("interviewType"));
        record.setScore(report.getTotalScore());
        record.setCreatedAt(LocalDateTime.now());

        try {
            record.setQuestionJson(objectMapper.writeValueAsString(session.get("answers")));
            record.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            log.warn("序列化面试记录失败", e);
        }
        recordMapper.insert(record);

        redisTemplate.delete(key);

        report.setId(record.getId());
        report.setInterviewType(record.getInterviewType());
        report.setCreatedAt(record.getCreatedAt());
        return report;
    }

    // ==================== AI 调用方法 ====================

    /**
     * 调用 DeepSeek 生成一道面试题目（含多维去重检查 + 题型追踪）
     */
    @SuppressWarnings("unchecked")
    private QuestionResult callAIForQuestion(Map<String, Object> session,
                                              String lastAnswer, String lastQuestion) {
        int totalQuestions = (int) session.getOrDefault("totalQuestions", 5);
        try {
            JobPosition job = getJob(session);

            // 构建历史问答
            List<Map<String, String>> history = (List<Map<String, String>>) session.get("answers");
            StringBuilder historyStr = new StringBuilder();
            if (history != null) {
                for (Map<String, String> h : history) {
                    historyStr.append("- 问题: ").append(h.get("question")).append("\n");
                    historyStr.append("  回答: ").append(h.get("answer")).append("\n");
                }
            }

            int nextIdx = ((Number) session.get("questionIndex")).intValue() + 1;

            // 追加已用题型到上下文，引导 AI 轮换维度
            List<String> usedTypes = (List<String>) session.getOrDefault("usedQuestionTypes", List.of());
            String availableTypes = String.join("、",
                    QUESTION_TYPE_ORDER.stream().filter(t -> !usedTypes.contains(t)).toList());

            // 构建衔接上下文 + 题型约束
            StringBuilder taskBuilder = new StringBuilder();
            taskBuilder.append("这是第 ").append(nextIdx).append(" / ").append(totalQuestions)
                    .append(" 题。请根据岗位要求和面试类型，出一道与之前不重复的题目。");

            // 题型轮换约束（核心去重手段）
            if (!usedTypes.isEmpty()) {
                taskBuilder.append("\n【重要】已出过的题型: ").append(String.join("、", usedTypes))
                           .append("。本次请优先选择以下未使用过的题型: ").append(
                                   availableTypes.isEmpty() ? "可自由选择" : availableTypes)
                           .append("。严禁出自我介绍、职业规划等基础题。");
            } else {
                // 第一题也避免太基础
                taskBuilder.append("\n注意：第一题应直接切入技术/项目深度，不要问自我介绍等基础问题。");
            }

            if (lastAnswer != null && !lastAnswer.isEmpty() && lastQuestion != null && !lastQuestion.isEmpty()) {
                taskBuilder.append("\n上一题「").append(lastQuestion).append("」的回答中提到了相关技术点和经验，")
                           .append("请尽量从已有回答中自然延伸出新的题目，保持面试对话的连贯性和递进感。")
                           .append("同时确保题目覆盖不同能力维度，避免集中在同一维度反复出题。");
            }
            Map<String, String> params = new HashMap<>();
            params.put("job_title", job != null ? job.getTitle() : "通用岗位");
            params.put("job_jd", job != null ? job.getJd() : "请根据候选人背景提问");
            params.put("interview_type", (String) session.getOrDefault("interviewType", "COMPREHENSIVE"));
            params.put("current_stage", "FIRST_QUESTION");
            params.put("current_task", taskBuilder.toString());
            params.put("history_qa", historyStr.length() > 0 ? historyStr.toString() : "（暂无历史问答）");

            // 最多重试2次去重（增强后检测更严格）
            for (int retry = 0; retry <= 2; retry++) {
                String prompt = promptUtil.loadAndRender("mock_interview", params);
                String response = deepSeekService.callAPI("你是一位资深技术面试官", prompt, 15000L, 256);
                Map<String, Object> result = deepSeekService.parseJSONResponse(response);

                if (result != null && result.containsKey("question")) {
                    String newQuestion = (String) result.get("question");
                    String questionType = normalizeQuestionType((String) result.getOrDefault("questionType", ""));
                    // 多维去重检查：字符串+题型+基础题拦截
                    if (!isDuplicateQuestion(newQuestion, questionType, session)) {
                        // 追踪已使用题型
                        usedTypes.add(questionType);
                        session.put("usedQuestionTypes", usedTypes);
                        // 记录到全局已出题列表，用于跨轮次去重
                        List<String> allGenerated = (List<String>) session.computeIfAbsent("allGeneratedQuestions", k -> new ArrayList<>());
                        allGenerated.add(newQuestion);
                        session.put("allGeneratedQuestions", allGenerated);
                        log.info("AI 出题通过: type={}, 已用类型={}, 重试次数={}", questionType, usedTypes, retry);
                        return new QuestionResult(newQuestion, questionType);
                    }
                    log.warn("AI 出题多维去重不通过 (retry={}), type={}", retry + 1, questionType);
                }
            }
        } catch (Exception e) {
            log.warn("DeepSeek 出题异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 标准化 AI 返回的题型名称为内部常量
     */
    private String normalizeQuestionType(String aiType) {
        if (aiType == null || aiType.isBlank()) return TYPE_PROJECT;
        if (aiType.contains("技术") || aiType.contains("基础") || aiType.contains("原理")) return TYPE_TECHNICAL;
        if (aiType.contains("项目") || aiType.contains("经验")) return TYPE_PROJECT;
        if (aiType.contains("行为") || aiType.contains("HR")) return TYPE_BEHAVIORAL;
        if (aiType.contains("情景") || aiType.contains("场景") || aiType.contains("模拟")) return TYPE_SCENARIO;
        if (aiType.contains("系统设计") || aiType.contains("架构")) return TYPE_SYSTEM_DESIGN;
        return TYPE_PROJECT; // 默认
    }

    /**
     * 调用 DeepSeek 生成面试综合评估报告（含追问链分析 + 容错重试）
     */
    @SuppressWarnings("unchecked")
    private InterviewReportResponse callAIForEvaluation(Map<String, Object> session) {
        // 最多尝试2次
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                JobPosition job = getJob(session);

                List<Map<String, String>> history = (List<Map<String, String>>) session.get("answers");
                StringBuilder historyStr = new StringBuilder();
                if (history != null) {
                    for (int i = 0; i < history.size(); i++) {
                        Map<String, String> h = history.get(i);
                        boolean isFollowUp = "true".equals(h.get("isFollowUp"));
                        String label = isFollowUp ? "（追问）" : "";
                        historyStr.append("第").append(i + 1).append("题").append(label)
                                .append(": ").append(h.get("question")).append("\n");
                        historyStr.append("回答: ").append(h.get("answer")).append("\n\n");
                    }
                }

                // 追加追问链结构信息
                List<Map<String, String>> chain = (List<Map<String, String>>) session.get("currentQuestionChain");
                if (chain != null && !chain.isEmpty()) {
                    historyStr.append("\n=== 追问链结构（辅助评估参考） ===\n");
                    for (Map<String, String> entry : chain) {
                        historyStr.append("[").append(entry.get("role")).append("] ")
                                .append(entry.get("content")).append("\n");
                    }
                }

                Map<String, String> params = new HashMap<>();
                params.put("job_title", job != null ? job.getTitle() : "通用岗位");
                params.put("job_jd", job != null ? job.getJd() : "");
                params.put("interview_type", (String) session.getOrDefault("interviewType", "COMPREHENSIVE"));
                params.put("current_stage", "EVALUATION");
                params.put("current_task", "请根据以上完整的问答记录进行综合评估，给出评分和改进建议。"
                        + "\n重要：必须返回严格的JSON格式，totalScore为0-100的整数，dimensionScores包含5个维度。");
                params.put("history_qa", historyStr.toString());

                String prompt = promptUtil.loadAndRender("mock_interview", params);
                String response = deepSeekService.callAPI("你是一位资深技术面试官", prompt, 20000L, 768);
                log.info("AI 评估原始响应 (attempt={}): {}", attempt + 1, truncate(response, 200));

                Map<String, Object> result = deepSeekService.parseJSONResponse(response);
                if (result != null) {
                    InterviewReportResponse report = sanitizeEvaluationResult(result);
                    if (report != null) {
                        log.info("AI 评估成功: totalScore={}", report.getTotalScore());
                        return report;
                    }
                    log.warn("AI 评估结果校验不通过 (attempt={}), 将重试...", attempt + 1);
                } else {
                    log.warn("AI 评估 JSON 解析失败 (attempt={})", attempt + 1);
                }
            } catch (Exception e) {
                log.warn("DeepSeek 评估异常 (attempt={}): {}", attempt + 1, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 校验和清洗 AI 评估结果 — 确保字段完整且值在合理范围内
     */
    @SuppressWarnings("unchecked")
    private InterviewReportResponse sanitizeEvaluationResult(Map<String, Object> raw) {
        if (raw == null || !raw.containsKey("totalScore")) return null;

        // 校验 totalScore：必须是数字且在 [0,100]
        double totalScore;
        try {
            totalScore = ((Number) raw.get("totalScore")).doubleValue();
        } catch (Exception e) {
            log.warn("totalScore 格式无效: {}", raw.get("totalScore"));
            return null;
        }
        if (totalScore < 0 || totalScore > 100) {
            log.warn("totalScore 超出范围[0,100]: {}", totalScore);
            totalScore = Math.max(0, Math.min(100, totalScore));
        }

        // 校验并清洗 dimensionScores
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        String[] requiredDims = {"logic", "professionalism", "communication", "adaptability", "jobFit"};
        Map<String, Object> dimMap = (Map<String, Object>) raw.get("dimensionScores");

        boolean hasValidDim = false;
        for (String dim : requiredDims) {
            double val = 60.0; // 默认中等分
            if (dimMap != null && dimMap.containsKey(dim)) {
                try {
                    double rawVal = ((Number) dimMap.get(dim)).doubleValue();
                    val = Math.max(0, Math.min(100, rawVal)); // 钳制到 [0,100]
                    hasValidDim = true;
                } catch (Exception ignored) {}
            }
            dimensionScores.put(dim, val);
        }

        // 如果完全没有 dimensionScores 字段或全部解析失败，仍返回但标记
        if (!hasValidDim && dimMap == null) {
            log.warn("缺少 dimensionScores 字段，使用默认值");
        }

        // 清洗 highlights 和 improvements
        List<String> highlights = safeStringList(raw.get("highlights"), 3);
        List<String> improvements = safeStringList(raw.get("improvements"), 3);

        // summary
        String summary = (String) raw.getOrDefault("summary", "");
        if (summary.isBlank()) {
            summary = buildInterviewSummary(totalScore, dimensionScores,
                    dimensionScores.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("logic"),
                    dimensionScores.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("adaptability"));
        }

        return InterviewReportResponse.builder()
                .totalScore(Math.round(totalScore * 10.0) / 10.0)
                .dimensionScores(dimensionScores)
                .highlights(highlights)
                .improvements(improvements)
                .summary(summary)
                .build();
    }

    /**
     * 安全提取字符串列表 — 处理 AI 返回的各种格式
     */
    @SuppressWarnings("unchecked")
    private List<String> safeStringList(Object raw, int defaultCount) {
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().trim());
                }
            }
            return result.isEmpty() ? List.of("表现良好") : result;
        }
        // 兜底
        return List.of("表现良好");
    }

    /**
     * 本地评分兜底 — 基于字数/关键词/耗时的加权评分算法
     * 每题满分100分，三个维度加权求和后映射到各评估维度
     */
    @SuppressWarnings("unchecked")
    private InterviewReportResponse localEvaluation(Map<String, Object> session) {
        List<Map<String, String>> answers = (List<Map<String, String>>) session.get("answers");
        if (answers == null || answers.isEmpty()) {
            return InterviewReportResponse.builder()
                    .totalScore(50.0)
                    .dimensionScores(Map.of("logic", 50.0, "professionalism", 50.0,
                            "communication", 50.0, "adaptability", 50.0, "jobFit", 50.0))
                    .highlights(List.of())
                    .improvements(List.of("本次面试未记录有效回答，建议重新参与面试"))
                    .summary("面试记录为空")
                    .build();
        }

        // 1. 逐题评分
        List<double[]> questionScores = new ArrayList<>(); // 每题 [lengthScore, keywordScore, timeScore, weightedTotal]
        List<String> allHighlights = new ArrayList<>();
        List<String> allImprovements = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            Map<String, String> ans = answers.get(i);
            String answerText = ans.getOrDefault("answer", "");
            boolean isFollowUp = "true".equals(ans.get("isFollowUp"));
            long elapsedMs = 0;
            try { elapsedMs = Long.parseLong(ans.getOrDefault("elapsedMs", "0")); } catch (NumberFormatException ignored) {}

            // 从答案记录中推断题型
            String qType = inferQuestionType(ans.getOrDefault("question", ""));

            double lengthScore = evaluateLength(answerText);        // 权重 30%
            double keywordScore = evaluateKeywordsByType(answerText, qType);   // 权重 40%
            double timeScore = evaluateTime(elapsedMs);              // 权重 30%
            double weightedTotal = lengthScore * 0.3 + keywordScore * 0.4 + timeScore * 0.3;

            questionScores.add(new double[]{lengthScore, keywordScore, timeScore, weightedTotal, isFollowUp ? 1.0 : 0.0});

            // 生成每题级别的评语（追问条目特殊标记）
            String qLabel = isFollowUp ? String.format("第%d题（追问）", i + 1) : String.format("第%d题", i + 1);
            if (keywordScore >= 30) {
                allHighlights.add(String.format("%s：回答涵盖了多个关键概念，专业度较高（关键词匹配%.0f%%）",
                        qLabel, keywordScore / 0.4));
            }
            if (lengthScore < 10) {
                allImprovements.add(String.format("%s：回答过于简短（%d字），建议展开论述并补充具体案例",
                        qLabel, countChineseChars(answerText)));
            }
            if (timeScore < 10) {
                allImprovements.add(String.format("%s：作答耗时过短（%.1f秒），建议更深入思考后再回答",
                        qLabel, elapsedMs / 1000.0));
            }
        }

        // 2. 分维度汇总（每题按维度权重贡献）
        String[] dims = {"logic", "professionalism", "communication", "adaptability", "jobFit"};
        Map<String, Double> dimWeightedSum = new LinkedHashMap<>();
        Map<String, Double> dimWeightTotal = new LinkedHashMap<>();
        for (String d : dims) {
            dimWeightedSum.put(d, 0.0);
            dimWeightTotal.put(d, 0.0);
        }

        for (int i = 0; i < questionScores.size(); i++) {
            double total = questionScores.size() > i ? questionScores.get(i)[3] : 0;
            // 追问条目权重减半
            double isFollowUp = questionScores.get(i)[4];
            double effectiveWeight = isFollowUp > 0 ? 0.5 : 1.0;

            // 从答案中推断题型
            String qType = inferQuestionType(answers.get(i).getOrDefault("question", ""));
            Map<String, Double> weights = TYPE_DIMENSION_WEIGHTS.getOrDefault(qType, Map.of(
                    "logic", 0.2, "professionalism", 0.2, "communication", 0.2, "adaptability", 0.2, "jobFit", 0.2));
            for (String d : dims) {
                double w = weights.getOrDefault(d, 0.2) * effectiveWeight;
                dimWeightedSum.merge(d, total * w, Double::sum);
                dimWeightTotal.merge(d, w, Double::sum);
            }
        }

        // 计算各维度归一化得分（0-100）
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        for (String d : dims) {
            double wSum = dimWeightedSum.getOrDefault(d, 0.0);
            double wTotal = dimWeightTotal.getOrDefault(d, 1.0);
            dimensionScores.put(d, Math.min(100, Math.round(wSum / wTotal * 10.0) / 10.0));
        }

        // 3. 综合总分（五维度平均）
        double totalScore = dimensionScores.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);

        // 4. 生成动态亮点和改进建议
        // 亮点：取最高分维度
        String topDim = dimensionScores.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("logic");
        String dimLabel = getDimensionLabel(topDim);
        if (dimensionScores.get(topDim) >= 70) {
            allHighlights.add(0, String.format("「%s」维度表现最佳（%.0f分），展现了该领域的扎实基础",
                    dimLabel, dimensionScores.get(topDim)));
        }

        // 改进：取最低分维度
        String lowDim = dimensionScores.entrySet().stream()
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("adaptability");
        String lowLabel = getDimensionLabel(lowDim);
        allImprovements.add(0, String.format("「%s」维度建议重点提升（%.0f分），推荐针对性训练",
                lowLabel, dimensionScores.get(lowDim)));

        // 限制数量
        if (allHighlights.size() > 5) allHighlights = allHighlights.subList(0, 5);
        if (allImprovements.size() > 5) allImprovements = allImprovements.subList(0, 5);
        if (allHighlights.isEmpty()) allHighlights.add("面试流程完整，建议在后续面试中更充分地展现技术深度");
        if (allImprovements.isEmpty()) allImprovements.add("建议在回答中增加具体案例和量化成果");

        // 5. 生成综合评语
        String summary = buildInterviewSummary(totalScore, dimensionScores, topDim, lowDim);

        return InterviewReportResponse.builder()
                .totalScore(Math.round(totalScore * 10.0) / 10.0)
                .dimensionScores(dimensionScores)
                .highlights(allHighlights)
                .improvements(allImprovements)
                .summary(summary)
                .build();
    }

    /**
     * 评估回答长度得分（权重30%，满分30）
     */
    private double evaluateLength(String answer) {
        int charCount = countChineseChars(answer);
        if (charCount >= 150) return 30;
        if (charCount >= 100) return 22;
        if (charCount >= 50) return 15;
        if (charCount >= 20) return 8;
        return 3;
    }

    /**
     * 评估关键词匹配得分（权重40%，满分40）— 按题型匹配关键词
     */
    private double evaluateKeywordsByType(String answer, String questionType) {
        List<String> keywords = TYPE_KEYWORDS.getOrDefault(questionType, List.of());
        if (keywords.isEmpty()) return 20; // 无关键词定义时给均分

        int hitCount = 0;
        for (String keyword : keywords) {
            if (answer.contains(keyword)) {
                hitCount++;
            }
        }
        double hitRate = (double) hitCount / keywords.size();
        return hitRate * 40;
    }

    /**
     * 根据题目内容推断题型（用于本地评分时从答案记录反推）
     */
    private String inferQuestionType(String questionText) {
        if (questionText == null || questionText.isEmpty()) return TYPE_PROJECT;
        String lower = questionText.toLowerCase();
        if (lower.contains("设计") && (lower.contains("架构") || lower.contains("系统") || lower.contains("高并发") || lower.contains("微服务"))) {
            return TYPE_SYSTEM_DESIGN;
        }
        if (lower.contains("排查") || lower.contains("故障") || lower.contains("报警") || lower.contains("线上") || lower.contains("假设")) {
            return TYPE_SCENARIO;
        }
        if (lower.contains("项目") || lower.contains("挑战") || lower.contains("实践") || lower.contains("经历") || lower.contains("分享")) {
            return TYPE_PROJECT;
        }
        if (lower.contains("原理") || lower.contains("实现") || lower.contains("机制") || lower.contains("底层") || lower.contains("理解")) {
            return TYPE_TECHNICAL;
        }
        if (lower.contains("团队") || lower.contains("沟通") || lower.contains("规划") || lower.contains("分歧") || lower.contains("决策")) {
            return TYPE_BEHAVIORAL;
        }
        return TYPE_PROJECT; // 默认
    }

    /**
     * 评估作答耗时合理性得分（权重30%，满分30）
     */
    private double evaluateTime(long elapsedMs) {
        double seconds = elapsedMs / 1000.0;
        if (seconds < 10) return 5;        // 敷衍：<10秒
        if (seconds < 30) return 15;       // 偏短：10-30秒
        if (seconds <= 180) return 30;     // 合理：30-180秒
        return 20;                          // 过长：>180秒
    }

    /**
     * 统计中文字符数（去空白）
     */
    private int countChineseChars(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (char c : text.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 本地回答质量快速检测 —— 前置判断是否值得调用 AI 追问
     * @return true=回答有实质内容可追问，false=回答过短或空洞应跳过追问
     */
    private boolean isAnswerSubstantial(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            log.debug("isAnswerSubstantial: 空回答");
            return false;
        }
        String trimmed = answer.trim();

        // 1. 长度检测：中文字符数 < 30 视为过短
        int charCount = countChineseChars(trimmed);
        if (charCount < 30) {
            log.info("isAnswerSubstantial=false，字数={} < 30", charCount);
            return false;
        }

        // 2. 空洞内容检测：仅包含敷衍词汇
        String lower = trimmed.toLowerCase();
        String[] hollowPatterns = {
            "不知道", "不会", "不懂", "不清楚", "不太了解", "不太清楚",
            "没做过", "没经验", "没有做过", "忘记了", "想不起来"
        };
        // 去除标点和空白后判断
        String stripped = lower.replaceAll("[\\p{P}\\s]", "");
        for (String pattern : hollowPatterns) {
            if (stripped.equals(pattern.replaceAll("[\\s]", ""))) {
                log.info("isAnswerSubstantial=false，空洞回答: {}", trimmed);
                return false;
            }
        }

        return true;
    }

    /**
     * 生成面试综合评语
     */
    private String buildInterviewSummary(double totalScore, Map<String, Double> dimScores,
                                          String topDim, String lowDim) {
        String level;
        if (totalScore >= 85) level = "表现优秀";
        else if (totalScore >= 70) level = "表现良好";
        else if (totalScore >= 55) level = "表现一般";
        else level = "需要提升";

        String topLabel = getDimensionLabel(topDim);
        String lowLabel = getDimensionLabel(lowDim);

        return String.format("面试综合评分%.0f分，%s。在「%s」方面表现突出（%.0f分），" +
                        "建议继续保持优势。在「%s」方面有待加强（%.0f分），" +
                        "建议通过系统学习和专项练习提升该领域能力。",
                totalScore, level,
                topLabel, dimScores.getOrDefault(topDim, 0.0),
                lowLabel, dimScores.getOrDefault(lowDim, 0.0));
    }

    /**
     * 维度英文名 → 中文标签
     */
    private String getDimensionLabel(String dimKey) {
        return switch (dimKey) {
            case "logic" -> "逻辑思维";
            case "professionalism" -> "专业能力";
            case "communication" -> "沟通表达";
            case "adaptability" -> "适应能力";
            case "jobFit" -> "岗位匹配";
            default -> dimKey;
        };
    }

    /**
     * 获取岗位信息
     */
    private JobPosition getJob(Map<String, Object> session) {
        Long jobId = (Long) session.get("targetJobId");
        if (jobId != null) {
            return jobPositionMapper.selectById(jobId);
        }
        return null;
    }

    /**
     * 获取本地题库的第 index 道题
     * 委托给 selectSmartFallback，传入真实 session 确保 usedQuestionTypes 持续更新
     */
    private String getLocalQuestion(int index, Map<String, Object> session) {
        return selectSmartFallback(session, null).get("q");
    }

    /**
     * 从指定题型池选题并记录使用状态 — 实现题型轮换 + 题内轮换
     * @param type    题目类型
     * @param pool    该题型的题目列表
     * @param session 当前会话（会更新 usedQuestionTypes 和 typeUseCount）
     * @return 选中的题目 Map(q, type)
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> pickFromPool(String type, List<Map<String, String>> pool,
                                              Map<String, Object> session) {
        List<String> usedTypes = (List<String>) session.computeIfAbsent("usedQuestionTypes", k -> new ArrayList<>());
        Map<String, Integer> typeUseCount = (Map<String, Integer>) session.computeIfAbsent("typeUseCount", k -> new HashMap<>());

        // 记录该题型已被使用
        if (!usedTypes.contains(type)) {
            usedTypes.add(type);
        }

        // 递增该题型使用次数，用 count % pool.size() 实现题内轮换
        int count = typeUseCount.getOrDefault(type, 0);
        typeUseCount.put(type, count + 1);

        int qIdx = count % pool.size();
        log.info("pickFromPool: 题型={}, 题号索引={}/{}", type, qIdx, pool.size());
        return Map.of("q", pool.get(qIdx).get("q"), "type", pool.get(qIdx).get("type"));
    }

    /**
     * 根据题型和岗位方向解析对应的题目池
     * 技术基础和系统设计题按方向区分，其他题型使用通用题库
     */
    private List<Map<String, String>> resolvePool(String type, String direction) {
        if (TYPE_TECHNICAL.equals(type)) {
            return DIRECTION_TECHNICAL_POOL.getOrDefault(direction,
                    DIRECTION_TECHNICAL_POOL.get(DEFAULT_DIRECTION));
        }
        if (TYPE_SYSTEM_DESIGN.equals(type)) {
            if (DIRECTION_SYSTEM_DESIGN_POOL.containsKey(direction)) {
                return DIRECTION_SYSTEM_DESIGN_POOL.get(direction);
            }
            return DIRECTION_SYSTEM_DESIGN_POOL.get("通用");
        }
        return QUESTION_POOL.getOrDefault(type, List.of());
    }

    /**
     * 智能兜底选题 — 优先选择未使用过的题型，按 QUESTION_TYPE_ORDER 轮换
     * 技术基础和系统设计题根据 jobDirection 从方向专用题库选取
     * @param session 当前面试会话（含 usedQuestionTypes、jobDirection）
     * @param preferredType AI 指定的偏好题型（可为null）
     * @return 选中的题目 Map(q, type)
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> selectSmartFallback(Map<String, Object> session, String preferredType) {
        List<String> usedTypes = (List<String>) session.computeIfAbsent("usedQuestionTypes", k -> new ArrayList<>());
        // typeUseCount 也按需初始化，方便 pickFromPool 使用
        session.computeIfAbsent("typeUseCount", k -> new HashMap<>());
        String jobDirection = (String) session.getOrDefault("jobDirection", DEFAULT_DIRECTION);

        // 策略1：如果有偏好题型且未用过，优先选
        if (preferredType != null && !usedTypes.contains(preferredType)) {
            List<Map<String, String>> pool = resolvePool(preferredType, jobDirection);
            if (pool != null && !pool.isEmpty()) {
                log.info("selectSmartFallback: 选择偏好题型={}, 方向={}", preferredType, jobDirection);
                return pickFromPool(preferredType, pool, session);
            }
        }

        // 策略2：按轮换顺序找到第一个未使用的题型
        for (String type : QUESTION_TYPE_ORDER) {
            if (!usedTypes.contains(type)) {
                List<Map<String, String>> pool = resolvePool(type, jobDirection);
                if (pool != null && !pool.isEmpty()) {
                    log.info("selectSmartFallback: 轮换选择题型={}, 方向={}, 已用={}", type, jobDirection, usedTypes);
                    return pickFromPool(type, pool, session);
                }
            }
        }

        // 策略3：所有类型都用过了，循环选一个
        int idx = usedTypes.size() % QUESTION_TYPE_ORDER.size();
        String fallbackType = QUESTION_TYPE_ORDER.get(idx);
        List<Map<String, String>> pool = resolvePool(fallbackType, jobDirection);
        if (pool != null && !pool.isEmpty()) {
            log.info("selectSmartFallback: 全部类型已用，循环选择题型={}, 方向={}", fallbackType, jobDirection);
            return pickFromPool(fallbackType, pool, session);
        }

        // 终极兜底
        return Map.of("q", "请描述你在技术学习或项目实践中遇到的最大挑战及解决方案。", "type", TYPE_PROJECT);
    }

    /**
     * 根据目标岗位动态计算面试题目总数
     * 基础5道，技术岗位+2道，管理岗位+1道，上限8道
     */
    private int calculateQuestionCount(Long jobId) {
        return 5;
    }

    /**
     * 多维去重检查 — 字符串相似度 + 题型维度 + 主题关键词
     * @param newQuestion 新生成的题目
     * @param newType     新题目的类型（可为null）
     * @param session     当前会话（含 usedQuestionTypes 和 answers）
     * @return true=重复应重试，false=通过可使用
     */
    @SuppressWarnings("unchecked")
    private boolean isDuplicateQuestion(String newQuestion, String newType,
                                         Map<String, Object> session) {
        List<Map<String, String>> history = (List<Map<String, String>>) session.getOrDefault("answers", List.of());
        List<String> usedTypes = (List<String>) session.getOrDefault("usedQuestionTypes", List.of());

        // 检查1：题型维度重复 — 如果同类型已出过2题以上，标记为疑似重复
        if (newType != null && !newType.isEmpty()) {
            long sameTypeCount = usedTypes.stream().filter(t -> t.equals(newType)).count();
            if (sameTypeCount >= 2 && QUESTION_TYPE_ORDER.size() > usedTypes.size()) {
                log.warn("题型重复: type={}, 已用={}, 同类型出现{}次", newType, usedTypes, sameTypeCount);
                return true;
            }
        }

        // 检查2：字符串相似度（LCS > 70% 视为重复）
        if (!history.isEmpty()) {
            for (Map<String, String> answerRecord : history) {
                String existingQ = answerRecord.get("question");
                if (existingQ != null && !existingQ.isEmpty()) {
                    String longer = newQuestion.length() >= existingQ.length() ? newQuestion : existingQ;
                    String shorter = newQuestion.length() < existingQ.length() ? newQuestion : existingQ;
                    int matchLen = longestCommonSubstring(longer, shorter);
                    double ratio = shorter.length() > 0 ? (double) matchLen / shorter.length() : 0;
                    if (ratio > 0.7) {
                        log.warn("题目内容重复: ratio={:.2f}, newQ={}", ratio, truncate(newQuestion, 40));
                        return true;
                    }
                }
            }
        }

        // 检查2b：全局已出题列表去重 — 捕获"已出但尚未回答"的重复
        List<String> allGenerated = (List<String>) session.getOrDefault("allGeneratedQuestions", List.of());
        if (!allGenerated.isEmpty()) {
            for (String generatedQ : allGenerated) {
                if (generatedQ != null && !generatedQ.isEmpty()) {
                    String longer = newQuestion.length() >= generatedQ.length() ? newQuestion : generatedQ;
                    String shorter = newQuestion.length() < generatedQ.length() ? newQuestion : generatedQ;
                    int matchLen = longestCommonSubstring(longer, shorter);
                    double ratio = shorter.length() > 0 ? (double) matchLen / shorter.length() : 0;
                    if (ratio > 0.7) {
                        log.warn("全局已出题列表检测到重复: ratio={:.2f}, newQ={}", ratio, truncate(newQuestion, 40));
                        return true;
                    }
                }
            }
        }

        // 检查3：禁止非首题出现自我介绍类基础题
        int qIndex = ((Number) session.getOrDefault("questionIndex", 0)).intValue();
        if (qIndex > 0) {
            String lower = newQuestion.toLowerCase();
            String[] bannedPatterns = {"自我介绍", "介绍一下你自己", "请简单介绍", "基本情况"};
            for (String pattern : bannedPatterns) {
                if (lower.contains(pattern)) {
                    log.warn("非首题({})出现基础题模式: {}", qIndex, pattern);
                    return true;
                }
            }
        }

        return false;
    }

    /** 截断字符串用于日志 */
    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 最长公共子串长度
     */
    private int longestCommonSubstring(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        int maxLen = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    maxLen = Math.max(maxLen, dp[i][j]);
                }
            }
        }
        return maxLen;
    }

    /**
     * 调用 DeepSeek 进行 FOLLOW_UP 追问（传入完整追问链上下文）
     */
    @SuppressWarnings("unchecked")
    private FollowUpResult callAIForFollowUp(Map<String, Object> session,
                                              String userAnswer, String currentQuestion, int followUpRound) {
        try {
            JobPosition job = getJob(session);

            Map<String, String> params = new HashMap<>();
            params.put("job_title", job != null ? job.getTitle() : "通用岗位");
            params.put("job_jd", job != null ? job.getJd() : "请根据候选人背景提问");
            params.put("interview_type", (String) session.getOrDefault("interviewType", "COMPREHENSIVE"));
            params.put("current_stage", "FOLLOW_UP");
            params.put("current_task", "候选人对当前题目已作答，请根据回答决定是否追问（第" + (followUpRound + 1) + "轮追问机会，最多1轮）。");

            // 传入完整追问链上下文（而非仅当前 Q&A）
            StringBuilder followUpQA = new StringBuilder();
            List<Map<String, String>> chain = (List<Map<String, String>>) session.get("currentQuestionChain");
            if (chain != null && !chain.isEmpty()) {
                followUpQA.append("=== 当前题目追问链 ===\n");
                for (Map<String, String> entry : chain) {
                    String role = entry.get("role");
                    String content = entry.get("content");
                    switch (role) {
                        case "question" -> followUpQA.append("【主问题】").append(content).append("\n");
                        case "answer" -> followUpQA.append("【回答】").append(content).append("\n");
                        case "follow_up_question" -> followUpQA.append("【第" + (followUpRound) + "轮追问】").append(content).append("\n");
                        case "follow_up_answer" -> followUpQA.append("【追问回答】").append(content).append("\n");
                    }
                }
            } else {
                followUpQA.append("当前问题: ").append(currentQuestion).append("\n");
                followUpQA.append("候选人回答: ").append(userAnswer).append("\n");
            }
            followUpQA.append("当前追问轮次: ").append(followUpRound + 1).append("/1\n");
            followUpQA.append("请根据以上完整追问历史，评估回答质量并决定是否继续追问。");
            params.put("history_qa", followUpQA.toString());

            String prompt = promptUtil.loadAndRender("mock_interview", params);
            String response = deepSeekService.callAPI("你是一位资深技术面试官", prompt, 8000L, 256);
            Map<String, Object> result = deepSeekService.parseJSONResponse(response);

            if (result != null) {
                String action = (String) result.getOrDefault("action", "NEXT_QUESTION");
                String followUpQuestion = (String) result.getOrDefault("followUpQuestion", "");
                return new FollowUpResult(action, followUpQuestion,
                        (String) result.getOrDefault("feedback", ""));
            }
        } catch (Exception e) {
            log.warn("DeepSeek 追问异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * AI 返回的题目
     */
    private static class QuestionResult {
        final String question;
        final String questionType;
        QuestionResult(String question, String questionType) {
            this.question = question;
            this.questionType = questionType;
        }
    }

    /**
     * AI 追问结果
     */
    private static class FollowUpResult {
        final String action;       // "FOLLOW_UP" 或 "NEXT_QUESTION"
        final String question;     // 追问内容 (action=FOLLOW_UP时有效)
        final String feedback;     // 对当前回答的简短评价
        FollowUpResult(String action, String question, String feedback) {
            this.action = action;
            this.question = question;
            this.feedback = feedback;
        }
    }

    @Override
    public InterviewReportResponse getReport(Long recordId) {
        InterviewRecord record = recordMapper.selectById(recordId);
        if (record == null) return null;
        // 尝试从 reportJson 反序列化完整报告
        if (record.getReportJson() != null && !record.getReportJson().isEmpty()) {
            try {
                InterviewReportResponse report = objectMapper.readValue(
                        record.getReportJson(), InterviewReportResponse.class);
                report.setId(record.getId());
                report.setInterviewType(record.getInterviewType());
                report.setCreatedAt(record.getCreatedAt());
                return report;
            } catch (Exception e) {
                log.warn("反序列化历史报告失败，返回基础字段: {}", e.getMessage());
            }
        }
        // 兜底：返回基础字段
        return InterviewReportResponse.builder()
                .id(record.getId()).interviewType(record.getInterviewType())
                .totalScore(record.getScore()).createdAt(record.getCreatedAt())
                .highlights(List.of("已完成面试")).improvements(List.of())
                .summary("面试记录").build();
    }

    @Override
    public List<InterviewReportResponse> getHistory(Long userId) {
        return recordMapper.selectList(new LambdaQueryWrapper<InterviewRecord>()
                        .eq(InterviewRecord::getUserId, userId)
                        .orderByDesc(InterviewRecord::getCreatedAt))
                .stream().map(r -> InterviewReportResponse.builder()
                        .id(r.getId()).interviewType(r.getInterviewType())
                        .totalScore(r.getScore()).createdAt(r.getCreatedAt())
                        .build()).collect(Collectors.toList());
    }
}
