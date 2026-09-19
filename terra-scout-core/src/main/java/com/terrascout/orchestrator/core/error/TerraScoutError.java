package com.terrascout.orchestrator.core.error;

/**
 * Terra Scout 统一错误码体系。
 *
 * <p>编码规则（强约束）：
 * <ul>
 *   <li>6 位数字 = 前 3 位 HTTP 状态码 + 后 3 位业务序号；</li>
 *   <li>HTTP 状态码必须等于 code ÷ 1000（{@link #getHttpStatus()} 结构化保证，永不漂移）；</li>
 *   <li>成功响应 = HTTP 200 + {@code 200000}；</li>
 *   <li>幂等重放返回 200 + 200000 + 首次结果，不设错误码；</li>
 *   <li>总量：46 个错误码 / 9 个 HTTP 族。</li>
 * </ul>
 *
 * <p>新增错误码必须四表同步、保持一致。
 */
public enum TerraScoutError {

    /** 成功（所有成功响应 = HTTP 200 + 200000）。 */
    SUCCESS(200000, "success"),

    // ==================== 400 · 请求参数错误 ====================
    /** 400001：项目路径为空或格式非法。 */
    INVALID_PROJECT_PATH(400001, "项目路径为空或格式非法"),
    /** 400002：请求体 JSON 结构非法。 */
    INVALID_JSON_BODY(400002, "请求体 JSON 结构非法"),
    /** 400003：缺少必填字段。 */
    FIELD_MISSING(400003, "缺少必填字段"),
    /** 400004：idempotencyKey 格式非法。 */
    INVALID_IDEMPOTENCY_KEY(400004, "idempotencyKey 格式非法"),

    // ==================== 401 · 鉴权失败 ====================
    /** 401001：Token 缺失或校验失败。 */
    TOKEN_INVALID(401001, "Token 缺失或校验失败"),

    // ==================== 403 · 权限与安全拒绝 ====================
    /** 403001：请求来源非 127.0.0.1。 */
    FORBIDDEN_SOURCE(403001, "请求来源非 127.0.0.1"),
    /** 403002：命令不在白名单，或参数包含 shell 元字符。 */
    COMMAND_REJECTED(403002, "命令不在白名单，或参数包含 shell 元字符"),
    /** 403003：隔离域无写入权限。 */
    ISOLATION_WRITE_FORBIDDEN(403003, "隔离域无写入权限"),
    /** 403004：项目根路径无读取权限。 */
    PROJECT_READ_FORBIDDEN(403004, "项目根路径无读取权限"),

    // ==================== 404 · 资源不存在 ====================
    /** 404001：项目根路径不存在。 */
    PROJECT_PATH_NOT_FOUND(404001, "项目根路径不存在"),
    /** 404002：任务 ID 不存在。 */
    TASK_NOT_FOUND(404002, "任务 ID 不存在"),
    /** 404003：装配计划 ID 不存在。 */
    PLAN_NOT_FOUND(404003, "装配计划 ID 不存在"),

    // ==================== 409 · 状态冲突 ====================
    /** 409001：同一项目已有任务在执行，进程锁获取失败。 */
    PROJECT_LOCKED(409001, "同一项目已有任务在执行，进程锁获取失败"),
    /** 409002：本机已装版本与项目约束冲突，需用户决策。 */
    VERSION_CONFLICT(409002, "本机已装版本与项目约束冲突，需用户决策"),
    /** 409003：隔离域 .devenv 被其他进程占用。 */
    ISOLATION_LOCKED(409003, "隔离域 .devenv 被其他进程占用"),
    /** 409004：隔离域已存在且非本工具创建，拒绝覆盖。 */
    ISOLATION_DIR_CONFLICT(409004, "隔离域已存在且非本工具创建，拒绝覆盖"),
    /** 409005：任务已处于终态，无法再次操作。 */
    TASK_TERMINAL_STATE(409005, "任务已处于终态，无法再次操作"),
    /** 409006：任务队列已满，拒绝新任务。 */
    TASK_QUEUE_FULL(409006, "任务队列已满，拒绝新任务"),

