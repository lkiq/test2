package com.xuelian.career.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面试会话（每道题目的返回）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSession {
    private String sessionId;
    private String question;
    private String questionType;
    private Integer questionIndex;
    private Integer totalQuestions;
    private Boolean finished;

    /** 当前问题是否为追问（前端据此显示"🔍 追问"标识） */
    private Boolean isFollowUp;
    /** AI 对上一回答的简短反馈（可选，追问时展示在追问问题上方） */
    private String feedback;
    /** 当前属于第几道主问题（追问时不递增，用于前端进度条展示主问题进度） */
    private Integer mainQuestionIndex;
    /** 已答题目总数（含追问），用于前端进度显示 */
    private Integer answeredCount;
}
