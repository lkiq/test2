# AI 面试功能诊断与测试指南

## 一、DeepSeek API Key 配置诊断

### 1.1 问题根因

当前 `application-dev.yml` 配置：
```yaml
deepseek:
  api-key: ${DEEPSEEK_API_KEY}  # 从环境变量读取
```

**问题**：`DEEPSEEK_API_KEY` 环境变量未设置，导致：
1. `DeepSeekConfig.init()` 打印警告：`DeepSeek API Key 未正确配置！`
2. `doCallAPI()` 抛出异常：`AI 服务未配置 API Key`
3. `isAvailable()` 始终返回 `true`（Redis 无缓存时默认 true）
4. 追问逻辑：尝试调用 AI → 抛异常 → catch 降级 → 直接进入下一题（**用户感知不到追问**）

### 1.2 诊断步骤

#### 步骤 1：检查环境变量

在 PowerShell 中执行：
```powershell
# 检查环境变量是否设置
Get-ChildItem Env: | Where-Object Name -eq "DEEPSEEK_API_KEY"

# 或简写
echo $env:DEEPSEEK_API_KEY
```

**预期结果**：
- 如果返回空或不存在 → API Key 未配置
- 如果返回 `sk-xxxxx` → 已配置

#### 步骤 2：检查应用启动日志

启动后端应用时，查看控制台日志，应该看到以下两种之一：

**✅ 正常（API Key 已配置）**：
```
INFO  c.x.c.config.DeepSeekConfig - DeepSeek API Key 已配置: sk-abcd...wxyz (长度=48)
```

**❌ 异常（API Key 未配置）**：
```
WARN  c.x.c.config.DeepSeekConfig - DeepSeek API Key 未正确配置！当前值: ${DEEPSEEK_...
```

#### 步骤 3：临时配置 API Key（开发测试用）

**方法 A - 在 application-dev.yml 中临时硬编码（不推荐提交 Git）**：

```yaml
deepseek:
  api-key: sk-your-actual-api-key-here  # 临时测试用
```

**方法 B - 设置环境变量（推荐）**：

PowerShell（当前会话）：
```powershell
$env:DEEPSEEK_API_KEY = "sk-your-actual-api-key-here"
```

或永久设置（需要重启 IDE）：
```powershell
[System.Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-your-actual-api-key-here", "User")
```

**方法 C - 在 IntelliJ IDEA / VSCode 运行配置中添加环境变量**：

在运行配置的 Environment Variables 中添加：
```
DEEPSEEK_API_KEY=sk-your-actual-api-key-here
```

### 1.3 验证 API Key 是否有效

创建一个简单的测试接口或直接使用 curl 测试：

```powershell
# 替换 YOUR_API_KEY 为实际的 Key
$headers = @{
    "Authorization" = "Bearer YOUR_API_KEY"
    "Content-Type" = "application/json"
}

$body = @{
    model = "deepseek-chat"
    messages = @(
        @{ role = "user"; content = "你是谁？" }
    )
    max_tokens = 100
} | ConvertTo-Json

Invoke-RestMethod -Uri "https://api.deepseek.com/v1/chat/completions" -Method Post -Headers $headers -Body $body
```

**预期结果**：返回 JSON 响应，包含 `choices[0].message.content`

---

## 二、AI 面试功能测试用例

### 2.1 测试用例概览

| 用例 ID | 测试功能 | 前置条件 | 预期结果 |
|---------|----------|----------|----------|
| TC-001 | 开始面试（AI 可用） | DeepSeek API 正常 | 返回 AI 生成的题目 |
| TC-002 | 开始面试（AI 不可用） | DeepSeek API 异常 | 返回本地题库题目 |
| TC-003 | 追问功能（回答质量高） | 回答字数 ≥ 50 字 | 返回追问问题 |
| TC-004 | 追问功能（回答质量低） | 回答字数 < 20 字 | 跳过追问，直接进入下一题 |
| TC-005 | 最多 2 轮追问 | 连续 2 次高质量回答 | 第 2 次追问后进入下一题 |
| TC-006 | 面试结束自动生成报告 | 答完所有题目 | 返回 finished=true |
| TC-007 | AI 评分（API 可用） | DeepSeek API 正常 | 返回 5 维度评分 + 综合总分 |
| TC-008 | 本地评分（API 不可用） | DeepSeek API 异常 | 返回基于字数/关键词的评分 |
| TC-009 | 题目去重 | 多轮面试 | 题目不重复 |
| TC-010 | 动态题目数量 | 技术岗位 | 题目数量 = 7 道 |

