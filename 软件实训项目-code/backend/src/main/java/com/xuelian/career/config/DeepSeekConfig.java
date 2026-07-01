package com.xuelian.career.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * DeepSeek API 配置属性 - 从 application.yml 读取 deepseek.* 配置
 * <p>
 * API Key 加载优先级：环境变量 DEEPSEEK_API_KEY > 项目根目录 .env 文件 > 空（降级本地兜底）
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekConfig {
    /** API 密钥 */
    private String apiKey;
    /** API 地址 */
    private String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    /** 模型名称 */
    private String model = "deepseek-chat";
    /** 超时时间（秒） */
    private int timeoutSeconds = 60;
    /** 最大重试次数 */
    private int maxRetries = 1;
    /** 缓存 TTL（秒） */
    private long cacheTtl = 3600;

    /** API Key 是否有效 */
    private transient boolean apiKeyValid = false;

    @PostConstruct
    public void init() {
        // 1. 优先从环境变量获取（Spring 已尝试注入）
        // 2. 如果为空，尝试从 .env 文件加载
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("xxx")) {
            apiKey = loadFromEnvFile();
        }

        // 3. 验证最终值
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("xxx")) {
            this.apiKeyValid = true;
            log.info("DeepSeek API Key 已配置: {}...{} (长度={})",
                    apiKey.substring(0, Math.min(5, apiKey.length())),
                    apiKey.substring(Math.max(0, apiKey.length() - 4)),
                    apiKey.length());
        } else {
            this.apiKeyValid = false;
            log.warn("DeepSeek API Key 未正确配置！当前值: {}, 将使用本地兜底算法。"
                    + "请设置环境变量 DEEPSEEK_API_KEY 或确保项目根目录存在 .env 文件。",
                    apiKey == null ? "null" : (apiKey.length() > 6 ? apiKey.substring(0, 6) + "..." : apiKey));
        }
    }

    /**
     * 从项目根目录的 .env 文件中读取 DEEPSEEK_API_KEY
     * 用于本地开发时，IDE/终端未设置环境变量的情况
     */
    private String loadFromEnvFile() {
        // 尝试多个可能的路径
        String[] candidatePaths = {
                ".env",                                    // 从项目根目录运行
                "../.env",                                 // 从 backend 目录运行
                System.getProperty("user.dir") + "/.env"   // 绝对路径
        };
        for (String path : candidatePaths) {
            try (BufferedReader reader = Files.newBufferedReader(Paths.get(path))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("DEEPSEEK_API_KEY=")) {
                        String key = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                        // 去除可能的引号包裹
                        if ((key.startsWith("\"") && key.endsWith("\""))
                                || (key.startsWith("'") && key.endsWith("'"))) {
                            key = key.substring(1, key.length() - 1);
                        }
                        if (!key.isBlank() && !key.contains("xxx")) {
                            log.info("从 .env 文件加载 DEEPSEEK_API_KEY 成功: {}", path);
                            return key;
                        }
                    }
                }
            } catch (IOException e) {
                // 文件不存在，继续尝试下一个路径
            }
        }
        return apiKey; // 返回原值，保持 null/blank
    }

    /**
     * 检查 API Key 是否有效可用
     */
    public boolean isApiKeyValid() {
        return apiKeyValid && apiKey != null && !apiKey.isBlank();
    }
}