    // ==================== 422 · 业务校验失败 ====================
    /** 422001：项目类型无法识别。 */
    PROJECT_TYPE_UNKNOWN(422001, "项目类型无法识别"),
    /** 422002：声明文件冲突：pom.xml 与 package.json 约束矛盾。 */
    DECLARATION_CONFLICT(422002, "声明文件冲突：pom.xml 与 package.json 约束矛盾"),
    /** 422003：父 POM 未解析到。 */
    PARENT_POM_MISSING(422003, "父 POM 未解析到"),
    /** 422004：属性占位符无法求值。 */
    PROPERTY_UNRESOLVED(422004, "属性占位符无法求值"),
    /** 422005：锁文件与声明文件不一致。 */
    LOCKFILE_MISMATCH(422005, "锁文件与声明文件不一致"),
    /** 422006：项目约束无任何 SDK 版本满足。 */
    NO_SDK_VERSION_MATCH(422006, "项目约束无任何 SDK 版本满足"),
    /** 422007：项目约束只能由 EOL 版本满足，已拒绝。 */
    ONLY_EOL_MATCH(422007, "项目约束只能由 EOL 版本满足，已拒绝"),
    /** 422008：项目约束只能由存在高危 CVE 的版本满足，已拒绝。 */
    ONLY_VULNERABLE_MATCH(422008, "项目约束只能由存在高危 CVE 的版本满足，已拒绝"),
    /** 422009：SHA256 校验失败。 */
    CHECKSUM_MISMATCH(422009, "SHA256 校验失败"),
    /** 422010：解压中止：检测到 Zip-Slip 或符号链接。 */
    ARCHIVE_UNSAFE(422010, "解压中止：检测到 Zip-Slip 或符号链接"),
    /** 422011：隔离域路径超出 Windows 260 字符限制。 */
    PATH_TOO_LONG(422011, "隔离域路径超出 Windows 260 字符限制"),
    /** 422012：依赖安装失败：mvn install 或 npm install 非零退出。 */
    DEPENDENCY_INSTALL_FAILED(422012, "依赖安装失败：mvn install 或 npm install 非零退出"),
    /** 422013：隔离配置文件生成失败。 */
    ISOLATION_CONFIG_FAILED(422013, "隔离配置文件生成失败"),
    /** 422014：注入后 JAVA_HOME / NODE_HOME 路径不存在。 */
    HOME_PATH_INVALID(422014, "注入后 JAVA_HOME / NODE_HOME 路径不存在"),
    /** 422015：命令执行失败：非零退出或超时。 */
    COMMAND_EXECUTION_FAILED(422015, "命令执行失败：非零退出或超时"),

    // ==================== 500 · 服务端内部错误 ====================
    /** 500001：子进程未继承系统基础变量，环境异常。 */
    ENV_INHERIT_FAILED(500001, "子进程未继承系统基础变量，环境异常"),
    /** 500002：env.ps1 脚本生成或执行失败。 */
    ENV_SCRIPT_FAILED(500002, "env.ps1 脚本生成或执行失败"),
    /** 500003：任务心跳超时，进程可能被强制终止。 */
    HEARTBEAT_TIMEOUT(500003, "任务心跳超时，进程可能被强制终止"),
    /** 500004：回滚执行失败：安装目录或 .devenv 无法删除。 */
    ROLLBACK_FAILED(500004, "回滚执行失败：安装目录或 .devenv 无法删除"),
    /** 500005：H2 数据库文件损坏。 */
    DB_CORRUPTED(500005, "H2 数据库文件损坏"),
    /** 500006：H2 迁移脚本校验失败。 */
    MIGRATION_VALIDATION_FAILED(500006, "H2 迁移脚本校验失败"),
    /** 500007：未知内部错误。 */
    UNKNOWN(500007, "未知内部错误"),

    // ==================== 502 · 第三方服务错误 ====================
    /** 502001：SDK 源不可达：官方源与所有镜像均连接失败。 */
    SDK_SOURCE_UNREACHABLE(502001, "SDK 源不可达：官方源与所有镜像均连接失败"),
    /** 502002：镜像内容与官方 checksum 不一致，疑似镜像劫持。 */
    MIRROR_CHECKSUM_MISMATCH(502002, "镜像内容与官方 checksum 不一致，疑似镜像劫持"),
    /** 502003：服务端不支持 Range，断点续传不可用。 */
    RANGE_UNSUPPORTED(502003, "服务端不支持 Range，断点续传不可用"),
    /** 502004：AI 服务不可用，已降级。 */
    AI_SERVICE_UNAVAILABLE(502004, "AI 服务不可用，已降级"),

    // ==================== 507 · 容量不足 ====================
    /** 507001：下载目录磁盘空间不足。 */
    DOWNLOAD_DISK_FULL(507001, "下载目录磁盘空间不足"),
    /** 507002：解压目标磁盘空间不足。 */
    EXTRACT_DISK_FULL(507002, "解压目标磁盘空间不足");

    /** 6 位错误码：前 3 位 HTTP 状态码 + 后 3 位业务序号。 */
    private final int code;

    /** 默认消息。 */
    private final String defaultMessage;

    TerraScoutError(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * HTTP 状态码恒等于 code 前 3 位（强约束）。
     * 由 code 直接推导，结构上杜绝 401 载荷配 403 码之类的错位缺陷。
     */
    public int getHttpStatus() {
        return code / 1000;
    }

    /**
     * 类加载期自检：全部常量满足「前 3 位为合法 HTTP 状态码（200-599，成功码前缀为 200）」
     * 与「后 3 位在 000-999 内」。违反说明枚举被手工改坏，立即失败（fail-fast）而非运行期错位返回。
     */
    static {
        for (TerraScoutError error : values()) {
            int httpStatus = error.getHttpStatus();
            int sequence = error.code % 1000;
            boolean validHttpStatus = httpStatus >= 200 && httpStatus <= 599;
            boolean validSequence = error == SUCCESS ? sequence == 0 : (sequence >= 1 && sequence <= 999);
            if (!validHttpStatus || !validSequence) {
                throw new IllegalStateException("错误码 " + error.code + " 违反编码不变量");
            }
        }
    }
}