---

### 2.2 详细测试用例

#### TC-001: 开始面试（AI 可用）

**接口**：`POST /api/interview/start`

**请求体**：
```json
{
    "targetJobId": 1,
    "interviewType": "TECHNICAL"
}
```

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "sessionId": "uuid",
        "question": "AI 生成的题目（不是本地题库的固定题目）",
        "questionType": "技术基础",
        "questionIndex": 1,
        "totalQuestions": 7,
        "isFollowUp": false,
        "finished": false
    }
}
```

**验证点**：
- ✅ `question` 不是本地题库的 5 道固定题目
- ✅ `totalQuestions` 根据岗位动态计算（技术岗 = 7）
- ✅ 后端日志显示：`调用 DeepSeek API: model=deepseek-chat`

---

#### TC-002: 开始面试（AI 不可用）

**模拟方法**：临时删除 API Key 或断开网络

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "sessionId": "uuid",
        "question": "请做一个简单的自我介绍",  // 本地题库第 1 题
        "questionType": "行为面试",
        "isFollowUp": false,
        "finished": false
    }
}
```

**验证点**：
- ✅ 返回本地题库题目
- ✅ 后端日志显示：`AI 出题失败，使用本地题库: ...`

---

#### TC-003: 追问功能（回答质量高）

**步骤**：
1. 开始面试，获取 `sessionId`
2. 提交高质量回答（≥ 50 字，包含技术细节）

**请求**：
```json
POST /api/interview/submit
{
    "sessionId": "uuid",
    "answer": "我在上个项目中使用 Spring Boot 开发了用户认证模块，采用了 JWT Token 机制实现无状态登录。具体实现了 Token 生成、刷新、黑名单功能。在性能优化方面，使用 Redis 缓存用户信息，将接口响应时间从 200ms 降低到 50ms。项目中还使用了 Redis 分布式锁解决并发问题。"
}
```

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "sessionId": "uuid",
        "question": "你提到的 JWT Token 机制，能详细说说 Token 过期后你是如何处理的吗？",  // 追问问题
        "questionType": "追问",
        "isFollowUp": true,
        "finished": false
    }
}
```

**验证点**：
- ✅ `isFollowUp = true`
- ✅ `questionType = "追问"`
- ✅ 追问问题与上一题相关（不是新题目）
- ✅ 后端日志显示：`callAIForFollowUp 成功`

---

#### TC-004: 追问功能（回答质量低）

**请求**：
```json
POST /api/interview/submit
{
    "sessionId": "uuid",
    "answer": "好的"  // < 20 字
}
```

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "sessionId": "uuid",
        "question": "请描述一个你参与过的最有挑战性的项目",  // 下一题（不是追问）
        "isFollowUp": false,
        "finished": false
    }
}
```

**验证点**：
- ✅ `isFollowUp = false`（跳过追问）
- ✅ 后端日志显示：`isAnswerSubstantial=false，跳过追问，直接进入下一题`

---

#### TC-005: 最多 2 轮追问

**步骤**：
1. 回答第 1 题（高质量）→ 收到第 1 轮追问
2. 回答第 1 轮追问（高质量）→ 收到第 2 轮追问
3. 回答第 2 轮追问（高质量）→ 收到第 2 题（不是第 3 轮追问）

**验证点**：
- ✅ 第 1 次回答后：`followUpCount = 1`, `isFollowUp = true`
- ✅ 第 2 次回答后：`followUpCount = 2`, `isFollowUp = true`
- ✅ 第 3 次回答后：`followUpCount = 0`, `isFollowUp = false`（进入下一题）

---

#### TC-006: 面试结束自动生成报告

**步骤**：
1. 答完所有题目（如 7 道）
2. 提交最后一道题的回答

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "sessionId": "uuid",
        "question": null,
        "isFollowUp": false,
        "finished": true
    }
}
```

**验证点**：
- ✅ `finished = true`
- ✅ 前端自动调用 `endInterviewApi()` 生成报告
- ✅ 后端日志显示：`endInterview 成功，报告已生成`

---

#### TC-007: AI 评分（API 可用）

**接口**：`POST /api/interview/end`

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "totalScore": 85,
        "dimensionScores": {
            "logic": 88,
            "professionalism": 90,
            "communication": 82,
            "adaptability": 80,
            "jobFit": 85
        },
        "highlights": ["技术深度扎实", "项目经验丰富", "..."],
        "improvements": ["沟通表达可以更简洁", "..."],
        "summary": "候选人在技术面试中表现优秀...",
        "evaluationMethod": "AI智能评分"
    }
}
```

**验证点**：
- ✅ `evaluationMethod = "AI智能评分"`
- ✅ 5 个维度都有分数（0-100）
- ✅ `highlights` 和 `improvements` 有具体内容（不是模板文字）
- ✅ 后端日志显示：`callAIForEvaluation 成功`

---

#### TC-008: 本地评分（API 不可用）

**预期结果**：
```json
{
    "code": 200,
    "data": {
        "totalScore": 72,
        "dimensionScores": {
            "logic": 75,
            "professionalism": 78,
            "communication": 68,
            "adaptability": 65,
            "jobFit": 70
        },
        "highlights": ["回答问题较为完整", "..."],
        "improvements": ["回答过于简短", "..."],
        "evaluationMethod": "本地算法评分（AI服务不可用）"
    }
}
```

**验证点**：
- ✅ `evaluationMethod = "本地算法评分（AI服务不可用）"`
- ✅ 评分基于字数、关键词、耗时计算

---

### 2.3 自动化测试脚本（PowerShell）

创建一个 PowerShell 脚本来自动化测试核心流程：

```powershell
# test-interview.ps1
# AI 面试功能自动化测试脚本

$baseUrl = "http://localhost:8080/api"

Write-Host "=== AI 面试功能测试 ===" -ForegroundColor Cyan

# 1. 开始面试
Write-Host "`n[TC-001] 开始面试..." -ForegroundColor Yellow
$startBody = @{
    targetJobId = 1
    interviewType = "TECHNICAL"
} | ConvertTo-Json

try {
    $startResp = Invoke-RestMethod -Uri "$baseUrl/interview/start" -Method Post -Body $startBody -ContentType "application/json"
    $sessionId = $startResp.data.sessionId
    Write-Host "✅ 开始面试成功" -ForegroundColor Green
    Write-Host "   题目: $($startResp.data.question)" -ForegroundColor Gray
    Write-Host "   是否追问: $($startResp.data.isFollowUp)" -ForegroundColor Gray
} catch {
    Write-Host "❌ 开始面试失败: $_" -ForegroundColor Red
    exit 1
}

# 2. 提交回答（触发追问）
Write-Host "`n[TC-003] 提交高质量回答（触发追问）..." -ForegroundColor Yellow
$answerBody = @{
    sessionId = $sessionId
    answer = "我在上个项目中使用 Spring Boot 开发了用户认证模块，采用了 JWT Token 机制实现无状态登录。具体实现了 Token 生成、刷新、黑名单功能。在性能优化方面，使用 Redis 缓存用户信息，将接口响应时间从 200ms 降低到 50ms。"
} | ConvertTo-Json

Start-Sleep -Seconds 3  # 等待 AI 响应

try {
    $submitResp = Invoke-RestMethod -Uri "$baseUrl/interview/submit" -Method Post -Body $answerBody -ContentType "application/json"
    Write-Host "✅ 提交回答成功" -ForegroundColor Green
    Write-Host "   下一题: $($submitResp.data.question)" -ForegroundColor Gray
    Write-Host "   是否追问: $($submitResp.data.isFollowUp)" -ForegroundColor Gray
    
    if ($submitResp.data.isFollowUp -eq $true) {
        Write-Host "   ✅ 追问功能正常！" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️ 未触发追问（可能是 AI 不可用或回答质量检测未通过）" -ForegroundColor Yellow
    }
} catch {
    Write-Host "❌ 提交回答失败: $_" -ForegroundColor Red
}

# 3. 结束面试（生成报告）
Write-Host "`n[TC-006] 结束面试（生成报告）..." -ForegroundColor Yellow

try {
    $endResp = Invoke-RestMethod -Uri "$baseUrl/interview/end?sessionId=$sessionId" -Method Post
    Write-Host "✅ 生成报告成功" -ForegroundColor Green
    Write-Host "   总分: $($endResp.data.totalScore)" -ForegroundColor Gray
    Write-Host "   评分方法: $($endResp.data.evaluationMethod)" -ForegroundColor Gray
    Write-Host "   亮点: $($endResp.data.highlights -join ', ')" -ForegroundColor Gray
} catch {
    Write-Host "❌ 生成报告失败: $_" -ForegroundColor Red
}

Write-Host "`n=== 测试完成 ===" -ForegroundColor Cyan
```

**使用方法**：
1. 保存为 `test-interview.ps1`
2. 确保后端已启动（端口 8080）
3. 在 PowerShell 中执行：
   ```powershell
   .\test-interview.ps1
   ```

---

### 2.4 手动测试步骤（前端界面）

1. **启动前端**：
   ```powershell
   cd "软件实训项目-code\frontend"
   npm run dev
   ```

2. **访问面试页面**：
   - 登录学生账号
   - 进入"AI 模拟面试"页面
   - 选择一个目标岗位
   - 点击"开始面试"

3. **验证追问功能**：
   - 第 1 题回答后，观察是否出现追问
   - 如果直接出现第 2 题 → 检查 DeepSeek API Key 配置
   - 如果出现"面试官追问：" → 追问功能正常

4. **验证报告生成**：
   - 答完所有题目后，自动弹出报告页面
   - 查看评分详情和维度分析

---

## 三、修复建议

### 3.1 修复 isAvailable() 方法

**问题**：`isAvailable()` 在未缓存时始终返回 `true`，导致追问逻辑错误地尝试调用 AI。

**修复方案**：在应用启动时检查 API Key，并缓存可用状态。

修改 `DeepSeekConfig.java`：

```java
@PostConstruct
public void init() {
    if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("xxx")) {
        log.info("DeepSeek API Key 已配置: {}...{} (长度={})",
                apiKey.substring(0, 5), apiKey.substring(apiKey.length() - 4), apiKey.length());
    } else {
        log.warn("DeepSeek API Key 未正确配置！当前值: {}, 将使用本地兜底算法",
                apiKey == null ? "null" : apiKey.substring(0, Math.min(6, apiKey.length())) + "...");
        // 设置不可用标记到 Redis（如果 Redis 可用）
        try {
            redisTemplate.opsForValue().set("ai:available", false, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 不可用，无法缓存 AI 可用状态");
        }
    }
}
```

### 3.2 临时测试用 API Key 配置

如果还没有 DeepSeek API Key，可以：

1. 注册 DeepSeek 账号：https://platform.deepseek.com/
2. 充值并获取 API Key
3. 按本文档 **1.3** 节的方法配置

---

## 四、快速诊断命令

```powershell
# 1. 检查环境变量
echo $env:DEEPSEEK_API_KEY

# 2. 测试 DeepSeek API 连通性（需要真实 Key）
$curl = curl -X POST "https://api.deepseek.com/v1/chat/completions" `
  -H "Authorization: Bearer $env:DEEPSEEK_API_KEY" `
  -H "Content-Type: application/json" `
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"Hello"}],"max_tokens":10}'

Write-Host $curl

# 3. 查看后端日志（应用启动后）
Get-Content "软件实训项目-code\backend\logs\spring.log" -Tail 50 -Wait
```

---

## 五、总结

| 问题 | 状态 | 解决方案 |
|------|------|----------|
| DeepSeek API Key 未配置 | ❌ 未解决 | 设置环境变量或临时硬编码 |
| 追问功能不生效 | ❌ 依赖 AI | 配置 API Key 后自动生效 |
| 报告评分不准确 | ⚠️ 降级中 | 配置 API Key 后使用 AI 评分 |
| isAvailable() 误判 | ⚠️ 需优化 | 参考 3.1 节修复 |

**下一步**：
1. 配置 DeepSeek API Key
2. 重启后端应用
3. 运行 `test-interview.ps1` 自动化测试
4. 验证追问功能和 AI 评分是否正常
